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
import world.willfrog.agent.platform.capacity.SchedulerPauseDecision;
import world.willfrog.agent.platform.capacity.SchedulerRoundScope;
import world.willfrog.agent.platform.capacity.SchedulerStateStore;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.coordination.RunCoordination;
import world.willfrog.agent.platform.coordination.RunCoordinationDeferReason;
import world.willfrog.agent.platform.coordination.RunCoordinationStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeDispatchDeferReason;
import world.willfrog.agent.platform.workitem.NodeWorkItemClaim;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.workflow.TodoItem;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.control.LegacyRunHandoff;
import world.willfrog.agentlangchain.execution.DualPoolWaitGroupNodeExecutor;
import world.willfrog.agentlangchain.execution.ExecutionModeResolver;
import world.willfrog.agentlangchain.execution.FreshRunPipeline;
import world.willfrog.agentlangchain.execution.LangchainCompletedTodo;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipelineImpl;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.execution.WaitGroupSuspensionMarker;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.time.Duration;
import java.time.OffsetDateTime;
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
import java.util.concurrent.atomic.AtomicLong;

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
    private final RunCoordinationStore coordinationStore;
    private final SchedulerStateStore stateStore;
    private final Duration claimLease;
    private final Duration coordinationDeferRetry;
    private final Duration hintQueueFullRetry;
    private final int perRunUnfinishedLimit;
    private final int perTurnNewNodeLimit;
    private final int globalHighWatermark;
    private final int globalLowWatermark;
    private final String claimant = DualPoolToolJobCoordinator.processNodeClaimant();
    private final RunServiceLeaseStore leaseStore;
    private final ProcessInstanceIdentity processIdentity;
    private final ObjectProvider<LegacyRunHandoff> legacyHandoff;
    private final Duration serviceLeaseTtl;

    /** 交出去的旧版本 Run 累计条数（反复扫描同一行的重复命中也算，是「次数」不是「当前候选数」）。 */
    private final AtomicLong legacyHandedOff = new AtomicLong();
    /** 旧版本 Run 这一轮没接手、按所有权原因推后的累计次数。 */
    private final AtomicLong legacyDeferred = new AtomicLong();
    /** 服务所有权在别的进程手上、这一轮不接手的累计次数（双池那一路）。 */
    private final AtomicLong leaseNotAcquired = new AtomicLong();
    /** 版本读不出来、或不属于任何已知执行层的候选累计次数：只记账，不动这些 Run 的任何状态。 */
    private final AtomicLong routingIsolated = new AtomicLong();
    /** 最近一轮的当前条数：与上面的累计值分开，读数里两个都要看得见。 */
    private volatile long legacyCandidatesLastRound;
    private volatile long leaseNotAcquiredLastRound;
    private volatile long routingIsolatedLastRound;
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
            RunCoordinationStore coordinationStore,
            SchedulerStateStore stateStore,
            RunServiceLeaseStore leaseStore,
            ProcessInstanceIdentity processIdentity,
            ObjectProvider<LegacyRunHandoff> legacyHandoff,
            @Value("${agent.langchain.dual-pool.node-worker.claim-lease-seconds:300}") long claimLeaseSeconds,
            @Value("${agent.langchain.dual-pool.per-run-unfinished-limit:256}") int perRunUnfinishedLimit,
            @Value("${agent.langchain.dual-pool.per-turn-new-node-limit:8}") int perTurnNewNodeLimit,
            @Value("${agent.langchain.dual-pool.global-unfinished-high-watermark:128}") int globalHighWatermark,
            @Value("${agent.langchain.dual-pool.global-unfinished-low-watermark:96}") int globalLowWatermark,
            @Value("${agent.langchain.dual-pool.coordination-defer-retry-ms:1000}") long coordinationDeferRetryMs,
            @Value("${agent.langchain.dual-pool.hint-queue-full-retry-ms:5000}") long hintQueueFullRetryMs,
            @Value("${agent.langchain.dual-pool.service-lease-ttl-seconds:120}") long serviceLeaseTtlSeconds) {
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
        this.coordinationStore = coordinationStore;
        this.stateStore = stateStore;
        this.claimLease = Duration.ofSeconds(Math.max(1L, claimLeaseSeconds));
        this.coordinationDeferRetry = Duration.ofMillis(Math.max(1L, coordinationDeferRetryMs));
        this.hintQueueFullRetry = Duration.ofMillis(Math.max(1L, hintQueueFullRetryMs));
        this.perRunUnfinishedLimit = Math.max(1, perRunUnfinishedLimit);
        this.perTurnNewNodeLimit = Math.max(1, perTurnNewNodeLimit);
        this.globalHighWatermark = Math.max(0, globalHighWatermark);
        this.globalLowWatermark = Math.max(0, Math.min(globalLowWatermark, this.globalHighWatermark));
        this.leaseStore = leaseStore;
        this.processIdentity = processIdentity;
        this.legacyHandoff = legacyHandoff;
        this.serviceLeaseTtl = Duration.ofSeconds(Math.max(5L, serviceLeaseTtlSeconds));
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
            coordinationTurn(hint.runId());
        }
    }

    /**
     * 一轮 Run 协调：先把这个 Run 的协调资格与轮转位置对齐，再按 Run 状态推进，最后把
     * 「这一轮推进了没有、没推进是卡在哪一条上」落库。
     *
     * <p>记账放在这里而不是散在各个分支里：延期原因是给验收看的事实，不能靠日志推断，也不能
     * 因为走的是哪条分支而漏写。成功推进与延期记录都按同一把条带锁串行，条带锁之外读到的都是旧值。</p>
     */
    private void coordinationTurn(String runId) {
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            releaseRun(runId);
            return;
        }
        if (!schedulerVersionPolicy.isDualPoolFamily(run)) {
            log.error("已接纳 Run 的调度器版本不属于双池执行层，停止协调: runId={} version={}",
                    runId, run.getSchedulerVersion());
            releaseRun(runId);
            return;
        }
        RunCoordination coordination = prepareCoordinationRow(run);
        // 轮次在回合开始时取一次：这一回合被服务的是哪一轮，由它说话。
        long turnRound = stateStore.currentRound(SchedulerRoundScope.RUN_COORDINATION);
        CoordinationTurn turn = new CoordinationTurn(run, coordination, turnRound);
        try {
            coordinateLocked(run, turn);
        } finally {
            recordTurn(turn);
        }
    }

    /**
     * 确保这个 Run 有协调资格记录，并让记录上的计划代际跟着 Run 走；返回这一轮读到的资格记录。
     *
     * <p>记录上的版本与计划代际都由存储层从 Run 主表派生，这里不传值：滚动部署期间同一条 Run 的
     * 冻结版本只有一个出处，传进去就会出现主记录与子记录各说一套。计划代际只同步前移的那一代，
     * 拿旧观察去写会让存储层拒掉（影响 0 行）。</p>
     */
    private RunCoordination prepareCoordinationRow(AgentRun run) {
        int generation = run.getPlanGeneration() == null ? -1 : run.getPlanGeneration();
        RunCoordination existing = coordinationStore.find(run.getId()).orElse(null);
        if (existing == null) {
            coordinationStore.ensure(run.getId());
            RunCoordination created = coordinationStore.find(run.getId()).orElse(null);
            if (created == null) {
                throw new IllegalStateException("协调资格记录刚建好就读不回来，Run 可能不在库里：runId=" + run.getId());
            }
            return created;
        }
        if (value(existing.getPlanGeneration()) != generation) {
            if (coordinationStore.syncPlanGeneration(run.getId(), generation)) {
                existing.setPlanGeneration(generation);
            }
        }
        return existing;
    }

    /**
     * 把这一轮的记账写进数据库。
     *
     * <p>没推进又有明确原因时记延期（原因 + 下次可见时间）；其余情况都记「被服务过」——包括那些
     * 只是在等已经在跑的节点、既没新建也没被挡住的回合。这么记是为了让轮转公平：等节点的图这一轮
     * 确实占到了协调机会，下一轮可以先让别人来。</p>
     */
    private void recordTurn(CoordinationTurn turn) {
        if (!turn.progressed && turn.deferReason != null) {
            OffsetDateTime nextVisibleAt = OffsetDateTime.now().plus(coordinationDeferRetry);
            if (coordinationStore.deferFor(turn.runId, turn.deferReason, nextVisibleAt,
                    turn.planGeneration, turn.coordinationServedRound)) {
                log.info("Run 协调延期: runId={} reason={} nextVisibleAt={}",
                        turn.runId, turn.deferReason, nextVisibleAt);
            }
            return;
        }
        if (!coordinationStore.markCoordinationServed(turn.runId, turn.turnRound, turn.planGeneration)) {
            // 计划代际已经变了、或者资格记录已经被别的回合推进过：这次成功写不生效。
            log.info("Run 协调的成功推进没有写进去（计划代际或轮次已经变化）: runId={} turnRound={}",
                    turn.runId, turn.turnRound);
        }
    }

    private void coordinateLocked(AgentRun run, CoordinationTurn turn) {
        AgentRunStatus status = run.getStatus();
        if (status == AgentRunStatus.RECEIVED) {
            ensurePlanningWorkItem(run, turn);
            return;
        }
        if (status == AgentRunStatus.EXECUTING) {
            try {
                advanceExecutingRun(run, turn);
            } catch (RuntimeException e) {
                // 冻结 Plan 损坏、结构不合法或模式判定失败时，不能只让 dispatcher 记日志后
                // 把 Run 永久留在 EXECUTING。把协调异常转成同一套持久失败收尾。
                String reason = "dual_pool_coordination_failed:" + safeReason(e);
                log.error("双池协调失败，转入持久失败收尾: runId={} reason={}", run.getId(), reason, e);
                persistInfrastructureFailure(run, null, List.of(), reason,
                        Map.of("stage", "advance_executing_run"));
            }
            return;
        }
        String runId = run.getId();
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

    private void ensurePlanningWorkItem(AgentRun run, CoordinationTurn turn) {
        List<NodeWorkItem> unfinished = workItemStore.listUnfinishedByRun(run.getId());
        Optional<NodeWorkItem> existingPlanning = unfinished.stream()
                .filter(item -> PLANNING_NODE_ID.equals(item.getNodeId()))
                .filter(item -> item.getPlanGeneration() != null
                        && item.getPlanGeneration().equals(run.getPlanGeneration()))
                .findFirst();
        if (existingPlanning.isPresent()) {
            if (offerIfRunnable(existingPlanning.get())) {
                turn.served();
            }
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
        if (newNodesAllowed(run.getId(), turn) == 0) {
            return;
        }
        NodeWorkItemIdentity identity = identity(run.getId(), generation, PLANNING_NODE_ID);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", KIND_PLANNING);
        payload.put("workflow", "PENDING_PLAN");
        createAndHint(identity, 0L, value(run.getRunControlVersion()), payload,
                run.getSchedulerVersion(), turn);
    }

    private void advanceExecutingRun(AgentRun run, CoordinationTurn turn) {
        int generation = run.getPlanGeneration() == null ? -1 : run.getPlanGeneration();
        if (generation < 0) {
            persistInfrastructureFailure(run, null, List.of(), "dual_pool_plan_generation_missing", null);
            return;
        }
        Map<String, NodeWorkItem> latestSegments = latestSegmentsByNode(run.getId(), generation);
        NodeWorkItem planning = latestSegments.get(PLANNING_NODE_ID);
        if (planning == null) {
            persistInfrastructureFailure(run, null, List.of(), "dual_pool_planning_work_item_missing", null);
            return;
        }
        if (!planning.terminal()) {
            if (offerIfRunnable(planning)) {
                turn.served();
            }
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
            NodeWorkItem row = latestSegments.get(item.getId());
            if (row == null) {
                continue;
            }
            itemRows.put(item.getId(), row);
            existingIds.add(item.getId());
            if (!row.terminal()) {
                unfinishedExists = true;
                if (offerIfRunnable(row)) {
                    turn.served();
                }
                continue;
            }
            if (row.stateEnum() == NodeWorkItemState.RESULT_COMMITTED && segmentSuccess(row)) {
                completedIds.add(item.getId());
                completed.add(completedTodo(item, row));
                continue;
            }
            if (segmentSuspended(row)) {
                // 这一段把整组工具交出去了，节点并没有失败：下一段已经建好，等结果齐备放行。
                unfinishedExists = true;
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
            advanceFinalAnswer(run, plan, generation, completed, latestSegments, turn);
            return;
        }

        boolean useDag = ExecutionModeResolver.inspectFrozen(plan).useDag();
        String schedulerVersion = run.getSchedulerVersion();
        List<NodeWorkDraft> drafts = useDag
                ? planAdapter.runnableDag(run.getId(), generation, plan, completedIds, existingIds,
                        completedIds.size(), value(run.getRunControlVersion()), schedulerVersion)
                : planAdapter.runnableLinear(run.getId(), generation, plan, completedIds, existingIds,
                        completedIds.size(), value(run.getRunControlVersion()), schedulerVersion);
        if (drafts.isEmpty() && !unfinishedExists) {
            persistInfrastructureFailure(run, plan, completed,
                    "dual_pool_no_runnable_node", null);
            return;
        }
        if (!drafts.isEmpty()) {
            createTodoWorkItems(drafts, completed, turn);
            return;
        }
        // 没有新节点可建、也没有失败：这一次协调只是在等已经在跑的节点，记一次被服务过。
        turn.served();
    }

    /**
     * 一个计划代际下每个逻辑节点的最新分段，按节点编号索引。
     *
     * <p>一次等待会把当前分段写成已提交并建出下一段，所以这里必须看最新分段：拿第一段去判断，
     * 会把「悬在等待里的节点」当成有结果的节点。</p>
     */
    private Map<String, NodeWorkItem> latestSegmentsByNode(String runId, int planGeneration) {
        Map<String, NodeWorkItem> byNode = new LinkedHashMap<>();
        for (NodeWorkItem item : workItemStore.listLatestSegments(runId, planGeneration)) {
            byNode.put(item.getNodeId(), item);
        }
        return byNode;
    }

    /** 只把确实能领取的分段投进提醒队列：等待中的下一段不该占掉别人的派发机会。 */
    private boolean offerIfRunnable(NodeWorkItem item) {
        if (item.stateEnum() != NodeWorkItemState.RUNNABLE
                && item.stateEnum() != NodeWorkItemState.RESUMABLE) {
            return false;
        }
        dispatcher.offerNode(item.identity());
        return true;
    }

    /**
     * 新增节点前的两道闸：这个 Run 还能背多少未完成节点、全局是否已经暂停新增。
     *
     * <p>返回还能新增的数量，0 表示这一轮不允许新增（原因已经记在这一轮的记账上）。两道闸对
     * 规划节点、普通节点、最终回答节点一视同仁：共同计数、共用名额，才谈得上「谁也不能绕过」。</p>
     */
    private int newNodesAllowed(String runId, CoordinationTurn turn) {
        int perRunRoom = Math.max(0, perRunUnfinishedLimit - workItemStore.countUnfinishedByRun(runId));
        if (perRunRoom == 0) {
            turn.defer(RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT);
            return 0;
        }
        SchedulerPauseDecision pause = stateStore.decideAndRecord(
                workItemStore.countUnfinished(), globalHighWatermark, globalLowWatermark);
        if (pause.paused()) {
            // 暂停标记是持久事实：进程重启后也只按库里那一条继续判断，不拿当前数量重新起算。
            turn.defer(RunCoordinationDeferReason.GLOBAL_UNFINISHED_PAUSED);
            log.info("全局新增已暂停，暂不新增节点: runId={} {}", runId, pause.describe());
            return 0;
        }
        return perRunRoom;
    }

    /** 这一行是不是「整组工具交出去」的挂起分段：它已提交但不是完成。 */
    private boolean segmentSuspended(NodeWorkItem row) {
        try {
            return WaitGroupSuspensionMarker.present(objectMapper.readTree(row.getPayloadJson()));
        } catch (Exception e) {
            // 载荷读不出来时不能当成挂起：宁可走原有的失败收尾，也不要把坏数据当等待继续挂着。
            return false;
        }
    }

    /**
     * 把这一轮定下来的就绪节点写成工作项，并同步记下这一轮为什么没能建完。
     *
     * <p>三道限制一起算，取最小的那个：这一回合的节点数上限、这个 Run 还能背多少未完成节点、
     * 以及全局高水位。前两道是给单张图和单个回合设的上限，第三道是全局的：到高水位就整批停新增，
     * 直到回落到低水位才恢复，中途不允许因为「已经低于高水位」提前开闸。</p>
     */
    private void createTodoWorkItems(List<NodeWorkDraft> drafts,
                                     List<LangchainCompletedTodo> completed,
                                     CoordinationTurn turn) {
        String runId = drafts.get(0).identity().runId();
        int perRunRoom = newNodesAllowed(runId, turn);
        if (perRunRoom == 0) {
            return;
        }
        int allowed = Math.min(Math.min(drafts.size(), perTurnNewNodeLimit), perRunRoom);
        Map<String, String> datasetRefs = datasetRefs(completed);
        int toolCallsUsed = completed.stream().mapToInt(todo -> resultToolCalls(
                runId, drafts.get(0).identity().planGeneration(), todo.getTodoId())).sum();
        for (NodeWorkDraft draft : drafts.stream().limit(allowed).toList()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", KIND_TODO);
            payload.put("workflow", draft.workflow());
            payload.put("dependencies", draft.dependencyNodeIds());
            payload.put("todo", draft.item());
            payload.put("completedContext", completed);
            payload.put("datasetRefs", datasetRefs);
            payload.put("toolCallsUsed", toolCallsUsed);
            createAndHint(draft.identity(), draft.contextVersion(), draft.runControlVersion(), payload,
                    draft.schedulerVersion(), turn);
        }
        if (allowed < drafts.size()) {
            // 这一轮确实推进了，只是没全建完：剩下的就绪节点留给后续回合，由节点完成后的协调提示或
            // 周期补扫接着建。这里不记延期原因，因为延期原因是「这一轮没推进」的事实。
            log.info("这一回合没有建完所有就绪节点，剩下的留给后续回合: runId={} 已建={} 就绪={} 每回合上限={} Run 剩余={}",
                    runId, allowed, drafts.size(), perTurnNewNodeLimit, perRunRoom);
        }
    }

    private void advanceFinalAnswer(AgentRun run,
                                    LangchainTodoPlan plan,
                                    int generation,
                                    List<LangchainCompletedTodo> completed,
                                    Map<String, NodeWorkItem> latestSegments,
                                    CoordinationTurn turn) {
        NodeWorkItemIdentity identity = identity(run.getId(), generation, FINAL_ANSWER_NODE_ID);
        NodeWorkItem finalItem = latestSegments.get(FINAL_ANSWER_NODE_ID);
        if (finalItem == null) {
            if (newNodesAllowed(run.getId(), turn) == 0) {
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
            createAndHint(identity, completed.size(), value(run.getRunControlVersion()), payload,
                    run.getSchedulerVersion(), turn);
            return;
        }
        if (!finalItem.terminal()) {
            if (offerIfRunnable(finalItem)) {
                turn.served();
            }
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
        // 这一回合归属的轮次在回合开始时取一次：领取成功时记的是「这一回合被服务」，
        // 写到一半再去读全局轮次会把拖久了的回合说成更晚才被服务。
        long dispatchTurnRound = stateStore.currentRound(SchedulerRoundScope.NODE_DISPATCH);
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
        // 领取成功才算这张图真的拿到了节点执行机会：写失败只记日志，不影响这次执行。
        try {
            coordinationStore.markDispatchServed(identity.runId(), dispatchTurnRound,
                    run.getPlanGeneration());
        } catch (RuntimeException e) {
            log.warn("记录节点派发轮转位置失败: runId={} reason={}",
                    identity.runId(), safeReason(e));
        }
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

    /**
     * 一轮 Run 协调的补扫：按轮转位置取这一轮该被协调的 Run。
     *
     * <p>取法有两路，缺一不可。一路读库里的协调资格（按最近被服务的轮次升序、跳过还没到下次可见时间的），
     * 这样进程重启后或者内存提示丢光时，图仍然会被重新发现；另一路是本进程记住的 Run，用于刚受理、
     * 还没写进轮转表的那些。两路都只出还活着的双池 Run，按第一次出现的顺序去重。</p>
     *
     * <p>每一轮开始先推进全局轮次号，再把上一轮没被服务到的记录的「连续错过轮数」刷新一遍：
     * 这两个数是公平性的观测事实，只在每轮边界上更新。</p>
     */
    @Override
    public List<RunCoordinationHint> scanRunnableRuns(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        refreshRoundCounters();
        long round = stateStore.currentRound(SchedulerRoundScope.RUN_COORDINATION);
        LinkedHashMap<String, AgentRun> candidates = new LinkedHashMap<>();
        long legacyThisRound = 0L;
        long leaseMissThisRound = 0L;
        long isolatedThisRound = 0L;
        // 一次全局扫描：三个调度器版本排在同一份候选顺序里，选出来之后按每一行冻结的版本路由。
        // 候选是共享的，谁能被接手不共享：能不能动这条 Run，由它自己的服务所有权说了算。
        for (RunCoordination due : coordinationStore.scanDue(limit)) {
            AgentRun run = runMapper.findById(due.getRunId());
            if (run == null) {
                // 资格行指向一条读不回来的 Run：不动它的状态，也不接手。
                isolatedThisRound++;
                routingIsolated.incrementAndGet();
                if (routingIsolated.get() == 1L || routingIsolated.get() % 1000L == 0L) {
                    log.error("协调候选指向一条读不回来的 Run，保持失败关闭: runId={} 累计{}条",
                            due.getRunId(), routingIsolated.get());
                }
                continue;
            }
            SchedulerVersion version;
            try {
                version = SchedulerVersion.fromWire(run.getSchedulerVersion());
            } catch (RuntimeException unknownVersion) {
                // 冻结版本读不出来：这条 Run 归哪套执行层都不知道，谁都不许动它的状态。
                isolatedThisRound++;
                long isolated = routingIsolated.incrementAndGet();
                if (isolated == 1L || isolated % 1000L == 0L) {
                    log.error("协调候选里的调度器版本读不出来，保持失败关闭: runId={} version={} 累计{}条",
                            due.getRunId(), run.getSchedulerVersion(), isolated);
                }
                continue;
            }
            if (!Objects.equals(due.getSchedulerVersion(), run.getSchedulerVersion())) {
                // 资格行上的版本只是一份镜像，算数的是 Run 主表上冻结的那一个：两边不一致说明
                // 有人绕开写入路径改过，这里按主表走并留一条记录，不按镜像路由。
                log.warn("资格行上的调度器版本与 Run 主表不一致，按主表路由: runId={} coordination={} run={}",
                        due.getRunId(), due.getSchedulerVersion(), run.getSchedulerVersion());
            }
            if (version == SchedulerVersion.LEGACY) {
                legacyThisRound++;
                handOffLegacyRun(run, due, round);
                continue;
            }
            if (!version.isDualPoolFamily()) {
                isolatedThisRound++;
                long isolated = routingIsolated.incrementAndGet();
                if (isolated == 1L || isolated % 1000L == 0L) {
                    log.error("协调候选里的版本不属于任何已知执行层，保持失败关闭: runId={} version={} 累计{}条",
                            due.getRunId(), run.getSchedulerVersion(), isolated);
                }
                continue;
            }
            if (!holdsServiceOwnership(run.getId())) {
                // 别的进程正活着持有这条 Run：这一轮不碰它，也不改它的轮次位置。
                leaseMissThisRound++;
                leaseNotAcquired.incrementAndGet();
                continue;
            }
            candidates.put(run.getId(), run);
        }
        legacyCandidatesLastRound = legacyThisRound;
        leaseNotAcquiredLastRound = leaseMissThisRound;
        routingIsolatedLastRound = isolatedThisRound;

        for (String locallyAdmitted : admissionRegistry.snapshotRunIds()) {
            candidates.computeIfAbsent(locallyAdmitted, runMapper::findById);
        }

        List<RunCoordinationHint> hints = new ArrayList<>();
        for (Map.Entry<String, AgentRun> entry : candidates.entrySet()) {
            if (hints.size() >= limit) {
                break;
            }
            String runId = entry.getKey();
            AgentRun run = entry.getValue();
            if (run == null || !schedulerVersionPolicy.isDualPoolFamily(run)) {
                releaseRun(runId);
                continue;
            }
            if (!admissionRegistry.isAdmitted(runId)
                    && !admissionRegistry.restorePersistedToolJob(runId)) {
                // 库里有资格记录但这个进程没有受理它，而且持久事实也不足以证明可以恢复：
                // 不在这里凭一次扫描就执行。
                continue;
            }
            hints.add(new RunCoordinationHint(runId, RunCoordinationHint.Reason.SCAN_REDISCOVERED));
        }
        return List.copyOf(hints);
    }

    /**
     * 旧版本 Run 的处理：拿到服务所有权之后才交给旧入口，拿不到就按所有权原因推后。
     *
     * <p>三道都要过。第一道是所有权：候选是三个版本共用的，但「能不能动这条 Run」由它自己的
     * 所有权决定——没有可跨进程核对、会过期、能原子转交的持久事实，凭一条候选就接手，会在滚动
     * 部署新旧并存的窗口里对着旧进程正在跑的图再启动一次。第二道是旧入口在不在（它随配置开关）。
     * 第三道是旧入口自己的领取条件（领取语句会核对状态与重试次数）。</p>
     *
     * <p>没接手的要推后：不推后它会一直停在候选页首，页数一满，排在它后面的双池 Run 永远看不见。</p>
     */
    private void handOffLegacyRun(AgentRun run, RunCoordination due, long round) {
        String runId = run.getId();
        if (!holdsServiceOwnership(runId)) {
            deferLegacyRun(due, "所有权不在本进程");
            return;
        }
        LegacyRunHandoff handoff = legacyHandoff.getIfAvailable();
        if (handoff == null) {
            deferLegacyRun(due, "旧入口没有启用");
            return;
        }
        if (!handoff.handOff(runId)) {
            deferLegacyRun(due, "旧入口没有接手");
            return;
        }
        legacyHandedOff.incrementAndGet();
        // 交出去了：记下这一轮服务过它，它从此排在别的候选后面。
        boolean marked = coordinationStore.markCoordinationServed(runId, round, planGeneration(due));
        if (!marked) {
            log.info("旧版本 Run 已经交给旧入口，但轮次位置没有写进去（可能刚换了计划代际）: runId={}", runId);
        }
    }

    /** 按所有权原因推后一条旧版本候选，让它从页首让开。 */
    private void deferLegacyRun(RunCoordination due, String why) {
        legacyDeferred.incrementAndGet();
        boolean deferred = coordinationStore.deferFor(due.getRunId(),
                RunCoordinationDeferReason.SERVICE_OWNERSHIP_ELSEWHERE,
                OffsetDateTime.now().plus(coordinationDeferRetry),
                planGeneration(due), servedRound(due));
        if (!deferred) {
            log.debug("旧版本 Run 的推后没有生效（轮次或代际已经变了）: runId={}", due.getRunId());
        }
        if (legacyDeferred.get() == 1L || legacyDeferred.get() % 1000L == 0L) {
            log.info("旧版本 Run 这一轮不接手（{}），按所有权原因推后: runId={} 累计{}次",
                    why, due.getRunId(), legacyDeferred.get());
        }
    }

    /**
     * 本进程现在是不是这条 Run 的服务所有者。
     *
     * <p>先读一次：已经是自己的、还没过期就直接算持有，免得每一轮扫描都为同一批 Run 写一遍。
     * 需要的时候才去领——没建立过、或者上一代已经过期，两种都靠领取语句按条件决定。</p>
     */
    private boolean holdsServiceOwnership(String runId) {
        String owner = processIdentity.value();
        RunServiceLease current = leaseStore.find(runId).orElse(null);
        if (current != null && owner.equals(current.ownerInstanceId())
                && !current.expiredAt(OffsetDateTime.now())) {
            return true;
        }
        return leaseStore.acquire(runId, owner, serviceLeaseTtl).isPresent();
    }

    private static int planGeneration(RunCoordination due) {
        return due.getPlanGeneration() == null ? -1 : due.getPlanGeneration();
    }

    private static long servedRound(RunCoordination due) {
        return due.getCoordinationServedRound() == null ? 0L : due.getCoordinationServedRound();
    }

    /** 每轮边界：把上一轮结束时该记的轮数刷新掉，再进到这一轮。刷新针对同一份全局候选集合。 */
    private void refreshRoundCounters() {
        long round = stateStore.currentRound(SchedulerRoundScope.RUN_COORDINATION);
        coordinationStore.refreshCoordinationMissedRounds(round);
        stateStore.advanceRound(SchedulerRoundScope.RUN_COORDINATION);
    }

    /**
     * 节点派发那一组轮次的轮边界：刷新上一轮的「连续未获派发轮数」，再进到这一轮。
     *
     * <p>它与 Run 协调各用一组计数器：两类轮转互不覆盖，「上一轮谁先拿到节点机会」才算得出来。</p>
     */
    private void refreshDispatchRound() {
        long round = stateStore.currentRound(SchedulerRoundScope.NODE_DISPATCH);
        coordinationStore.refreshDispatchMissedRounds(round);
        stateStore.advanceRound(SchedulerRoundScope.NODE_DISPATCH);
    }

    @Override
    public List<NodeWorkItemIdentity> scanRunnableNodes(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        refreshDispatchRound();
        // 新旧版本共用同一个节点池：一次全局扫描取回所有双池版本的到期分段，顺序按这张图最近
        // 被派发的轮次排。按版本各扫一次会让每种版本各占一份名额，轮转就散了。
        return workItemStore.scanClaimableAcrossDualPool(limit).stream()
                .map(NodeWorkItem::identity)
                .filter(identity -> admissionRegistry.isAdmitted(identity.runId()))
                .toList();
    }

    /**
     * 写一条节点工作项并给出第一次派发提示。
     *
     * <p>调度器版本跟着它所属的 Run 走：同一个 Run 建出来的行必须是同一个版本，否则新版本的 Run
     * 会被旧版本的扫描或恢复路径处理。写库成功才算推进，写失败当场抛错——同一身份重复创建说明
     * 上游把同一张图推导了两次，掩盖它只会让后面更难查。</p>
     */
    private void createAndHint(NodeWorkItemIdentity identity,
                               long contextVersion,
                               long runControlVersion,
                               Map<String, Object> payload,
                               String schedulerVersion,
                               CoordinationTurn turn) {
        if (workItemStore.countUnfinishedByRun(identity.runId()) >= perRunUnfinishedLimit) {
            turn.defer(RunCoordinationDeferReason.PER_RUN_UNFINISHED_LIMIT);
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
        item.setSchedulerVersion(schedulerVersion);
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
        turn.served();
    }

    /**
     * 内存提示队列满时把这条工作项的下次可见时间推后。
     *
     * <p>「没送出去」正常会在提示队列满的那一瞬间发生，也可能因为这条工作项已经被领走、已经进终态而在
     * 这里落空——后一种不是错，直接按已不需要提醒处理。原因字段会在真正领取时被清掉（领取语句自己清），
     * 所以这里不需要再写一次「派发成功」留痕。</p>
     */
    @Override
    public void deferHintDelivery(NodeWorkItemIdentity identity) {
        if (identity == null) {
            return;
        }
        OffsetDateTime nextVisibleAt = OffsetDateTime.now().plus(hintQueueFullRetry);
        NodeWorkItemMutationResult deferred = workItemStore.deferDispatch(
                identity, NodeDispatchDeferReason.HINT_QUEUE_FULL, nextVisibleAt);
        if (!deferred.applied()) {
            log.debug("提示未送出但这条工作项已不需要提醒: identity={}", identity.describe());
        }
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

    /**
     * 一轮 Run 协调的记账：这一轮到底推进了这个 Run 没有，没推进是卡在哪一条上。
     *
     * <p>延期原因只记第一道挡住的限制：后面的检查是在「前面已经通过」的前提下做的，把最后一个
     * 原因写上去会让「这张图被什么挡住」换个人看就换一个答案。已经推进过的一轮不记延期——
     * 那个字段的含义是「这一轮没能推进」，推进并同时受限是两件事，受限的账记在日志里。</p>
     */
    private static final class CoordinationTurn {

        private final String runId;
        /** 这一回合开始时读到的计划代际：延期与成功写都要带它做条件，旧的观察不许覆盖新事实。 */
        private final int planGeneration;
        /** 这一轮开始时读到的协调轮次：同样作为延期的条件，图已经被服务过时这次延期不写。 */
        private final long coordinationServedRound;
        /**
         * 这一回合归属的轮次，在回合开始时取一次。
         *
         * <p>成功写记的是「这一回合被服务」，所以记的是这个值。写到一半再去读全局轮次的话，
         * 一个拖了很久的回合会拿到一个更大的轮次号，等于把旧回合说成新回合。</p>
         */
        private final long turnRound;
        private RunCoordinationDeferReason deferReason;
        private boolean progressed;

        private CoordinationTurn(AgentRun run, RunCoordination coordination, long turnRound) {
            this.runId = run.getId();
            this.planGeneration = run.getPlanGeneration() == null ? -1 : run.getPlanGeneration();
            this.coordinationServedRound = value(coordination.getCoordinationServedRound());
            this.turnRound = turnRound;
        }

        private void served() {
            progressed = true;
        }

        private void defer(RunCoordinationDeferReason reason) {
            if (!progressed && deferReason == null) {
                deferReason = reason;
            }
        }
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

    /**
     * 路由的读数：交出去的旧版本、没接手而推后的、因为所有权在别人手上而跳过的、版本读不出来隔离的。
     *
     * <p>累计值是「反复扫描同一行的命中次数」，不是当前候选数——两个含义分开报，免得把
     * 「同一行被扫了很多轮」看成「候选里堆了很多条」。当前条数单独一组。</p>
     */
    @Override
    public Map<String, Object> routingSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("legacyHandedOffTotal", legacyHandedOff.get());
        snapshot.put("legacyDeferredTotal", legacyDeferred.get());
        snapshot.put("leaseNotAcquiredTotal", leaseNotAcquired.get());
        snapshot.put("routingIsolatedTotal", routingIsolated.get());
        snapshot.put("legacyCandidatesLastRound", legacyCandidatesLastRound);
        snapshot.put("leaseNotAcquiredLastRound", leaseNotAcquiredLastRound);
        snapshot.put("routingIsolatedLastRound", routingIsolatedLastRound);
        return snapshot;
    }
}
