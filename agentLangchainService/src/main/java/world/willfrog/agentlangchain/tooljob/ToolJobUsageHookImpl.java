package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

import java.util.ArrayList;
import java.util.List;

/** 将 T3 durable terminal anchor 接到 T4 幂等 usage recorder。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolJobUsageHookImpl implements ToolJobUsageHook {

    private final DataAnalysisTerminalRecorder recorder;
    private final ObjectMapper objectMapper;

    @Override
    public boolean upsertUsage(String runId, ToolJobAnchor anchor) {
        try {
            DataAnalysisTerminalEnvelope envelope = toEnvelope(runId, anchor);
            DataAnalysisUpsertOutcome outcome = recorder.upsert(envelope);
            return outcome == DataAnalysisUpsertOutcome.INSERTED
                    || outcome == DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME;
        } catch (Exception e) {
            log.warn("Data-analysis usage 写入失败: runId={}, operationId={}, error={}",
                    runId, anchor == null ? null : anchor.getOperationId(), e.getMessage());
            return false;
        }
    }

    DataAnalysisTerminalEnvelope toEnvelope(String runId, ToolJobAnchor anchor) throws Exception {
        if (anchor == null) {
            throw new IllegalArgumentException("anchor must not be null");
        }
        DataAnalysisReservation stored = objectMapper.readValue(
                anchor.getReservationJson(), DataAnalysisReservation.class);
        DataAnalysisReservation confirmed = new DataAnalysisReservation(
                stored.reservationId(), stored.identity(), stored.resourceClass(), stored.capacityUnits(),
                DataAnalysisReservationState.TERMINAL_CONFIRMED, stored.taskId(), stored.acquiredAt());
        DataAnalysisEstimate estimate = objectMapper.readValue(
                anchor.getEstimateJson(), DataAnalysisEstimate.class);
        DataAnalysisResourceUsage usage = parseUsage(confirmed.resourceClass(), anchor.getTerminalUsageJson());
        String terminalStatus = anchor.getTerminalStatus();
        boolean success = "SUCCEEDED".equals(terminalStatus);
        String preview = trim(anchor.getTerminalResultPreview());
        String rawRef = trim(anchor.getTerminalRawRef());
        if (success && preview == null && rawRef == null) {
            preview = "(preview unavailable)";
        }
        String errorCode = trim(anchor.getTerminalErrorCode());
        if (!success && errorCode == null) {
            errorCode = terminalStatus;
        }
        return new DataAnalysisTerminalEnvelope(
                runId,
                anchor.getToolCallId(),
                anchor.getAttempt(),
                anchor.getOperationId(),
                anchor.getTaskId(),
                terminalStatus,
                success,
                preview,
                rawRef,
                errorCode,
                success ? null : "sandbox " + terminalStatus,
                !success && !"RESULT_LOST".equals(terminalStatus),
                estimate,
                confirmed,
                usage,
                anchor.getTerminalAt(),
                true);
    }

    private DataAnalysisResourceUsage parseUsage(DataAnalysisResourceClass resourceClass, String json)
            throws Exception {
        if (json == null || json.isBlank()) {
            return DataAnalysisResourceUsage.missing(resourceClass);
        }
        JsonNode root = objectMapper.readTree(json);
        if (root == null || !root.isObject()) {
            return DataAnalysisResourceUsage.missing(resourceClass);
        }
        Long cpu = longValue(root, "cpuMillis");
        Long memoryPeak = longValue(root, "memoryPeakBytes");
        Long memoryByteMillis = longValue(root, "memoryByteMillis");
        Long logicalBytes = longValue(root, "logicalBytesScanned");
        Long artifactBytes = longValue(root, "artifactBytesWritten");
        Long temporaryBytes = longValue(root, "temporaryBytesWritten");
        Long queueWait = longValue(root, "queueWaitMillis");
        Long prepare = longValue(root, "prepareMillis");
        Long execution = longValue(root, "executionWallMillis");
        Long cleanup = longValue(root, "cleanupMillis");
        Integer datasetOpenCount = integerValue(root, "datasetOpenCount");
        String exitReason = textValue(root, "exitReason");
        Long samplingInterval = longValue(root, "samplingIntervalMillis");

        List<String> missing = new ArrayList<>();
        addMissing(missing, "cpuMillis", cpu);
        addMissing(missing, "memoryPeakBytes", memoryPeak);
        addMissing(missing, "logicalBytesScanned", logicalBytes);
        addMissing(missing, "queueWaitMillis", queueWait);
        addMissing(missing, "prepareMillis", prepare);
        addMissing(missing, "executionWallMillis", execution);
        addMissing(missing, "cleanupMillis", cleanup);
        addMissing(missing, "datasetOpenCount", datasetOpenCount);
        addMissing(missing, "exitReason", exitReason);

        return new DataAnalysisResourceUsage(
                resourceClass,
                cpu,
                memoryPeak,
                memoryByteMillis,
                logicalBytes,
                artifactBytes,
                temporaryBytes,
                queueWait,
                prepare,
                execution,
                cleanup,
                datasetOpenCount,
                exitReason,
                booleanValue(root, "oomKilled"),
                booleanValue(root, "timedOut"),
                missing.isEmpty(),
                samplingInterval,
                missing);
    }

    private static Long longValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.textValue());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer integerValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isIntegralNumber() ? node.intValue() : null;
    }

    private static String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isTextual() && !node.textValue().isBlank()
                ? node.textValue().trim() : null;
    }

    private static boolean booleanValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static void addMissing(List<String> target, String field, Object value) {
        if (value == null) {
            target.add(field);
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
