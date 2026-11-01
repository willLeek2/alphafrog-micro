package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.control.dualpool.RootRunResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RootTreeCallBudgetTest {
    private final RootTreeBudgetStore store = mock(RootTreeBudgetStore.class);
    private final RootRunResolver roots = mock(RootRunResolver.class);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final AgentRunBudgetService config = mock(AgentRunBudgetService.class);
    private final RootTreeCallBudget budget = new RootTreeCallBudget(store, roots, runs, config);



}
