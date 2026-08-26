package world.willfrog.agentlangchain.orchestration;

import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 线性工作流在进入执行循环前的恢复/重启校验。
 *
 * <p>从 {@link LangchainLinearWorkflowExecutor} 原样迁出六段检查：还原已完成前缀、
 * 定位恢复边界、边界节点必须在计划里、算出序号边界、已完成前缀不得越过边界、
 * 已消费的失败终态不得继续跑后续待办。原因码字符串是对外契约，本类不得改字。</p>
 *
 * <p>首次执行（恢复上下文与重启检查点都为空）不做这些检查，只给出空前缀。</p>
 */
public class WorkflowResumeValidator {

    static final String RESUME_TODO_NOT_IN_PLAN = "resume_todo_not_in_plan";
    static final String RESTART_TODO_NOT_IN_PLAN = "restart_todo_not_in_plan";
    static final String RESUME_COMPLETED_TODO_OUT_OF_ORDER = "resume_completed_todo_out_of_order";
    static final String RESTART_COMPLETED_TODO_OUT_OF_ORDER = "restart_completed_todo_out_of_order";
    static final String PYTHON_REPAIR_EXHAUSTED = "python_repair_exhausted";
    static final String EXTERNAL_TOOL_TERMINAL_FAILURE = "external_tool_terminal_failure";

    private static final Set<String> REPAIRABLE_PYTHON_EXIT_REASONS = Set.of("NON_ZERO_EXIT");

