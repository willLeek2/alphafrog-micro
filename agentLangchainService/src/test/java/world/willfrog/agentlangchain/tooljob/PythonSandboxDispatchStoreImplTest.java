package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PythonSandboxDispatchStoreImplTest {

    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
    private final PythonSandboxDispatchStoreImpl store =
            new PythonSandboxDispatchStoreImpl(anchorService, redisCache);

    @Test
    void preparingClaimCannotOverwriteAnExistingAnchor() {
        ToolJobAnchor preparing = anchor("PREPARING");
        when(anchorService.claimPreparing("run-1", preparing, AgentRunStatus.EXECUTING))
                .thenReturn(false);

        assertThat(store.persistPreparing("run-1", preparing)).isFalse();
        verify(anchorService).claimPreparing("run-1", preparing, AgentRunStatus.EXECUTING);
    }

    @Test
    void pendingTransferCommitsPostgresBeforeBestEffortRedis() {
        ToolJobAnchor anchor = anchor("PENDING");
        when(anchorService.updateActiveAndStatus(
                "run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, "run-1:call-1:1"))
                .thenReturn(true);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redisCache).atomicWritePendingAndDue("run-1", anchor);

        assertThat(store.transferToPending("run-1", anchor)).isTrue();
        var order = inOrder(anchorService, redisCache);
        order.verify(anchorService).updateActiveAndStatus(
                "run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB,
                AgentRunStatus.EXECUTING, "run-1:call-1:1");
        order.verify(redisCache).atomicWritePendingAndDue("run-1", anchor);
    }

    @Test
    void pendingTransferDoesNotPublishRedisWhenDurableCasFails() {
        ToolJobAnchor anchor = anchor("PENDING");
        when(anchorService.updateActiveAndStatus(any(), any(), any(), any(), any())).thenReturn(false);

        assertThat(store.transferToPending("run-1", anchor)).isFalse();
        verifyNoInteractions(redisCache);
    }

    @Test
    void attachedAndTerminalSnapshotsRemainWritableWhileRunExecutes() {
        when(anchorService.updateActive(
                eq("run-1"), any(), eq(AgentRunStatus.EXECUTING), eq("run-1:call-1:1")))
                .thenReturn(true);

        assertThat(store.persistAttached("run-1", anchor("ATTACHED"))).isTrue();
        assertThat(store.persistAttached("run-1", anchor("TERMINAL"))).isTrue();
        assertThat(store.persistAttached("run-1", anchor("PENDING"))).isFalse();
        verify(anchorService, times(2)).updateActive(
                eq("run-1"), any(), eq(AgentRunStatus.EXECUTING), eq("run-1:call-1:1"));
    }

    @Test
    void synchronousCompletionUsesProofGatedExecutingCas() {
        when(anchorService.clearSynchronouslyCompleted(
                "run-1", AgentRunStatus.EXECUTING, "run-1:call-1:1"))
                .thenReturn(true);

        assertThat(store.clearSynchronouslyCompleted(
                "run-1", "run-1:call-1:1")).isTrue();

        verify(anchorService).clearSynchronouslyCompleted(
                "run-1", AgentRunStatus.EXECUTING, "run-1:call-1:1");
        verify(anchorService, never()).clearActive(any(), any(), any());
    }

    private ToolJobAnchor anchor(String state) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setAnchorState(state);
        return anchor;
    }
}
