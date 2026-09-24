package world.willfrog.agentlangchain.control.dualpool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.capacity.HintQueueDepthSource;
import world.willfrog.agent.platform.capacity.SchedulerPermitLayer;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 双 Worker 池的进程内分发骨架。
 *
 * <p>两个内存队列都只是降低延迟的提示：写库成功后可以调用 offer，队列满时允许丢弃，
 * 周期扫描会从数据库重新发现工作。队列中的元素既不表示任务存在，也不表示当前进程拥有
 * 执行权；真正领取必须由 {@link DualPoolWorkHandler} 的数据库条件更新完成。</p>
 */
@Component
@Slf4j
public class DualPoolDispatcher implements HintQueueDepthSource {

    private final ThreadPoolTaskExecutor runExecutor;
    private final ThreadPoolTaskExecutor nodeExecutor;
    private final ObjectProvider<DualPoolWorkHandler> handlerProvider;
    private final ObjectProvider<RunServiceLeaseKeeper> leaseKeeperProvider;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final SchedulerPermitLedger permitLedger;
    private final TreeFairHintQueue<RunCoordinationHint> runHints;
    private final TreeFairHintQueue<NodeWorkItemIdentity> nodeHints;
    /** 已经在队列里的提示身份：同一个 Run 的协调提示、同一条工作项的节点提示都只留一份。 */
    private final Set<String> pendingRunHints = ConcurrentHashMap.newKeySet();
    private final Set<NodeWorkItemIdentity> pendingNodeHints = ConcurrentHashMap.newKeySet();
    /** 队列满时没能入队的节点提示：等下一个节拍把下次检查时间落到数据库上。 */
    private final Queue<NodeWorkItemIdentity> undeliveredNodeHints = new ConcurrentLinkedQueue<>();
    private final int scanBatchSize;
    private final int runSubmitBudget;
    private final int nodeSubmitBudget;
    private final AtomicInteger runInFlight = new AtomicInteger();
    private final AtomicInteger nodeInFlight = new AtomicInteger();
    private final AtomicLong runHintDropped = new AtomicLong();
    private final AtomicLong nodeHintDropped = new AtomicLong();
    private final AtomicLong runHintDeduped = new AtomicLong();
    private final AtomicLong nodeHintDeduped = new AtomicLong();
    private final AtomicLong undeliveredHintDeferred = new AtomicLong();
    private final AtomicLong runSubmitRejected = new AtomicLong();
    private final AtomicLong nodeSubmitRejected = new AtomicLong();

