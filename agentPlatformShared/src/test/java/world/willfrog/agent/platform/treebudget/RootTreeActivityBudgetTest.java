package world.willfrog.agent.platform.treebudget;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.mapper.RootTreeBudgetMapper;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RootTreeActivityBudgetTest {
    private final RootTreeBudgetMapper mapper = mock(RootTreeBudgetMapper.class);
    private final ChildRunIntentStore childRuns = mock(ChildRunIntentStore.class);
    private final RootTreeBudgetStore budget = mock(RootTreeBudgetStore.class);
    private final RootTreeActivityBudget activity = new RootTreeActivityBudget(mapper, childRuns, budget, 64, 64);

    @Test
    void waitIdentityKeepsNamesWithSeparatorsDistinct() {
        String first = RootTreeActivityBudget.waitOperationId(
                new NodeWorkItemIdentity("root", 1, "a:2:b", 0, 0), 3);
        String second = RootTreeActivityBudget.waitOperationId(
                new NodeWorkItemIdentity("root", 1, "a", 2, 0), 3);
        assertThat(first).isNotEqualTo(second);
        assertThat(RootTreeActivityBudget.waitOperationId(
                new NodeWorkItemIdentity("root", 1, "😀", 0, 0), 3))
                .contains(":1:😀:");
        assertThat(RootTreeActivityBudget.nodeOperationId(27, 2)).isEqualTo("active-node:27:2");
    }

    @Test
    void lockRequiresTransactionAndPersistentRoot() {
        assertThatThrownBy(() -> activity.lockForRun("child"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("事务");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            when(childRuns.rootRunIdOf("child")).thenReturn(Optional.of("root"));
            when(mapper.lockRun("child")).thenReturn("child");
            assertThat(activity.lockForRun("child")).isEqualTo("root");
            verify(mapper).lockRun("child");
            verify(budget).lockExistingRoot("root");
            verify(budget, never()).lockRoot("root");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void startupRejectsUntrackedActivityAndInvalidLimits() {
        when(mapper.listUntrackedActivity(20)).thenReturn(List.of("ACTIVE_NODE work_item=8 run=old epoch=1"));
        assertThatThrownBy(activity::verifyExistingActivity)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("work_item=8");
        assertThatThrownBy(() -> new RootTreeActivityBudget(mapper, childRuns, budget, 0, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
