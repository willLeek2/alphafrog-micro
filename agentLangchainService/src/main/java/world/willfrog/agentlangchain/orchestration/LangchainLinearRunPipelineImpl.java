package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.debug.DebugObservabilityRequest;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.mapper.AgentRunDagNodeMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningRequest;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.failure.LangchainFailureDecision;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;
import world.willfrog.agentlangchain.tools.LangchainToolInvocationKeys;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointWriter;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;
import world.willfrog.agentlangchain.tooljob.ToolJobCheckpointFailureRecoveryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.time.LocalDate;

/**
 * agentLangchainService 的 run 级总控流水线。
 *
 * <p>面试时可以把这个类理解成“一个 AgentRun 从创建后到完成/失败落库”的主线剧本：
 * 先恢复 run 记录和运行上下文，再解析 planning（规划）、execution（执行）、
 * final answer（最终答案）三个阶段要用的模型，接着调用 {@link LangchainAiPlanner}
 * 生成 todo（任务项）计划，之后根据计划选择
 * {@link LangchainLinearWorkflowExecutor} 或 {@link LangchainDagWorkflowExecutor} 执行，
 * 最后把答案、事件、运行快照和可观测数据写回共享存储。</p>
 *
 * <p>这个类本身不直接和大模型对话，也不直接执行工具；它负责把“模型、prompt、工具目录、
 * 状态机、可观测性、取消/暂停控制”串成一个完整业务流程。具体规划逻辑在
 * {@link LangchainAiPlanner}，单个 todo 的工具循环在 {@link LangchainTodoNodeExecutor}。</p>
 *
 * <p>Agent V2 前端接入后，这个类还承担一个很关键的契约边界：plan 必须在
 * {@code PLAN_READY} 事件前后可恢复，事件 payload 中要带完整 plan，执行阶段的
 * {@code TODO_NODE_*}、{@code LLM_CALL_*}、{@code TOOL_CALL_*} 再用同一批 todo id
 * 做归属。也就是说，前端的 stepper / DAG 图并不是事后解析答案得到的，而是从这里发出的
 * plan 和后续节点事件逐步拼出来的。</p>
 */
@Service
@Slf4j
public class LangchainLinearRunPipelineImpl implements LangchainLinearRunPipeline {

    private final LangchainAiPlanner planner;
    private final LangchainLinearWorkflowExecutor linearWorkflowExecutor;
    private final LangchainDagWorkflowExecutor dagWorkflowExecutor;
    private final LangchainRunStageModelResolver stageModelResolver;
    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ToolProvider> toolProviderProvider;
    private final ObjectProvider<AgentRunStateStore> stateStoreProvider;
    private final ObjectProvider<AgentObservabilityService> observabilityServiceProvider;
    private final LangchainFailureMapper failureMapper;
    private final LangchainFollowUpContextSupport followUpContextSupport;
    private final AgentMessageService messageService;
    private final LangchainRunExecutionGuard executionGuard;
    private final LangchainRunConcurrencyScheduler runConcurrencyScheduler;
    private final AgentCreditService creditService;
    private final AgentRunCreditSettlementService creditSettlementService;
    private final AgentRunFinalizationService finalizationService;
    private final AgentPromptService promptService;
    /**
     * 260623-agent-service-deprecation task #47 (P0-2)：agentLangchainService 的 run 级执行
     * 完毕后清理 {@link AgentRunDatasetRegistry} 的 per-run 状态，避免长生命周期进程里
     * registry 无限累积。{@link ObjectProvider} 模式与 {@link #toolProviderProvider} 等
     * 可选依赖保持一致：bean 不存在时静默跳过（如单元测试场景）。
     */
    private final ObjectProvider<AgentRunDatasetRegistry> agentRunDatasetRegistryProvider;
    private final ObjectProvider<DebugObservabilityService> debugObservabilityServiceProvider;

    @Autowired(required = false)
    private ToolJobCheckpointWriter toolJobCheckpointWriter;

    @Autowired(required = false)
    private ToolJobCheckpointFailureRecoveryService checkpointFailureRecoveryService;

    @Autowired(required = false)
    private WorkflowCheckpointService workflowCheckpointService;

    @Autowired(required = false)
    private AgentRunDagNodeMapper dagNodeMapper;

    @Autowired(required = false)
    private LangchainSchedulerMetrics schedulerMetrics;

    public LangchainLinearRunPipelineImpl(LangchainAiPlanner planner,
                                          LangchainLinearWorkflowExecutor linearWorkflowExecutor,
                                          LangchainDagWorkflowExecutor dagWorkflowExecutor,
                                          LangchainRunStageModelResolver stageModelResolver,
                                          AgentRunMapper runMapper,
                                          AgentEventService eventService,
                                          ObjectMapper objectMapper,
                                          ObjectProvider<ToolProvider> toolProviderProvider,
                                          ObjectProvider<AgentRunStateStore> stateStoreProvider,
                                          ObjectProvider<AgentObservabilityService> observabilityServiceProvider,
                                          LangchainFailureMapper failureMapper,
                                          LangchainFollowUpContextSupport followUpContextSupport,
                                          AgentMessageService messageService,
                                          LangchainRunExecutionGuard executionGuard,
                                          LangchainRunConcurrencyScheduler runConcurrencyScheduler,
                                          AgentCreditService creditService,
                                          AgentRunCreditSettlementService creditSettlementService,
                                          AgentRunFinalizationService finalizationService,
                                          AgentPromptService promptService,
                                          ObjectProvider<AgentRunDatasetRegistry> agentRunDatasetRegistryProvider,
                                          ObjectProvider<DebugObservabilityService> debugObservabilityServiceProvider) {
        this.planner = planner;
        this.linearWorkflowExecutor = linearWorkflowExecutor;
        this.dagWorkflowExecutor = dagWorkflowExecutor;
        this.stageModelResolver = stageModelResolver;
        this.runMapper = runMapper;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.toolProviderProvider = toolProviderProvider;
        this.stateStoreProvider = stateStoreProvider;
        this.observabilityServiceProvider = observabilityServiceProvider;
        this.failureMapper = failureMapper;
        this.followUpContextSupport = followUpContextSupport;
        this.messageService = messageService;
        this.executionGuard = executionGuard;
        this.runConcurrencyScheduler = runConcurrencyScheduler;
        this.creditService = creditService;
        this.creditSettlementService = creditSettlementService;
        this.finalizationService = finalizationService;
        this.promptService = promptService;
        this.agentRunDatasetRegistryProvider = agentRunDatasetRegistryProvider;
        this.debugObservabilityServiceProvider = debugObservabilityServiceProvider;
    }

    @Override
    public void launchAsync(AgentRun run) {
        launchAsync(run, null);
    }

    @Override
    public void launchAsync(AgentRun run, LangchainRunConcurrencyScheduler.Reservation reservation) {
        // 入口层只把 run 交给并发调度器，不直接开线程。
        // hard/current 并发闸门、排队顺序和队列容量都集中在 scheduler，pipeline 只负责拿到执行权后的业务流程。
        runConcurrencyScheduler.submit(reservation, run, () -> executeRun(run));
    }

    @Override
    public boolean launchRestartedAsync(AgentRun run) {
        if (run == null || isBlank(run.getId())) {
            return false;
        }
        runConcurrencyScheduler.submit(null, run, () -> executeRun(run, true));
        return true;
    }

