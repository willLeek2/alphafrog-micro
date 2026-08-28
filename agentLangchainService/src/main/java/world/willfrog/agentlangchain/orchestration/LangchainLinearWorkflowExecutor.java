package world.willfrog.agentlangchain.orchestration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.CodeRefineProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.CodeRefineLocalConfigLoader;
import world.willfrog.agent.workflow.DatasetRefRegistry;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * LINEAR（线性）工作流执行器 —— 按顺序逐个执行 Todo，一步失败则整个 run 失败。
 *
 * <h2>与 DAG 执行器的关系</h2>
 * DAG 是主线（支持并行依赖图），LINEAR 是降级简化版（顺序执行）。
 * 当 planning LLM 判断任务无需并行（或 DAG 执行失败触发 FALLBACK_TO_LINEAR）时走此路径。
 * <h2>执行流程</h2>
 * <ol>
 *   <li>接收 pipeline 已冻结的 Todo 计划</li>
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
public class LangchainLinearWorkflowExecutor {

    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final LangchainRunExecutionGuard executionGuard;
    private final AgentRunEventService eventService;
    private final CodeRefineLocalConfigLoader codeRefineConfigLoader;
    private final CodeRefineProperties startupCodeRefineProperties;

    /**
     * 生产 Spring 注入的修复目录。测试直接构造时为空，回落到带本执行器配置的 handler。
     */
    @Autowired(required = false)
    private ToolRepairCatalog repairCatalog;

    /**
     * 工作流级 Todo 边界 checkpoint。测试或极简上下文可以不装配；生产 Spring 上下文必须存在。
     */
    @Autowired(required = false)
    private WorkflowCheckpointService workflowCheckpointService;

    /**
     * 生产构造器显式选择五个依赖，避免存在测试便利构造器时 Spring 猜错构造器。
     */
    @Autowired
    public LangchainLinearWorkflowExecutor(LangchainTodoNodeExecutor todoNodeExecutor,
                                           LangchainRunExecutionGuard executionGuard,
                                           AgentRunEventService eventService,
                                           CodeRefineLocalConfigLoader codeRefineConfigLoader,
                                           CodeRefineProperties startupCodeRefineProperties) {
        this.todoNodeExecutor = todoNodeExecutor;
        this.executionGuard = executionGuard;
        this.eventService = eventService;
        this.codeRefineConfigLoader = codeRefineConfigLoader;
        this.startupCodeRefineProperties = startupCodeRefineProperties;
    }

    /** 测试与历史直接构造调用的兼容入口；生产 Spring 必须使用上面的 @Autowired 构造器。 */
    public LangchainLinearWorkflowExecutor(LangchainTodoNodeExecutor todoNodeExecutor,
                                           LangchainRunExecutionGuard executionGuard,
                                           AgentRunEventService eventService) {
        this(todoNodeExecutor, executionGuard, eventService,
                null, new CodeRefineProperties());
    }

