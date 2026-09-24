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

    @Test
    void childAndParentUseOneRootAndReplayOneModelTurnWithoutAnotherCharge() {
        AgentRun child = mock(AgentRun.class);
        when(child.getSchedulerVersion()).thenReturn("DUAL_POOL_V2");
        when(runs.findById("child")).thenReturn(child);
        when(roots.rootRunId("child")).thenReturn("parent");
        when(config.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(
                600000, 2, 1, 300000, 3));
        NodeWorkItemIdentity turn = new NodeWorkItemIdentity("child", 1, "todo", 0, 0);
        when(store.reserve(eq("parent"), anyString(), eq(RootTreeBudgetStore.Kind.LLM_CALL), eq(2L)))
                .thenReturn(RootTreeBudgetStore.State.RESERVED, RootTreeBudgetStore.State.CONFIRMED);

        budget.beforeModelCall(turn, "0");
        budget.beforeModelCall(turn, "0");

        verify(store, times(2)).reserve(eq("parent"), anyString(),
                eq(RootTreeBudgetStore.Kind.LLM_CALL), eq(2L));
        verify(store, times(1)).confirm(anyString());
    }

    @Test
    void toolLimitRejectsBeforeDispatchAndReportsTheSharedCount() {
        AgentRun child = mock(AgentRun.class);
        when(child.getSchedulerVersion()).thenReturn("DUAL_POOL_V2");
        when(runs.findById("child")).thenReturn(child);
        when(roots.rootRunId("child")).thenReturn("parent");
        when(config.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(
                600000, 2, 1, 300000, 3));
        when(store.reserve(eq("parent"), anyString(), eq(RootTreeBudgetStore.Kind.TOOL_CALL), eq(1L)))
                .thenReturn(RootTreeBudgetStore.State.REJECTED);
        when(store.snapshot("parent")).thenReturn(new RootTreeBudgetStore.Snapshot(1, 1, 0, 0));

        assertThatThrownBy(() -> budget.beforeToolCall("child", 7, "member"))
                .isInstanceOf(RunBudgetException.class)
                .hasMessage("RUN_BUDGET_EXCEEDED:tool_calls:1/1");
        verify(store, times(0)).confirm(anyString());
    }

    @Test
    void oldRunsKeepTheirExistingBudgetPath() {
        AgentRun old = mock(AgentRun.class);
        when(old.getSchedulerVersion()).thenReturn("DUAL_POOL_V1");
        when(runs.findById("old")).thenReturn(old);
        when(config.effectiveConfig()).thenReturn(new AgentRunBudgetService.EffectiveRunBudget(
                600000, 2, 1, 300000, 3));

        budget.beforeModelCall(new NodeWorkItemIdentity("old", 1, "todo", 0, 0), "0");

        org.mockito.Mockito.verifyNoInteractions(store, roots);
    }
}