    /**
     * 把一次持久化恢复提交到普通 Run 共用的有界调度器。
     *
     * @param run 从数据库读取到的待恢复 Run，只使用稳定 id 定位最新状态
     * @param context 由 anchor 解码出的 checkpoint、终态结果和恢复租约
     * @param terminalConsumed 工作流接受终态结果后执行的持久化消费确认
     * @param completion 恢复 Runnable 退出时回报“结果是否已经持久化”的回调
     * @return 参数有效且成功进入调度器时返回 true；不会在这里调用 planner
     */
    public boolean launchResumedAsync(AgentRun run,
                                      ToolJobResumeContext context,
                                      BooleanSupplier terminalConsumed,
                                      Consumer<Boolean> completion) {
        // 缺少 Run 身份或恢复上下文时不允许创建一个无法 fencing 的排队任务。
        if (run == null || context == null || isBlank(run.getId())) {
            return false;
        }
        // reservation 传 null，让恢复任务重新经过普通 Run 的限流、排队和拒绝策略。
        runConcurrencyScheduler.submit(null, run, () -> {
            // durable 只表示恢复结果已经写入真相源，不等同于工作流一定成功。
            boolean durable = false;
            try {
                // 新 worker 根据 checkpoint 重建线程上下文并继续原 plan。
                durable = executeResumedRun(run, context, terminalConsumed);
            } finally {
                // launcher 用该回调决定是否可以把 LAUNCHING anchor 清理为 CONSUMED。
                if (completion != null) {
                    completion.accept(durable);
                }
            }
        });
        // 返回 true 仅说明调度器已接收；真正执行可能仍在有界队列中等待。
        return true;
    }

    void executeRun(AgentRun initialRun) {
        executeRun(initialRun, false);
    }

