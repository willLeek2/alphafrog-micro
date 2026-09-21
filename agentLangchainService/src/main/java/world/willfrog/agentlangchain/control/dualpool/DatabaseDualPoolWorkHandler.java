package world.willfrog.agentlangchain.control.dualpool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.execution.DualPoolWaitGroupNodeExecutor;
import world.willfrog.agentlangchain.execution.ExecutionModeResolver;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainCompletedTodo;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 双 Worker 池的数据库实现。
 *
 * <p>Run 协调线程只做状态读取、依赖判断、工作项创建和终态接入。规划、Todo 执行与最终回答
 * 都被建模成节点工作项，由节点池执行；因此慢模型调用不会占住 Run 协调许可。</p>
 *
 * <p>内存提示只负责唤醒。每次节点执行都先通过数据库条件更新取得领取代际，提交时再带上
 * 五字段身份与四类版本；重复提示、迟到提交和并发协调都不能绕过数据库事实。</p>
 */
@Component
@Slf4j
public class DatabaseDualPoolWorkHandler implements DualPoolWorkHandler {

    private static final String KIND_PLANNING = "PLANNING";
    private static final String KIND_TODO = "TODO";
    private static final String KIND_FINAL_ANSWER = "FINAL_ANSWER";
    private static final String PLANNING_NODE_ID = "__dual_pool_planning__";
    private static final String FINAL_ANSWER_NODE_ID = "__dual_pool_final_answer__";
    private static final int INITIAL_ATTEMPT = 0;
    private static final int INITIAL_SEGMENT = 0;
    private static final int RUN_LOCK_STRIPES = 256;

    private final AgentRunMapper runMapper;
    private final FreshRunPipeline freshRunPipeline;
    private final NodeWorkItemStore workItemStore;
    private final NodeWorkPlanAdapter planAdapter;
    private final LangchainTodoNodeExecutor todoNodeExecutor;
    private final AgentRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final DualPoolDispatcher dispatcher;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final SchedulerVersionPolicy schedulerVersionPolicy;
    private final DualPoolToolJobCoordinator toolJobCoordinator;
    private final DualPoolWaitGroupNodeExecutor waitGroupNodeExecutor;
    private final Duration claimLease;
    private final int perRunUnfinishedLimit;
    private final String claimant = DualPoolToolJobCoordinator.processNodeClaimant();
    /** 固定条带锁不会按 runId 增长，也不会在旧协调回合仍等待时被删除并创建第二把锁。 */
    private final Object[] runLockStripes = createRunLockStripes();

    public DatabaseDualPoolWorkHandler(
            AgentRunMapper runMapper,
            FreshRunPipeline freshRunPipeline,
            NodeWorkItemStore workItemStore,
            NodeWorkPlanAdapter planAdapter,
            LangchainTodoNodeExecutor todoNodeExecutor,
            AgentRunEventService eventService,
            ObjectMapper objectMapper,
            DualPoolDispatcher dispatcher,
            DualPoolRunAdmissionRegistry admissionRegistry,
            SchedulerVersionPolicy schedulerVersionPolicy,
            DualPoolToolJobCoordinator toolJobCoordinator,
            DualPoolWaitGroupNodeExecutor waitGroupNodeExecutor,
            @Value("${agent.langchain.dual-pool.node-worker.claim-lease-seconds:300}") long claimLeaseSeconds,
            @Value("${agent.langchain.dual-pool.per-run-unfinished-limit:256}") int perRunUnfinishedLimit) {
        this.runMapper = runMapper;
        this.freshRunPipeline = freshRunPipeline;
        this.workItemStore = workItemStore;
        this.planAdapter = planAdapter;
        this.todoNodeExecutor = todoNodeExecutor;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.admissionRegistry = admissionRegistry;
        this.schedulerVersionPolicy = schedulerVersionPolicy;
        this.toolJobCoordinator = toolJobCoordinator;
        this.waitGroupNodeExecutor = waitGroupNodeExecutor;
        this.claimLease = Duration.ofSeconds(Math.max(1L, claimLeaseSeconds));
        this.perRunUnfinishedLimit = Math.max(1, perRunUnfinishedLimit);
    }

    @Override
    @Transactional
    public void coordinateRun(RunCoordinationHint hint) {
        if (hint == null) {
            return;
        }
        long admissionEpoch = admissionRegistry.currentAdmissionEpoch(hint.runId());
        if (admissionEpoch < 0L) {
            return;
        }
        Object lock = runLockFor(hint.runId());
        synchronized (lock) {
            // 提示可能在等待条带锁期间跨过终态与追问/恢复边界；旧 epoch 不能协调新一轮。
            if (admissionRegistry.currentAdmissionEpoch(hint.runId()) != admissionEpoch) {
                return;
            }
            coordinateLocked(hint.runId());
        }
    }

