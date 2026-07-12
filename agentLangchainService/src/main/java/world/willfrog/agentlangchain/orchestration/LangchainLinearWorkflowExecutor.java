package world.willfrog.agentlangchain.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.workflow.DatasetRefRegistry;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LINEAR（线性）工作流执行器 —— 按顺序逐个执行 Todo，一步失败则整个 run 失败。
 *
 * <h2>与 DAG 执行器的关系</h2>
 * DAG 是主线（支持并行依赖图），LINEAR 是降级简化版（顺序执行）。
 * 当 planning LLM 判断任务无需并行（或 DAG 执行失败触发 FALLBACK_TO_LINEAR）时走此路径。
 * 面试被问"你们执行模式有几种"，答案在本类和 DAG 执行器。
 *
 * <h2>执行流程</h2>
 * <ol>
 *   <li>调用 planner 生成 Todo 列表</li>
 *   <li>逐个执行 Todo（每个 Todo 经过 {@link LangchainTodoNodeExecutor}）</li>
 *   <li>每完成一个 Todo 后注册 dataset ref，供后续 Todo 引用</li>
 *   <li>执行前后各检查一次 cancel/pause 状态</li>
 *   <li>全部完成后调用 final answer 生成</li>
 * </ol>
 *
 * <h2>cancel/pause 防护</h2>
 * 每个 Todo 执行前和 final answer 生成前都通过
 * {@link LangchainRunExecutionGuard#stopReason} 检查是否有未处理的 cancel/pause 信号。
 *
 * @see LangchainDagWorkflowExecutor DAG 并行执行器
 * @see LangchainWorkflowRouting 决策 LINEAR vs DAG 的路由逻辑
 */
@Service
@RequiredArgsConstructor
public class LangchainLinearWorkflowExecutor {

    private final LangchainAiPlanner planner;
    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final LangchainRunExecutionGuard executionGuard;
    private final AgentEventService eventService;

    public LangchainLinearWorkflowResult execute(LangchainLinearWorkflowRequest request) {
        validate(request);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            AgentContext.setPhase("planning");
            LangchainTodoPlan plan = planner.plan(LangchainPlanningRequest.builder()
                    .runId(request.getRunId())
                    .userId(request.getUserId())
                    .userGoal(request.getUserGoal())
                    .dialogueContext(request.getDialogueContext())
                    .model(request.planningModelOrDefault())
                    .planningEndpointName(request.getPlanningEndpointName())
                    .planningModelName(request.getPlanningModelName())
                    .planningProviderOrder(request.getPlanningProviderOrder())
                    .toolSpecifications(request.getToolSpecifications())
                    .executionMode(PlanExecutionMode.LINEAR)
                    .maxTodos(request.getMaxTodos())
                    .build());
            return executePlanned(request, plan, toolCalls);
        } catch (Exception e) {
            return LangchainLinearWorkflowResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } finally {
            AgentContext.clear();
        }
    }

    public LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                        LangchainTodoPlan plan) {
        validate(request);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            return executePlanned(request, plan, toolCalls);
        } catch (Exception e) {
            return LangchainLinearWorkflowResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .plan(plan)
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } finally {
            AgentContext.clear();
        }
    }

    private LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                         LangchainTodoPlan plan,
                                                         AtomicInteger toolCalls) {
        AgentContext.setWorkflow("linear");
        AgentContext.setExtractedEntities(plan.getExtractedEntities());
        List<LangchainCompletedTodo> completedTodos = new ArrayList<>();
        Map<String, String> datasetRefs = LangchainTodoUserMessageBuilder.newDatasetRefMap();
        for (TodoItem item : plan.getItems()) {
            Optional<String> stop = executionGuard.stopReason(request.getRunId(), request.getUserId());
            if (stop.isPresent()) {
                // Cancel/pause：当前剩余节点全部标记为 SKIPPED
                for (TodoItem remaining : plan.getItems()) {
                    if (remaining.getSequence() >= item.getSequence()) {
                        emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                                "TODO_NODE_SKIPPED", remaining, "run_canceled", 0,
                                null, false, null);
                    }
                }
                return interrupted(plan, completedTodos, stop.get(), toolCalls.get());
            }
            AgentContext.setPhase("linear_execution");
            AgentContext.setStage("todo_execution");
            emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                    "TODO_NODE_STARTED", item, null, 0, null, false, null);
            long nodeStartMs = System.currentTimeMillis();
            LangchainTodoNodeResult nodeResult = todoNodeExecutor.execute(
                    request, item, completedTodos, datasetRefs, toolCalls);
            long nodeDurationMs = System.currentTimeMillis() - nodeStartMs;
            if (nodeResult.isSuspended()) {
                emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                        "TODO_NODE_SUSPENDED", item, "external_tool_job_pending", nodeDurationMs,
                        null, false, null);
                return LangchainLinearWorkflowResult.builder()
                        .success(false)
                        .suspended(true)
                        .plan(plan)
                        .completedTodos(completedTodos)
                        .toolCallsUsed(toolCalls.get())
                        .suspendedTodoId(item.getId())
                        .suspendedTodoSequence(item.getSequence())
                        .pendingRunId(nodeResult.getPendingRunId())
                        .pendingToolCallId(nodeResult.getPendingToolCallId())
                        .pendingAttempt(nodeResult.getPendingAttempt())
                        .build();
            }
            if (!nodeResult.isSuccess()) {
                String reason = nvl(nodeResult.getFailureReason(), nodeResult.getSummary());
                Map<String, Object> failureMetadata = nodeResult.getFailureMetadata();
                emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                        "TODO_NODE_FAILED", item, reason, nodeDurationMs,
                        failureMetadata, false, null);
                // Phase 3.2 A3 M2/G2/G3: budget 超限分支 — 区分 partial (有产出) 与 fail-fast (零产出)，
                // 跳过 writeFinalAnswer() 的 LLM 调用（budget 已触顶，再发 LLM 会立即再触发 RunBudgetException）。
                if (failureMetadata != null && Boolean.TRUE.equals(failureMetadata.get("budget_exceeded"))) {
                    return handleBudgetExhaustion(request, plan, completedTodos, reason,
                            failureMetadata, toolCalls.get());
                }
                return failure(plan, completedTodos, reason, toolCalls.get(), failureMetadata);
            }
            emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                    "TODO_NODE_COMPLETED", item, null, nodeDurationMs,
                    null, nodeResult.isRecovered(), nodeResult.getRecoveryOutcome());
            String trimmed = nodeResult.getOutput();
            DatasetRefRegistry.registerFromJson(trimmed, datasetRefs);
            completedTodos.add(LangchainCompletedTodo.builder()
                    .todoId(item.getId())
                    .sequence(item.getSequence())
                    .description(item.getDescription())
                    .output(trimmed)
                    .summary(nodeResult.getSummary())
                    .build());
        }

        Optional<String> stopBeforeAnswer = executionGuard.stopReason(request.getRunId(), request.getUserId());
        if (stopBeforeAnswer.isPresent()) {
            return interrupted(plan, completedTodos, stopBeforeAnswer.get(), toolCalls.get());
        }

        AgentContext.setPhase("summarizing");
        AgentContext.setStage("final_answer");
        String finalAnswer = todoNodeExecutor.writeFinalAnswer(request, completedTodos);
        if (isBlank(finalAnswer)) {
            return failure(plan, completedTodos, "empty_final_answer", toolCalls.get(), null);
        }
        return LangchainLinearWorkflowResult.builder()
                .success(true)
                .finalAnswer(finalAnswer.trim())
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCalls.get())
                .build();
    }

    private LangchainLinearWorkflowResult failure(LangchainTodoPlan plan,
                                                  List<LangchainCompletedTodo> completedTodos,
                                                  String reason,
                                                  int toolCallsUsed,
                                                  Map<String, Object> failureMetadata) {
        return LangchainLinearWorkflowResult.builder()
                .success(false)
                .failureReason(reason)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .failureMetadata(failureMetadata)
                .build();
    }

    /**
     * Phase 3.2 A3 M3/G3: budget 触顶时的确定性降级路径。
     * <ul>
     *   <li>已完成 todo ≥ 1 → 用 {@link LangchainBudgetPartialAnswerBuilder} 拼 deterministic finalAnswer
     *       （受 MAX_TODOS / MAX_PER_TODO_CHARS / MAX_TOTAL_CHARS 三重上限保护），发 WORKFLOW_PARTIAL_BUDGET；</li>
     *   <li>已完成 todo = 0 → 无 partial 内容可拼，发 WORKFLOW_FAILED_BUDGET（completed_todo_count=0）；</li>
     *   <li>两条路径都不调用 {@code todoNodeExecutor.writeFinalAnswer()} —— budget 已超限，再触发 LLM
     *       会立即再被 {@code AgentRunBudgetService.check()} 拦截抛 {@code RunBudgetException}。</li>
     * </ul>
     * failureMetadata 来源：{@link LangchainTodoNodeExecutor#extractBudgetFailureMetadata} 写入的
     * {@code budget_exceeded=true, dimension, actual, limit, ratio, partial} 子 map。
     */
    private LangchainLinearWorkflowResult handleBudgetExhaustion(LangchainLinearWorkflowRequest request,
                                                                  LangchainTodoPlan plan,
                                                                  List<LangchainCompletedTodo> completedTodos,
                                                                  String reason,
                                                                  Map<String, Object> budgetMetadata,
                                                                  int toolCallsUsed) {
        String runId = request.getRunId();
        String userId = request.getUserId();
        String dimension = nvl(String.valueOf(budgetMetadata.get("dimension")), "unknown");
        long actual = toLong(budgetMetadata.get("actual"));
        long limit = toLong(budgetMetadata.get("limit"));
        double ratio = toDouble(budgetMetadata.get("ratio"));

        if (!completedTodos.isEmpty()) {
            LangchainBudgetPartialAnswerBuilder.PartialAnswer partial =
                    LangchainBudgetPartialAnswerBuilder.build(completedTodos);
            String partialReason = "RUN_BUDGET_EXCEEDED:" + dimension + ":" + actual + "/" + limit
                    + " — partial answer built from " + partial.includedTodoCount() + " completed todo(s)";
            LangchainLinearWorkflowResult result = LangchainLinearWorkflowResult.builder()
                    .success(false)
                    .partial(true)
                    .failureReason(partialReason)
                    .finalAnswer(partial.finalAnswer())
                    .plan(plan)
                    .completedTodos(completedTodos)
                    .toolCallsUsed(toolCallsUsed)
                    .failureMetadata(budgetMetadata)
                    .build();
            if (!isBlank(runId) && !isBlank(userId)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.putAll(LangchainBudgetPartialAnswerBuilder.completedTodoIdsPayload(completedTodos));
                payload.put("dimension", dimension);
                payload.put("actual", actual);
                payload.put("limit", limit);
                payload.put("ratio", ratio);
                payload.put("final_answer", partial.finalAnswer());
                payload.put("final_answer_length", partial.finalAnswerLength());
                payload.put("final_answer_included_todo_count", partial.includedTodoCount());
                payload.put("final_answer_skipped_todo_count", partial.skippedTodoCount());
                payload.put("final_answer_original_total_length", partial.originalTotalLength());
                payload.put("tool_calls_used", toolCallsUsed);
                payload.put("failure_reason", partialReason);
                try {
                    eventService.append(runId, userId, "WORKFLOW_PARTIAL_BUDGET", payload);
                } catch (Exception e) {
                    // 事件失败不影响降级结果
                }
            }
            return result;
        }

        // completedTodos 空：fail-fast，无 partial 可拼
        String failedReason = "RUN_BUDGET_EXCEEDED:" + dimension + ":" + actual + "/" + limit
                + " — no completed todo, fail-fast";
        LangchainLinearWorkflowResult result = LangchainLinearWorkflowResult.builder()
                .success(false)
                .failureReason(failedReason)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .failureMetadata(budgetMetadata)
                .build();
        if (!isBlank(runId) && !isBlank(userId)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dimension", dimension);
            payload.put("actual", actual);
            payload.put("limit", limit);
            payload.put("ratio", ratio);
            payload.put("completed_todo_count", 0);
            payload.put("tool_calls_used", toolCallsUsed);
            payload.put("failure_reason", failedReason);
            try {
                eventService.append(runId, userId, "WORKFLOW_FAILED_BUDGET", payload);
            } catch (Exception e) {
                // 事件失败不影响降级结果
            }
        }
        return result;
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException nfe) {
                return 0L;
            }
        }
        return 0L;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException nfe) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private LangchainLinearWorkflowResult interrupted(LangchainTodoPlan plan,
                                                      List<LangchainCompletedTodo> completedTodos,
                                                      String controlStatus,
                                                      int toolCallsUsed) {
        return LangchainLinearWorkflowResult.builder()
                .success(false)
                .interrupted(true)
                .failureReason("RUN_INTERRUPTED:" + controlStatus)
                .plan(plan)
                .completedTodos(completedTodos)
                .toolCallsUsed(toolCallsUsed)
                .build();
    }

    private void applyRunContext(LangchainLinearWorkflowRequest request) {
        if (!isBlank(request.getRunId())) {
            AgentContext.setRunId(request.getRunId());
        }
        if (!isBlank(request.getUserId())) {
            AgentContext.setUserId(request.getUserId());
        }
        AgentContext.setWebSearchEnabled(Boolean.TRUE.equals(request.getWebSearchEnabled()));
    }

    private void validate(LangchainLinearWorkflowRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("linear_workflow_request_required");
        }
        if (request.getModel() == null && request.getPlanningModel() == null) {
            throw new IllegalArgumentException("linear_workflow_chat_model_required");
        }
        if (isBlank(request.getUserGoal())) {
            throw new IllegalArgumentException("linear_workflow_user_goal_required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String primary, String fallback) {
        if (!isBlank(primary)) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    private void emitTodoNodeEvent(String runId, String userId, String eventType,
                                    TodoItem item, String reason, long durationMs,
                                    Map<String, Object> failureMetadata,
                                    boolean recovered, String recoveryOutcome) {
        if (isBlank(runId) || isBlank(userId)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("todo_id", item.getId());
        payload.put("todo_sequence", item.getSequence());
        payload.put("workflow", nvl(AgentContext.getWorkflow(), "linear"));
        payload.put("phase", "execution");
        boolean isStarted = "TODO_NODE_STARTED".equals(eventType);
        if (isStarted) {
            payload.put("started_at", System.currentTimeMillis());
        } else {
            payload.put("duration_ms", durationMs);
        }
        if (!isBlank(reason)) {
            if ("TODO_NODE_FAILED".equals(eventType)) {
                payload.put("failure_reason", reason);
                if (reason.startsWith("RUN_INTERRUPTED:CANCEL")) {
                    payload.put("error_code", "RUN_CANCELED");
                }
            } else {
                payload.put("reason", reason);
            }
        }
        // recovery 成功标记：仅 TODO_NODE_COMPLETED 时填，TODO_NODE_FAILED 的 recovery 信息在 failureMetadata 里
        if (!isStarted && recovered) {
            payload.put("recovered", true);
            if (!isBlank(recoveryOutcome)) {
                payload.put("recovery_outcome", recoveryOutcome);
            }
        }
        // failureMetadata 结构化观测：按语义路由到对应子字段（budget_failure / empty_output_observation / failure_metadata），
        // 让压测报告 / dashboard 能直接消费，不必回 trace 翻。
        // Phase 3.2 A3: budget metadata 不再误挂 empty_output_observation，避免 budget failure 被误归类为 empty_todo_output。
        if (failureMetadata != null && !failureMetadata.isEmpty()) {
            String field = LangchainTodoNodeResult.routeFailureMetadataField(failureMetadata);
            if (field != null) {
                payload.put(field, failureMetadata);
            }
        }
        try {
            eventService.append(runId, userId, eventType, payload);
        } catch (Exception e) {
            // 事件失败不影响节点执行
        }
    }
}
