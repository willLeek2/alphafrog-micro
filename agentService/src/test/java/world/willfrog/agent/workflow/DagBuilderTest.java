package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DagBuilderTest {

    private final DagBuilder dagBuilder = new DagBuilder();

    @Test
    void buildGraph_shouldCreateGraphWithNoDependencies() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("searchStock").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("searchStock").build()
                ))
                .build();

        ExecutionGraph graph = dagBuilder.buildGraph(plan);

        assertEquals(2, graph.getTotalNodes());
        Set<String> ready = graph.getNodesWithZeroIndegree();
        assertEquals(2, ready.size());
        assertTrue(ready.contains("todo_1"));
        assertTrue(ready.contains("todo_2"));
    }

    @Test
    void buildGraph_shouldCreateLinearChain() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("b")
                                .dependsOn(List.of("todo_1")).build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("c")
                                .dependsOn(List.of("todo_2")).build()
                ))
                .build();

        ExecutionGraph graph = dagBuilder.buildGraph(plan);

        assertEquals(3, graph.getTotalNodes());
        Set<String> ready = graph.getNodesWithZeroIndegree();
        assertEquals(1, ready.size());
        assertTrue(ready.contains("todo_1"));
        assertEquals(0, graph.getIndegree("todo_1"));
        assertEquals(1, graph.getIndegree("todo_2"));
        assertEquals(1, graph.getIndegree("todo_3"));
        assertEquals(List.of("todo_2"), graph.getSuccessors("todo_1"));
        assertEquals(List.of("todo_3"), graph.getSuccessors("todo_2"));
    }

    @Test
    void buildGraph_shouldCreateDiamondDag() {
        //   1
        //  / \
        // 2   3
        //  \ /
        //   4
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("b")
                                .dependsOn(List.of("todo_1")).build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("c")
                                .dependsOn(List.of("todo_1")).build(),
                        TodoItem.builder().id("todo_4").type(TodoType.TOOL_CALL).toolName("d")
                                .dependsOn(List.of("todo_2", "todo_3")).build()
                ))
                .build();

        ExecutionGraph graph = dagBuilder.buildGraph(plan);

        assertEquals(4, graph.getTotalNodes());
        assertEquals(Set.of("todo_1"), graph.getNodesWithZeroIndegree());
        assertEquals(2, graph.getIndegree("todo_4"));
    }

    @Test
    void buildGraph_shouldDetectCyclicDependency() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a")
                                .dependsOn(List.of("todo_3")).build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("b")
                                .dependsOn(List.of("todo_1")).build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("c")
                                .dependsOn(List.of("todo_2")).build()
                ))
                .build();

        DagValidationException ex = assertThrows(DagValidationException.class,
                () -> dagBuilder.buildGraph(plan));
        assertTrue(ex.getMessage().contains("circular dependencies"));
    }

    @Test
    void buildGraph_shouldHandleEmptyPlan() {
        TodoPlan plan = TodoPlan.builder().items(List.of()).build();

        ExecutionGraph graph = dagBuilder.buildGraph(plan);

        assertEquals(0, graph.getTotalNodes());
        assertTrue(graph.getNodesWithZeroIndegree().isEmpty());
    }

    @Test
    void buildGraph_shouldHandleNullDependsOn() {
        List<TodoItem> items = new ArrayList<>();
        TodoItem item = TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a").build();
        item.setDependsOn(null);
        items.add(item);

        TodoPlan plan = TodoPlan.builder().items(items).build();

        ExecutionGraph graph = dagBuilder.buildGraph(plan);
        assertEquals(1, graph.getTotalNodes());
        assertEquals(Set.of("todo_1"), graph.getNodesWithZeroIndegree());
    }

    @Test
    void decrementIndegree_shouldBeThreadSafe() throws InterruptedException {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("a").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("b").build(),
                        TodoItem.builder().id("todo_3").type(TodoType.TOOL_CALL).toolName("c")
                                .dependsOn(List.of("todo_1", "todo_2")).build()
                ))
                .build();

        ExecutionGraph graph = dagBuilder.buildGraph(plan);
        assertEquals(2, graph.getIndegree("todo_3"));

        Thread t1 = new Thread(() -> graph.decrementIndegree("todo_3"));
        Thread t2 = new Thread(() -> graph.decrementIndegree("todo_3"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(0, graph.getIndegree("todo_3"));
    }

    @Test
    void buildGraph_shouldSupportParallelBatchWithFinalJoin() {
        // 10 parallel stock analyses → 1 summary
        List<TodoItem> items = new ArrayList<>();
        List<String> analysisIds = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String id = "todo_" + i;
            analysisIds.add(id);
            items.add(TodoItem.builder()
                    .id(id)
                    .type(TodoType.TOOL_CALL)
                    .toolName("searchStock")
                    .parallelizable(true)
                    .groupKey("batch_analysis")
                    .build());
        }
        items.add(TodoItem.builder()
                .id("todo_summary")
                .type(TodoType.SUB_AGENT)
                .toolName("summarize")
                .dependsOn(analysisIds)
                .build());

        TodoPlan plan = TodoPlan.builder().items(items).build();
        ExecutionGraph graph = dagBuilder.buildGraph(plan);

        assertEquals(11, graph.getTotalNodes());
        assertEquals(10, graph.getNodesWithZeroIndegree().size());
        assertEquals(10, graph.getIndegree("todo_summary"));
        assertFalse(graph.getNodesWithZeroIndegree().contains("todo_summary"));
    }
}
