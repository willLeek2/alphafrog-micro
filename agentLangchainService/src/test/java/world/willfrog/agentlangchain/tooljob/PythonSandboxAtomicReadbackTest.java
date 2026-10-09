package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.MemberPreparingInterruption;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonSandboxAtomicReadbackTest {
    @AfterEach void cleanThread() { Thread.interrupted(); MemberPreparingInterruption.consume(); AgentContext.clear(); }

    @Test void commitExceptionOnlyReadsOriginalFactsAndRetriesReadUnavailable() throws Exception {
        var f = new SqlAtomicMemberPreparingTest.Fixture();
        ToolJobAnchorService service = mock(ToolJobAnchorService.class);
        when(service.claimPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("commit response lost"));
        when(service.readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("read unavailable"))
                .thenReturn(ToolJobAnchorService.MemberPreparingReadback.COMMITTED);
        assertThat(store(service).persistPreparingWaitMember("run", f.anchor, 7, "member", f.proof, null, null)).isTrue();
        verify(service).claimPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any());
        verify(service, times(2)).readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any());
        assertThat(MemberPreparingInterruption.consume()).isFalse();
    }

    @Test void interruptedReadbackWaitsForConfirmationAndRestoresOnlyInterruptMarker() throws Exception {
        var f = new SqlAtomicMemberPreparingTest.Fixture();
        ToolJobAnchorService service = uncertain(ToolJobAnchorService.MemberPreparingReadback.COMMITTED);
        Thread.currentThread().interrupt();
        assertThat(store(service).persistPreparingWaitMember("run", f.anchor, 7, "member", f.proof, null, null)).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(MemberPreparingInterruption.consume()).isTrue();
        assertThat(MemberPreparingInterruption.consume()).isFalse();
        verify(service).readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test void interruptedButNotWrittenReturnsFalseWithNoPendingProof() throws Exception {
        var f = new SqlAtomicMemberPreparingTest.Fixture();
        Thread.currentThread().interrupt();
        assertThat(store(uncertain(ToolJobAnchorService.MemberPreparingReadback.NOT_WRITTEN))
                .persistPreparingWaitMember("run", f.anchor, 7, "member", f.proof, null, null)).isFalse();
        assertThat(MemberPreparingInterruption.consume()).isTrue();
    }

    @Test void committedButBusyHandsActualFullProofToMemberWithoutNewTransaction() throws Exception {
        var f = new SqlAtomicMemberPreparingTest.Fixture();
        ToolJobAnchorService service = uncertain(ToolJobAnchorService.MemberPreparingReadback.COMMITTED_DEFERRED);
        assertThatThrownBy(() -> store(service).persistPreparingWaitMember("run", f.anchor, 7,
                "member", f.proof, null, null)).isInstanceOfSatisfying(WaitGroupMemberPendingException.class,
                pending -> assertThat(pending.getProof().createRequestJson()).isEqualTo(f.anchor.getCreateRequestJson()));
        verify(service).claimPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test void unresolvedOwnershipDoesNotPublishAnUnconfirmedPendingProof() throws Exception {
        var f = new SqlAtomicMemberPreparingTest.Fixture();
        ToolJobAnchorService service = uncertain(ToolJobAnchorService.MemberPreparingReadback.OWNERSHIP_LOST);
        when(service.readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(ToolJobAnchorService.MemberPreparingReadback.OWNERSHIP_LOST,
                        ToolJobAnchorService.MemberPreparingReadback.NOT_WRITTEN);
        assertThat(store(service).persistPreparingWaitMember("run", f.anchor, 7, "member", f.proof, null, null)).isFalse();
        verify(service, times(2)).readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any());
    }

    private ToolJobAnchorService uncertain(ToolJobAnchorService.MemberPreparingReadback outcome) {
        ToolJobAnchorService service = mock(ToolJobAnchorService.class);
        when(service.claimPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("commit response lost"));
        when(service.readPreparingWaitMember(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(outcome);
        return service;
    }
    private PythonSandboxDispatchStoreImpl store(ToolJobAnchorService service) {
        ToolJobConfig config = new ToolJobConfig(); config.setReconcilerIntervalMs(1);
        return new PythonSandboxDispatchStoreImpl(service, mock(ToolJobRedisCache.class), config,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics.class));
    }
}