    void executeRun(AgentRun initialRun, boolean serviceRestart) {
        if (initialRun == null || isBlank(initialRun.getId())) {
            return;
        }
        // 重新从数据库读取 run，而不是完全相信入口传入的对象。
        // createRun 之后到异步线程真正执行之间，run 可能已经被取消、更新或存活时间（TTL）状态变化。
        AgentRun run = runMapper.findById(initialRun.getId());
        if (run == null) {
            log.warn("LangChain run not found, skip: {}", initialRun.getId());
            return;
        }
        String runId = run.getId();
        String userId = run.getUserId();
        String userGoal = "";
        try {
            // AgentContext 是本次 LLM/tool 调用链的线程级上下文。
            // 后续 OpenRouterProviderRoutedChatModel、ToolRouterToolProvider、可观测服务都会从这里取 runId/userId/phase。
            AgentContext.setRunId(runId);
            AgentContext.setUserId(userId);
            // D02：先恢复并校验 run 级 Prompt 身份，再允许任何模型解析、观测初始化或
            // follow-up 摘要调用。坏摘要必须在第一次 LLM 副作用之前 fail closed。
            setPromptSelectionFromExt(run.getExt());
            DebugObservabilityService debugObservabilityService = debugObservabilityServiceProvider.getIfAvailable();
            if (debugObservabilityService != null) {
                DebugObservabilityRequest debugRequest = debugObservabilityService.parseFromExt(run.getExt());
                debugObservabilityService.openRunSession(debugRequest, runId, userId);
            }
            if (!eventService.isRunnable(runId, userId)) {
                return;
            }
            // 260612-01-02: 防御性跑前校验（HTTP/Dubbo 已校验，pipeline 再校验一次防漏）
            if (!hasAdminCreditBypass(run) && !creditService.hasPositiveCredit(userId)) {
                String reason = "insufficient_credit";
                log.warn("LangChain run blocked by credit pre-check: runId={} userId={}", runId, userId);
                AgentObservabilityService observability = observabilityServiceProvider.getIfAvailable();
                if (observability != null) {
                    observability.recordFailure(runId, "CREDIT_BLOCK", reason);
                }
                String blockedSnapshot = attachObservability(
                        runId, run.getSnapshotJson(), AgentRunStatus.FAILED, "CreditBlocked", reason);
                int snapshotRows = runMapper.updateSnapshot(
                        runId, userId, AgentRunStatus.FAILED, blockedSnapshot, true, reason);
                int ttlRows = runMapper.updateStatusWithTtl(
                        runId, userId, AgentRunStatus.FAILED, eventService.nextInterruptedExpiresAt());
                if (snapshotRows == 1 && ttlRows == 1) {
                    recordSchedulerCompletion(AgentRunStatus.FAILED);
                }
                eventService.append(runId, userId, "EXECUTION_BLOCKED",
                        Map.of("reason", reason, "by", "credit_pre_check", "engine", "agentLangchainService"));
                markRunStatus(runId, AgentRunStatus.FAILED);
                return;
            }
            runMapper.updateStatus(runId, userId, AgentRunStatus.EXECUTING);
            markRunStatus(runId, AgentRunStatus.EXECUTING);
            // 先写 EXECUTION_STARTED，让前端和压测脚本知道 run 已经从队列进入执行线程。
            // 此时还没完成 planning，所以 workflow 先标 pending_plan，后续 PLAN_READY 再给出 linear/dag。
            eventService.append(runId, userId, "EXECUTION_STARTED", Map.of(
                    "run_id", runId,
                    "engine", "agentLangchainService",
                    "workflow", serviceRestart ? "frozen_plan_restart" : "pending_plan",
                    "service_restart", serviceRestart,
                    "restart_attempt", run.getRestartAttempt() == null ? 0 : run.getRestartAttempt()
            ));

            // 阶段模型解析是 run 级配置落地的入口：
            // 用户请求、stage_config_json、Nacos agent-llm 配置会在这里合并成 planning/execution/final 三个 ChatModel。
            LangchainRunStageModelResolver.StageModels stageModels = stageModelResolver.resolve(run);
            boolean captureLlmRequests = eventService.extractCaptureLlmRequests(run.getExt());
            AgentObservabilityService observabilityService = observabilityServiceProvider.getIfAvailable();
            if (observabilityService != null) {
                // initializeRun 只初始化观测容器和默认模型信息，实际每次 LLM/tool 调用会在各自组件里继续追加 trace。
                observabilityService.initializeRun(
                        runId,
                        firstNonBlank(stageModels.planningEndpointName(), eventService.extractEndpointName(run.getExt())),
                        firstNonBlank(stageModels.planningModelName(), eventService.extractModelName(run.getExt())),
                        captureLlmRequests);
            }
            // follow-up 复用同一个 run：这里把历史对话压缩内容和当前用户目标拆出来，
            // 让 planning（规划）阶段既能看到追问上下文，又不会把旧消息原样塞满上下文窗口。
            LangchainFollowUpContextSupport.ExecutionContext executionContext = followUpContextSupport.resolve(run);
            userGoal = executionContext.userGoal();
            String dialogueContext = executionContext.dialogueContext();
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            AgentContext.setWebSearchEnabled(runConfig.webSearchEnabled());
            AgentContext.setWebSearchConfig(runConfig.webSearchConfig());

            // 从 ext 反序列化 run 启动时冻结的 dataFreshness 快照
            setDataFreshnessFromExt(run.getExt());

            // 这里先解析”当前 run 允许暴露给模型的工具列表”，planning（规划）阶段会把这些工具能力写进 prompt，
            // execution（执行）阶段也会用同一套 ToolSpecification 注册到 LC4j（LangChain4j）AiServices。
            // 如果两处工具目录不一致，planner 可能安排一个执行阶段拿不到的工具，这是最难排查的类型之一。
            List<ToolSpecification> toolSpecifications = resolveToolSpecifications(runConfig, userGoal);
            LangchainLinearWorkflowRequest workflowRequest = buildWorkflowRequest(
                    runId, userId, userGoal, dialogueContext, stageModels,
                    toolSpecifications, runConfig);

            /*
             * execution mode 是 Run 创建时冻结的用户契约。代码解释器开关只决定是否暴露
             * executePython，不能再覆盖 LINEAR / DAG / AUTO。非法值由统一解析器 fail-closed；
             * 缺省值保持 AUTO。
             */
            PlanExecutionMode requestedExecutionMode = AgentEventService.parseExecutionMode(
                    eventService.extractExecutionMode(run.getExt()));

            LangchainTodoPlan plan;
            boolean useDag;
            PlanExecutionMode effectiveExecutionMode;
            WorkflowExecutionCheckpoint restartCheckpoint = null;
            if (serviceRestart) {
                // 服务重启只读取数据库里的冻结 Plan；这里绝不调用 planner。
                if (isBlank(run.getPlanJson()) || "{}".equals(run.getPlanJson().trim())) {
                    throw new IllegalStateException("workflow_restart_plan_missing");
                }
                plan = objectMapper.readValue(run.getPlanJson(), LangchainTodoPlan.class);
                validateFrozenPlan(plan);
                useDag = LangchainWorkflowRouting.shouldUseDag(plan);
                effectiveExecutionMode = useDag ? PlanExecutionMode.DAG : PlanExecutionMode.LINEAR;
                if (useDag) {
                    if (workflowCheckpointService == null) {
                        throw new IllegalStateException("workflow_checkpoint_service_unavailable");
                    }
                    // DAG 从头重跑，但仍必须确认崩溃前没有启动过 UNSAFE 工具。
                    workflowCheckpointService.parseAndValidateDagRestart(run);
                    // DAG 不复用旧节点执行进度；删除仅用于 durable 展示/恢复的节点行。
                    if (dagNodeMapper != null) {
                        dagNodeMapper.deleteByRunId(runId);
                        dagNodeMapper.clearFrontierForWorkflowRestart(runId);
                    }
                } else {
                    if (workflowCheckpointService == null) {
                        throw new IllegalStateException("workflow_checkpoint_service_unavailable");
                    }
                    restartCheckpoint = workflowCheckpointService.parseAndValidate(run, plan);
                }
                int restartAttempt = run.getRestartAttempt() == null ? 0 : run.getRestartAttempt();
                eventService.appendOnce(runId, userId, "WORKFLOW_RESTARTED",
                        runId + ":restart:" + restartAttempt,
                        Map.of(
                                "run_id", runId,
                                "restart_attempt", restartAttempt,
                                "planner_skipped", true,
                                "workflow", useDag ? "dag" : "linear",
                                "restart_from", useDag ? "graph_start" : restartCheckpoint.getNextTodoId()
                        ));
            } else {
                // planning（规划）阶段只负责把用户目标变成 todo plan（任务计划）。
                AgentContext.setPhase("planning");
                LangchainTodoPlan planned = planner.plan(LangchainPlanningRequest.builder()
                        .runId(runId)
                        .userId(userId)
                        .userGoal(userGoal)
                        .dialogueContext(dialogueContext)
                        .model(stageModels.planningModel())
                        .planningEndpointName(stageModels.planningEndpointName())
                        .planningModelName(stageModels.planningModelName())
                        .planningProviderOrder(stageModels.planningProviderOrder())
                        .toolSpecifications(toolSpecifications)
                        .executionMode(requestedExecutionMode)
                        .build());
                plan = LangchainWorkflowRouting.effectivePlan(planned, requestedExecutionMode);
                useDag = LangchainWorkflowRouting.shouldUseDag(plan);
                effectiveExecutionMode = useDag ? PlanExecutionMode.DAG : PlanExecutionMode.LINEAR;
                if (planned != plan) {
                    log.info("Resolved effective workflow plan: runId={} requestedMode={} plannerMode={} effectiveMode={}",
                            runId, requestedExecutionMode, planned.getExecutionMode(), effectiveExecutionMode);
                }
                // PLAN_READY 前先把冻结 Plan 和 LINEAR 初始 checkpoint 写稳。
                persistPlan(runId, userId, plan);
                if (workflowCheckpointService != null) {
                    if (useDag) {
                        workflowCheckpointService.initializeDag(runId, userId);
                    } else {
                        workflowCheckpointService.initializeLinear(runId, userId, plan);
                    }
                }
                eventService.append(runId, userId, "PLAN_READY", Map.of(
                        "execution_mode", effectiveExecutionMode.name(),
                        "requested_execution_mode", requestedExecutionMode.name(),
                        "effective_execution_mode", effectiveExecutionMode.name(),
                        "workflow", useDag ? "dag" : "linear",
                        "todo_count", plan.getItems() == null ? 0 : plan.getItems().size(),
                        "plan", plan
                ));
            }

            if (abortIfStopped(runId, userId, "before_execution")) {
                return;
            }

            // LINEAR 重启从 checkpoint 边界继续；DAG 重启与首次执行一样从整图开头调度。
            LangchainLinearWorkflowResult result;
            if (serviceRestart && !useDag) {
                result = linearWorkflowExecutor.restartPlanned(workflowRequest, plan, restartCheckpoint);
            } else {
                LangchainWorkflowStepCoordinator.Outcome stepOutcome = stepCoordinator().execute(
                        runId, userId, workflowRequest, plan, useDag);
                result = stepOutcome.result();
                if (stepOutcome.workerReleased()) {
                    return;
                }
            }

            // cancel/pause 可能发生在 todo 执行和最终落库之间。
            // 这里再次检查，避免用户已经取消后 pipeline 又把 run 覆盖成 COMPLETED/FAILED。
            if (result.isInterrupted() || abortIfStopped(runId, userId, "before_persist")) {
                log.info("LangChain run {} stopped before persist (interrupted={}, reason={})",
                        runId, result.isInterrupted(), result.getFailureReason());
                return;
            }

            runMapper.updatePlanJson(runId, userId, writeJson(result.getPlan()));
            if (result.isSuccess()) {
                persistCompletedOutcome(run, userGoal, stageModels, result, null);
            } else if (result.isPartial()) {
                persistPartialOutcome(run, userGoal, stageModels, result, null);
            } else {
                publishFailure(runId, userId, userGoal, result, null);
                tryScheduleSettlement(runId, userId);
            }
        } catch (Exception e) {
            log.error("LangChain run failed: runId={}", runId, e);
            // 所有未被 workflow result（工作流结果）显式表达的异常都会收敛到统一失败出口，
            // 由 LangchainFailureMapper 决定前端事件类型和 observability failure type（可观测失败类型）。
            publishFailure(runId, userId, userGoal,
                    LangchainLinearWorkflowResult.builder()
                            .success(false)
                            .failureReason(e.getMessage())
                            .toolCallsUsed(0)
                            .build(),
                    e);
            // 260612-01-02: 异常路径也触发结算
            tryScheduleSettlement(runId, userId);
        } finally {
            try {
                DebugObservabilityService debugObservabilityService = debugObservabilityServiceProvider.getIfAvailable();
                if (debugObservabilityService != null) {
                    debugObservabilityService.closeRunSession(runId);
                }
            } catch (Exception debugCloseEx) {
                log.warn("Failed to close debug observability session for runId={}: {}",
                        runId, debugCloseEx.getMessage());
            }
            // 260623-agent-service-deprecation task #47 (P0-2)：清理当前 run 的 dataset/manifest 编号转译层状态。
            // 长生命周期进程里若不清理，registry 会无限累积；run 终态后下一个 run 串到上一个 run 的编号 → 错位。
            // ObjectProvider 兜底：bean 不存在时静默跳过（保持纯单元测试启动）。
            try {
                AgentRunDatasetRegistry registry = agentRunDatasetRegistryProvider.getIfAvailable();
                if (registry != null) {
                    registry.reset(runId);
                }
            } catch (Exception cleanupEx) {
                log.warn("Failed to reset AgentRunDatasetRegistry for runId={}: {}",
                        runId, cleanupEx.getMessage());
            }
            // 异步线程会被线程池复用，必须清理 ThreadLocal（线程本地变量），避免下一个 run 继承上一个 run 的 phase/todo/provider 信息。
            AgentContext.clear();
        }
    }

