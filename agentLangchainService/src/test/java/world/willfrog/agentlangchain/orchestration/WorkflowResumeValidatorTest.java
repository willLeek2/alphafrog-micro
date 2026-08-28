package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接构造损坏的恢复上下文喂给校验器，不启动执行环境。
 */
class WorkflowResumeValidatorTest {

    private final WorkflowResumeValidator validator = new WorkflowResumeValidator();

    @Test
    void firstRunPassesWithEmptyPrefix() {
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.violationCode()).isNull();
        assertThat(result.completedTodos()).isEmpty();
        assertThat(result.suspendedItem()).isNull();
        assertThat(result.resumeSequence()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void resumeBoundaryTodoMustExistInPlan() {
        ToolJobResumeContext context = successResume("missing-todo", false);
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), context, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.violationCode()).isEqualTo(WorkflowResumeValidator.RESUME_TODO_NOT_IN_PLAN);
    }

    @Test
    void restartBoundaryTodoMustExistInPlan() {
        WorkflowExecutionCheckpoint checkpoint = new WorkflowExecutionCheckpoint();
        checkpoint.setNextTodoId("missing-todo");
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), null, checkpoint);

        assertThat(result.ok()).isFalse();
        assertThat(result.violationCode()).isEqualTo(WorkflowResumeValidator.RESTART_TODO_NOT_IN_PLAN);
    }

    @Test
    void resumeAtFinalAnswerSkipsMissingTodoCheck() {
        ToolJobResumeContext context = successResume(ToolJobResumeContext.FINAL_TODO_ID, true);
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), context, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.suspendedItem()).isNull();
        assertThat(result.resumeSequence()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.resumeAtFinal()).isTrue();
    }

    @Test
    void restartAtFinalAnswerSkipsMissingTodoCheck() {
        WorkflowExecutionCheckpoint checkpoint = new WorkflowExecutionCheckpoint();
        checkpoint.setNextTodoId(WorkflowExecutionCheckpoint.FINAL_TODO_ID);
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), null, checkpoint);

        assertThat(result.ok()).isTrue();
        assertThat(result.suspendedItem()).isNull();
        assertThat(result.resumeSequence()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.restartAtFinal()).isTrue();
    }

    @Test
    void resumeCompletedPrefixMustNotReachBoundary() {
        ToolJobResumeContext context = successResume("todo-2", false);
        CompletedTodoRecord overlapping = record("todo-2", 2);
        context.setCompletedTodos(List.of(overlapping));
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), context, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.violationCode())
                .isEqualTo(WorkflowResumeValidator.RESUME_COMPLETED_TODO_OUT_OF_ORDER);
    }

    @Test
    void restartCompletedPrefixMustNotReachBoundary() {
        WorkflowExecutionCheckpoint checkpoint = new WorkflowExecutionCheckpoint();
        checkpoint.setNextTodoId("todo-2");
        checkpoint.setCompletedTodos(List.of(record("todo-2", 2)));
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), null, checkpoint);

        assertThat(result.ok()).isFalse();
        assertThat(result.violationCode())
                .isEqualTo(WorkflowResumeValidator.RESTART_COMPLETED_TODO_OUT_OF_ORDER);
    }

    @Test
    void validResumeKeepsBoundaryAndPrefix() {
        ToolJobResumeContext context = successResume("todo-2", false);
        context.setCompletedTodos(List.of(record("todo-1", 1)));
        WorkflowResumeValidator.Result result = validator.validate(threeTodoPlan(), context, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.suspendedItem().getId()).isEqualTo("todo-2");
        assertThat(result.resumeSequence()).isEqualTo(2);
        assertThat(result.completedIds()).containsExactly("todo-1");
        assertThat(result.handoffAccepted()).isFalse();
    }

    @Test
    void consumedFailedTerminalWithoutActiveRepairIsExternalToolFailure() {
        ToolJobResumeContext context = failedPythonContext(true, 1);
        context.setTerminalExitReason("OOM");
        context.setTodoId("todo-2");
        WorkflowResumeValidator.Result result = validator.validate(singleTodoPlan(), context, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.violationCode())
                .isEqualTo(WorkflowResumeValidator.EXTERNAL_TOOL_TERMINAL_FAILURE);
    }

    @Test
    void consumedExhaustedPythonRepairUsesExhaustedCode() {
        ToolJobResumeContext context = failedPythonContext(true, 2);
        context.setPythonRepairPending(false);
        context.setPythonRepairExhausted(true);
        context.setTodoId("todo-2");
        WorkflowResumeValidator.Result result = validator.validate(singleTodoPlan(), context, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.violationCode()).isEqualTo(WorkflowResumeValidator.PYTHON_REPAIR_EXHAUSTED);
    }

    @Test
    void activePythonRepairDoesNotFailConsumedCheck() {
        ToolJobResumeContext context = failedPythonContext(true, 1);
        context.setTodoId("todo-2");
        WorkflowResumeValidator.Result result = validator.validate(singleTodoPlan(), context, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.activeRepair()).isTrue();
        assertThat(result.handoffAccepted()).isTrue();
    }

    private static LangchainTodoPlan threeTodoPlan() {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-1", 1), item("todo-2", 2), item("todo-3", 3)))
                .build();
    }

    private static LangchainTodoPlan singleTodoPlan() {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
    }

    private static TodoItem item(String id, int sequence) {
        return TodoItem.builder().id(id).sequence(sequence).description(id).build();
    }

    private static CompletedTodoRecord record(String todoId, int sequence) {
        CompletedTodoRecord record = new CompletedTodoRecord();
        record.setTodoId(todoId);
        record.setSequence(sequence);
        record.setDescription(todoId);
        return record;
    }

    private static ToolJobResumeContext successResume(String todoId, boolean consumed) {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setTodoId(todoId);
        context.setTerminalSuccess(true);
        context.setResultConsumed(consumed);
        return context;
    }

    private static ToolJobResumeContext failedPythonContext(boolean consumed, int repairAttempt) {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setTodoId("todo-2");
        context.setTodoSequence(2);
        context.setTerminalSuccess(false);
        context.setTerminalStatus("FAILED");
        context.setTerminalRetryable(false);
        context.setTerminalExitReason("NON_ZERO_EXIT");
        context.setPythonRepairAttempt(repairAttempt);
        context.setPythonRepairPending(consumed);
        context.setPythonFailedRequestFingerprints(List.of("sha256:failed-code"));
        context.setResultConsumed(consumed);
        return context;
    }
}
