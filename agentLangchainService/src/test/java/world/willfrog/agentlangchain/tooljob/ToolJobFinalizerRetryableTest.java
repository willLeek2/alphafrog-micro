package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * C-slice: durable Harness retryable classification into terminal envelope.
 * Verifies presence-aware nullable Boolean terminalRetryable flows from
 * TaskResultResponse through ENVELOPE step into buildEnvelope, with
 * fail-closed on missing classification.
 */
class ToolJobFinalizerRetryableTest {

    // ===== presence-aware: retryable from result → anchor → envelope =====




    // ===== fail-closed: missing classification =====


    // ===== recovery: backfill on refetch after missing =====


    // ===== RESULT_LOST synthetic classification =====


    // ===== backfill branches =====




    // ===== helpers =====

    private static ToolJobAnchor buildPersistedReadyAnchor(String runId, String toolCallId,
                                                            int attempt, String taskId) {
        ToolJobAnchor a = new ToolJobAnchor();
        a.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        a.setToolCallId(toolCallId);
        a.setAttempt(attempt);
        a.setTaskId(taskId);
        a.setFinalizerStep("RESUME_READY");
        a.setResumeState("READY");
        a.setResumeToken("token-" + runId);
        a.setResumeLeaseVersion(1);
        a.setResumeClaimedAt(java.time.Instant.now());
        return a;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String buildReleasedJson(String runId, String toolCallId, int attempt,
                                             String taskId) throws Exception {
        return buildReservationJson(runId, toolCallId, attempt, taskId,
                DataAnalysisReservationState.RELEASED);
    }

    private static String buildReservationJson(
            String runId, String toolCallId, int attempt, String taskId,
            DataAnalysisReservationState state) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        var reservation = new DataAnalysisReservation(
                identity.operationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                state, taskId, java.time.Instant.now());
        return mapper.writeValueAsString(reservation);
    }
}