    boolean executeResumedRun(AgentRun initialRun,
                              ToolJobResumeContext resumeContext,
                              BooleanSupplier terminalConsumed) {
        // 排队期间 Run 可能被取消或 checkpoint 被更新，因此必须重新读取数据库真相源。
        AgentRun run = runMapper.findById(initialRun.getId());
        if (run == null) {
            return false;
        }
        // 后续事件、快照和 CAS 都使用数据库中的稳定身份。
        String runId = run.getId();
        String userId = run.getUserId();
        // 先给失败路径一个安全默认值，解析 follow-up 上下文后再覆盖。
        String userGoal = "";
        try {
            // 新线程不会继承旧 worker 的 ThreadLocal，必须从持久化身份重新建立。
            AgentContext.setRunId(runId);
            AgentContext.setUserId(userId);
            // durable resume 使用同一前置门；不能让 follow-up 摘要先于 digest 校验调用模型。
            setPromptSelectionFromExt(run.getExt());
            // 恢复排队期间若用户已暂停/取消，不能继续执行原 Todo。
            if (!eventService.isRunnable(runId, userId)) {
                // 只有停止状态已经持久化，才算这次 launcher 可以安全完成交接。
                return hasDurableStopState(runId);
            }
            // plan 是恢复必需上下文；缺失时严禁重新规划，因为新 plan 可能重复副作用。
            if (isBlank(run.getPlanJson())) {
                throw new IllegalStateException("resume_plan_missing");
            }
            // 从 DB 还原挂起前冻结的原计划。
            LangchainTodoPlan plan = objectMapper.readValue(run.getPlanJson(), LangchainTodoPlan.class);
            // 当前 durable resume 只实现 LINEAR 顺序语义；DAG 不能降级成 LINEAR 猜测恢复。
            if (LangchainWorkflowRouting.shouldUseDag(plan)) {
                throw new IllegalStateException("resume_dag_not_supported");
            }

            // 模型配置也按当前 Run 的冻结配置重建，不沿用旧线程对象。
            LangchainRunStageModelResolver.StageModels stageModels = stageModelResolver.resolve(run);
            // follow-up 对话上下文从 Run 数据重新计算，保持与首次执行一致。
            LangchainFollowUpContextSupport.ExecutionContext executionContext = followUpContextSupport.resolve(run);
            userGoal = executionContext.userGoal();
            // 工具开关、检索配置和数据新鲜度都从持久化 ext 恢复。
            AgentEventService.RunConfig runConfig = eventService.extractRunConfig(run.getExt());
            AgentContext.setWebSearchEnabled(runConfig.webSearchEnabled());
            AgentContext.setWebSearchConfig(runConfig.webSearchConfig());
            setDataFreshnessFromExt(run.getExt());
            // 用相同运行配置重建工具目录，但不会再次调用 planner。
            List<ToolSpecification> toolSpecifications = resolveToolSpecifications(runConfig, userGoal);
            // 构造新的请求对象，把持久化数据重新放入当前 worker 的调用链。
            LangchainLinearWorkflowRequest workflowRequest = buildWorkflowRequest(
                    runId, userId, userGoal, executionContext.dialogueContext(),
                    stageModels, toolSpecifications, runConfig);

            // token + leaseVersion 使重复 launcher 只能写出一条 WORKFLOW_RESUMED 事件。
            String resumedDedupeKey = runId + ":" + resumeContext.getResumeToken()
                    + ":" + resumeContext.getResumeLeaseVersion() + ":workflow_resumed";
            eventService.appendOnce(runId, userId, "WORKFLOW_RESUMED", resumedDedupeKey, Map.of(
                    "run_id", runId,
                    "todo_id", nvl(resumeContext.getTodoId()),
                    "resume_token", nvl(resumeContext.getResumeToken()),
                    "resume_lease_version", resumeContext.getResumeLeaseVersion(),
                    "planner_skipped", true
            ));

            // executor 按 checkpoint 跳过已完成 Todo，并把终态结果注入原挂起节点。
            LangchainLinearWorkflowResult result = linearWorkflowExecutor.resumePlanned(
                    workflowRequest, plan, resumeContext, terminalConsumed);
            // 只有结果成功写入 DB，launcher 才能完成消费确认并清理 anchor。
            return persistResumedResult(run, userGoal, stageModels, result, resumeContext);
        } catch (Exception e) {
            // 恢复异常也要尝试持久化为可见失败，不能只依赖当前进程日志。
            log.error("Resumed LangChain run failed: runId={}", runId, e);
            try {
                return publishResumedFailure(runId, userId, userGoal,
                        LangchainLinearWorkflowResult.builder()
                                .success(false)
                                .failureReason(e.getMessage())
                                .toolCallsUsed(resumeContext.getToolCallsUsed())
                                .build(), e, resumeContext);
            } catch (Exception persistEx) {
                // 失败本身未持久化时返回 false，保留 LAUNCHING claim 供 reconciler 重入。
                log.error("Resumed failure could not be persisted runId={}", runId, persistEx);
                return false;
            }
        } finally {
            // dataset registry 是进程内缓存；本轮恢复结束后必须按 runId 清理。
            try {
                AgentRunDatasetRegistry registry = agentRunDatasetRegistryProvider.getIfAvailable();
                if (registry != null) {
                    registry.reset(runId);
                }
            } catch (Exception cleanupEx) {
                log.warn("Failed to reset resumed dataset registry runId={}: {}",
                        runId, cleanupEx.getMessage());
            }
            // worker 会被线程池复用，清除新建的所有 AgentContext ThreadLocal。
            AgentContext.clear();
        }
    }