    public LangchainLinearWorkflowResult executePlanned(LangchainLinearWorkflowRequest request,
                                                        LangchainTodoPlan plan) {
        validate(request);
        AtomicInteger toolCalls = new AtomicInteger();
        try {
            applyRunContext(request);
            plan = LangchainWorkflowRouting.effectivePlan(plan, true);
            return executePlanned(request, plan, toolCalls, null, null, null);
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

    /**
     * 从持久化 checkpoint 恢复已经规划过的 LINEAR 工作流。
     *
     * @param request 在新 worker 上重建的 Run/模型/工具配置
     * @param plan 挂起前已经落库的原计划；恢复过程绝不重新调用 planner
     * @param context 已完成 Todo、dataset 快照、挂起点、终态结果及恢复租约
     * @param terminalConsumed 结果注入内存后执行的持久化消费确认
     * @return 继续执行后的工作流结果，也可能再次 suspended
     */
    public LangchainLinearWorkflowResult resumePlanned(LangchainLinearWorkflowRequest request,
                                                       LangchainTodoPlan plan,
                                                       ToolJobResumeContext context,
                                                       BooleanSupplier terminalConsumed) {
        // 使用普通执行相同的 Run 身份和模型配置校验。
        validate(request);
        // plan/context 任一缺失都不能猜测恢复点或重新规划。
        if (plan == null || context == null) {
            throw new IllegalArgumentException("resume_plan_and_context_required");
        }
        // 从挂起点延续工具调用计数，避免上下文切换绕过 run 预算。
        AtomicInteger toolCalls = new AtomicInteger(Math.max(0, context.getToolCallsUsed()));
        try {
            // 新 worker 需要重新建立 AgentContext，旧线程已经在 finally 中清理。
            applyRunContext(request);
            // 传入原计划与恢复上下文，执行器会跳过已完成前缀。
            return executePlanned(request, plan, toolCalls, context, terminalConsumed, null);
        } catch (Exception e) {
            // 把恢复异常转换为普通 workflow result，由 pipeline 统一持久化。
            return LangchainLinearWorkflowResult.builder()
                    .success(false)
                    .failureReason(e.getMessage())
                    .plan(plan)
                    .toolCallsUsed(toolCalls.get())
                    .build();
        } finally {
            // 方法也可被测试或同步调用，因此在这里再次保证 ThreadLocal 清理。
            AgentContext.clear();
        }
    }

    /**
     * 服务重启后的 LINEAR 执行：复用冻结 Plan 和已完成前缀，从 checkpoint 指向的 Todo 开头重跑。
     * 这不是 ToolJob handoff，不设置 resume token，也不尝试接管崩溃前的 Sandbox 任务。
     */
    public LangchainLinearWorkflowResult restartPlanned(LangchainLinearWorkflowRequest request,
                                                        LangchainTodoPlan plan,
                                                        WorkflowExecutionCheckpoint checkpoint) {
        validate(request);
        if (plan == null || checkpoint == null) {
            throw new IllegalArgumentException("restart_plan_and_checkpoint_required");
        }
        AtomicInteger toolCalls = new AtomicInteger(Math.max(0, checkpoint.getToolCallsUsed()));
        try {
            applyRunContext(request);
            return executePlanned(request, plan, toolCalls, null, null, checkpoint);
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
                                                         AtomicInteger toolCalls,
                                                         ToolJobResumeContext resumeContext,
                                                         BooleanSupplier terminalConsumed,
                                                         WorkflowExecutionCheckpoint restartCheckpoint) {
        // 恢复和首次执行共享本方法；resumeContext 为 null 表示首次执行。
        AgentContext.setWorkflow("linear");
        // extractedEntities 来自原 plan，不重新运行 planning LLM。
        AgentContext.setExtractedEntities(plan.getExtractedEntities());
        // 进入循环前的恢复/重启不变量由 WorkflowResumeValidator 一次性检查；违例带原原因码失败即关闭。
        WorkflowResumeValidator.Result resumeCheck = resumeValidator().validate(
                plan, resumeContext, restartCheckpoint);
        List<LangchainCompletedTodo> completedTodos = resumeCheck.completedTodos();
        if (!resumeCheck.ok()) {
            Map<String, Object> failureMetadata = isHandlerExhaustedCode(resumeCheck.violationCode())
                    ? exhaustedMetadata(resumeContext)
                    : null;
            return failure(plan, completedTodos, resumeCheck.violationCode(),
                    toolCalls.get(), failureMetadata);
        }
        // datasetRefs 是当前 worker 的堆内映射，必须从已完成输出重新注册。
        Map<String, String> datasetRefs = LangchainTodoUserMessageBuilder.newDatasetRefMap();
        completedTodos.forEach(todo -> DatasetRefRegistry.registerFromJson(todo.displayOutput(), datasetRefs));
        // completedIds 用于 O(1) 跳过已经确认成功写入数据库的待办节点，防止重复工具副作用。
        java.util.Set<String> completedIds = resumeCheck.completedIds();
        // resultConsumed=true 表示上一次恢复已确认消费终态，当前应从下一节点继续。
        boolean handoffAccepted = resumeCheck.handoffAccepted();
        boolean activeRepair = resumeCheck.activeRepair();
        if (handoffAccepted) {
            // 崩溃重入的 worker 继续持有同一 token/version；第二次长工具用它精确替换旧 anchor。
            AgentContext.setToolJobResumeHandoff(
                    resumeContext.getResumeToken(), resumeContext.getResumeLeaseVersion());
        }
        TodoItem suspendedItem = resumeCheck.suspendedItem();
        int resumeSequence = resumeCheck.resumeSequence();
        // 从原 plan 开头遍历，依靠 completedIds/sequence 精确跳过持久化前缀。
        for (TodoItem item : plan.getItems()) {
            // crash reentry 会从已接受的修复 handoff 重跑当前 Todo，不再次消费终态或增加轮次。
            ToolJobResumeContext repairExecutionContext = activeRepair
                    && java.util.Objects.equals(item.getId(), resumeContext.getTodoId())
                    ? resumeContext : null;
            // 已完成节点已经在 completedTodos 中恢复，绝不重复执行。
            if ((resumeContext != null || restartCheckpoint != null) && completedIds.contains(item.getId())) {
                continue;
            }
            // 对旧格式 checkpoint，sequence 边界提供第二层跳过保护。
            if ((resumeContext != null || restartCheckpoint != null)
                    && item.getSequence() < resumeSequence) {
                continue;
            }
            // 只有尚未消费终态且正好到原挂起节点时，执行结果注入分支。
            if (resumeContext != null && !handoffAccepted
                    && java.util.Objects.equals(item.getId(), suspendedItem.getId())) {
                // 注入不调用 LLM/tool，仍记录本节点恢复耗时与节点事件。
                long nodeStartMs = System.currentTimeMillis();
                // preview/rawRef 被整理成与普通工具输出兼容的文本。
                String injectedOutput = resumeTerminalOutput(resumeContext);
                // 失败终态先发节点失败，再推进/持久化消费位置，保证重入幂等。
                if (!resumeContext.isTerminalSuccess()) {
                    java.util.Optional<ToolRepairHandler> repairHandler =
                            catalog().handlerForFailure(resumeContext);
                    if (repairHandler.isEmpty()) {
                        emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                                "TODO_NODE_FAILED", item, "external_tool_terminal_failure", 0,
                                null, false, null);
                        prepareAcceptedHandoff(resumeContext, plan, completedTodos, item, toolCalls.get(), false);
                        // 消费确认是 durable gate；失败则保留 LAUNCHING anchor 供再次恢复。
                        if (terminalConsumed == null || !terminalConsumed.getAsBoolean()) {
                            return failure(plan, completedTodos, "resume_result_consume_failed",
                                    toolCalls.get(), null);
                        }
                        return failure(plan, completedTodos, "external_tool_terminal_failure",
                                toolCalls.get(), null);
                    }
                    ToolRepairHandler handler = repairHandler.get();
                    int nextRepairAttempt = handler.currentAttempt(resumeContext) + 1;
                    int effectiveMaxAttempts = handler.maxAttempts();
                    if (nextRepairAttempt >= effectiveMaxAttempts) {
                        Map<String, Object> metadata = handler.exhaustedMetadata(
                                resumeContext, effectiveMaxAttempts);
                        emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                                "TODO_NODE_FAILED", item, handler.exhaustedFailureCode(), 0,
                                metadata, false, null);
                        prepareAcceptedHandoff(resumeContext, plan, completedTodos, item, toolCalls.get(), false);
                        handler.markExhausted(resumeContext);
                        if (terminalConsumed == null || !terminalConsumed.getAsBoolean()) {
                            return failure(plan, completedTodos, "resume_result_consume_failed",
                                    toolCalls.get(), null);
                        }
                        return failure(plan, completedTodos, handler.exhaustedFailureCode(),
                                toolCalls.get(), metadata);
                    }
                    prepareRepairHandoff(
                            resumeContext, completedTodos, item, toolCalls.get(), handler, nextRepairAttempt);
                    emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                            "TODO_NODE_REPAIRING", item, null, 0,
                            handler.repairingMetadata(resumeContext), false, null);
                    if (terminalConsumed == null || !terminalConsumed.getAsBoolean()) {
                        return failure(plan, completedTodos, "resume_result_consume_failed",
                                toolCalls.get(), null);
                    }
                    AgentContext.setToolJobResumeHandoff(
                            resumeContext.getResumeToken(), resumeContext.getResumeLeaseVersion());
                    repairExecutionContext = resumeContext;
                }
                if (resumeContext.isTerminalSuccess()) {
                    // 成功输出可能包含 dataset ref，必须在后续 Todo 执行前重新注册。
                    DatasetRefRegistry.registerFromJson(injectedOutput, datasetRefs);
                    // 把挂起 Todo 追加为已完成，但不增加 toolCalls：那次调用在挂起前已经计数。
                    completedTodos.add(LangchainCompletedTodo.builder()
                            .todoId(item.getId())
                            .sequence(item.getSequence())
                            .description(item.getDescription())
                            .modelOutput(injectedOutput)
                            .output(injectedOutput)
                            .summary("external_tool_result")
                            .build());
                    persistWorkflowCheckpoint(request, plan, completedTodos, toolCalls.get());
                    emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                            "TODO_NODE_COMPLETED", item, null,
                            System.currentTimeMillis() - nodeStartMs, null, false, null);
                    // 在内存 context 中把恢复点推进到下一 Todo，或推进到「所有普通节点已完成」的结尾位置标记。
                    prepareAcceptedHandoff(resumeContext, plan, completedTodos, item, toolCalls.get(), true);
                    // 先持久化推进后的 checkpoint，成功后才允许继续执行后续 Todo。
                    if (terminalConsumed == null || !terminalConsumed.getAsBoolean()) {
                        return failure(plan, completedTodos, "resume_result_consume_failed",
                                toolCalls.get(), null);
                    }
                    // markHandoffAccepted 已把 Run 推回 EXECUTING。后续长工具只能凭这份
                    // token/version 原子替换旧 LAUNCHING anchor。
                    AgentContext.setToolJobResumeHandoff(
                            resumeContext.getResumeToken(), resumeContext.getResumeLeaseVersion());
                    // 当前节点已经通过注入完成，进入原 plan 的下一节点。
                    continue;
                }
            }
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
            LangchainTodoNodeResult nodeResult = repairExecutionContext == null
                    ? todoNodeExecutor.execute(request, item, completedTodos, datasetRefs, toolCalls)
                    : todoNodeExecutor.executeRepairRound(
                            request, item, completedTodos, datasetRefs, toolCalls, repairExecutionContext);
            long nodeDurationMs = System.currentTimeMillis() - nodeStartMs;
            // 外部工具 pending 不属于节点失败：保存挂起身份并立刻返回到 pipeline。
            if (nodeResult.isSuspended()) {
                emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                        "TODO_NODE_SUSPENDED", item, "external_tool_job_pending", nodeDurationMs,
                        null, false, null);
                // result 只携带堆内上下文；pipeline 下一步负责原子写入 durable anchor。
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
                // 额度已超限：有已完成节点则拼部分答案，一个都没有则立即失败。
                // 跳过 writeFinalAnswer() 的模型调用（额度已用尽，再发请求会立刻再次触发 RunBudgetException）。
                if (failureMetadata != null && Boolean.TRUE.equals(failureMetadata.get("budget_exceeded"))) {
                    return handleBudgetExhaustion(request, plan, completedTodos, reason,
                            failureMetadata, toolCalls.get());
                }
                return failure(plan, completedTodos, reason, toolCalls.get(), failureMetadata);
            }
            emitTodoNodeEvent(request.getRunId(), request.getUserId(),
                    "TODO_NODE_COMPLETED", item, null, nodeDurationMs,
                    todoRetryEventMetadata(nodeResult),
                    nodeResult.isRecovered(), nodeResult.getRecoveryOutcome());
            String trimmed = nodeResult.getOutput();
            DatasetRefRegistry.registerFromJson(trimmed, datasetRefs);
            completedTodos.add(LangchainCompletedTodo.builder()
                    .todoId(item.getId())
                    .sequence(item.getSequence())
                    .description(item.getDescription())
                    .output(trimmed)
                    .summary(nodeResult.getSummary())
                    .build());
            // checkpoint 写成功后才进入下一 Todo；写失败会被外层转换为明确 workflow failure。
            persistWorkflowCheckpoint(request, plan, completedTodos, toolCalls.get());
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

