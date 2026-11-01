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



}
