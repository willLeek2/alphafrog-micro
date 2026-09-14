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

/** 把数据库里的终态 anchor 记录接到幂等 usage recorder，写入资源用量。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolJobUsageHookImpl implements ToolJobUsageHook {

    private final DataAnalysisTerminalRecorder recorder;
    private final ObjectMapper objectMapper;

    @Override
    public boolean upsertUsage(String runId, ToolJobAnchor anchor) {
        try {
            // 从数据库里的 anchor 重建完整终态 envelope，不读取进程内临时 usage。
            DataAnalysisTerminalEnvelope envelope = toEnvelope(runId, anchor);
            // recorder 按 operation identity 幂等写入。
            DataAnalysisUpsertOutcome outcome = recorder.upsert(envelope);
            // 首次插入和内容相同的已存在记录都算 gate 成功。
            return outcome == DataAnalysisUpsertOutcome.INSERTED
                    || outcome == DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME;
        } catch (Exception e) {
            log.warn("Data-analysis usage 写入失败: runId={}, operationId={}, error={}",
                    runId, anchor == null ? null : anchor.getOperationId(), e.getMessage());
            return false;
        }
    }

    DataAnalysisTerminalEnvelope toEnvelope(String runId, ToolJobAnchor anchor) throws Exception {
        // 缺 anchor 无法证明任务身份。
        if (anchor == null) {
            throw new IllegalArgumentException("anchor must not be null");
        }
        // retryable 必须显式分类；null 不能默认为 false。
        if (anchor.getTerminalRetryable() == null) {
            throw new IllegalArgumentException("terminalRetryable must be durably classified");
        }
        // 从 anchor 恢复原 reservation，再规范成终态确认状态用于 recorder 契约。
        DataAnalysisReservation stored = objectMapper.readValue(
                anchor.getReservationJson(), DataAnalysisReservation.class);
        DataAnalysisReservation confirmed = new DataAnalysisReservation(
                stored.reservationId(), stored.identity(), stored.resourceClass(), stored.capacityUnits(),
                DataAnalysisReservationState.TERMINAL_CONFIRMED, stored.taskId(), stored.acquiredAt());
        // estimate 与实际 usage 共同形成资源用量记录。
        DataAnalysisEstimate estimate = objectMapper.readValue(
                anchor.getEstimateJson(), DataAnalysisEstimate.class);
        DataAnalysisResourceUsage usage = ToolJobResourceUsageParser.parse(
                objectMapper, confirmed.resourceClass(), anchor.getTerminalUsageJson());
        String terminalStatus = anchor.getTerminalStatus();
        // 只有明确 SUCCEEDED 才是成功，其余终态均带 errorCode。
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