    private boolean persistResumedResult(AgentRun run,
                                         String userGoal,
                                         LangchainRunStageModelResolver.StageModels stageModels,
                                         LangchainLinearWorkflowResult result,
                                         ToolJobResumeContext resumeContext) {
        String runId = run.getId();
        String userId = run.getUserId();
        // 恢复过程中还可能再次遇到另一个长工具，因此允许二次挂起。
        if (result.isSuspended()) {
            // 二次挂起必须覆盖为新的完整 checkpoint，再释放这次恢复 worker。
            LangchainToolJobCheckpointCoordinator.Attempt checkpoint = checkpointCoordinator().persist(runId, result);
            // 写失败时仍使用同一 durable failure ownership 协议。
            if (!checkpoint.persisted()
                    && checkpointCoordinator().recordFailure(runId, userId, result, checkpoint.request())
                    != ToolJobCheckpointFailureRecoveryService.Outcome.HEALTHY_CHECKPOINT) {
                return false;
            }
            // 事件写在 checkpoint 之后，避免观察者先看到不可恢复的挂起。
            eventService.append(runId, userId, "TOOL_CALL_SUSPENDED", Map.of(
                    "run_id", runId,
                    "tool_call_id", nvl(result.getPendingToolCallId()),
                    "attempt", result.getPendingAttempt(),
                    "todo_id", nvl(result.getSuspendedTodoId()),
                    "todo_sequence", result.getSuspendedTodoSequence() == null
                            ? 0 : result.getSuspendedTodoSequence(),
                    "workflow", "linear"
            ));
            // true 表示“新的挂起点已经持久化”，launcher 可以结束本轮旧 handoff。
            return true;
        }
        // 恢复执行期间用户状态可能再次变化，终态覆盖前做最后一次停止检查。
        if (result.isInterrupted() || abortIfStopped(runId, userId, "resume_before_persist")) {
            return hasDurableStopState(runId);
        }
        if (result.isSuccess()) {
            return persistCompletedOutcome(run, userGoal, stageModels, result, resumeContext);
        }
        if (result.isPartial()) {
            return persistPartialOutcome(run, userGoal, stageModels, result, resumeContext);
        }
        if ("resume_result_consume_failed".equals(result.getFailureReason())) {
            // 终态结果已注入内存，但消费确认没有持久化。
            // 保留 Run 与 LAUNCHING anchor，reconciler 会用同一 claim 重新进入，避免丢结果。
            log.warn("Resume result consume failed for run={}, leaving claim for retry", runId);
            return false;
        }
        boolean durable = publishResumedFailure(
                runId, userId, userGoal, result, null, resumeContext);
        if (durable) {
            tryScheduleSettlement(runId, userId);
        }
        return durable;
    }

    /**
     * 组装执行器所需的运行环境参数。正常执行与恢复执行使用完全相同的字段集，
     * 收成单一构造点后，两处调用不会再各自维护一份字段清单。
     */
    private LangchainLinearWorkflowRequest buildWorkflowRequest(String runId,
                                                                 String userId,
                                                                 String userGoal,
                                                                 String dialogueContext,
                                                                 LangchainRunStageModelResolver.StageModels stageModels,
                                                                 List<ToolSpecification> toolSpecifications,
                                                                 AgentEventService.RunConfig runConfig) {
        return LangchainLinearWorkflowRequest.builder()
                .runId(runId)
                .userId(userId)
                .userGoal(userGoal)
                .dialogueContext(dialogueContext)
                .model(stageModels.executionModel())
                .planningModel(stageModels.planningModel())
                .executionModel(stageModels.executionModel())
                .finalAnswerModel(stageModels.finalAnswerModel())
                .planningEndpointName(stageModels.planningEndpointName())
                .planningModelName(stageModels.planningModelName())
                .planningProviderOrder(stageModels.planningProviderOrder())
                .toolSpecifications(toolSpecifications)
                .webSearchEnabled(runConfig.webSearchEnabled())
                .codeInterpreterEnabled(runConfig.codeInterpreterEnabled())
                .build();
    }

    /**
     * COMPLETED 终态的持久化与收尾副作用，正常执行与恢复执行共用。
     *
     * <p>resumeContext 为空表示正常执行：直接附加可观测摘要并更新终态快照，
     * 副作用异常向上传播，由外层统一失败出口处理。resumeContext 非空表示恢复执行：
     * 先预生成可观测候选项，再按恢复租约做条件更新（数据库未接受写入时返回 false，
     * 保留恢复锚点供重试），写入成功后提交可观测；副作用降级为尽力而为——终态已
     * 持久化，收尾动作失败不应把结果伪装成可重试。</p>
     */
    private boolean persistCompletedOutcome(AgentRun run,
                                            String userGoal,
                                            LangchainRunStageModelResolver.StageModels stageModels,
                                            LangchainLinearWorkflowResult result,
                                            ToolJobResumeContext resumeContext) {
        String runId = run.getId();
        String userId = run.getUserId();
        if (resumeContext != null) {
            // 按恢复租约条件更新前只生成观测候选项；失去租约的 worker 不能写 Redis 或清理终态锁。
            PreparedResumedTerminal prepared = prepareResumedTerminalObservability(runId,
                    buildSnapshot(userGoal, result, AgentRunStatus.COMPLETED),
                    AgentRunStatus.COMPLETED, null, null);
            if (persistResumedTerminal(runId, userId, AgentRunStatus.COMPLETED,
                    result, prepared.snapshot(), null, resumeContext) != 1) {
                // 数据库没有接受写入就返回 false，不能清理恢复锚点。
                log.warn("Resumed COMPLETED snapshot was not persisted for run={}", runId);
                return false;
            }
            commitResumedTerminalObservability(prepared);
            recordSchedulerCompletion(AgentRunStatus.COMPLETED);
            try {
                appendCompletedSideEffects(runId, userId, stageModels, result, true);
            } catch (Exception sideEffect) {
                log.warn("Resumed COMPLETED side effect failed after durable snapshot run={}: {}",
                        runId, sideEffect.getMessage());
            }
            // durable 主结果已存在，允许 handoff 完成。
            return true;
        }
        // 正常执行的写入顺序：运行快照带可观测摘要 → 数据库终态 → Redis 控制状态 → 事件 → assistant 消息。
        // 这样前端先看到终态时，通常也能拿到完整答案和可观测摘要；assistant 消息最后写，
        // 是因为 follow-up 只应该引用已经确定落库的最终答案。
        String snapshot = attachObservability(
                runId, buildSnapshot(userGoal, result, AgentRunStatus.COMPLETED), AgentRunStatus.COMPLETED, null, null);
        int completedRows = runMapper.updateSnapshot(
                runId, userId, AgentRunStatus.COMPLETED, snapshot, true, null);
        if (completedRows == 1) {
            recordSchedulerCompletion(AgentRunStatus.COMPLETED);
        }
        appendCompletedSideEffects(runId, userId, stageModels, result, false);
        return true;
    }

    /**
     * PARTIAL（部分完成）终态的持久化与收尾副作用，正常执行与恢复执行共用，
     * 持久化两条路径的划分与 {@link #persistCompletedOutcome} 相同。
     */
    private boolean persistPartialOutcome(AgentRun run,
                                          String userGoal,
                                          LangchainRunStageModelResolver.StageModels stageModels,
                                          LangchainLinearWorkflowResult result,
                                          ToolJobResumeContext resumeContext) {
        String runId = run.getId();
        String userId = run.getUserId();
        if (resumeContext != null) {
            PreparedResumedTerminal prepared = prepareResumedTerminalObservability(
                    runId, buildPartialSnapshot(userGoal, result),
                    AgentRunStatus.PARTIAL, null, result.getFailureReason());
            if (persistResumedTerminal(runId, userId, AgentRunStatus.PARTIAL,
                    result, prepared.snapshot(), result.getFailureReason(), resumeContext) != 1) {
                log.warn("Resumed PARTIAL snapshot was not persisted for run={}", runId);
                return false;
            }
            commitResumedTerminalObservability(prepared);
            recordSchedulerCompletion(AgentRunStatus.PARTIAL);
            try {
                appendPartialSideEffects(runId, userId, stageModels, result, true);
            } catch (Exception sideEffect) {
                log.warn("Resumed PARTIAL side effect failed after durable snapshot run={}: {}",
                        runId, sideEffect.getMessage());
            }
            return true;
        }
        String snapshot = attachObservability(
                runId, buildPartialSnapshot(userGoal, result), AgentRunStatus.PARTIAL, null,
                result.getFailureReason());
        int partialRows = runMapper.updateSnapshot(
                runId, userId, AgentRunStatus.PARTIAL, snapshot, true, result.getFailureReason());
        if (partialRows == 1) {
            recordSchedulerCompletion(AgentRunStatus.PARTIAL);
        }
        appendPartialSideEffects(runId, userId, stageModels, result, false);
        return true;
    }