    private void persistWorkflowCheckpoint(LangchainLinearWorkflowRequest request,
                                           LangchainTodoPlan plan,
                                           List<LangchainCompletedTodo> completedTodos,
                                           int toolCallsUsed) {
        if (workflowCheckpointService == null) {
            return;
        }
        workflowCheckpointService.persistLinearProgress(
                request.getRunId(), request.getUserId(), plan, completedTodos, toolCallsUsed);
    }

    private void prepareAcceptedHandoff(ToolJobResumeContext context,
                                        LangchainTodoPlan plan,
                                        List<LangchainCompletedTodo> completedTodos,
                                        TodoItem current,
                                        int toolCallsUsed,
                                        boolean continueWorkflow) {
        // 把当前堆内 completedTodos 再次转换成可写回 anchor 的稳定 DTO。
        List<CompletedTodoRecord> records = completedTodos.stream().map(todo -> {
            // 每个字段都来自已完成节点，不从当前模型或工具重新计算。
            CompletedTodoRecord record = new CompletedTodoRecord();
            record.setTodoId(todo.getTodoId());
            record.setSequence(todo.getSequence());
            record.setDescription(todo.getDescription());
            record.setModelOutput(todo.getModelOutput());
            record.setOutput(todo.getOutput());
            record.setSummary(todo.getSummary());
            // map 返回的新对象会被序列化进下一版 checkpoint。
            return record;
        }).toList();
        // 更新 context 后，terminalConsumed 回调会把这些字段原子写回 durable anchor。
        context.setCompletedTodos(records);
        // 保存最新 run 级计数，下一次 worker 接管时从这里继续。
        context.setToolCallsUsed(toolCallsUsed);
        // 成功消费时选择 sequence 更大的最早节点；失败消费不继续 workflow。
        TodoItem next = continueWorkflow ? plan.getItems().stream()
                .filter(item -> item.getSequence() > current.getSequence())
                .min(java.util.Comparator.comparingInt(TodoItem::getSequence))
                .orElse(null) : null;
        // 没有后续 Todo 时写入「所有普通节点已完成」的结尾位置标记，防止恢复时重新命中当前节点。
        context.setTodoId(next == null ? ToolJobResumeContext.FINAL_TODO_ID : next.getId());
        // 结尾位置标记沿用当前序号；有下一节点时保存其真实序号。
        context.setTodoSequence(next == null ? current.getSequence() : next.getSequence());
        catalog().handlers().forEach(handler -> handler.clearPending(context));
        // 最后置 resultConsumed，表示以上上下文字段已经准备好交给 durable consume CAS。
        context.setResultConsumed(true);
    }

