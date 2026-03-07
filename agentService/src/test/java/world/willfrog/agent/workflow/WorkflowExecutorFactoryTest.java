package world.willfrog.agent.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class WorkflowExecutorFactoryTest {

    private final LinearWorkflowExecutor linear = mock(LinearWorkflowExecutor.class);
    private final DagWorkflowExecutor dag = mock(DagWorkflowExecutor.class);
    private final WorkflowExecutorFactory factory = new WorkflowExecutorFactory(linear, dag);

    @Test
    void getExecutor_shouldUseLinearForPlainPlan() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("t").build()))
                .build();

        assertSame(linear, factory.getExecutor(plan));
    }

    @Test
    void getExecutor_shouldUseDagWhenDependsOnExists() {
        TodoPlan plan = TodoPlan.builder()
                .items(List.of(
                        TodoItem.builder().id("todo_1").type(TodoType.TOOL_CALL).toolName("t").build(),
                        TodoItem.builder().id("todo_2").type(TodoType.TOOL_CALL).toolName("t").dependsOn(List.of("todo_1")).build()
                ))
                .build();

        assertSame(dag, factory.getExecutor(plan));
    }
}