    public DualPoolDispatcher(
            @Qualifier("agentLangchainRunCoordinationTaskExecutor") ThreadPoolTaskExecutor runExecutor,
            @Qualifier("agentLangchainNodeTaskExecutor") ThreadPoolTaskExecutor nodeExecutor,
            ObjectProvider<DualPoolWorkHandler> handlerProvider,
            ObjectProvider<RunServiceLeaseKeeper> leaseKeeperProvider,
            DualPoolRunAdmissionRegistry admissionRegistry,
            SchedulerPermitLedger permitLedger,
            @Value("${agent.langchain.dual-pool.run-worker.hint-capacity:256}") int runHintCapacity,
            @Value("${agent.langchain.dual-pool.node-worker.hint-capacity:1024}") int nodeHintCapacity,
            @Value("${agent.langchain.dual-pool.scan.batch-size:100}") int scanBatchSize,
            @Value("${agent.langchain.dual-pool.run-worker.submit-budget:32}") int runSubmitBudget,
            @Value("${agent.langchain.dual-pool.node-worker.submit-budget:64}") int nodeSubmitBudget,
            @Value("${agent.langchain.dual-pool.business-admission-limit:100}") int businessAdmissionLimit,
            @Value("${agent.langchain.dual-pool.run-worker.permit-limit:2}") int runPermitLimit,
            @Value("${agent.langchain.dual-pool.node-worker.permit-limit:4}") int nodePermitLimit,
            @Value("${agent.langchain.dual-pool.scan.interval-ms:1000}") long scanIntervalMs,
            @Value("${agent.langchain.dual-pool.hint-drain-interval-ms:50}") long hintDrainIntervalMs,
            FrozenEffectiveSettings frozenEffectiveSettings) {
        this.runExecutor = runExecutor;
        this.nodeExecutor = nodeExecutor;
        this.handlerProvider = handlerProvider;
        this.leaseKeeperProvider = leaseKeeperProvider;
        this.admissionRegistry = admissionRegistry;
        this.permitLedger = permitLedger;
        this.runHints = new TreeFairHintQueue<>(runHintCapacity);
        this.nodeHints = new TreeFairHintQueue<>(nodeHintCapacity);
        this.scanBatchSize = Math.max(1, scanBatchSize);
        this.runSubmitBudget = Math.max(1, runSubmitBudget);
        this.nodeSubmitBudget = Math.max(1, nodeSubmitBudget);
        this.permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION,
                Math.max(1, businessAdmissionLimit));
        this.permitLedger.setLimit(SchedulerPermitLayer.RUN_COORDINATION_TURN,
                Math.max(1, runPermitLimit));
        this.permitLedger.setLimit(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT,
                Math.max(1, nodePermitLimit));
        // 这一层登记的是构造完之后真正在用的值：容量做过「至少 1」的归一化，许可上限从台账里读回来，
        // 两个定时扫描的间隔与 @Scheduled 上那个属性名在启动时解析出来的是同一个数。
        String component = "DualPoolDispatcher";
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_RUN_WORKER_HINT_CAPACITY,
                component, this.runHints.remainingCapacity());
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_NODE_WORKER_HINT_CAPACITY,
                component, this.nodeHints.remainingCapacity());
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SCAN_BATCH_SIZE,
                component, this.scanBatchSize);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_RUN_WORKER_SUBMIT_BUDGET,
                component, this.runSubmitBudget);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_NODE_WORKER_SUBMIT_BUDGET,
                component, this.nodeSubmitBudget);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_BUSINESS_ADMISSION_LIMIT,
                component, this.permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).limit());
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_RUN_WORKER_PERMIT_LIMIT,
                component, this.permitLedger.usage(SchedulerPermitLayer.RUN_COORDINATION_TURN).limit());
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_NODE_WORKER_PERMIT_LIMIT,
                component, this.permitLedger.usage(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT).limit());
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SCAN_INTERVAL_MS,
                component, Math.max(1L, scanIntervalMs));
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_HINT_DRAIN_INTERVAL_MS,
                component, Math.max(1L, hintDrainIntervalMs));
    }

    /**
     * 非阻塞写入 Run 提示；false 只表示本地提示没进队列，数据库事实仍保留。
     *
     * <p>同一张图在队列里只留一份提示：重复提示不带来新信息，却会把别的图挤到队尾。</p>
     */
    public boolean offerRun(RunCoordinationHint hint) {
        if (hint == null || !admissionRegistry.isAdmitted(hint.runId())) {
            return false;
        }
        if (!pendingRunHints.add(hint.runId())) {
            runHintDeduped.incrementAndGet();
            return false;
        }
        String rootRunId;
        try {
            rootRunId = admissionRegistry.rootRunIdForAdmitted(hint.runId());
        } catch (IllegalStateException missingRoot) {
            pendingRunHints.remove(hint.runId());
            log.error("协调提示缺少可验证的根树身份，等待数据库扫描: runId={}", hint.runId(), missingRoot);
            return false;
        }
        if (!runHints.offer(rootRunId, hint.runId(), hint)) {
            pendingRunHints.remove(hint.runId());
            runHintDropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 非阻塞写入节点提示；false 只表示本地提示没进队列，数据库事实仍保留。
     *
     * <p>同一条工作项只留一份提示，队列满时把身份记下来，交给下一个节拍写延期原因与下次检查时间：
     * 内存提示可以丢，但「这条工作项为什么还没被派发」必须留在数据库上。</p>
     */
    public boolean offerNode(NodeWorkItemIdentity identity) {
        if (identity == null || !admissionRegistry.isAdmitted(identity.runId())) {
            return false;
        }
        if (!pendingNodeHints.add(identity)) {
            nodeHintDeduped.incrementAndGet();
            return false;
        }
        String rootRunId;
        try {
            rootRunId = admissionRegistry.rootRunIdForAdmitted(identity.runId());
        } catch (IllegalStateException missingRoot) {
            pendingNodeHints.remove(identity);
            log.error("节点提示缺少可验证的根树身份，等待数据库扫描: identity={}",
                    identity.describe(), missingRoot);
            return false;
        }
        if (!nodeHints.offer(rootRunId, identity.runId(), identity)) {
            pendingNodeHints.remove(identity);
            nodeHintDropped.incrementAndGet();
            undeliveredNodeHints.offer(identity);
            return false;
        }
        return true;
    }

    public boolean isReady() {
        return handlerProvider.getIfAvailable() != null;
    }

    /** 每轮只搬运有限数量的提示，避免调度线程在大队列下长时间独占。 */
    @Scheduled(fixedDelayString = "${agent.langchain.dual-pool.hint-drain-interval-ms:50}")
    public void drainHints() {
        DualPoolWorkHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            return;
        }
        deferUndeliveredNodeHints(handler);
        drainRunHints(handler);
        drainNodeHints(handler);
    }

    /**
     * 数据库扫描是提示丢失后的恢复来源。扫描结果仍需进入相同提示队列，不能绕过领取状态机。
     */
    @Scheduled(fixedDelayString = "${agent.langchain.dual-pool.scan.interval-ms:1000}")
    public void rediscoverFromDatabase() {
        DualPoolWorkHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            return;
        }
        safeRunHints(handler.scanRunnableRuns(scanBatchSize)).stream()
                .filter(hint -> admissionRegistry.isAdmitted(hint.runId()))
                .forEach(this::offerRun);
        safeNodeHints(handler.scanRunnableNodes(scanBatchSize)).stream()
                .filter(identity -> admissionRegistry.isAdmitted(identity.runId()))
                .forEach(this::offerNode);
    }

    private void drainRunHints(DualPoolWorkHandler handler) {
        for (int i = 0; i < runSubmitBudget; i++) {
            if (!permitLedger.tryAcquire(SchedulerPermitLayer.RUN_COORDINATION_TURN)) {
                return;
            }
            RunCoordinationHint hint = runHints.poll();
            if (hint == null) {
                permitLedger.release(SchedulerPermitLayer.RUN_COORDINATION_TURN);
                return;
            }
            pendingRunHints.remove(hint.runId());
            runInFlight.incrementAndGet();
            try {
                runExecutor.execute(() -> {
                    try {
                        handler.coordinateRun(hint);
                    } catch (RuntimeException e) {
                        log.error("Run 协调回合失败: runId={} reason={}", hint.runId(), hint.reason(), e);
                    } finally {
                        runInFlight.decrementAndGet();
                        permitLedger.release(SchedulerPermitLayer.RUN_COORDINATION_TURN);
                    }
                });
            } catch (RejectedExecutionException e) {
                runInFlight.decrementAndGet();
                permitLedger.release(SchedulerPermitLayer.RUN_COORDINATION_TURN);
                runSubmitRejected.incrementAndGet();
                // 物理线程暂时满时把提示放回队尾；放回也失败则等待数据库扫描重新发现。
                offerRun(hint);
                return;
            }
        }
    }

    private void drainNodeHints(DualPoolWorkHandler handler) {
        for (int i = 0; i < nodeSubmitBudget; i++) {
            if (!permitLedger.tryAcquire(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT)) {
                return;
            }
            NodeWorkItemIdentity identity = nodeHints.poll();
            if (identity == null) {
                permitLedger.release(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT);
                return;
            }
            pendingNodeHints.remove(identity);
            nodeInFlight.incrementAndGet();
            try {
                nodeExecutor.execute(() -> {
                    try {
                        handler.executeNode(identity);
                    } catch (RuntimeException e) {
                        log.error("节点工作项执行失败: identity={}", identity.describe(), e);
                    } finally {
                        nodeInFlight.decrementAndGet();
                        permitLedger.release(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT);
                    }
                });
            } catch (RejectedExecutionException e) {
                nodeInFlight.decrementAndGet();
                permitLedger.release(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT);
                nodeSubmitRejected.incrementAndGet();
                offerNode(identity);
                return;
            }
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runHintQueueDepth", runHints.size());
        snapshot.put("nodeHintQueueDepth", nodeHints.size());
        snapshot.put("nodeHintRunBuckets", nodeHints.runBucketCount());
        snapshot.put("runInFlight", runInFlight.get());
        snapshot.put("nodeInFlight", nodeInFlight.get());
        snapshot.put("runHintDroppedTotal", runHintDropped.get());
        snapshot.put("nodeHintDroppedTotal", nodeHintDropped.get());
        snapshot.put("runHintDedupedTotal", runHintDeduped.get());
        snapshot.put("nodeHintDedupedTotal", nodeHintDeduped.get());
        snapshot.put("undeliveredHintDeferredTotal", undeliveredHintDeferred.get());
        snapshot.put("runSubmitRejectedTotal", runSubmitRejected.get());
        snapshot.put("nodeSubmitRejectedTotal", nodeSubmitRejected.get());
        snapshot.put("admittedRuns", admissionRegistry.snapshotRunIds().size());
        snapshot.put("admittedRootTrees", admissionRegistry.admittedCount());
        snapshot.put("rootCapacityRecoveryBlocked", admissionRegistry.rootCapacityRecoveryBlocked());
        snapshot.put("rootCapacityRecoveryReason", admissionRegistry.rootCapacityRecoveryReason());
        snapshot.put("startupResidueBlocked", admissionRegistry.startupResidueBlocked());
        // 启动残留按版本各报一条：哪个版本被阻断、为什么、隔离了哪些 Run，另一版还能不能服务。
        snapshot.put("startup", admissionRegistry.startupSnapshot());
        snapshot.put("permits", permitLedger.snapshot());
        snapshot.put("workHandlerReady", isReady());
        DualPoolWorkHandler handler = handlerProvider.getIfAvailable();
        if (handler != null) {
            snapshot.putAll(handler.routingSnapshot());
        }
        // 服务所有权续期的读数也放在这一份里：所有权是「谁该动这条 Run」的账，
        // 看容量与候选的地方就应该能看到它续上没有、有没有丢。
        RunServiceLeaseKeeper leaseKeeper = leaseKeeperProvider.getIfAvailable();
        if (leaseKeeper != null) {
            snapshot.putAll(leaseKeeper.snapshot());
        }
        return snapshot;
    }

    @Override
    public int hintQueueDepth() {
        return runHints.size() + nodeHints.size();
    }

    @Override
    public int reservedNotEnqueued() {
        // 本实现先写数据库再非阻塞 offer，不存在跨调用保留的队列位置。
        return 0;
    }

    private List<RunCoordinationHint> safeRunHints(List<RunCoordinationHint> hints) {
        return hints == null ? List.of() : new ArrayList<>(hints);
    }

    private List<NodeWorkItemIdentity> safeNodeHints(List<NodeWorkItemIdentity> hints) {
        return hints == null ? List.of() : new ArrayList<>(hints);
    }

    /** 队列满时丢掉的提示：把下次检查时间推到数据库上，交给周期补扫，而不是等下一次提示。 */
    private void deferUndeliveredNodeHints(DualPoolWorkHandler handler) {
        for (int i = 0; i < UNDELIVERED_HINT_BATCH; i++) {
            NodeWorkItemIdentity identity = undeliveredNodeHints.poll();
            if (identity == null) {
                return;
            }
            try {
                handler.deferHintDelivery(identity);
                undeliveredHintDeferred.incrementAndGet();
            } catch (RuntimeException e) {
                log.error("写入派发延期失败: identity={}", identity.describe(), e);
            }
        }
    }

    /** 一批最多补写多少条延期：不在这里长跑，剩下的留给下一个节拍。 */
    private static final int UNDELIVERED_HINT_BATCH = 32;
}