    private void prepareRepairHandoff(ToolJobResumeContext context,
                                      List<LangchainCompletedTodo> completedTodos,
                                      TodoItem current,
                                      int toolCallsUsed,
                                      ToolRepairHandler handler,
                                      int repairAttempt) {
        List<CompletedTodoRecord> records = completedTodos.stream().map(todo -> {
            CompletedTodoRecord record = new CompletedTodoRecord();
            record.setTodoId(todo.getTodoId());
            record.setSequence(todo.getSequence());
            record.setDescription(todo.getDescription());
            record.setModelOutput(todo.getModelOutput());
            record.setOutput(todo.getOutput());
            record.setSummary(todo.getSummary());
            return record;
        }).toList();
        context.setCompletedTodos(records);
        context.setToolCallsUsed(toolCallsUsed);
        context.setTodoId(current.getId());
        context.setTodoSequence(current.getSequence());
        handler.markPending(context, repairAttempt);
        context.setResultConsumed(true);
    }

    private ToolRepairCatalog catalog() {
        if (repairCatalog != null) {
            return repairCatalog;
        }
        return new ToolRepairCatalog(List.of(new PythonSandboxRepairHandler(
                null, codeRefineConfigLoader, startupCodeRefineProperties)));
    }

    private WorkflowResumeValidator resumeValidator() {
        return new WorkflowResumeValidator(catalog());
    }

