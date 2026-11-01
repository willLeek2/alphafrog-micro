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
