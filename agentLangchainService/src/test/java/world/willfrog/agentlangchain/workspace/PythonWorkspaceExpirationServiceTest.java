package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PythonWorkspaceExpirationServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final WaitGroupMapper waitGroups = mock(WaitGroupMapper.class);
    private final PythonWorkspaceExpirationService service =
            new PythonWorkspaceExpirationService(runs, waitGroups);

    @Test
    void onlyConfirmedExpiredRunCompactsSettledMemberProofs() {
        when(runs.markWorkspaceExpired("run-1")).thenReturn(1);

        service.confirmDeleted("run-1");

        var order = inOrder(runs, waitGroups);
        order.verify(runs).markWorkspaceExpired("run-1");
        order.verify(waitGroups).compactExpiredWaitMemberProofs("run-1");
    }

    @Test
    void unfinishedResponsibilityCannotClearAnyReplayBody() {
        when(runs.markWorkspaceExpired("run-1")).thenReturn(0);

        assertThatThrownBy(() -> service.confirmDeleted("run-1"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(waitGroups);
    }
}