    private boolean isHandlerExhaustedCode(String violationCode) {
        return catalog().handlers().stream()
                .anyMatch(handler -> handler.exhaustedFailureCode().equals(violationCode));
    }

    private Map<String, Object> exhaustedMetadata(ToolJobResumeContext context) {
        return catalog().handlerForFailure(context)
                .map(handler -> handler.exhaustedMetadata(context, handler.maxAttempts()))
                .orElse(null);
    }

    private String resumeTerminalOutput(ToolJobResumeContext context) {
        // preview 是 formatter 产出的完整有界 JSON；rawRef 仅用于内部引用，
        // 不泄露给模型上下文。返回 exact bytes，不 trim/重写。
        String preview = context.getTerminalResultPreview();
        if (!isBlank(preview)) {
            return preview;
        }
        // 终态缺少可见正文时给确定性占位，避免 null 破坏后续 prompt 构建。
        return context.isTerminalSuccess() ? "external tool completed" : "external tool failed";
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
     * 额度用尽时的固定降级路径（不再问恢复判定器，避免再次触发额度异常）。
     * <ul>
     *   <li>已完成 todo ≥ 1 → 用 {@link LangchainBudgetPartialAnswerBuilder} 按已有产出拼最终回答
     *       （受 MAX_TODOS / MAX_PER_TODO_CHARS / MAX_TOTAL_CHARS 三重上限保护），发 WORKFLOW_PARTIAL_BUDGET；</li>
     *   <li>已完成 todo = 0 → 没有可拼的部分内容，发 WORKFLOW_FAILED_BUDGET（completed_todo_count=0）；</li>
     *   <li>两条路径都不调用 {@code todoNodeExecutor.writeFinalAnswer()} —— 额度已超限，再触发模型调用
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

        // completedTodos 空：没有可拼的部分答案，立即失败。
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
        // 额度失败的元数据不要写进 empty_output_observation，否则会被误判成「待办输出为空」。
        if (failureMetadata != null && !failureMetadata.isEmpty()) {
            if (failureMetadata.containsKey("todo_retry_attempts")) {
                payload.put("todo_retry_attempts", failureMetadata.get("todo_retry_attempts"));
                if (failureMetadata.get("todo_retry_outcome") != null) {
                    payload.put("todo_retry_outcome", failureMetadata.get("todo_retry_outcome"));
                }
            }
            String field = LangchainTodoNodeResult.routeFailureMetadataField(failureMetadata);
            if (field != null && "TODO_NODE_FAILED".equals(eventType)) {
                payload.put(field, failureMetadata);
            }
        }
        try {
            eventService.append(runId, userId, eventType, payload);
        } catch (Exception e) {
            // 事件失败不影响节点执行
        }
    }

    private Map<String, Object> todoRetryEventMetadata(LangchainTodoNodeResult result) {
        if (result == null || result.getTodoRetryAttempts() <= 0) {
            return null;
        }
        return Map.of(
                "todo_retry_attempts", result.getTodoRetryAttempts(),
                "todo_retry_outcome", nvl(result.getTodoRetryOutcome(), "success"));
    }
}