    private void coordinateLocked(String runId) {
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            releaseRun(runId);
            return;
        }
        if (!schedulerVersionPolicy.isDualPool(run)) {
            log.error("已接纳 Run 的调度器版本不是 DUAL_POOL_V1，停止协调: runId={} version={}",
                    runId, run.getSchedulerVersion());
            releaseRun(runId);
            return;
        }
        AgentRunStatus status = run.getStatus();
        if (status == AgentRunStatus.RECEIVED) {
            ensurePlanningWorkItem(run);
            return;
        }
        if (status == AgentRunStatus.EXECUTING) {
            try {
                advanceExecutingRun(run);
            } catch (RuntimeException e) {
                // 冻结 Plan 损坏、结构不合法或模式判定失败时，不能只让 dispatcher 记日志后
                // 把 Run 永久留在 EXECUTING。把协调异常转成同一套持久失败收尾。
                String reason = "dual_pool_coordination_failed:" + safeReason(e);
                log.error("双池协调失败，转入持久失败收尾: runId={} reason={}", runId, reason, e);
                persistInfrastructureFailure(run, null, List.of(), reason,
                        Map.of("stage", "advance_executing_run"));
            }
            return;
        }
        if (status == AgentRunStatus.WAITING_TOOL_JOB && toolJobCoordinator.hasActiveWait(runId)) {
            return;
        }
        if (isTerminal(status) || status == AgentRunStatus.WAITING
                || status == AgentRunStatus.WAITING_TOOL_JOB || status == AgentRunStatus.CANCELING) {
            if (cancelUnfinished(runId, "run_status_" + (status == null ? "unknown" : status.name()))) {
                releaseRun(runId);
            }
        }
    }

    private void ensurePlanningWorkItem(AgentRun run) {
        List<NodeWorkItem> unfinished = workItemStore.listUnfinishedByRun(run.getId());
        Optional<NodeWorkItem> existingPlanning = unfinished.stream()
                .filter(item -> PLANNING_NODE_ID.equals(item.getNodeId()))
                .filter(item -> item.getPlanGeneration() != null
                        && item.getPlanGeneration().equals(run.getPlanGeneration()))
                .findFirst();
        if (existingPlanning.isPresent()) {
            dispatcher.offerNode(existingPlanning.get().identity());
            return;
        }
        for (NodeWorkItem old : unfinished) {
            workItemStore.markStale(old.identity(), value(old.getContextVersion()),
                    value(old.getRunControlVersion()), "new_plan_generation");
        }
        int expectedGeneration = run.getPlanGeneration() == null ? -1 : run.getPlanGeneration();
        int observedWorkItemGeneration = workItemStore.maxPlanGenerationByRun(run.getId());
        int generation;
        if (expectedGeneration > observedWorkItemGeneration) {
            // 追问/显式恢复会在把 Run 写成 RECEIVED 的同一条 SQL 里预先推进代际。
            // 这里直接使用那一代，不能再加一次。
            generation = expectedGeneration;
        } else {
            Integer advanced = runMapper.advancePlanGeneration(
                    run.getId(), run.getUserId(), AgentRunStatus.RECEIVED,
                    expectedGeneration, observedWorkItemGeneration);
            if (advanced == null) {
                return;
            }
            generation = advanced;
        }
        NodeWorkItemIdentity identity = identity(run.getId(), generation, PLANNING_NODE_ID);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", KIND_PLANNING);
        payload.put("workflow", "PENDING_PLAN");
        createAndHint(identity, 0L, value(run.getRunControlVersion()), payload);
    }

    private void advanceExecutingRun(AgentRun run) {
        int generation = run.getPlanGeneration() == null ? -1 : run.getPlanGeneration();
        if (generation < 0) {
            persistInfrastructureFailure(run, null, List.of(), "dual_pool_plan_generation_missing", null);
            return;
        }
        NodeWorkItem planning = workItemStore.findByIdentity(
                identity(run.getId(), generation, PLANNING_NODE_ID)).orElse(null);
        if (planning == null) {
            persistInfrastructureFailure(run, null, List.of(), "dual_pool_planning_work_item_missing", null);
            return;
        }
        if (!planning.terminal()) {
            dispatcher.offerNode(planning.identity());
            return;
        }
        if (planning.stateEnum() != NodeWorkItemState.RESULT_COMMITTED
                || !segmentSuccess(planning)) {
            AgentRun latest = runMapper.findById(run.getId());
            if (latest != null && isTerminal(latest.getStatus())) {
                releaseRun(run.getId());
                return;
            }
            persistInfrastructureFailure(run, null, List.of(),
                    segmentFailure(planning, "dual_pool_planning_failed"), segmentFailureMetadata(planning));
            return;
        }
        LangchainTodoPlan plan = readPlan(run);
        List<TodoItem> items = plan.getItems() == null ? List.of() : plan.getItems();
        Map<String, NodeWorkItem> itemRows = new LinkedHashMap<>();
        List<LangchainCompletedTodo> completed = new ArrayList<>();
        Set<String> completedIds = new LinkedHashSet<>();
        Set<String> existingIds = new LinkedHashSet<>();
        String failureReason = null;
        Map<String, Object> failureMetadata = null;
        boolean unfinishedExists = false;

        for (TodoItem item : items) {
            NodeWorkItem row = workItemStore.findByIdentity(identity(run.getId(), generation, item.getId()))
                    .orElse(null);
            if (row == null) {
                continue;
            }
            itemRows.put(item.getId(), row);
            existingIds.add(item.getId());
            if (!row.terminal()) {
                unfinishedExists = true;
                dispatcher.offerNode(row.identity());
                continue;
            }
            if (row.stateEnum() == NodeWorkItemState.RESULT_COMMITTED && segmentSuccess(row)) {
                completedIds.add(item.getId());
                completed.add(completedTodo(item, row));
                continue;
            }
            failureReason = segmentFailure(row, "dual_pool_todo_failed:" + item.getId());
            failureMetadata = segmentFailureMetadata(row);
            break;
        }
        completed.sort(Comparator.comparingInt(LangchainCompletedTodo::getSequence));
        if (failureReason != null) {
            persistInfrastructureFailure(run, plan, completed, failureReason, failureMetadata);
            return;
        }
        if (completedIds.size() == items.size()) {
            advanceFinalAnswer(run, plan, generation, completed);
            return;
        }

        boolean useDag = ExecutionModeResolver.inspectFrozen(plan).useDag();
        List<NodeWorkDraft> drafts = useDag
                ? planAdapter.runnableDag(run.getId(), generation, plan, completedIds, existingIds,
                        completedIds.size(), value(run.getRunControlVersion()))
                : planAdapter.runnableLinear(run.getId(), generation, plan, completedIds, existingIds,
                        completedIds.size(), value(run.getRunControlVersion()));
        if (drafts.isEmpty() && !unfinishedExists) {
            persistInfrastructureFailure(run, plan, completed,
                    "dual_pool_no_runnable_node", null);
            return;
        }
        if (!drafts.isEmpty()) {
            createTodoWorkItems(drafts, completed);
        }
    }

    private void createTodoWorkItems(List<NodeWorkDraft> drafts, List<LangchainCompletedTodo> completed) {
        int available = Math.max(0,
                perRunUnfinishedLimit - workItemStore.countUnfinishedByRun(drafts.get(0).identity().runId()));
        if (available == 0) {
            log.warn("Run 的未完成工作项达到上限，等待后续协调回合: runId={} limit={}",
                    drafts.get(0).identity().runId(), perRunUnfinishedLimit);
            return;
        }
        Map<String, String> datasetRefs = datasetRefs(completed);
        int toolCallsUsed = completed.stream().mapToInt(todo -> resultToolCalls(
                drafts.get(0).identity().runId(), drafts.get(0).identity().planGeneration(), todo.getTodoId())).sum();
        for (NodeWorkDraft draft : drafts.stream().limit(available).toList()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", KIND_TODO);
            payload.put("workflow", draft.workflow());
            payload.put("dependencies", draft.dependencyNodeIds());
            payload.put("todo", draft.item());
            payload.put("completedContext", completed);
            payload.put("datasetRefs", datasetRefs);
            payload.put("toolCallsUsed", toolCallsUsed);
            createAndHint(draft.identity(), draft.contextVersion(), draft.runControlVersion(), payload);
        }
    }

    private void advanceFinalAnswer(AgentRun run,
                                    LangchainTodoPlan plan,
                                    int generation,
                                    List<LangchainCompletedTodo> completed) {
        NodeWorkItemIdentity identity = identity(run.getId(), generation, FINAL_ANSWER_NODE_ID);
        NodeWorkItem finalItem = workItemStore.findByIdentity(identity).orElse(null);
        if (finalItem == null) {
            if (workItemStore.countUnfinishedByRun(run.getId()) >= perRunUnfinishedLimit) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", KIND_FINAL_ANSWER);
            payload.put("workflow", ExecutionModeResolver.inspectFrozen(plan).useDag() ? "DAG" : "LINEAR");
            payload.put("dependencies", completed.stream().map(LangchainCompletedTodo::getTodoId).toList());
            payload.put("completedContext", completed);
            payload.put("datasetRefs", datasetRefs(completed));
            payload.put("toolCallsUsed", completed.stream().mapToInt(todo ->
                    resultToolCalls(run.getId(), generation, todo.getTodoId())).sum());
            createAndHint(identity, completed.size(), value(run.getRunControlVersion()), payload);
            return;
        }
        if (!finalItem.terminal()) {
            dispatcher.offerNode(identity);
            return;
        }
        if (finalItem.stateEnum() != NodeWorkItemState.RESULT_COMMITTED || !segmentSuccess(finalItem)) {
            persistInfrastructureFailure(run, plan, completed,
                    segmentFailure(finalItem, "dual_pool_final_answer_failed"),
                    segmentFailureMetadata(finalItem));
            return;
        }
        JsonNode result = segmentResult(finalItem);
        String finalAnswer = result.path("finalAnswer").asText("").trim();
        if (finalAnswer.isBlank()) {
            persistInfrastructureFailure(run, plan, completed, "empty_final_answer", null);
            return;
        }
        LangchainLinearRunPipelineImpl.DualPoolNodeContext context = null;
        try {
            context = freshRunPipeline.rebuildDualPoolNodeContext(run.getId());
            if (context == null) {
                return;
            }
            boolean durable = freshRunPipeline.persistDualPoolWorkflowResult(context,
                    LangchainWorkflowResult.builder()
                            .success(true)
                            .finalAnswer(finalAnswer)
                            .plan(plan)
                            .completedTodos(completed)
                            .toolCallsUsed(result.path("toolCallsUsed").asInt(0))
                            .build());
            if (durable || terminalNow(run.getId())) {
                releaseRun(run.getId());
            }
        } catch (Exception e) {
            log.error("双池最终结果接入失败: runId={}", run.getId(), e);
        } finally {
            freshRunPipeline.clearDualPoolNodeContext(run.getId());
        }
    }

    private void persistInfrastructureFailure(AgentRun run,
                                              LangchainTodoPlan plan,
                                              List<LangchainCompletedTodo> completed,
                                              String reason,
                                              Map<String, Object> failureMetadata) {
        if (run == null || run.getStatus() != AgentRunStatus.EXECUTING) {
            if (run != null && terminalNow(run.getId())) {
                releaseRun(run.getId());
            }
            return;
        }
        try {
            int generation = run.getPlanGeneration() == null ? -1 : run.getPlanGeneration();
            int totalToolCalls = totalCommittedToolCalls(run.getId(), generation, plan);
            if (plan == null) {
                boolean durable = freshRunPipeline.persistDualPoolFailureWithoutPlan(
                        run, reason, failureMetadata, totalToolCalls);
                if (durable || terminalNow(run.getId())) {
                    if (cancelUnfinished(run.getId(), "run_failed")) {
                        releaseRun(run.getId());
                    }
                }
                return;
            }
            LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                    freshRunPipeline.rebuildDualPoolNodeContext(run.getId());
            if (context == null) {
                return;
            }
            boolean durable = freshRunPipeline.persistDualPoolWorkflowResult(context,
                    LangchainWorkflowResult.builder()
                            .success(false)
                            .failureReason(reason)
                            .failureMetadata(failureMetadata)
                            .plan(plan)
                            .completedTodos(completed)
                            .toolCallsUsed(totalToolCalls)
                            .build());
            if (durable || terminalNow(run.getId())) {
                if (cancelUnfinished(run.getId(), "run_failed")) {
                    releaseRun(run.getId());
                }
            }
        } catch (Exception e) {
            log.error("双池失败结果接入失败: runId={} reason={}", run.getId(), reason, e);
        } finally {
            freshRunPipeline.clearDualPoolNodeContext(run.getId());
        }
    }

    @Override
    public void executeNode(NodeWorkItemIdentity identity) {
        if (identity == null || !admissionRegistry.isAdmitted(identity.runId())) {
            return;
        }
        NodeWorkItem item = workItemStore.findByIdentity(identity).orElse(null);
        if (item == null || (item.stateEnum() != NodeWorkItemState.RUNNABLE
                && item.stateEnum() != NodeWorkItemState.RESUMABLE)
                || !item.schedulerVersionEnum().isDualPoolFamily()) {
            return;
        }
        SchedulerVersion version = item.schedulerVersionEnum();
        boolean waitGroupVersion = version.usesWaitGroups();
        AgentRun run = runMapper.findById(identity.runId());
        boolean planning = PLANNING_NODE_ID.equals(identity.nodeId());
        if (run == null || !schedulerVersionPolicy.isDualPoolFamily(run)
                || run.getPlanGeneration() == null
                || run.getPlanGeneration() != identity.planGeneration()
                || run.getRunControlVersion() == null
                || run.getRunControlVersion() != value(item.getRunControlVersion())
                || (planning && run.getStatus() != AgentRunStatus.RECEIVED)
                || (!planning && run.getStatus() != AgentRunStatus.EXECUTING)) {
            // 计划代际或 Run 状态已经变化：旧提示不得领取。协调回合会把旧行标为 STALE/CANCELED。
            return;
        }
        Optional<NodeWorkItemClaim> claimed = workItemStore.claim(
                identity, item.versions(), claimant, claimLease, version);
        if (claimed.isEmpty()) {
            return;
        }
        NodeWorkItemClaim claim = claimed.get();
        NodeWorkItemMutationResult started = workItemStore.startExecution(
                identity, claim.claimEpoch(), claimant);
        if (!started.applied()) {
            reportRejection(identity.runId(), started);
            return;
        }
        NodeWorkItemVersions submitted = new NodeWorkItemVersions(
                value(item.getContextVersion()), value(item.getRunControlVersion()), claim.claimEpoch());
        try {
            JsonNode payload = objectMapper.readTree(item.getPayloadJson());
            String kind = payload.path("kind").asText("");
            Map<String, Object> resultPatch;
            TodoExecution todoExecution = null;
            try (DualPoolToolJobExecutionContext.Scope ignored =
                         DualPoolToolJobExecutionContext.install(
                                 identity, submitted, claimant, item.getPayloadJson())) {
                if (KIND_TODO.equals(kind) && waitGroupVersion) {
                    Optional<Map<String, Object>> waitGroupPatch =
                            executeWaitGroupSegment(identity, submitted, claim, payload);
                    if (waitGroupPatch.isEmpty()) {
                        // 这一段把整组工具交出去了（或已经不在自己手里）：挂起语句已经把它写成
                        // 分段结果已提交，没有第二份结果要提交，节点执行名额在这里交还。
                        return;
                    }
                    resultPatch = waitGroupPatch.get();
                } else if (KIND_TODO.equals(kind)) {
                    todoExecution = executeTodo(identity.runId(), payload);
                    if (todoExecution.result().isSuspended()) {
                        NodeWorkItemMutationResult suspended = toolJobCoordinator.suspend(
                                item, claim, payload, todoExecution.result(), todoExecution.totalToolCalls());
                        if (!suspended.applied()) {
                            reportRejection(identity.runId(), suspended);
                        }
                        return;
                    }
                    resultPatch = todoExecution.resultPatch();
                } else {
                    resultPatch = switch (kind) {
                        case KIND_PLANNING -> executePlanning(identity.runId());
                        case KIND_FINAL_ANSWER -> executeFinalAnswer(identity.runId(), payload);
                        default -> throw new IllegalArgumentException("unknown_dual_pool_work_kind:" + kind);
                    };
                }
            }
            String patchJson = objectMapper.writeValueAsString(Map.of("segmentResult", resultPatch));
            NodeWorkItemMutationResult committed = toolJobCoordinator.isResumePayload(payload)
                    ? toolJobCoordinator.commitResumedResult(
                            identity, submitted, toolJobCoordinator.resumeOperationId(payload),
                            patchJson, externalSideEffectRef(resultPatch))
                    : workItemStore.commitSegmentResult(
                            identity, submitted, patchJson, externalSideEffectRef(resultPatch));
            if (committed.applied()) {
                dispatcher.offerRun(new RunCoordinationHint(identity.runId(), RunCoordinationHint.Reason.NODE_RESULT));
            } else {
                reportRejection(identity.runId(), committed);
            }
        } catch (ToolJobInjectedInterruption interruption) {
            // 这不是业务失败，而是 Beta 验收专门模拟“当前节点 worker 在持久写入后消失”。
            // 保留 EXECUTING/锚点现场，交给长工具对账与恢复链继续推进；任何失败回写都会
            // 把本来可恢复的 Run 提前终结，掩盖真实的进程退出语义。
            log.warn("双池节点命中一次性中断，保留持久现场等待恢复: identity={} reason={}",
                    identity.describe(), interruption.getMessage());
            if (!toolJobCoordinator.recoverInterruptedWorker(identity, submitted)) {
                log.warn("双池节点中断后未能立即补上恢复唤醒，保留持久现场等待周期补扫: identity={}",
                        identity.describe());
            }
            return;
        } catch (Exception e) {
            if (toolJobCoordinator.hasActiveWait(identity.runId())) {
                // 外部任务已经取得持久锚点并把 Run 切到等待态。此时不能把原工作项写成
                // EXECUTION_FAILED，否则终态对账器将失去可推进的 WAITING/EXECUTING 所有者。
                log.error("双池长工具已持久挂起，节点交接尚未完成，保留原状态等待恢复: identity={}",
                        identity.describe(), e);
                return;
            }
            NodeWorkItemMutationResult failed = workItemStore.reportExecutionFailure(
                    identity, claim.claimEpoch(), claimant, safeReason(e));
            if (!failed.applied()) {
                reportRejection(identity.runId(), failed);
            }
            dispatcher.offerRun(new RunCoordinationHint(identity.runId(), RunCoordinationHint.Reason.NODE_RESULT));
        } finally {
            freshRunPipeline.clearDualPoolNodeContext(identity.runId());
        }
    }

    private Map<String, Object> executePlanning(String runId) {
        AgentRun run = runMapper.findById(runId);
        LangchainLinearRunPipelineImpl.DualPoolPlanPreparation preparation =
                freshRunPipeline.prepareDualPoolRun(run);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", KIND_PLANNING);
        result.put("success", preparation != null);
        if (preparation != null) {
            result.put("workflow", preparation.useDag() ? "DAG" : "LINEAR");
            result.put("effectiveExecutionMode", preparation.effectiveExecutionMode().name());
            result.put("todoCount", preparation.plan().getItems() == null
                    ? 0 : preparation.plan().getItems().size());
        } else {
            AgentRun current = runMapper.findById(runId);
            result.put("failureReason", current != null && current.getLastError() != null
                    ? current.getLastError() : "dual_pool_planning_not_completed");
        }
        return result;
    }

    /**
     * 新调度器版本的节点分段：交给共用节点执行器跑一次模型回合。
     *
     * <p>返回空表示这一段把整组工具交了出去——挂起语句已经把它写成「分段结果已提交」，
     * 没有第二份结果要提交，调用方只要交还节点执行名额。</p>
     */
    private Optional<Map<String, Object>> executeWaitGroupSegment(NodeWorkItemIdentity identity,
                                                                  NodeWorkItemVersions versions,
                                                                  NodeWorkItemClaim claim,
                                                                  JsonNode payload) throws Exception {
        LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                freshRunPipeline.rebuildDualPoolNodeContext(identity.runId());
        if (context == null) {
            throw new IllegalStateException("dual_pool_run_not_executing");
        }
        TodoItem todo = objectMapper.treeToValue(payload.path("todo"), TodoItem.class);
        List<LangchainCompletedTodo> completed = completedContext(payload.path("completedContext"));
        Map<String, String> datasetRefs = objectMapper.convertValue(
                payload.path("datasetRefs"), new TypeReference<Map<String, String>>() { });
        AgentContext.setPhase("dual_pool_node_execution");
        AgentContext.setStage("todo_execution");
        AgentContext.setWorkflow(payload.path("workflow").asText("linear").toLowerCase());
        // 取工具目录时网页搜索开关按「调用参数 → 线程上下文 → 默认值」取值，而派发这一层不经过调用参数，
        // 所以这里要把规划阶段写进请求的开关落到上下文，否则派发时算出来的目录会比模型看到的那份少。
        AgentContext.setWebSearchEnabled(
                Boolean.TRUE.equals(context.workflowRequest().getWebSearchEnabled()));
        long startedAt = System.currentTimeMillis();
        freshRunPipeline.emitDualPoolTodoNodeEvent(
                identity.runId(), context.run().getUserId(), "TODO_NODE_STARTED", todo,
                null, 0L, null, false, null);
        DualPoolWaitGroupNodeExecutor.Outcome outcome;
        // 这一段执行的工具调用事件要带上节点归属，与旧节点路径一致；跑完就清掉，不留在线程上。
        AgentContext.setTodoContext(todo.getId(), todo.getSequence());
        try {
            outcome = waitGroupNodeExecutor.executeSegment(
                    new DualPoolWaitGroupNodeExecutor.SegmentExecution(
                            identity, versions, claimant, context.workflowRequest(), todo, completed,
                            datasetRefs, payload));
        } finally {
            AgentContext.clearTodoContext();
        }
        if (outcome instanceof DualPoolWaitGroupNodeExecutor.Outcome.Completed completedOutcome) {
            Map<String, Object> patch = completedOutcome.resultPatch();
            boolean success = Boolean.TRUE.equals(patch.get("success"));
            freshRunPipeline.emitDualPoolTodoNodeEvent(
                    identity.runId(), context.run().getUserId(),
                    success ? "TODO_NODE_COMPLETED" : "TODO_NODE_FAILED", todo,
                    success ? null : String.valueOf(patch.get("failureReason")),
                    System.currentTimeMillis() - startedAt, failureMetadata(patch), false, null);
            return Optional.of(patch);
        }
        if (outcome instanceof DualPoolWaitGroupNodeExecutor.Outcome.Suspended suspended) {
            log.info("节点分段把整组工具交了出去：segment={} group={} turn={} members={}",
                    identity.describe(), suspended.groupId(), suspended.modelTurn(), suspended.memberCount());
            return Optional.empty();
        }
        DualPoolWaitGroupNodeExecutor.Outcome.NotOwned notOwned =
                (DualPoolWaitGroupNodeExecutor.Outcome.NotOwned) outcome;
        log.warn("节点分段已经不在本 Worker 手里，没有提交任何结果：segment={} detail={}",
                identity.describe(), notOwned.detail());
        return Optional.empty();
    }

    private Map<String, Object> failureMetadata(Map<String, Object> patch) {
        Object metadata = patch.get("failureMetadata");
        if (metadata instanceof Map<?, ?> map && !map.isEmpty()) {
            return objectMapper.convertValue(map, new TypeReference<Map<String, Object>>() { });
        }
        return null;
    }

    private TodoExecution executeTodo(String runId, JsonNode payload) throws Exception {
        LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                freshRunPipeline.rebuildDualPoolNodeContext(runId);
        if (context == null) {
            throw new IllegalStateException("dual_pool_run_not_executing");
        }
        TodoItem todo = objectMapper.treeToValue(payload.path("todo"), TodoItem.class);
        List<LangchainCompletedTodo> completed = completedContext(payload.path("completedContext"));
        Map<String, String> datasetRefs = objectMapper.convertValue(
                payload.path("datasetRefs"), new TypeReference<Map<String, String>>() { });
        AtomicInteger toolCalls = new AtomicInteger(payload.path("toolCallsUsed").asInt(0));
        AgentContext.setPhase("dual_pool_node_execution");
        AgentContext.setStage("todo_execution");
        AgentContext.setWorkflow(payload.path("workflow").asText("linear").toLowerCase());
        freshRunPipeline.emitDualPoolTodoNodeEvent(
                runId, context.run().getUserId(), "TODO_NODE_STARTED", todo,
                null, 0L, null, false, null);
        long startedAt = System.currentTimeMillis();
        boolean resumingToolJob = toolJobCoordinator.isResumePayload(payload);
        LangchainTodoNodeResult nodeResult;
        if (resumingToolJob) {
            LangchainTodoNodeResult terminal = toolJobCoordinator.resumeResult(payload);
            if (toolJobCoordinator.resumeTerminalSuccess(payload)) {
                nodeResult = todoNodeExecutor.executeResumedToolResult(
                        context.workflowRequest(), todo, completed, datasetRefs,
                        toolJobCoordinator.resumeToolOutput(payload));
                toolJobCoordinator.afterModelCompleted(runId);
            } else {
                nodeResult = terminal;
            }
        } else {
            nodeResult = todoNodeExecutor.execute(
                    context.workflowRequest(), todo, completed, datasetRefs, toolCalls);
        }
        if (resumingToolJob) {
            toolCalls.set(toolJobCoordinator.resumeTotalToolCalls(payload));
        }
        if (nodeResult.isSuspended()) {
            return new TodoExecution(Map.of(), nodeResult, toolCalls.get());
        }
        String eventType = nodeResult.isSuccess() ? "TODO_NODE_COMPLETED" : "TODO_NODE_FAILED";
        freshRunPipeline.emitDualPoolTodoNodeEvent(
                runId, context.run().getUserId(), eventType, todo,
                nodeResult.isSuccess() ? null : firstNonBlank(
                        nodeResult.getFailureReason(), nodeResult.getSummary()),
                System.currentTimeMillis() - startedAt,
                nodeResult.getFailureMetadata(), nodeResult.isRecovered(), nodeResult.getRecoveryOutcome());
        return new TodoExecution(
                nodeResultPatch(KIND_TODO, nodeResult, toolCalls.get()),
                nodeResult,
                toolCalls.get());
    }

    private Map<String, Object> executeFinalAnswer(String runId, JsonNode payload) throws Exception {
        LangchainLinearRunPipelineImpl.DualPoolNodeContext context =
                freshRunPipeline.rebuildDualPoolNodeContext(runId);
        if (context == null) {
            throw new IllegalStateException("dual_pool_run_not_executing");
        }
        List<LangchainCompletedTodo> completed = completedContext(payload.path("completedContext"));
        AgentContext.setPhase("summarizing");
        AgentContext.setStage("final_answer");
        AgentContext.setWorkflow(payload.path("workflow").asText("linear").toLowerCase());
        String finalAnswer = todoNodeExecutor.writeFinalAnswer(context.workflowRequest(), completed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", KIND_FINAL_ANSWER);
        result.put("success", finalAnswer != null && !finalAnswer.isBlank());
        result.put("finalAnswer", finalAnswer == null ? "" : finalAnswer.trim());
        result.put("toolCallsUsed", payload.path("toolCallsUsed").asInt(0));
        if (finalAnswer == null || finalAnswer.isBlank()) {
            result.put("failureReason", "empty_final_answer");
        }
        return result;
    }

    @Override
    public List<RunCoordinationHint> scanRunnableRuns(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<RunCoordinationHint> hints = new ArrayList<>();
        for (String runId : admissionRegistry.snapshotRunIds()) {
            if (hints.size() >= limit) {
                break;
            }
            AgentRun run = runMapper.findById(runId);
            if (run == null || !schedulerVersionPolicy.isDualPool(run)) {
                releaseRun(runId);
                continue;
            }
            hints.add(new RunCoordinationHint(runId, RunCoordinationHint.Reason.SCAN_REDISCOVERED));
        }
        return List.copyOf(hints);
    }

    @Override
    public List<NodeWorkItemIdentity> scanRunnableNodes(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<NodeWorkItem> claimable = new ArrayList<>(
                workItemStore.scanClaimable(SchedulerVersion.DUAL_POOL_V1, limit));
        // 新版本的等待分段与恢复分段和旧版本共用同一个节点池。按 Run 分组、跨图轮转与「谁先谁后」
        // 由后面的外层推进那一组来做；这里只保证新版本的行不会因为没人扫而一直躺着。
        for (SchedulerVersion version : SchedulerVersion.values()) {
            if (version.usesWaitGroups()) {
                claimable.addAll(workItemStore.scanClaimable(version, limit));
            }
        }
        return claimable.stream()
                .map(NodeWorkItem::identity)
                .filter(identity -> admissionRegistry.isAdmitted(identity.runId()))
                .toList();
    }

    private void createAndHint(NodeWorkItemIdentity identity,
                               long contextVersion,
                               long runControlVersion,
                               Map<String, Object> payload) {
        if (workItemStore.countUnfinishedByRun(identity.runId()) >= perRunUnfinishedLimit) {
            return;
        }
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(identity.runId());
        item.setPlanGeneration(identity.planGeneration());
        item.setNodeId(identity.nodeId());
        item.setNodeAttempt(identity.nodeAttempt());
        item.setSegmentSequence(identity.segmentSequence());
        item.setState(NodeWorkItemState.RUNNABLE.name());
        item.setContextVersion(contextVersion);
        item.setRunControlVersion(runControlVersion);
        item.setClaimEpoch(0);
        item.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        try {
            item.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("dual_pool_work_payload_encode_failed", e);
        }
        NodeWorkItemMutationResult created = workItemStore.create(item);
        if (!created.applied()) {
            reportRejection(identity.runId(), created);
            throw new IllegalStateException("dual_pool_work_identity_conflict:" + identity.describe());
        }
        dispatcher.offerNode(identity);
    }

    private boolean cancelUnfinished(String runId, String reason) {
        for (NodeWorkItem item : workItemStore.listUnfinishedByRun(runId)) {
            NodeWorkItemMutationResult canceled = workItemStore.cancel(
                    item.identity(), value(item.getRunControlVersion()), value(item.getClaimEpoch()), reason);
            if (!canceled.applied()) {
                reportRejection(runId, canceled);
            }
        }
        return workItemStore.countUnfinishedByRun(runId) == 0;
    }

    private LangchainTodoPlan readPlan(AgentRun run) {
        if (run.getPlanJson() == null || run.getPlanJson().isBlank()) {
            throw new IllegalStateException("dual_pool_plan_missing");
        }
        try {
            return objectMapper.readValue(run.getPlanJson(), LangchainTodoPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("dual_pool_plan_decode_failed", e);
        }
    }

    private LangchainCompletedTodo completedTodo(TodoItem item, NodeWorkItem row) {
        JsonNode result = segmentResult(row);
        String output = result.path("output").asText("");
        return LangchainCompletedTodo.builder()
                .todoId(item.getId())
                .sequence(item.getSequence())
                .description(item.getDescription())
                .output(output)
                .modelOutput(output)
                .summary(result.path("summary").asText(output))
                .build();
    }

    private List<LangchainCompletedTodo> completedContext(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<LangchainCompletedTodo> completed = new ArrayList<>();
        for (JsonNode node : array) {
            completed.add(LangchainCompletedTodo.builder()
                    .todoId(node.path("todoId").asText())
                    .sequence(node.path("sequence").asInt())
                    .description(node.path("description").asText(""))
                    .modelOutput(node.path("modelOutput").asText(""))
                    .output(node.path("output").asText(""))
                    .summary(node.path("summary").asText(""))
                    .build());
        }
        return List.copyOf(completed);
    }

    private Map<String, String> datasetRefs(List<LangchainCompletedTodo> completed) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (LangchainCompletedTodo todo : completed) {
            world.willfrog.agent.workflow.DatasetRefRegistry.registerFromJson(todo.getOutput(), refs);
        }
        return refs;
    }

    private int resultToolCalls(String runId, int generation, String nodeId) {
        NodeWorkItem row = workItemStore.findByIdentity(identity(runId, generation, nodeId)).orElse(null);
        return row == null ? 0 : segmentResult(row).path("toolCallsUsed").asInt(0);
    }

    private int totalCommittedToolCalls(String runId, int generation, LangchainTodoPlan plan) {
        if (generation < 0 || plan == null || plan.getItems() == null) {
            return 0;
        }
        int total = 0;
        for (TodoItem item : plan.getItems()) {
            NodeWorkItem row = workItemStore.findByIdentity(identity(runId, generation, item.getId())).orElse(null);
            if (row != null && row.stateEnum() == NodeWorkItemState.RESULT_COMMITTED) {
                total += segmentResult(row).path("toolCallsUsed").asInt(0);
            }
        }
        return total;
    }

    private Map<String, Object> nodeResultPatch(String kind,
                                                LangchainTodoNodeResult result,
                                                int cumulativeToolCalls) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("kind", kind);
        patch.put("success", result.isSuccess());
        patch.put("output", result.getOutput() == null ? "" : result.getOutput());
        patch.put("summary", result.getSummary() == null ? "" : result.getSummary());
        patch.put("failureReason", result.getFailureReason() == null ? "" : result.getFailureReason());
        patch.put("failureMetadata", result.getFailureMetadata() == null ? Map.of() : result.getFailureMetadata());
        patch.put("toolCallsUsed", result.getToolCallsUsed());
        patch.put("cumulativeToolCallsUsed", cumulativeToolCalls);
        if (result.getPendingToolCallId() != null) {
            patch.put("pendingToolCallId", result.getPendingToolCallId());
            patch.put("pendingAttempt", result.getPendingAttempt());
        }
        return patch;
    }

    private record TodoExecution(Map<String, Object> resultPatch,
                                 LangchainTodoNodeResult result,
                                 int totalToolCalls) {
    }

    private JsonNode segmentResult(NodeWorkItem row) {
        try {
            JsonNode root = objectMapper.readTree(row.getPayloadJson());
            JsonNode result = root.path("segmentResult");
            return result.isMissingNode() ? objectMapper.createObjectNode() : result;
        } catch (Exception e) {
            throw new IllegalStateException("dual_pool_segment_result_decode_failed", e);
        }
    }

    private boolean segmentSuccess(NodeWorkItem row) {
        return segmentResult(row).path("success").asBoolean(false);
    }

    private String segmentFailure(NodeWorkItem row, String fallback) {
        JsonNode result = segmentResult(row);
        String reason = result.path("failureReason").asText("");
        if (!reason.isBlank()) {
            return reason;
        }
        try {
            JsonNode root = objectMapper.readTree(row.getPayloadJson());
            String executionFailure = root.path("executionFailure").asText("");
            return executionFailure.isBlank() ? fallback : executionFailure;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Map<String, Object> segmentFailureMetadata(NodeWorkItem row) {
        JsonNode metadata = segmentResult(row).path("failureMetadata");
        if (!metadata.isObject()) {
            return null;
        }
        return objectMapper.convertValue(metadata, new TypeReference<Map<String, Object>>() { });
    }

    private String externalSideEffectRef(Map<String, Object> resultPatch) {
        Object pending = resultPatch.get("pendingToolCallId");
        return pending == null ? null : pending.toString();
    }

    private void reportRejection(String runId, NodeWorkItemMutationResult result) {
        if (result == null || result.rejection() == null) {
            return;
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            return;
        }
        try {
            eventService.append(runId, run.getUserId(), result.rejection().eventName(), Map.of(
                    "identity", result.rejection().identity().describe(),
                    "versions", result.rejection().versions().describe(),
                    "reason", result.rejection().reason().name(),
                    "detail", result.rejection().detail(),
                    "external_side_effect_ref", result.rejection().externalSideEffectRef() == null
                            ? "" : result.rejection().externalSideEffectRef()));
        } catch (RuntimeException e) {
            log.warn("工作项拒绝事实写事件失败: runId={} reason={}", runId, e.getMessage());
        }
    }

    private boolean terminalNow(String runId) {
        AgentRun current = runMapper.findById(runId);
        return current == null || isTerminal(current.getStatus());
    }

    private void releaseRun(String runId) {
        AgentRun releaseCandidate = runMapper.findById(runId);
        Integer expectedPlanGeneration = releaseCandidate == null
                ? null : releaseCandidate.getPlanGeneration();
        Long expectedRunControlVersion = releaseCandidate == null
                ? null : releaseCandidate.getRunControlVersion();
        long expectedAdmissionEpoch = admissionRegistry.currentAdmissionEpoch(runId);
        Runnable release = () -> {
            if (!stillOwnsAdmissionRelease(
                    runId, releaseCandidate, expectedPlanGeneration, expectedRunControlVersion)) {
                return;
            }
            boolean released = admissionRegistry.releaseBusinessPermitIfCurrent(
                    runId, expectedAdmissionEpoch, () -> {
                        freshRunPipeline.completeDualPoolRunCleanup(runId);
                    });
            if (!released) {
                // 新一轮准入已经换了生命周期令牌。旧回调不能清上下文或释放许可。
                log.info("跳过旧准入生命周期的双池释放: runId={} expectedAdmissionEpoch={}",
                        runId, expectedAdmissionEpoch);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    release.run();
                }
            });
            return;
        }
        release.run();
    }

    /**
     * 终态提交与 afterCommit 回调之间可能插入同一 Run 的追问或显式恢复。新一轮会推进
     * planGeneration/runControlVersion 并回到 RECEIVED/EXECUTING；此时旧回调必须保留现有
     * 准入和串行锁，让新一轮继续使用，不能按 runId 无条件删除。
     */
    private boolean stillOwnsAdmissionRelease(String runId,
                                               AgentRun releaseCandidate,
                                               Integer expectedPlanGeneration,
                                               Long expectedRunControlVersion) {
        if (releaseCandidate == null
                || !SchedulerVersion.DUAL_POOL_V1.name().equals(releaseCandidate.getSchedulerVersion())) {
            return true;
        }
        final AgentRun current;
        try {
            current = runMapper.findById(runId);
        } catch (RuntimeException e) {
            // 无法确认是否已经开始新一轮时，宁可暂时保留许可；扫描器恢复后仍能再次收尾。
            log.error("无法确认双池准入是否仍属于待释放代际，暂不释放: runId={}", runId, e);
            return false;
        }
        if (current == null) {
            return true;
        }
        boolean generationUnchanged = Objects.equals(
                expectedPlanGeneration, current.getPlanGeneration())
                && Objects.equals(expectedRunControlVersion, current.getRunControlVersion());
        boolean newRoundRunning = current.getStatus() == AgentRunStatus.RECEIVED
                || current.getStatus() == AgentRunStatus.EXECUTING;
        if (!generationUnchanged || newRoundRunning) {
            log.info("跳过旧代际的双池准入释放，保留追问或恢复后的新一轮: runId={} planGeneration={} "
                            + "runControlVersion={} status={}",
                    runId, current.getPlanGeneration(), current.getRunControlVersion(), current.getStatus());
            return false;
        }
        return true;
    }

    private Object runLockFor(String runId) {
        return runLockStripes[Math.floorMod(runId.hashCode(), RUN_LOCK_STRIPES)];
    }

    private static Object[] createRunLockStripes() {
        Object[] stripes = new Object[RUN_LOCK_STRIPES];
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new Object();
        }
        return stripes;
    }

    private static boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    private static NodeWorkItemIdentity identity(String runId, int generation, String nodeId) {
        return new NodeWorkItemIdentity(runId, generation, nodeId, INITIAL_ATTEMPT, INITIAL_SEGMENT);
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String safeReason(Exception e) {
        String message = e.getMessage();
        String reason = e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ":" + message);
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