    /** COMPLETED 的收尾副作用序列：Redis 控制状态、完成事件、assistant 消息、结算、终态事件广播。 */
    private void appendCompletedSideEffects(String runId,
                                            String userId,
                                            LangchainRunStageModelResolver.StageModels stageModels,
                                            LangchainLinearWorkflowResult result,
                                            boolean resumed) {
        markRunStatus(runId, AgentRunStatus.COMPLETED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", result.getFinalAnswer());
        payload.put("toolCallsUsed", result.getToolCallsUsed());
        payload.put("engine", "agentLangchainService");
        if (resumed) {
            payload.put("resumed", true);
        }
        eventService.append(runId, userId, "WORKFLOW_COMPLETED", payload);
        persistAssistantMessage(runId, userId, stageModels, result.getFinalAnswer());
        tryScheduleSettlement(runId, userId);
        // 终态事件在同一 JVM 内触发 workspace dump 等后续处理。
        finalizationService.publishFinalizedEvent(runId, userId, AgentRunStatus.COMPLETED.name());
    }

    /**
     * PARTIAL 的收尾副作用序列。PARTIAL 不是普通失败：部分 todo 被明确跳过、最终答案仍可供参考，
     * 所以正常执行的事件要带 skippedTodoIds 与 recoveryRationale，方便前端解释为什么不是完整完成；
     * 恢复执行的事件保持既有字段集（不带这两个字段）。
     */
    private void appendPartialSideEffects(String runId,
                                          String userId,
                                          LangchainRunStageModelResolver.StageModels stageModels,
                                          LangchainLinearWorkflowResult result,
                                          boolean resumed) {
        markRunStatus(runId, AgentRunStatus.PARTIAL);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", nvl(result.getFinalAnswer()));
        payload.put("toolCallsUsed", result.getToolCallsUsed());
        payload.put("engine", "agentLangchainService");
        payload.put("partial", true);
        if (resumed) {
            payload.put("resumed", true);
        } else {
            if (result.getSkippedTodoIds() != null) {
                payload.put("skippedTodoIds", result.getSkippedTodoIds());
            }
            if (result.getRecoveryRationale() != null) {
                payload.put("recoveryRationale", result.getRecoveryRationale());
            }
        }
        eventService.append(runId, userId, "WORKFLOW_PARTIAL_COMPLETED", payload);
        if (!isBlank(result.getFinalAnswer())) {
            persistAssistantMessage(runId, userId, stageModels, result.getFinalAnswer());
        }
        tryScheduleSettlement(runId, userId);
        finalizationService.publishFinalizedEvent(runId, userId, AgentRunStatus.PARTIAL.name());
    }

    private boolean hasDurableStopState(String runId) {
        AgentRun latest = runMapper.findById(runId);
        if (latest == null || latest.getStatus() == null) {
            return false;
        }
        return latest.getStatus() == AgentRunStatus.WAITING
                || latest.getStatus() == AgentRunStatus.CANCELING
                || latest.getStatus() == AgentRunStatus.CANCELED
                || latest.getStatus() == AgentRunStatus.EXPIRED
                || latest.getStatus() == AgentRunStatus.FAILED
                || latest.getStatus() == AgentRunStatus.COMPLETED
                || latest.getStatus() == AgentRunStatus.PARTIAL;
    }

    private LangchainToolJobCheckpointCoordinator checkpointCoordinator() {
        return new LangchainToolJobCheckpointCoordinator(
                runMapper,
                eventService,
                objectMapper,
                agentRunDatasetRegistryProvider,
                toolJobCheckpointWriter,
                checkpointFailureRecoveryService);
    }

    private LangchainWorkflowStepCoordinator stepCoordinator() {
        return new LangchainWorkflowStepCoordinator(
                linearWorkflowExecutor,
                dagWorkflowExecutor,
                eventService,
                checkpointCoordinator());
    }

    private void persistPlan(String runId, String userId, LangchainTodoPlan plan) {
        String planJson = writeJson(plan);
        runMapper.updatePlanJson(runId, userId, planJson);
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore != null) {
            // Redis 中的 plan 是前端 snapshot/status 的快速恢复来源；DB 中的 plan 是终态和历史兜底。
            // valid=true 说明这是 planner 生成并被 pipeline 接受的计划，不是 HITL 修改中的临时草稿。
            stateStore.recordPlan(runId, planJson, true);
        }
    }

    private void validateFrozenPlan(LangchainTodoPlan plan) {
        LangchainWorkflowRouting.validateFrozenPlan(plan);
    }

    private List<ToolSpecification> resolveToolSpecifications(AgentEventService.RunConfig runConfig, String userGoal) {
        ToolProvider provider = toolProviderProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(LangchainToolInvocationKeys.WEB_SEARCH_ENABLED, runConfig.webSearchEnabled());
        params.put(LangchainToolInvocationKeys.CODE_INTERPRETER_ENABLED, runConfig.codeInterpreterEnabled());
        // LC4j（LangChain4j）ToolProvider 是动态的：同一个服务会根据 run 配置决定是否暴露 web search（网页搜索）、
        // code interpreter（代码解释器）等能力。planning prompt 和 execution tool-calling（执行阶段工具调用）
        // 必须使用同一份 ToolSpecification，避免“计划里有工具但执行时没有”。
        return provider.provideTools(ToolProviderRequest.builder()
                        .userMessage(UserMessage.from(nvl(userGoal)))
                        .invocationContext(InvocationContext.builder()
                                .userMessage(UserMessage.from(nvl(userGoal)))
                                .invocationParameters(InvocationParameters.from(params))
                                .timestampNow()
                                .build())
                        .build())
                .tools()
                .keySet()
                .stream()
                .toList();
    }

    private String buildSnapshot(String userGoal,
                                 LangchainLinearWorkflowResult result,
                                 AgentRunStatus status) {
        // snapshot 是给前端、调试脚本和恢复流程看的业务快照；它不是完整观测明细。
        // 大体积 LLM/tool 明细由 AgentObservabilityService 拆成摘要索引 + Redis detail blob，
        // 普通用户展开调用详情时走 safe detail API，不再从 snapshot 直接读取 raw HTTP / raw reasoning。
        // 因此这里保留的是“能解释最终答案从哪些 todo 来”的结构，而不是每次模型调用的全部证据。
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", result.getPlan());
        snapshot.put("completed_items", result.getCompletedTodos());
        snapshot.put("answer", nvl(result.getFinalAnswer()));
        snapshot.put("answer_markdown", nvl(result.getFinalAnswer()));
        snapshot.put("status", status.name());
        snapshot.put("failure_reason", nvl(result.getFailureReason()));
        snapshot.put("tool_calls_used", result.getToolCallsUsed());
        snapshot.put("engine", "agentLangchainService");
        return writeJson(snapshot);
    }

