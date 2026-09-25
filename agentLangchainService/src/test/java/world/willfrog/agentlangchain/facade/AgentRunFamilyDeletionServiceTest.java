package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentRunFamilyDeletionServiceTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
    private final AgentRunFamilyDeletionService service = new AgentRunFamilyDeletionService(runs, intents);

    @Test
    void activeChildKeepsRootAndHistoryIntact() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(intents.hasUnsettledDescendants("root")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.deleteRoot("root", "user"));

        verify(runs, never()).deleteByIdAndUser(anyString(), anyString());
        verify(runs, never()).deleteChildIntentsByRoot(anyString());
    }

    @Test
    void fullyStoppedChildrenAreDeletedWithRoot() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(runs.listChildRunsByRootForUpdate("root"))
                .thenReturn(List.of(run("child-a", "user", AgentRunStatus.COMPLETED),
                        run("child-b", "user", AgentRunStatus.CANCELED)));
        when(runs.deleteByIdAndUser(anyString(), eq("user"))).thenReturn(1);

        assertEquals(List.of("child-a", "child-b", "root"), service.deleteRoot("root", "user"));

        var order = inOrder(runs);
        // 下一层意图有非延迟的 parent_run_id 外键；必须先移除整树关系。
        order.verify(runs).deleteChildIntentsByRoot("root");
        order.verify(runs).deleteByIdAndUser("child-a", "user");
        order.verify(runs).deleteByIdAndUser("child-b", "user");
        order.verify(runs).deleteEmptyTreeCapacityByRoot("root");
        order.verify(runs).deleteByIdAndUser("root", "user");
    }

    @Test
    void nonterminalChildFailsClosedEvenIfCapacityRowLooksSettled() {
        when(runs.findByIdAndUserForUpdate("root", "user"))
                .thenReturn(run("root", "user", AgentRunStatus.COMPLETED));
        when(runs.listChildRunsByRootForUpdate("root"))
                .thenReturn(List.of(run("child", "user", AgentRunStatus.EXECUTING)));

        assertThrows(IllegalStateException.class, () -> service.deleteRoot("root", "user"));
        verify(runs, never()).deleteByIdAndUser(anyString(), anyString());
    }

    private static AgentRun run(String id, String userId, AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId(userId);
        run.setStatus(status);
        return run;
    }
}