    /**
     * 跑完六段检查。违例时 {@link Result#ok()} 为 false，{@link Result#violationCode()} 为原原因码。
     */
    public Result validate(LangchainTodoPlan plan,
                           ToolJobResumeContext resumeContext,
                           WorkflowExecutionCheckpoint restartCheckpoint) {
        List<LangchainCompletedTodo> completedTodos = buildCompletedTodos(resumeContext, restartCheckpoint);
        Set<String> completedIds = completedTodos.stream()
                .map(LangchainCompletedTodo::getTodoId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean handoffAccepted = resumeContext != null && resumeContext.isResultConsumed();
        boolean activePythonRepair = handoffAccepted && isActivePythonRepair(resumeContext);
        boolean resumeAtFinal = handoffAccepted
                && ToolJobResumeContext.FINAL_TODO_ID.equals(resumeContext.getTodoId());
        boolean restartAtFinal = restartCheckpoint != null
                && WorkflowExecutionCheckpoint.FINAL_TODO_ID.equals(restartCheckpoint.getNextTodoId());
        String boundaryTodoId = resumeContext != null
                ? resumeContext.getTodoId()
                : restartCheckpoint == null ? null : restartCheckpoint.getNextTodoId();
        TodoItem suspendedItem = locateBoundaryTodo(
                plan, resumeContext, restartCheckpoint, resumeAtFinal, restartAtFinal, boundaryTodoId);
        String missingTodoCode = checkBoundaryTodoExists(
                resumeContext, restartCheckpoint, suspendedItem, resumeAtFinal, restartAtFinal);
        if (missingTodoCode != null) {
            return Result.rejected(missingTodoCode, completedTodos, completedIds, handoffAccepted,
                    activePythonRepair, resumeAtFinal, restartAtFinal, suspendedItem, Integer.MIN_VALUE);
        }
        int resumeSequence = computeResumeSequence(resumeAtFinal, restartAtFinal, suspendedItem);
        String orderCode = checkCompletedPrefixNotOverlapping(
                resumeContext, restartCheckpoint, completedTodos, resumeSequence);
        if (orderCode != null) {
            return Result.rejected(orderCode, completedTodos, completedIds, handoffAccepted,
                    activePythonRepair, resumeAtFinal, restartAtFinal, suspendedItem, resumeSequence);
        }
        String consumedFailureCode = checkConsumedFailureMustNotContinue(
                resumeContext, handoffAccepted, activePythonRepair);
        if (consumedFailureCode != null) {
            return Result.rejected(consumedFailureCode, completedTodos, completedIds, handoffAccepted,
                    activePythonRepair, resumeAtFinal, restartAtFinal, suspendedItem, resumeSequence);
        }
        return Result.accepted(completedTodos, completedIds, handoffAccepted, activePythonRepair,
                resumeAtFinal, restartAtFinal, suspendedItem, resumeSequence);
    }

    /** 首次执行空列表；恢复/重启从存档还原已完成前缀。 */
    List<LangchainCompletedTodo> buildCompletedTodos(ToolJobResumeContext resumeContext,
                                                     WorkflowExecutionCheckpoint restartCheckpoint) {
        if (resumeContext != null) {
            return restoreCompletedTodos(resumeContext.getCompletedTodos());
        }
        if (restartCheckpoint != null) {
            return restoreCompletedTodos(restartCheckpoint.getCompletedTodos());
        }
        return new ArrayList<>();
    }

    /**
     * 长工具恢复找原挂起待办；服务重启找检查点指向的下一待办。
     * 首次执行、或边界已是最终回答位置时，没有挂起待办。
     */
    TodoItem locateBoundaryTodo(LangchainTodoPlan plan,
                                ToolJobResumeContext resumeContext,
                                WorkflowExecutionCheckpoint restartCheckpoint,
                                boolean resumeAtFinal,
                                boolean restartAtFinal,
                                String boundaryTodoId) {
        if ((resumeContext == null && restartCheckpoint == null) || resumeAtFinal || restartAtFinal) {
            return null;
        }
        return plan.getItems().stream()
                .filter(item -> Objects.equals(item.getId(), boundaryTodoId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 恢复/重启边界必须能在原计划里找到。找不到说明上下文坏了，直接拒绝。
     *
     * @return 原因码；通过时返回 null
     */
    String checkBoundaryTodoExists(ToolJobResumeContext resumeContext,
                                   WorkflowExecutionCheckpoint restartCheckpoint,
                                   TodoItem suspendedItem,
                                   boolean resumeAtFinal,
                                   boolean restartAtFinal) {
        if ((resumeContext != null || restartCheckpoint != null)
                && suspendedItem == null && !resumeAtFinal && !restartAtFinal) {
            return resumeContext != null ? RESUME_TODO_NOT_IN_PLAN : RESTART_TODO_NOT_IN_PLAN;
        }
        return null;
    }

    /**
     * 恢复序号是已完成前缀的严格上界。最终回答位置当作「所有普通待办之后」。
     */
    int computeResumeSequence(boolean resumeAtFinal, boolean restartAtFinal, TodoItem suspendedItem) {
        if (resumeAtFinal || restartAtFinal) {
            return Integer.MAX_VALUE;
        }
        return suspendedItem == null ? Integer.MIN_VALUE : suspendedItem.getSequence();
    }

    /**
     * 已完成快照不能包含挂起节点或它后面的节点，否则存档顺序自相矛盾。
     *
     * @return 原因码；通过时返回 null
     */
    String checkCompletedPrefixNotOverlapping(ToolJobResumeContext resumeContext,
                                              WorkflowExecutionCheckpoint restartCheckpoint,
                                              List<LangchainCompletedTodo> completedTodos,
                                              int resumeSequence) {
        if ((resumeContext != null || restartCheckpoint != null) && completedTodos.stream()
                .anyMatch(todo -> todo.getSequence() >= resumeSequence)) {
            return resumeContext != null
                    ? RESUME_COMPLETED_TODO_OUT_OF_ORDER
                    : RESTART_COMPLETED_TODO_OUT_OF_ORDER;
        }
        return null;
    }

    /**
     * 终态已被工作流接受、且不是进行中的 Python 修复时：失败终态必须落成确定性失败，
     * 不得继续跑后面的待办。
     *
     * @return 原因码；通过时返回 null
     */
    String checkConsumedFailureMustNotContinue(ToolJobResumeContext resumeContext,
                                               boolean handoffAccepted,
                                               boolean activePythonRepair) {
        if (handoffAccepted && !activePythonRepair
                && (!resumeContext.isTerminalSuccess() || resumeContext.isPythonRepairPending())) {
            if (resumeContext.isPythonRepairExhausted()) {
                return PYTHON_REPAIR_EXHAUSTED;
            }
            return EXTERNAL_TOOL_TERMINAL_FAILURE;
        }
        return null;
    }

    boolean isActivePythonRepair(ToolJobResumeContext context) {
        return context != null
                && isRepairablePythonFailure(context)
                && context.isPythonRepairPending()
                && !context.isPythonRepairExhausted()
                && context.getPythonRepairAttempt() > 0
                && context.isResultConsumed();
    }

    boolean isRepairablePythonFailure(ToolJobResumeContext context) {
        return context != null
                && !context.isTerminalSuccess()
                && "FAILED".equals(context.getTerminalStatus())
                && Boolean.FALSE.equals(context.getTerminalRetryable())
                && REPAIRABLE_PYTHON_EXIT_REASONS.contains(
                        nvl(context.getTerminalExitReason(), "").toUpperCase(java.util.Locale.ROOT))
                && context.getPythonFailedRequestFingerprints() != null
                && !context.getPythonFailedRequestFingerprints().isEmpty();
    }

    private List<LangchainCompletedTodo> restoreCompletedTodos(List<CompletedTodoRecord> records) {
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }
        return records.stream().map(record -> LangchainCompletedTodo.builder()
                        .todoId(record.getTodoId())
                        .sequence(record.getSequence())
                        .description(record.getDescription())
                        .modelOutput(record.getModelOutput())
                        .output(record.getOutput())
                        .summary(record.getSummary())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static String nvl(String primary, String fallback) {
        if (primary == null || primary.trim().isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        return primary;
    }

    /** 校验结果：通过时带给执行循环用的派生状态；失败时带原样原因码。 */
    public static final class Result {
        private final boolean ok;
        private final String violationCode;
        private final List<LangchainCompletedTodo> completedTodos;
        private final Set<String> completedIds;
        private final boolean handoffAccepted;
        private final boolean activePythonRepair;
        private final boolean resumeAtFinal;
        private final boolean restartAtFinal;
        private final TodoItem suspendedItem;
        private final int resumeSequence;

        private Result(boolean ok,
                       String violationCode,
                       List<LangchainCompletedTodo> completedTodos,
                       Set<String> completedIds,
                       boolean handoffAccepted,
                       boolean activePythonRepair,
                       boolean resumeAtFinal,
                       boolean restartAtFinal,
                       TodoItem suspendedItem,
                       int resumeSequence) {
            this.ok = ok;
            this.violationCode = violationCode;
            this.completedTodos = completedTodos;
            this.completedIds = completedIds;
            this.handoffAccepted = handoffAccepted;
            this.activePythonRepair = activePythonRepair;
            this.resumeAtFinal = resumeAtFinal;
            this.restartAtFinal = restartAtFinal;
            this.suspendedItem = suspendedItem;
            this.resumeSequence = resumeSequence;
        }

        static Result accepted(List<LangchainCompletedTodo> completedTodos,
                               Set<String> completedIds,
                               boolean handoffAccepted,
                               boolean activePythonRepair,
                               boolean resumeAtFinal,
                               boolean restartAtFinal,
                               TodoItem suspendedItem,
                               int resumeSequence) {
            return new Result(true, null, completedTodos, completedIds, handoffAccepted, activePythonRepair,
                    resumeAtFinal, restartAtFinal, suspendedItem, resumeSequence);
        }

        static Result rejected(String violationCode,
                               List<LangchainCompletedTodo> completedTodos,
                               Set<String> completedIds,
                               boolean handoffAccepted,
                               boolean activePythonRepair,
                               boolean resumeAtFinal,
                               boolean restartAtFinal,
                               TodoItem suspendedItem,
                               int resumeSequence) {
            return new Result(false, violationCode, completedTodos, completedIds, handoffAccepted,
                    activePythonRepair, resumeAtFinal, restartAtFinal, suspendedItem, resumeSequence);
        }

        public boolean ok() {
            return ok;
        }

        public String violationCode() {
            return violationCode;
        }

        public List<LangchainCompletedTodo> completedTodos() {
            return completedTodos;
        }

        public Set<String> completedIds() {
            return completedIds;
        }

        public boolean handoffAccepted() {
            return handoffAccepted;
        }

        public boolean activePythonRepair() {
            return activePythonRepair;
        }

        public boolean resumeAtFinal() {
            return resumeAtFinal;
        }

        public boolean restartAtFinal() {
            return restartAtFinal;
        }

        public TodoItem suspendedItem() {
            return suspendedItem;
        }

        public int resumeSequence() {
            return resumeSequence;
        }
    }
}