    private String buildPartialSnapshot(String userGoal,
                                        LangchainLinearWorkflowResult result) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("user_goal", userGoal);
        snapshot.put("plan", result.getPlan());
        snapshot.put("completed_items", result.getCompletedTodos());
        snapshot.put("answer", nvl(result.getFinalAnswer()));
        snapshot.put("answer_markdown", nvl(result.getFinalAnswer()));
        snapshot.put("status", AgentRunStatus.PARTIAL.name());
        snapshot.put("failure_reason", nvl(result.getFailureReason()));
        snapshot.put("tool_calls_used", result.getToolCallsUsed());
        snapshot.put("engine", "agentLangchainService");
        snapshot.put("partial", true);
        if (result.getSkippedTodoIds() != null) {
            snapshot.put("skipped_todo_ids", result.getSkippedTodoIds());
        }
        if (result.getRecoveryRationale() != null) {
            snapshot.put("recovery_rationale", result.getRecoveryRationale());
        }
        if (result.getRecoveryJudgeDecisionId() != null) {
            snapshot.put("recovery_judge_decision_id", result.getRecoveryJudgeDecisionId());
        }
        return writeJson(snapshot);
    }

    private void persistAssistantMessage(String runId,
                                         String userId,
                                         LangchainRunStageModelResolver.StageModels stageModels,
                                         String finalAnswer) {
        if (isBlank(finalAnswer)) {
            return;
        }
        try {
            // messages 表是 follow-up 的上下文来源之一；最终答案不仅要进 snapshot，也要作为 assistant message 留存。
            String assistantMetaJson = messageService.buildMetaJson(
                    stageModels.planningModelName(),
                    stageModels.planningEndpointName(),
                    null,
                    null);
            messageService.createAssistantMessage(runId, finalAnswer, assistantMetaJson);
            eventService.append(runId, userId, "MESSAGE_COMPLETED", Map.of(
                    "role", "assistant",
                    "content_preview", preview(finalAnswer, 200),
                    "model", nvl(stageModels.planningModelName()),
                    "endpoint", nvl(stageModels.planningEndpointName()),
                    "engine", "agentLangchainService"));
        } catch (Exception e) {
            log.warn("Failed to create assistant message for runId={}: {}", runId, e.getMessage());
        }
    }

    private String preview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLen ? content : content.substring(0, maxLen);
    }

    private void markRunStatus(String runId, AgentRunStatus status) {
        AgentRunStateStore stateStore = stateStoreProvider.getIfAvailable();
        if (stateStore != null) {
            stateStore.markRunStatus(runId, status.name());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 从 run.ext JSON 反序列化 run 启动时冻结的 dataFreshness 快照并设置到 AgentContext。
     * 旧 run 无此字段时跳过，AgentPromptService 会 fallback 到热加载配置。
     */
    private void setDataFreshnessFromExt(String extJson) {
        try {
            if (extJson == null || extJson.isBlank()) return;
            var extNode = objectMapper.readTree(extJson);
            var freshnessNode = extNode.get("data_freshness");
            if (freshnessNode == null || freshnessNode.isNull()) return;
            AgentLlmProperties.DataFreshness freshness = new AgentLlmProperties.DataFreshness();
            freshness.setStartDate(textOrNull(freshnessNode, "start_date"));
            freshness.setEndDate(textOrNull(freshnessNode, "end_date"));
            freshness.setAsOfDate(textOrNull(freshnessNode, "as_of_date"));
            freshness.setDescription(textOrNull(freshnessNode, "description"));
            AgentContext.setDataFreshness(freshness);
        } catch (Exception e) {
            log.warn("Failed to parse data_freshness from ext for agent run, falling back to live config", e);
        }
    }

    /** 从 run.ext 恢复 D02 冻结的 Prompt 选择；旧 Run 没有该字段时保持兼容的当前默认。 */
    private void setPromptSelectionFromExt(String extJson) {
        if (extJson == null || extJson.isBlank()) {
            return;
        }
        try {
            var extNode = objectMapper.readTree(extJson);
            var node = extNode.get("prompt_selection");
            if (node == null || node.isNull()) {
                return;
            }
            PromptRunSelection selection = new PromptRunSelection(
                    node.path("schema_version").asInt(-1),
                    textOrNull(node, "bundle_version"),
                    textOrNull(node, "variant"),
                    textOrNull(node, "bundle_digest"),
                    textOrNull(node, "capability_catalog_digest"),
                    LocalDate.parse(textOrNull(node, "reference_date")));
            AgentContext.setPromptRunSelection(selection);
            promptService.validatePromptSelection(selection);
        } catch (Exception e) {
            throw new IllegalStateException("PROMPT_SELECTION_INVALID", e);
        }
    }

    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode parent, String field) {
        var node = parent.get(field);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (!isBlank(primary)) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }

    private boolean abortIfStopped(String runId, String userId, String phase) {
        return executionGuard.stopReason(runId, userId)
                .map(reason -> {
                    // 这里不把状态改回失败，也不追加终态事件。
                    // cancel/pause 的状态机由控制接口负责，pipeline 只要在关键写点前停止继续覆盖即可。
                    log.info("LangChain run {} aborted at {} (control status={})", runId, phase, reason);
                    return true;
                })
                .orElse(false);
    }

    private void publishFailure(String runId,
                                String userId,
                                String userGoal,
                                LangchainLinearWorkflowResult result,
                                Throwable throwable) {
        publishFailureInternal(runId, userId, userGoal, result, throwable, null);
    }

    private boolean publishResumedFailure(String runId,
                                          String userId,
                                          String userGoal,
                                          LangchainLinearWorkflowResult result,
                                          Throwable throwable,
                                          ToolJobResumeContext resumeContext) {
        return publishFailureInternal(runId, userId, userGoal, result, throwable, resumeContext);
    }

    private boolean publishFailureInternal(String runId,
                                           String userId,
                                           String userGoal,
                                           LangchainLinearWorkflowResult result,
                                           Throwable throwable,
                                           ToolJobResumeContext resumeContext) {
        boolean requireDurableWrite = resumeContext != null;
        if (abortIfStopped(runId, userId, "before_failure_persist")) {
            return !requireDurableWrite || hasDurableStopState(runId);
        }
        String failureReason = nvl(result == null ? null : result.getFailureReason());
        // FailureMapper 把底层异常字符串翻译成业务事件。
        // 例如 budget（预算）超限、工具错误、重复工具调用、普通 workflow failed（工作流失败）
        // 会映射成不同 event payload（事件载荷），方便 matrix（矩阵测试脚本）和前端判断。
        LangchainFailureDecision decision = failureMapper.map(
                AgentContext.getPhase(),
                AgentContext.getTodoId(),
                null,
                failureReason,
                null,
                throwable,
                result == null ? null : result.getToolCallsUsed());
        PreparedResumedTerminal prepared = requireDurableWrite
                ? prepareResumedTerminalObservability(
                        runId,
                        buildSnapshot(userGoal, result, AgentRunStatus.FAILED),
                        AgentRunStatus.FAILED,
                        decision.getObservabilityFailureType(),
                        decision.getReason())
                : null;
        String snapshot = requireDurableWrite
                ? prepared.snapshot()
                : attachObservability(
                        runId,
                        buildSnapshot(userGoal, result, AgentRunStatus.FAILED),
                        AgentRunStatus.FAILED,
                        decision.getObservabilityFailureType(),
                        decision.getReason());
        int updated = requireDurableWrite
                ? persistResumedTerminal(runId, userId, AgentRunStatus.FAILED,
                result, snapshot, decision.getReason(), resumeContext)
                : runMapper.updateSnapshot(runId, userId, AgentRunStatus.FAILED,
                snapshot, true, decision.getReason());
        if (requireDurableWrite && updated != 1) {
            log.warn("FAILED snapshot was not persisted for run={}", runId);
            return false;
        }
        if (requireDurableWrite) {
            commitResumedTerminalObservability(prepared);
        }
        if (updated == 1) {
            recordSchedulerCompletion(AgentRunStatus.FAILED);
        }
        try {
            markRunStatus(runId, AgentRunStatus.FAILED);
            // 260618-workspace-v0: 触发终态事件
            finalizationService.publishFinalizedEvent(runId, userId, AgentRunStatus.FAILED.name());
            Map<String, Object> payload = new LinkedHashMap<>(decision.getEventPayload());
            // ccmax #59: empty_todo_output 结构化观测透传到最终 WORKFLOW_FAILED event payload。
            // 主路径：以 result.failureMetadata 非空为准（executor 在 try 块内构造，不依赖 failureReason 字符串协议）。
            // Phase 3.2 A3: failureMetadata 按语义路由到不同子字段（budget_failure / empty_output_observation / failure_metadata），
            // 避免 budget failure 被误归类为 empty_todo_output。
            // Fallback：failureReason 含 empty_todo_output 时也允许兼容（针对历史 / 未来 executor 还没填 metadata 的场景，但 fallback 不伪造 observation）。
            Map<String, Object> failureMetadata = result == null ? null : result.getFailureMetadata();
            if (failureMetadata != null && !failureMetadata.isEmpty()) {
                String field = LangchainTodoNodeResult.routeFailureMetadataField(failureMetadata);
                if (field != null) {
                    payload.put(field, failureMetadata);
                }
            } else if (failureReason != null && failureReason.contains("empty_todo_output")) {
                // legacy / 兼容路径：不写 observation 子 map，仅保留 failureReason 让 mapper 已经归类即可。
                log.debug("empty_todo_output fallback path (no failureMetadata available), failureReason={}", failureReason);
            }
            payload.put("engine", "agentLangchainService");
            eventService.append(runId, userId, decision.getEventType(), payload);
        } catch (RuntimeException sideEffect) {
            if (!requireDurableWrite) {
                throw sideEffect;
            }
            log.warn("Resumed FAILED side effect failed after durable snapshot run={}: {}",
                    runId, sideEffect.getMessage());
        }
        return true;
    }

    private int persistResumedTerminal(String runId,
                                       String userId,
                                       AgentRunStatus status,
                                       LangchainLinearWorkflowResult result,
                                       String snapshot,
                                       String lastError,
                                       ToolJobResumeContext resumeContext) {
        if (resumeContext == null || resumeContext.getResumeToken() == null
                || resumeContext.getResumeToken().isBlank()
                || resumeContext.getResumeLeaseVersion() <= 0
                || resumeContext.getResumeLauncherOwnerId() == null
                || resumeContext.getResumeLauncherOwnerId().isBlank()) {
            return 0;
        }
        String planJson = result == null || result.getPlan() == null
                ? null : writeJson(result.getPlan());
        return runMapper.updateResumedTerminal(
                runId, userId, status, planJson, snapshot, true, lastError,
                resumeContext.getResumeToken(), resumeContext.getResumeLeaseVersion(),
                resumeContext.getResumeLauncherOwnerId());
    }

    private PreparedResumedTerminal prepareResumedTerminalObservability(
            String runId,
            String snapshot,
            AgentRunStatus status,
            String failureType,
            String failureReason) {
        AgentObservabilityService observabilityService = observabilityServiceProvider.getIfAvailable();
        if (observabilityService == null) {
            return new PreparedResumedTerminal(snapshot, null, null);
        }
        AgentObservabilityService.TerminalSnapshotCandidate candidate =
                observabilityService.prepareTerminalSnapshot(
                        runId, snapshot, status,
                        isBlank(failureType) ? "WorkflowFailed" : failureType,
                        failureReason);
        return new PreparedResumedTerminal(candidate.snapshotJson(), observabilityService, candidate);
    }

    private void commitResumedTerminalObservability(PreparedResumedTerminal prepared) {
        if (prepared == null || prepared.service() == null || prepared.candidate() == null) {
            return;
        }
        try {
            prepared.service().commitTerminalSnapshot(prepared.candidate());
        } catch (RuntimeException e) {
            // 主数据库终态已落稳；观测 Redis 失败不得把 winner 伪装成可重试旧 worker。
            log.warn("Terminal observability commit failed after durable resumed snapshot run={}: {}",
                    prepared.candidate().runId(), e.getMessage());
        }
    }

    private record PreparedResumedTerminal(
            String snapshot,
            AgentObservabilityService service,
            AgentObservabilityService.TerminalSnapshotCandidate candidate) {
    }

    /** 只有数据库已经接受终态写入后，才把本次 Run 计入调度完成结果。 */
    private void recordSchedulerCompletion(AgentRunStatus status) {
        if (schedulerMetrics != null) {
            schedulerMetrics.recordCompletion(status);
        }
    }

    private void tryScheduleSettlement(String runId, String userId) {
        try {
            creditSettlementService.settleAsync(runId, userId);
        } catch (Exception e) {
            log.warn("Failed to schedule credit settlement: runId={} err={}", runId, e.getMessage());
        }
    }

    private boolean hasAdminCreditBypass(AgentRun run) {
        if (run == null || isBlank(run.getExt())) {
            return false;
        }
        try {
            Map<?, ?> ext = objectMapper.readValue(run.getExt(), Map.class);
            Object value = ext.get("is_admin");
            if (value == null) {
                value = ext.get("isAdmin");
            }
            if (value instanceof Boolean boolValue) {
                return boolValue;
            }
            if (value instanceof Number numberValue) {
                return numberValue.intValue() != 0;
            }
            return value != null && Boolean.parseBoolean(String.valueOf(value));
        } catch (Exception e) {
            return false;
        }
    }

    private String attachObservability(String runId,
                                       String snapshot,
                                       AgentRunStatus status,
                                       String observabilityFailureType,
                                       String failureReason) {
        AgentObservabilityService observabilityService = observabilityServiceProvider.getIfAvailable();
        if (observabilityService == null) {
            return snapshot;
        }
        if (status == AgentRunStatus.FAILED && !isBlank(failureReason)) {
            // 失败原因既写业务 snapshot，也写 observability failure（可观测失败记录），
            // 后者用于 trace（调用轨迹）/timeline（时间线）聚合和 matrix（矩阵测试脚本）诊断。
            observabilityService.recordFailure(
                    runId,
                    isBlank(observabilityFailureType) ? "WorkflowFailed" : observabilityFailureType,
                    failureReason);
        }
        return observabilityService.attachObservabilityToSnapshot(runId, snapshot, status);
    }
}
