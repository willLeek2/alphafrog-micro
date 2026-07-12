package world.willfrog.agentlangchain.tooljob;

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
        if (anchor.getTerminalRetryable() == null) {
            throw new IllegalArgumentException("terminalRetryable must be durably classified");
        }
        DataAnalysisReservation stored = objectMapper.readValue(
                anchor.getReservationJson(), DataAnalysisReservation.class);
        DataAnalysisReservation confirmed = new DataAnalysisReservation(
                stored.reservationId(), stored.identity(), stored.resourceClass(), stored.capacityUnits(),
                DataAnalysisReservationState.TERMINAL_CONFIRMED, stored.taskId(), stored.acquiredAt());
        DataAnalysisEstimate estimate = objectMapper.readValue(
                anchor.getEstimateJson(), DataAnalysisEstimate.class);
        DataAnalysisResourceUsage usage = ToolJobResourceUsageParser.parse(
                objectMapper, confirmed.resourceClass(), anchor.getTerminalUsageJson());
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
                Boolean.TRUE.equals(anchor.getTerminalRetryable()),
                estimate,
                confirmed,
                usage,
                anchor.getTerminalAt(),
                true);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
