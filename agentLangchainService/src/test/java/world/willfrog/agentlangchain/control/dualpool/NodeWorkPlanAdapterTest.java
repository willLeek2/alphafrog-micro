package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NodeWorkPlanAdapterTest {

    private final NodeWorkPlanAdapter adapter = new NodeWorkPlanAdapter();

    @Test
    void linearOnlyCreatesTheFirstUnfinishedNode() {
        LangchainTodoPlan plan = plan(
                todo("todo-1", 1, List.of()),
                todo("todo-2", 2, List.of()),
                todo("todo-3", 3, List.of()));

        List<NodeWorkDraft> drafts = adapter.runnableLinear(
                "run-1", 3, plan, Set.of("todo-1"), Set.of(), 7, 9);

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).identity().nodeId()).isEqualTo("todo-2");
        assertThat(drafts.get(0).dependencyNodeIds()).containsExactly("todo-1");
        assertThat(drafts.get(0).workflow()).isEqualTo("LINEAR");
    }

    @Test
    void dagCreatesAllNodesWhoseDependenciesAreComplete() {
        LangchainTodoPlan plan = plan(
                todo("root", 1, List.of()),
                todo("left", 2, List.of("root")),
                todo("right", 3, List.of("root")),
                todo("join", 4, List.of("left", "right")));

        List<NodeWorkDraft> drafts = adapter.runnableDag(
                "run-1", 4, plan, Set.of("root"), Set.of(), 8, 10);

        assertThat(drafts).extracting(draft -> draft.identity().nodeId())
                .containsExactly("left", "right");
        assertThat(drafts).allMatch(draft -> draft.workflow().equals("DAG"));
    }

    @Test
    void existingWorkItemPreventsDuplicateCreation() {
        LangchainTodoPlan plan = plan(todo("todo-1", 1, List.of()));

        assertThat(adapter.runnableLinear(
                "run-1", 0, plan, Set.of(), Set.of("todo-1"), 0, 0)).isEmpty();
        assertThat(adapter.runnableDag(
                "run-1", 0, plan, Set.of(), Set.of("todo-1"), 0, 0)).isEmpty();
    }

    private LangchainTodoPlan plan(TodoItem... items) {
        return LangchainTodoPlan.builder().items(List.of(items)).build();
    }

    private TodoItem todo(String id, int sequence, List<String> dependencies) {
        return TodoItem.builder()
                .id(id)
                .sequence(sequence)
                .description(id)
                .dependsOn(dependencies)
                .build();
    }
}
