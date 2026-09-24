package world.willfrog.agentlangchain.control.dualpool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.capacity.SchedulerPermitLayer;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemMutationResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemState;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.platform.workitem.ServiceOwnershipFence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 当前进程明确接纳的双池 Run 集合。
 *
 * <p>普通遗留工作项仍然失败关闭；只有能从持久事实里证明「这条 Run 本该由本进程接着跑」的记录会在
 * 启动时重新取得业务许可。两个版本的证明各有一套：旧骨架（DUAL_POOL_V1）认 Run 级长工具锚点，
 * 完整 DAG（DUAL_POOL_V2）认分段行本身——它没有 Run 级锚点，悬挂事实散在分段、等待组与恢复通知里。
 * 两个版本的阻断标志各记各的：一个版本的来历不明不该把另一个版本一起停掉。</p>
 */
@Component
@Slf4j
public class DualPoolRunAdmissionRegistry {

    /** 一次具体准入预留取得的令牌；数据库受理成功后才把它激活为新的生命周期。 */
    public record Admission(boolean admitted, long epoch) {
        private static Admission rejected() {
            return new Admission(false, -1L);
        }
    }

    /**
     * 一条 Run 在本进程里的全部状态：服务所有权凭据、准入生命周期令牌、还没落库的预留。
     *
     * <p>三样放在同一个对象里，是因为它们说的是同一件事——本进程此刻能不能推进这条 Run。分成两份映射
     * 存的时候，凭据与准入各自能单独变化，就出现了「凭据还在、准入没了」和「准入还在、凭据被删了」
     * 这类对不上的状态；合成一个对象之后，撤销、释放、换新一代都是同一个
     * {@link ConcurrentHashMap#compute} 临界区里的一次条件更新，读到的是同一代的三样东西。</p>
     *
     * <p>{@code activeEpoch} 是已经落库一轮的令牌，{@code reservations} 是数据库条件更新还没回来的预留。
     * 两者只要有一个在，这条 Run 就是根树准入成员；整棵树只占一份业务名额。
     * {@code fence} 为空的窗口只有一个：受控演练先清掉凭据、还没重新取得的那些时刻。</p>
     */
    private record RunState(ServiceOwnershipFence fence, long activeEpoch, Set<Long> reservations,
                            String rootRunId) {
        private RunState {
            reservations = Set.copyOf(reservations);
        }

        private boolean active() {
            return activeEpoch >= 0L;
        }

        /** 这条 Run 是否仍是根树准入成员：有落库的一轮，或者有还没回音的预留。 */
        private boolean admitted() {
            return activeEpoch >= 0L || !reservations.isEmpty();
        }

        private RunState withFence(ServiceOwnershipFence bound) {
            return new RunState(bound, activeEpoch, reservations, rootRunId);
        }

        private RunState withEpoch(long epoch, Set<Long> left) {
            return new RunState(fence, epoch, left, rootRunId);
        }

        private RunState withRoot(String root) {
            return new RunState(fence, activeEpoch, reservations, root);
        }

        /**
         * 摘掉准入令牌与预留，只留所有权凭据；连凭据也没有时返回空——调用方都是把返回值直接交给
         * {@link ConcurrentHashMap#computeIfPresent}，返回空就是把这个状态整个摘掉。
         *
         * <p>留着凭据是有意的：正常终态与受控演练之后，同一条 Run 的追问还要在本进程里接着走，
         * 凭据在手就不必重新去数据库领一趟。</p>
         */
        private RunState withoutAdmission() {
            return fence == null ? null : new RunState(fence, -1L, Set.of(), rootRunId);
        }
    }

    private final Set<String> knownRunIds = ConcurrentHashMap.newKeySet();
    /** 启动时已用持久锚点和唯一工作项证明可恢复的 Run，供长工具恢复定向处理。 */
    private final Set<String> startupRecoveredRunIds = ConcurrentHashMap.newKeySet();
    /** 启动时已用分段行事实证明可恢复的完整 DAG Run。 */
    private final Set<String> startupRecoveredWaitGroupRunIds = ConcurrentHashMap.newKeySet();
    /**
     * 本进程此刻服务哪些 Run、每个各是哪一代：凭据、准入令牌、预留都在一个 {@link RunState} 里。
     *
     * <p>只有凭据在手才能推进这条 Run——协调、领取、Run 级写入都先看这里。租约本身在数据库里，
     * 这里记的是「本进程认为自己据有哪一代」，两者一旦不一致就以数据库为准（写入会因条件不匹配落空），
     * 所以从数据库读到新的凭据之后要把同一代写回这里（{@link #bindFence}）。</p>
     */
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    /** 同一根调用树只占一份业务许可；两张表只在 admissionLock 下读写。 */
    private final Object admissionLock = new Object();
    private final Set<String> permittedRoots = new LinkedHashSet<>();
    private final Map<String, Set<String>> admittedRunsByRoot = new HashMap<>();
    private volatile boolean rootCapacityRecoveryBlocked;
    private volatile String rootCapacityRecoveryReason = "none";
    private final AtomicLong admissionEpochSequence = new AtomicLong();
    /** 启动扫描判定「说不清该怎么恢复」的 Run 与原因；只在启动扫描里写，受理层对这些 Run 一律拒绝。 */
    private final Map<String, String> isolatedRunReasons = new ConcurrentHashMap<>();
    /** 每个版本的启动残留判定：是否阻断、为什么、隔离了哪些 Run。 */
    private final Map<SchedulerVersion, Boolean> residueBlockedByVersion = new ConcurrentHashMap<>();
    private final Map<SchedulerVersion, String> residueReasonByVersion = new ConcurrentHashMap<>();
    private final Map<SchedulerVersion, Map<String, String>> residueIsolatedByVersion = new ConcurrentHashMap<>();
    private final AtomicLong requeuedAbandonedClaims = new AtomicLong();
    private final AtomicLong requeueFailures = new AtomicLong();
    private final AtomicLong startupLeaseHeldElsewhere = new AtomicLong();
    private final AtomicLong startupAdmissionUnavailable = new AtomicLong();
    private final SchedulerPermitLedger permitLedger;
    private final NodeWorkItemStore workItemStore;
    private final AgentRunMapper runMapper;
    private final RunServiceLeaseStore leaseStore;
    private final ProcessInstanceIdentity instanceIdentity;
    private final RootRunResolver rootRunResolver;
    private final Duration serviceLeaseTtl;

    @Autowired
    public DualPoolRunAdmissionRegistry(SchedulerPermitLedger permitLedger,
                                        NodeWorkItemStore workItemStore,
                                        AgentRunMapper runMapper,
                                        RunServiceLeaseStore leaseStore,
                                        ProcessInstanceIdentity instanceIdentity,
                                        @Value("${agent.langchain.dual-pool.service-lease-ttl-seconds:120}")
                                        long serviceLeaseTtlSeconds,
                                        FrozenEffectiveSettings frozenEffectiveSettings,
                                        RootRunResolver rootRunResolver) {
        this.permitLedger = permitLedger;
        this.workItemStore = workItemStore;
        this.runMapper = runMapper;
        this.leaseStore = leaseStore;
        this.instanceIdentity = instanceIdentity;
        this.rootRunResolver = rootRunResolver;
        this.serviceLeaseTtl = Duration.ofSeconds(Math.max(1L, serviceLeaseTtlSeconds));
        // 同一个参数有四个消费者，各自归一化出来的数可能不同（这里按 1 秒起，领取那一路按 5 秒起）：
        // 各自登记在用的值，读数里按「谁在用 → 用多少」列出来，不替它们挑一个。
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_SERVICE_LEASE_TTL_SECONDS,
                "DualPoolRunAdmissionRegistry", this.serviceLeaseTtl.toSeconds());
    }

    /** 旧版单 Run 单元测试的兼容入口；Spring 只使用带持久解析器的构造器。 */
    DualPoolRunAdmissionRegistry(SchedulerPermitLedger permitLedger,
                                 NodeWorkItemStore workItemStore,
                                 AgentRunMapper runMapper,
                                 RunServiceLeaseStore leaseStore,
                                 ProcessInstanceIdentity instanceIdentity,
                                 long serviceLeaseTtlSeconds,
                                 FrozenEffectiveSettings frozenEffectiveSettings) {
        this(permitLedger, workItemStore, runMapper, leaseStore, instanceIdentity,
                serviceLeaseTtlSeconds, frozenEffectiveSettings, RootRunResolver.identityForTests());
    }

    /**
     * 两个双池版本的启动残留处理，一个版本一遍。
     *
     * <p>版本之间互不牵连：一个版本被阻断时另一个照常服务，读数里分别能看到各自的状态。</p>
     */
    @PostConstruct
    void detectStartupResidue() {
        restorePersistedRootPermits();
        for (SchedulerVersion version : List.of(SchedulerVersion.DUAL_POOL_V1, SchedulerVersion.DUAL_POOL_V2)) {
            detectResidueFor(version);
        }
    }

    /**
     * 一个版本的启动残留处理：把该版本未完成的工作项读出来，按 Run 分组，逐条 Run 判定。
     *
     * <p>扫描按「Run 的调度器版本」取行、行自己的版本不参与筛选：这一步读出来的就是这条 Run 的全部
     * 未完成分段，逐条比对版本才有意义。按行版本先筛再分组会漏掉与 Run 版本不一致的那些行，
     * 让「全部都属于这一版」这个判断永远为真。</p>
     *
     * <p>两种结局分得很清。能归到具体 Run、只是证明不了它该怎么恢复的，只隔离这一条 Run：不占业务
     * 名额、不投递任何提示，恢复受理对它一律拒绝，其余 Run 照常。连归到哪条 Run 都做不到的（Run
     * 读不回来、行上的版本与 Run 对不上、扫描没有把该版本的未完成行读全），这个版本失败关闭，
     * 本进程不再接受该版本的新建。</p>
     */
    private void detectResidueFor(SchedulerVersion version) {
        int residueCount = workItemStore.countUnfinishedByRunSchedulerVersion(version);
        if (residueCount == 0) {
            publishResidue(version, false, "no_residue", Map.of());
            return;
        }
        if (runMapper == null) {
            publishResidue(version, true, "run_mapper_unavailable", Map.of());
            return;
        }
        List<NodeWorkItem> scanned = workItemStore.listUnfinishedByRunSchedulerVersion(
                version, Math.max(1, residueCount));
        Map<String, List<NodeWorkItem>> itemsByRun = new LinkedHashMap<>();
        Set<NodeWorkItemIdentity> allIdentities = new LinkedHashSet<>();
        for (NodeWorkItem item : scanned) {
            if (item == null || item.getRunId() == null) {
                continue;
            }
            itemsByRun.computeIfAbsent(item.getRunId(), ignored -> new ArrayList<>()).add(item);
            allIdentities.add(item.identity());
        }
        boolean unattributable = allIdentities.size() != residueCount;
        if (unattributable) {
            log.error("启动扫描没有把这一版的未完成工作项读全，这一版保持失败关闭: version={} 读到={} 计数={}",
                    version, allIdentities.size(), residueCount);
        }
        Map<String, String> isolated = new LinkedHashMap<>();
        for (Map.Entry<String, List<NodeWorkItem>> entry : itemsByRun.entrySet()) {
            String runId = entry.getKey();
            List<NodeWorkItem> items = entry.getValue();
            Set<NodeWorkItemIdentity> identities = new LinkedHashSet<>();
            for (NodeWorkItem item : items) {
                identities.add(item.identity());
            }
            AgentRun run;
            try {
                run = runMapper.findById(runId);
                if (closeTerminalResidue(run, version, identities)) {
                    continue;
                }
                if (run == null || !version.name().equals(run.getSchedulerVersion())) {
                    // 这一行属于哪条 Run、哪一版都说不清：这一版失败关闭。
                    unattributable = true;
                    log.error("启动时无法归属这一行遗留工作项: version={} runId={} runSchedulerVersion={} "
                                    + "unfinishedItemCount={}",
                            version, runId, run == null ? "<missing>" : run.getSchedulerVersion(),
                            items.size());
                    continue;
                }
                if (version == SchedulerVersion.DUAL_POOL_V1) {
                    // 旧骨架的恢复事实只有 Run 级锚点一种。锚点说不通就没法证明这条 Run 该怎么接着跑，
                    // 维持这一版整体的失败关闭——它没有第二套可核对的持久事实，不能只隔离这一条。
                    if (!recoverAnchoredToolJobRun(run, identities)) {
                        unattributable = true;
                    }
                    continue;
                }
                String refusal = waitGroupRunRecoveryRefusal(run, items);
                if (refusal != null) {
                    isolate(runId, version, refusal, isolated);
                    continue;
                }
                String recoveryRefusal = recoverWaitGroupRun(run, items);
                if (recoveryRefusal != null) {
                    isolate(runId, version, recoveryRefusal, isolated);
                }
            } catch (RuntimeException e) {
                // 单条 Run 读不动、行上的取值认不出来，都不该让整个进程起不来：
                // 旧骨架维持失败关闭，完整 DAG 只隔离这一条。
                if (version == SchedulerVersion.DUAL_POOL_V1) {
                    unattributable = true;
                } else {
                    isolate(runId, version, "residue_check_failed:" + e.getClass().getSimpleName(), isolated);
                }
                log.error("启动残留处理在一条 Run 上出错: version={} runId={}", version, runId, e);
            }
        }
        publishResidue(version, unattributable,
                unattributable ? "unattributable_residue" : "residue_handled", isolated);
    }

    /**
     * 旧骨架那一版的恢复：只有「Run 级长工具锚点 + 唯一一条未完成工作项」能证明这条 Run 该被接着跑。
     *
     * <p>LINEAR 长工具挂起时只能有锚点指向的这一条未完成工作项。若同一 Run 还残留其他未完成行，
     * 就无法证明它们也属于这次恢复，整个 Run 都保持失败关闭。</p>
     *
     * @return true 表示这条 Run 已经重新取得业务许可；false 表示证明不了，调用方按失败关闭处理
     */
    private boolean recoverAnchoredToolJobRun(AgentRun run, Set<NodeWorkItemIdentity> identities) {
        String runId = run.getId();
        ToolJobAnchor anchor = parseRecoverableAnchor(run);
        if (anchor == null || identities.size() != 1
                || !identities.contains(anchorIdentity(runId, anchor))) {
            return false;
        }
        ServiceOwnershipFence before = currentOwnershipFence(runId).orElse(null);
        Optional<RunServiceLease> acquired = acquireOwnership(runId);
        if (acquired.isEmpty()) {
            // 所有权在别的进程手上：这条 Run 已经有人在服务，本进程不接手，也不算证明不了。
            startupLeaseHeldElsewhere.incrementAndGet();
            log.warn("这条 Run 的服务所有权在别人手上，不接手: runId={}", runId);
            return true;
        }
        knownRunIds.add(runId);
        if (!activateNewRun(runId)) {
            knownRunIds.remove(runId);
            releaseOwnershipIfFreshlyTaken(runId,
                    new ServiceOwnershipFence(acquired.get().ownerInstanceId(), acquired.get().fencingToken()),
                    before != null);
            log.error("恢复长工具 Run 时无法重新取得业务许可: runId={}", runId);
            return false;
        }
        startupRecoveredRunIds.add(runId);
        return true;
    }

    /**
     * 一条有遗留分段的完整 DAG Run 能不能恢复；返回空表示能，返回的字符串是「只隔离这一条 Run」的原因。
     *
     * <p>这一版没有 Run 级锚点，可恢复的事实就是这些分段行本身：Run 还在跑、每条未完成分段都属于这一版、
     * 计划代际与控制版本都还是 Run 当前的值（旧计划或旧控制版本的分段再也不会被领取）、身份字段齐全、
     * 同一个节点的同一次尝试上没有两条未完成分段。少任何一条都只说明这一条 Run 说不清，不说明整个版本
     * 有问题，所以只隔离它。</p>
     */
    private String waitGroupRunRecoveryRefusal(AgentRun run, List<NodeWorkItem> items) {
        if (run.getStatus() == AgentRunStatus.CANCELING) {
            // 正在取消的图不该被接回来跑：它的分段由取消那一套收口。
            return "run_canceling";
        }
        if (!recoverableStatus(run.getStatus())) {
            // 终态（含父 Run 已终结但收口没成功那一种）不许借恢复重新拿到租约与业务名额：
            // 它已经没有执行权，接回来只会在被拒之后污染许可与观测。
            return "run_status_not_recoverable";
        }
        if (run.getPlanGeneration() == null || run.getRunControlVersion() == null) {
            return "run_version_fields_missing";
        }
        Set<String> activeSegments = new LinkedHashSet<>();
        for (NodeWorkItem item : items) {
            if (!run.getSchedulerVersion().equals(item.getSchedulerVersion())) {
                return "item_scheduler_version_mismatch";
            }
            if (!run.getPlanGeneration().equals(item.getPlanGeneration())) {
                return "item_plan_generation_stale";
            }
            if (run.getRunControlVersion().longValue() != value(item.getRunControlVersion())) {
                return "item_run_control_version_stale";
            }
            if (item.getNodeId() == null || item.getNodeId().isBlank()
                    || item.getNodeAttempt() == null || item.getSegmentSequence() == null
                    || item.getClaimEpoch() == null) {
                return "item_identity_incomplete";
            }
            // 一次等待只把节点推到下一段：同一节点同一次尝试上同时有两条未完成分段时，
            // 哪一条算数说不清。设计上不该出现，出现就隔离。
            if (!activeSegments.add(item.getNodeId() + "@" + item.getNodeAttempt())) {
                return "same_node_conflicting_segments";
            }
        }
        return null;
    }

    /**
     * 接手一条能证明的完整 DAG Run：先拿服务所有权，再把死在领取态的分段放回可领取，最后占业务名额。
     *
     * <p>顺序不能反。拿不到服务所有权说明别的进程正活着服务这条 Run，这一轮什么都别做——它不是失败，
     * 也不是证明不了，只是不归我们动。没有所有权就去改分段行，等于对着别人正在跑的图动手。</p>
     *
     * <p>业务名额放在最后是因为它可能拿不到（别的 Run 正占满）：拿不到就把刚取得的租约还回去，
     * 让这条 Run 留在原地等下一轮扫描或者下一台机器接手。握着租约却不受理，会把这条 Run 从所有
     * 人手里锁住，那才是最糟的结局。</p>
     *
     * @return 空表示已经受理或者这一轮有意不动它；返回字符串表示分段没能全部放回可领取，调用方按
     *         「只隔离这一条」处理
     */
    private String recoverWaitGroupRun(AgentRun run, List<NodeWorkItem> items) {
        String runId = run.getId();
        if (leaseStore == null || instanceIdentity == null) {
            log.error("没有服务所有权存储，完整 DAG 的启动恢复做不了，这条 Run 先不接手: runId={}", runId);
            return "lease_store_unavailable";
        }
        boolean alreadyMine = leaseStore.find(runId)
                .map(existing -> instanceIdentity.value().equals(existing.ownerInstanceId()))
                .orElse(false);
        Optional<RunServiceLease> acquired = acquireOwnership(runId);
        if (acquired.isEmpty()) {
            startupLeaseHeldElsewhere.incrementAndGet();
            log.warn("这条 Run 的服务所有权在别人手上，本进程不接手: runId={}", runId);
            return null;
        }
        if (!requeueAbandonedClaims(run, items)) {
            releaseFreshlyTaken(runId, acquired.get(), alreadyMine);
            return "claim_requeue_left_claimed";
        }
        knownRunIds.add(runId);
        if (!admitExistingRun(runId)) {
            startupAdmissionUnavailable.incrementAndGet();
            releaseFreshlyTaken(runId, acquired.get(), alreadyMine);
            log.error("启动恢复没能给这条 Run 占到业务名额，留到下一轮再来: runId={}", runId);
            return null;
        }
        startupRecoveredWaitGroupRunIds.add(runId);
        log.warn("启动恢复接手了一条完整 DAG 的 Run: runId={} 未完成分段={} 服务所有权={}",
                runId, items.size(), acquired.get().describe());
        return null;
    }

    /**
     * 把死在领取态的分段放回可领取状态，并核对没有哪一条还留在领取态。
     *
     * <p>服务所有权就是「旧执行者已经不在了」的凭据；放回时代际加一，旧执行者万一还活着，
     * 提交结果时会因为代际对不上被拒。凭据本身也进语句：租约在两次操作之间到期并被别人接管时，
     * 这一写会因所有权条件不匹配影响 0 行，而不是替新主人改行。</p>
     *
     * @return true 表示这条 Run 已经没有留在领取态的分段
     */
    private boolean requeueAbandonedClaims(AgentRun run, List<NodeWorkItem> items) {
        SchedulerVersion version = SchedulerVersion.fromWire(run.getSchedulerVersion());
        ServiceOwnershipFence fence = currentOwnershipFence(run.getId()).orElse(null);
        if (fence == null) {
            // 没有凭据就不该走到这里：放回领取态是「我接手了」的动作，先有所有权再动手。
            log.error("没有服务所有权凭据，拒绝放回领取态的分段: runId={}", run.getId());
            return false;
        }
        boolean clean = true;
        for (NodeWorkItem item : items) {
            NodeWorkItemState state = item.stateEnum();
            if (state != NodeWorkItemState.CLAIMED && state != NodeWorkItemState.EXECUTING) {
                continue;
            }
            NodeWorkItemMutationResult result = workItemStore.requeueAbandonedClaim(
                    item.identity(), NodeWorkItemVersions.of(item), fence, version);
            if (result != null && result.applied()) {
                requeuedAbandonedClaims.incrementAndGet();
                continue;
            }
            // 放不回去未必是坏事（这一段可能刚好自己走掉了），所以回读一次再下结论：
            // 还停在领取态才算这条 Run 现在不能接手。
            NodeWorkItem current = workItemStore.findByIdentity(item.identity()).orElse(null);
            if (current != null && (current.stateEnum() == NodeWorkItemState.CLAIMED
                    || current.stateEnum() == NodeWorkItemState.EXECUTING)) {
                requeueFailures.incrementAndGet();
                clean = false;
                log.error("启动恢复没能把这一段放回可领取，这条 Run 先不接手: runId={} identity={} "
                                + "previousState={} rejection={}",
                        run.getId(), item.identity().describe(), state, rejectionDescription(result));
            }
        }
        return clean;
    }

    /** 刚取得、还没接手的租约要让出去：握着不服务的租约会被续期循环一直续住，别人接不了手。 */
    private void releaseFreshlyTaken(String runId, RunServiceLease lease, boolean alreadyMine) {
        if (lease == null) {
            return;
        }
        releaseOwnershipIfFreshlyTaken(runId,
                new ServiceOwnershipFence(lease.ownerInstanceId(), lease.fencingToken()), alreadyMine);
    }

    /**
     * 记一条被隔离的 Run：不占业务名额、不投递提示，恢复受理对它一律拒绝。
     *
     * <p>挡住的只是「借一次恢复把它接回执行链」这条口子。人工取消、追问这些明确的操作照旧能进——
     * 它们要的是把这条 Run 收掉或者换一轮，不是替它证明恢复事实。</p>
     */
    private void isolate(String runId, SchedulerVersion version, String reason, Map<String, String> isolated) {
        isolated.put(runId, reason);
        isolatedRunReasons.put(runId, reason);
        log.error("这条 Run 的恢复事实说不清，只隔离这一条: runId={} version={} reason={}", runId, version, reason);
    }

    /** 落一个版本的启动判定：是否阻断、为什么、隔离了哪些 Run、各自为什么。 */
    private void publishResidue(SchedulerVersion version, boolean blocked, String reason,
                                Map<String, String> isolated) {
        residueBlockedByVersion.put(version, blocked);
        residueReasonByVersion.put(version, reason);
        residueIsolatedByVersion.put(version, Map.copyOf(isolated));
        if (blocked) {
            log.error("检测到无法归属来源的双池遗留工作项，本进程关闭该版本的新建准入: version={} reason={}",
                    version, reason);
        }
    }

    /**
     * 父 Run 已经提交终态后，遗留工作项不再具备任何执行权，可以在启动时按精确版本收口。
     *
     * <p>这条路径只处理本轮无遗漏扫描读到的、属于调用方这个版本的工作项。取消终态保留
     * {@code CANCELED} 语义；其他终态把工作项标成 {@code STALE}，表示父 Run 已经先结束。
     * 每一行仍使用五字段身份和当前版本做条件更新；取消还会核对领取代际。任何一行不能被
     * 证明已经进入终态，就返回 false，让启动保护继续失败关闭。启动扫描与准入阻断本来
     * 就按调度器版本覆盖全部部署，因此终态收口使用相同范围；父 Run 已终结后不再拥有执行权。</p>
     */
    private boolean closeTerminalResidue(AgentRun run, SchedulerVersion version,
                                         Set<NodeWorkItemIdentity> scannedIdentities) {
        int scannedCount = scannedIdentities == null ? 0 : scannedIdentities.size();
        String scannedRunId = scannedRunId(scannedIdentities);
        if (run == null) {
            log.error("启动时无法收口双池遗留工作项: reason=run_missing runId={} unfinishedItemCount={} "
                            + "scannedIdentities={}",
                    scannedRunId, scannedCount, describeIdentities(scannedIdentities));
            return false;
        }
        if (!version.name().equals(run.getSchedulerVersion())) {
            log.error("启动时无法收口双池遗留工作项: reason=scheduler_version_mismatch runId={} "
                            + "schedulerVersion={} unfinishedItemCount={} scannedIdentities={}",
                    run.getId(), run.getSchedulerVersion(), scannedCount,
                    describeIdentities(scannedIdentities));
            return false;
        }
        if (!terminal(run.getStatus())) {
            log.error("启动时无法收口双池遗留工作项: reason=parent_run_nonterminal runId={} "
                            + "runStatus={} unfinishedItemCount={} scannedIdentities={}",
                    run.getId(), run.getStatus(), scannedCount, describeIdentities(scannedIdentities));
            return false;
        }
        if (scannedIdentities == null || scannedIdentities.isEmpty()) {
            log.error("启动时无法收口双池遗留工作项: reason=scanned_identity_set_empty runId={} "
                            + "runStatus={} unfinishedItemCount={}",
                    run.getId(), run.getStatus(), scannedCount);
            return false;
        }
        List<NodeWorkItem> unfinished = workItemStore.listUnfinishedByRun(run.getId());
        Map<NodeWorkItemIdentity, NodeWorkItem> itemsByIdentity = new LinkedHashMap<>();
        for (NodeWorkItem item : unfinished) {
            if (item != null && version.name().equals(item.getSchedulerVersion())) {
                itemsByIdentity.put(item.identity(), item);
            }
        }
        if (!itemsByIdentity.keySet().equals(scannedIdentities)) {
            Set<NodeWorkItemIdentity> missingFromCurrent = new LinkedHashSet<>(scannedIdentities);
            missingFromCurrent.removeAll(itemsByIdentity.keySet());
            Set<NodeWorkItemIdentity> addedInCurrent = new LinkedHashSet<>(itemsByIdentity.keySet());
            addedInCurrent.removeAll(scannedIdentities);
            log.error("启动时无法收口双池遗留工作项: reason=identity_set_mismatch runId={} "
                            + "runStatus={} unfinishedItemCount={} dualPoolItemCount={} scannedItemCount={} "
                            + "missingFromCurrent={} addedInCurrent={}",
                    run.getId(), run.getStatus(), unfinished.size(), itemsByIdentity.size(), scannedCount,
                    describeIdentities(missingFromCurrent), describeIdentities(addedInCurrent));
            return false;
        }
        for (NodeWorkItem item : itemsByIdentity.values()) {
            NodeWorkItemMutationResult result;
            if (run.getStatus() == AgentRunStatus.CANCELED) {
                result = workItemStore.cancel(
                        item.identity(), value(item.getRunControlVersion()), value(item.getClaimEpoch()),
                        "parent_run_terminal_at_startup:CANCELED");
            } else {
                result = workItemStore.markStale(
                        item.identity(), value(item.getContextVersion()), value(item.getRunControlVersion()),
                        "parent_run_terminal_at_startup:" + run.getStatus().name());
            }
            if (result == null || !result.applied()) {
                NodeWorkItem current = workItemStore.findByIdentity(item.identity()).orElse(null);
                if (current == null || !current.terminal()) {
                    log.error("启动时无法收口双池遗留工作项: reason=conditional_update_rejected "
                                    + "runId={} runStatus={} identity={} previousState={} currentState={} "
                                    + "rejection={} unfinishedItemCount={}",
                            run.getId(), run.getStatus(), item.identity().describe(), item.getState(),
                            current == null ? "MISSING" : current.getState(), rejectionDescription(result),
                            itemsByIdentity.size());
                    return false;
                }
            }
        }
        log.warn("启动时收口父 Run 已终结的双池工作项: runId={} status={} itemCount={}",
                run.getId(), run.getStatus(), itemsByIdentity.size());
        return true;
    }

    private String scannedRunId(Set<NodeWorkItemIdentity> identities) {
        if (identities == null || identities.isEmpty()) {
            return "<unknown>";
        }
        return identities.iterator().next().runId();
    }

    private List<String> describeIdentities(Set<NodeWorkItemIdentity> identities) {
        if (identities == null || identities.isEmpty()) {
            return List.of();
        }
        return identities.stream().map(NodeWorkItemIdentity::describe).sorted().toList();
    }

    private String rejectionDescription(NodeWorkItemMutationResult result) {
        if (result == null) {
            return "mutation_result_missing";
        }
        return result.rejection() == null ? "reason_unknown" : result.rejection().describe();
    }

    private boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private ToolJobAnchor parseRecoverableAnchor(AgentRun run) {
        if (run == null
                || !SchedulerVersion.DUAL_POOL_V1.name().equals(run.getSchedulerVersion())
                || (run.getStatus() != AgentRunStatus.EXECUTING
                && run.getStatus() != AgentRunStatus.WAITING_TOOL_JOB)
                || run.getToolJobAnchorJson() == null
                || run.getToolJobAnchorJson().isBlank()) {
            return null;
        }
        try {
            ToolJobAnchor anchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
            return hasWorkItemIdentity(anchor) ? anchor : null;
        } catch (RuntimeException invalidAnchor) {
            log.error("双池遗留 Run 的长工具锚点无法解析: runId={}", run.getId(), invalidAnchor);
            return null;
        }
    }

    private boolean hasWorkItemIdentity(ToolJobAnchor anchor) {
        return anchor != null
                && anchor.getWorkItemPlanGeneration() != null
                && anchor.getWorkItemNodeId() != null
                && !anchor.getWorkItemNodeId().isBlank()
                && anchor.getWorkItemNodeAttempt() != null
                && anchor.getWorkItemSegmentSequence() != null
                && anchor.getWorkItemContextVersion() != null
                && anchor.getWorkItemRunControlVersion() != null
                && anchor.getWorkItemClaimEpoch() != null;
    }

    private NodeWorkItemIdentity anchorIdentity(String runId, ToolJobAnchor anchor) {
        return new NodeWorkItemIdentity(runId, anchor.getWorkItemPlanGeneration(),
                anchor.getWorkItemNodeId(), anchor.getWorkItemNodeAttempt(),
                anchor.getWorkItemSegmentSequence());
    }

    /**
     * 接纳一条刚建好的 Run。
     *
     * <p>按版本取启动残留的阻断标志：某个版本有说不清来历的遗留记录时，停的只是那个版本的新建，
     * 另一个版本照常服务。这个判断在创建路径上已经按版本做过一次，这里再核一遍是因为本方法对
     * 调用方是公开入口，不能靠调用方先查过。</p>
     */
    public boolean admitNewRun(String runId, String versionName) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id_required");
        }
        if (startupResidueBlockedFor(versionName)) {
            return false;
        }
        // 先取得这条 Run 的服务所有权：一条 Run 同一时刻只由一个进程服务，这件事必须是数据库里的事实，
        // 不能只是本进程的一个集合。拿不到说明别的进程正在服务它，这次不受理。
        ServiceOwnershipFence before = currentOwnershipFence(runId).orElse(null);
        Optional<RunServiceLease> acquired = acquireOwnership(runId);
        if (acquired.isEmpty()) {
            log.error("这条 Run 的服务所有权在别人手上，本进程不受理: runId={} version={}", runId, versionName);
            return false;
        }
        boolean alreadyMine = before != null;
        knownRunIds.add(runId);
        if (activateNewRun(runId)) {
            return true;
        }
        // 业务名额没拿到：把刚取得的租约让出去，别握着租约空占这条 Run。
        knownRunIds.remove(runId);
        releaseOwnershipIfFreshlyTaken(runId,
                new ServiceOwnershipFence(acquired.get().ownerInstanceId(), acquired.get().fencingToken()),
                alreadyMine);
        return false;
    }

    /**
     * Beta 线程级故障演练只清除这一条 Run 的进程内准入与许可，不改数据库状态。
     * 后续恢复必须重新从数据库证明长工具锚点与工作项身份，不能沿用当前线程的内存所有权。
     */
    public void forgetForFaultInjection(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        knownRunIds.remove(runId);
        releaseBusinessPermit(runId);
    }

    /**
     * 工具作业那几条路径（提升、提交、中断）把一条 Run 重新接回执行链时用：它们手里有一条持久事实
     * （工具锚点与工作项身份），但这不是「凭一次扫描就执行」的理由，所以仍然要过两道门——
     * 这条 Run 没有被启动扫描隔离，以及本进程能取得它的服务所有权。
     *
     * <p>遗留 Run 的接管不走这里：普通扫描用的是 {@link #takeoverLegacyRun(String)}，
     * 那条路会把 Run 与它全部未完成分段读回来，按与启动恢复同一套证明重新判定。</p>
     */
    public boolean restorePersistedToolJob(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        String isolation = isolatedRunReasons.get(runId);
        if (isolation != null) {
            log.warn("这条 Run 被隔离，工具作业的持久事实也不足以受理它: runId={} reason={}", runId, isolation);
            return false;
        }
        if (isAdmitted(runId)) {
            return true;
        }
        ServiceOwnershipFence before = currentOwnershipFence(runId).orElse(null);
        Optional<RunServiceLease> acquired = acquireOwnership(runId);
        if (acquired.isEmpty()) {
            log.warn("这条 Run 的服务所有权在别人手上，工具作业这条路径不受理: runId={}", runId);
            return false;
        }
        knownRunIds.add(runId);
        if (activateNewRun(runId)) {
            return true;
        }
        knownRunIds.remove(runId);
        releaseOwnershipIfFreshlyTaken(runId,
                new ServiceOwnershipFence(acquired.get().ownerInstanceId(), acquired.get().fencingToken()),
                before != null);
        return false;
    }

    /**
     * 恢复路径的受理预留：已经有活的准入生命周期就直接返回「已受理」，否则占一个可回滚的预留。
     *
     * <p>返回值里的 {@code epoch} 为 -1 表示这一次没有新占预留（本来就已经受理），调用方不需要
     * 激活，也不需要回滚。恢复消费是一锤子买卖，预留必须在这之前拿到手：拿不到就不消费。</p>
     */
    public Admission reserveForRecovery(String runId) {
        if (runId == null || runId.isBlank()) {
            return Admission.rejected();
        }
        String isolation = isolatedRunReasons.get(runId);
        if (isolation != null) {
            // 启动扫描判定这条 Run 的恢复事实说不清：不许借着一次恢复受理把它接回执行链。
            log.warn("这条 Run 在启动时被隔离，恢复受理一律拒绝: runId={} reason={}", runId, isolation);
            return Admission.rejected();
        }
        // 没有凭据就按数据库取一次。这一句以前写成「已经有凭据就不再取，否则取一次」，受理不成时
        // 这次新取的租约就留在手里了——握着租约却不受理，这条 Run 会被锁到租约过期为止，别的进程
        // 也接不了手。现在把「这次刚取的租约」记下来，受理没成就在返回前让出去。
        Optional<RunServiceLease> acquired = holdsOwnership(runId)
                ? Optional.empty()
                : acquireOwnership(runId);
        if (!holdsOwnership(runId)) {
            // 所有权在别人手上：这次恢复消费不该发生，也不该占预留。
            log.warn("这条 Run 的服务所有权在别人手上，恢复受理拒绝: runId={}", runId);
            return Admission.rejected();
        }
        knownRunIds.add(runId);
        if (isAdmitted(runId)) {
            return new Admission(true, -1L);
        }
        Admission admission = admitExistingRunWithLease(runId);
        if (admission.admitted() || acquired.isEmpty()) {
            return admission;
        }
        // 刚取得凭据、受理却没成（业务名额拿不到）：这条 Run 这一轮不接手，把刚取得的让出去，
        // 本进程也不再当成认识它。
        knownRunIds.remove(runId);
        releaseOwnershipIfFreshlyTaken(runId,
                new ServiceOwnershipFence(acquired.get().ownerInstanceId(), acquired.get().fencingToken()),
                false);
        return Admission.rejected();
    }

    /** 同一进程内的追问或显式恢复可以重新占用业务名额；重启前未知的 Run 一律拒绝。 */
    public boolean admitExistingRun(String runId) {
        Admission admission = admitExistingRunWithLease(runId);
        return admission.admitted() && activateReservedAdmission(runId, admission);
    }

    /**
     * 与 {@link #admitExistingRun(String)} 相同，但把本次取得的 epoch 返回给需要失败回滚的入口。
     */
    public Admission admitExistingRunWithLease(String runId) {
        if (!knownRunIds.contains(runId)) {
            return Admission.rejected();
        }
        if (!holdsOwnership(runId)) {
            // 没有服务所有权就不该受理：受理之后每一次推进都会因为凭据不匹配落空，
            // 与其造一个写不动的生命周期，不如在这里就拒绝。
            log.error("没有服务所有权凭据，拒绝受理这条 Run: runId={}", runId);
            return Admission.rejected();
        }
        long reservationEpoch = admissionEpochSequence.incrementAndGet();
        AtomicBoolean reserved = new AtomicBoolean();
        synchronized (admissionLock) {
            runs.compute(runId, (ignored, current) -> {
                RunState state = current;
                // Run 自己仍有独立准入代际；业务名额只按根调用树领取一次。
                if (state == null || !state.admitted()) {
                    String root = acquireTreeAdmission(runId, state);
                    if (root == null) {
                        return state;
                    }
                    if (state == null) {
                        state = new RunState(null, -1L, Set.of(), root);
                    } else {
                        state = state.withRoot(root);
                    }
                }
                Set<Long> reservations = new LinkedHashSet<>(state.reservations());
                reservations.add(reservationEpoch);
                reserved.set(true);
                return state.withEpoch(state.activeEpoch(), reservations);
            });
        }
        return reserved.get()
                ? new Admission(true, reservationEpoch)
                : Admission.rejected();
    }

    /** 数据库已把 Run 持久化为新一轮 RECEIVED 后，把对应预留激活。 */
    public boolean activateReservedAdmission(String runId, Admission admission) {
        if (runId == null || admission == null || !admission.admitted()) {
            return false;
        }
        AtomicBoolean activated = new AtomicBoolean();
        runs.computeIfPresent(runId, (ignored, state) -> {
            if (!state.reservations().contains(admission.epoch())) {
                return state;
            }
            Set<Long> reservations = new LinkedHashSet<>(state.reservations());
            reservations.remove(admission.epoch());
            activated.set(true);
            return state.withEpoch(admission.epoch(), reservations);
        });
        return activated.get();
    }

    /** 数据库条件更新失败时只撤销自己的预留，不能删除其他请求依赖的旧许可或新生命周期。 */
    public boolean rollbackReservedAdmission(String runId, Admission admission) {
        if (runId == null || admission == null || !admission.admitted()) {
            return false;
        }
        AtomicBoolean rolledBack = new AtomicBoolean();
        synchronized (admissionLock) {
            runs.computeIfPresent(runId, (ignored, state) -> {
                if (!state.reservations().contains(admission.epoch())) {
                    return state;
                }
                Set<Long> reservations = new LinkedHashSet<>(state.reservations());
                reservations.remove(admission.epoch());
                rolledBack.set(true);
                if (state.activeEpoch() < 0L && reservations.isEmpty()) {
                    releaseTreeAdmission(runId, state.rootRunId());
                    return state.withoutAdmission();
                }
                return state.withEpoch(state.activeEpoch(), reservations);
            });
        }
        return rolledBack.get();
    }

    public boolean isAdmitted(String runId) {
        RunState state = runId == null ? null : runs.get(runId);
        return state != null && state.active();
    }

    /** 返回当前准入生命周期令牌；没有准入时返回 -1。 */
    public long currentAdmissionEpoch(String runId) {
        RunState state = runId == null ? null : runs.get(runId);
        return state == null ? -1L : state.activeEpoch();
    }

    public boolean isKnownInCurrentProcess(String runId) {
        return runId != null && knownRunIds.contains(runId);
    }

    /** 任意一个双池版本被阻断都算阻断；要按版本分别看用 {@link #startupResidueBlockedFor(String)}。 */
    public boolean startupResidueBlocked() {
        return residueBlockedByVersion.getOrDefault(SchedulerVersion.DUAL_POOL_V1, false)
                || residueBlockedByVersion.getOrDefault(SchedulerVersion.DUAL_POOL_V2, false);
    }

    /**
     * 这个版本的新建准入是不是被启动残留挡住了。
     *
     * <p>不认识的版本名照旧失败关闭：调用方已经用版本解析校验过一次，这里再让它失败，比默默放行安全。
     * 旧路径（LEGACY）不走这套受理，永远返回 false。</p>
     */
    public boolean startupResidueBlockedFor(String versionName) {
        SchedulerVersion version = SchedulerVersion.fromWire(versionName);
        if (!version.isDualPoolFamily()) {
            return false;
        }
        return residueBlockedByVersion.getOrDefault(version, false);
    }

    /**
     * 返回本进程启动时已经严格核对过的长工具 Run。
     *
     * <p>通用工具锚点扫描有批次上限，不能用它证明所有双池遗留工作项都进入了本轮恢复。
     * 这里的集合来自无批次遗漏的工作项扫描，只包含锚点身份与唯一未完成工作项完全一致、
     * 且已经重新取得业务许可的 Run。</p>
     */
    public Set<String> startupRecoveredToolJobRunIds() {
        return Set.copyOf(startupRecoveredRunIds);
    }

    /** 启动时按分段行事实证明可恢复、且已经重新取得业务许可的完整 DAG Run。 */
    public Set<String> startupRecoveredWaitGroupRunIds() {
        return Set.copyOf(startupRecoveredWaitGroupRunIds);
    }

    /**
     * 启动残留检查的读数：每个版本是否被阻断、为什么、隔离了哪些 Run，以及本次接手与让出的计数。
     *
     * <p>隔离的原因不额外落表：每次启动都能从库里按同一套规则重新算出来，读数里给的就是这一次算出来的结果。</p>
     */
    public Map<String, Object> startupSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("startupResidueBlocked", startupResidueBlocked());
        Map<String, Object> versions = new LinkedHashMap<>();
        for (SchedulerVersion version : List.of(SchedulerVersion.DUAL_POOL_V1, SchedulerVersion.DUAL_POOL_V2)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("blocked", residueBlockedByVersion.getOrDefault(version, false));
            one.put("reason", residueReasonByVersion.getOrDefault(version, "not_checked"));
            Map<String, String> isolated = residueIsolatedByVersion.getOrDefault(version, Map.of());
            one.put("isolatedRunCount", isolated.size());
            one.put("isolatedRunIds", List.copyOf(isolated.keySet()));
            // 每条被隔离的 Run 各自为什么：只报总数看不出该修哪一条。
            one.put("isolatedReasons", isolated);
            versions.put(version.name(), one);
        }
        snapshot.put("versions", versions);
        snapshot.put("recoveredToolJobRunCount", startupRecoveredRunIds.size());
        snapshot.put("recoveredWaitGroupRunCount", startupRecoveredWaitGroupRunIds.size());
        snapshot.put("isolatedRunCount", isolatedRunReasons.size());
        // 运行期（遗留接管）也会隔离：这里报的是此刻全部被隔离的 Run 与原因，不只是启动时算出来的。
        snapshot.put("isolatedRunReasons", Map.copyOf(isolatedRunReasons));
        // 本进程此刻服务着哪些 Run：凭据里的代际号是「第几次服务」，换人之后旧的写不进去。
        Map<String, String> owned = new LinkedHashMap<>();
        runs.forEach((runId, state) -> {
            if (state.fence() != null) {
                owned.put(runId, state.fence().describe());
            }
        });
        snapshot.put("ownedRunCount", owned.size());
        snapshot.put("ownedRuns", owned);
        snapshot.put("requeuedAbandonedClaimTotal", requeuedAbandonedClaims.get());
        snapshot.put("requeueFailureTotal", requeueFailures.get());
        snapshot.put("leaseHeldElsewhereTotal", startupLeaseHeldElsewhere.get());
        snapshot.put("admissionUnavailableTotal", startupAdmissionUnavailable.get());
        return snapshot;
    }

    /**
     * 取得这条 Run 的服务所有权，并把凭据绑到本进程这次准入上。
     *
     * <p>拿不到（别人正拿着且没过期）就返回空：那条 Run 此刻归别的进程服务，本进程既不能受理它，
     * 也不能在没有所有权的情况下推进它。凭据里的代际号是「第几次服务」：同一个进程后来重新取得
     * 也会拿到新的号，写操作只认号，旧生命周期在换人之后写不动任何一行。</p>
     */
    private Optional<RunServiceLease> acquireOwnership(String runId) {
        if (leaseStore == null || instanceIdentity == null) {
            log.error("没有服务所有权存储，双池的受理做不了: runId={}", runId);
            return Optional.empty();
        }
        Optional<RunServiceLease> acquired = leaseStore.acquire(runId, instanceIdentity.value(), serviceLeaseTtl);
        acquired.ifPresent(lease -> bindFence(runId,
                new ServiceOwnershipFence(lease.ownerInstanceId(), lease.fencingToken())));
        return acquired;
    }

    /**
     * 把这一次从数据库读到的凭据写回这条 Run 的状态对象。
     *
     * <p>拿到或读到租约之后都要走这里：状态里记的凭据与数据库里那一代不一致时，协调、领取与
     * Run 级写入会带着旧代际去写，全被条件语句挡回来——看起来像「什么都没做」，实际是账不对。
     * 数据库是这对账里说了算的一边，读到什么就记什么。</p>
     *
     * @return true 表示写回之后这条 Run 记的凭据就是传进来的那一代
     */
    public boolean bindFence(String runId, ServiceOwnershipFence fence) {
        if (runId == null || runId.isBlank() || fence == null) {
            return false;
        }
        RunState bound = runs.compute(runId, (ignored, current) -> current == null
                ? new RunState(fence, -1L, Set.of(), null)
                : current.withFence(fence));
        return fence.equals(bound.fence());
    }

    /** 本进程此刻是不是这条 Run 的服务方。 */
    public boolean holdsOwnership(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    /** 本进程此刻对这条 Run 的服务所有权凭据；协调、领取与 Run 级写入都从这里取。 */
    public Optional<ServiceOwnershipFence> currentOwnershipFence(String runId) {
        RunState state = runId == null ? null : runs.get(runId);
        return state == null ? Optional.empty() : Optional.ofNullable(state.fence());
    }

    /**
     * 续期循环发现这条 Run 的所有权已经不在本进程时调用。
     *
     * <p>读、比、删在同一个 {@link ConcurrentHashMap#computeIfPresent} 临界区里做完：按令牌条件撤销，
     * 只有当前绑定的代际号就是调用方说的那一代才撤。照着「先读一次、比一下、再删」写出来的三步
     * 会在这三步之间被并发插进来——本进程刚好用新一代重新取得这条 Run 时，最后一次删除会把新的
     * 生命周期删掉，而数据库里这条 Run 是我们在服务：本进程从此写不动它，直到租约过期。</p>
     *
     * @return true 表示确实撤销了这一次生命周期
     */
    public boolean revokeOwnership(String runId, long fencingToken) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        RunState revoked = dropIf(runId, state -> state.fence() != null
                && state.fence().fencingToken() == fencingToken);
        if (revoked == null) {
            return false;
        }
        knownRunIds.remove(runId);
        log.warn("这条 Run 的服务所有权已经不在本进程，撤销本进程的准入: runId={} fence={} 名额已归还={}",
                runId, revoked.fence().describe(), revoked.admitted());
        return true;
    }

    /**
     * 挑起出这条 Run 状态对象里符合条件的那一代，摘掉时把它占着的业务名额一并归还。
     *
     * <p>摘除与归还名额必须在同一个临界区里成对做完：先摘掉再归还之间，别的请求看到的是
     * 「这一条已经不在了」，于是去抢名额——而名额其实还没还，白抢一次。</p>
     *
     * @return 被摘掉的那个状态；没摘到返回 null
     */
    private RunState dropIf(String runId, Predicate<RunState> matches) {
        AtomicReference<RunState> dropped = new AtomicReference<>();
        synchronized (admissionLock) {
            runs.computeIfPresent(runId, (ignored, state) -> {
                if (!matches.test(state)) {
                    return state;
                }
                dropped.set(state);
                if (state.admitted()) {
                    releaseTreeAdmission(runId, state.rootRunId());
                }
                return null;
            });
        }
        return dropped.get();
    }

    /**
     * 普通扫描遇到「库里有资格记录、本进程还没受理」的 Run 时走这里：接管一条遗留 Run。
     *
     * <p>它不是「凭一次扫描重新准入」：接管要把 Run 与它全部未完成分段读回来，按与启动恢复同一套
     * 证明判定——旧骨架认 Run 级长工具锚点与唯一未完成分段，完整 DAG 认分段行本身。证明不了就隔离
     * （不占名额、不投递提示），证明得了才取租约、把死在领取态的分段放回可领取、再占业务名额。
     * 这样启动扫描放过的 Run 不会在下一轮普通扫描里绕过同一套判定。</p>
     *
     * @return true 表示这条 Run 现在归本进程服务
     */
    public boolean takeoverLegacyRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        if (isAdmitted(runId)) {
            return true;
        }
        String isolation = isolatedRunReasons.get(runId);
        if (isolation != null) {
            log.warn("这条 Run 已经被隔离，普通扫描不接手: runId={} reason={}", runId, isolation);
            return false;
        }
        if (runMapper == null) {
            log.error("没有 Run 读取入口，遗留接管做不了: runId={}", runId);
            return false;
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            log.error("遗留接管读不到这条 Run: runId={}", runId);
            return false;
        }
        SchedulerVersion version;
        try {
            version = SchedulerVersion.fromWire(run.getSchedulerVersion());
        } catch (RuntimeException unknownVersion) {
            log.error("遗留接管的 Run 版本认不出来: runId={} schedulerVersion={}",
                    runId, run.getSchedulerVersion(), unknownVersion);
            return false;
        }
        if (!version.isDualPoolFamily()) {
            // 旧引擎的 Run 不归这套接管：它有自己的扫描与恢复。
            return false;
        }
        if (startupResidueBlockedFor(version.name())) {
            log.error("这一版在启动时被判为失败关闭，普通扫描不接管: runId={} version={}", runId, version);
            return false;
        }
        List<NodeWorkItem> items = workItemStore.listUnfinishedByRun(runId);
        if (version == SchedulerVersion.DUAL_POOL_V1) {
            if (!recoverAnchoredToolJobRun(run, identitiesOf(items))) {
                log.error("这条旧骨架 Run 的锚点证明不成立，普通扫描不接手: runId={} 未完成分段={}",
                        runId, items.size());
                return false;
            }
            return isAdmitted(runId);
        }
        String refusal = waitGroupRunRecoveryRefusal(run, items);
        if (refusal != null) {
            isolate(runId, version, refusal, new LinkedHashMap<>());
            return false;
        }
        String recoveryRefusal = recoverWaitGroupRun(run, items);
        if (recoveryRefusal != null) {
            isolate(runId, version, recoveryRefusal, new LinkedHashMap<>());
            return false;
        }
        return isAdmitted(runId);
    }

    /** 一条 Run 现在还能接着跑的状态：非终态、也不是「取消中」。 */
    private boolean recoverableStatus(AgentRunStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case RECEIVED, PLANNING, EXECUTING, WAITING, SUMMARIZING, WAITING_TOOL_JOB -> true;
            case COMPLETED, PARTIAL, FAILED, CANCELED, EXPIRED, CANCELING -> false;
        };
    }

    private Set<NodeWorkItemIdentity> identitiesOf(List<NodeWorkItem> items) {
        Set<NodeWorkItemIdentity> identities = new LinkedHashSet<>();
        for (NodeWorkItem item : items) {
            if (item != null) {
                identities.add(item.identity());
            }
        }
        return identities;
    }

    /**
     * 刚取得、还没用上的租约要让出去：握着不服务的租约会被续期循环一直续住，别人接不了手。
     *
     * <p>这条 Run 本来就是我们服务的时候（{@code alreadyMine}）什么都不动：这一次失败只是「这一轮
     * 不做这件事」，凭据与数据库里那条租约照旧；以前在这里按 runId 无条件删掉本进程的凭据，删完之后
     * 本进程还占着准入名额却写不动这条 Run，成了最麻烦的那种错位。</p>
     */
    private void releaseOwnershipIfFreshlyTaken(String runId, ServiceOwnershipFence fence, boolean alreadyMine) {
        if (fence == null || alreadyMine) {
            return;
        }
        // 只摘掉「就是刚取得的那一代」：中途被换过的状态不归这次调用管。
        boolean cleared = dropIf(runId, state -> fence.equals(state.fence())) != null;
        boolean released = leaseStore.release(runId, fence.ownerInstanceId(), fence.fencingToken());
        log.warn("这条 Run 没有受理就先把刚取得的服务所有权让出去: runId={} 状态已清={} released={}",
                runId, cleared, released);
    }

    /** 仅在创建后的调度入口失败时撤销；正常终态仍保留，以便同进程追问继续走原版本。 */
    public void forgetFailedAdmission(String runId) {
        if (runId == null) {
            return;
        }
        releaseBusinessPermit(runId);
        knownRunIds.remove(runId);
    }

    /** Run 到达数据库终态后交还业务名额，但保留同进程身份与所有权凭据，允许后续追问重新准入。 */
    public void releaseBusinessPermit(String runId) {
        if (runId == null) {
            return;
        }
        synchronized (admissionLock) {
            runs.computeIfPresent(runId, (ignored, state) -> {
                if (!state.admitted()) {
                    return state;
                }
                releaseTreeAdmission(runId, state.rootRunId());
                return state.withoutAdmission();
            });
        }
    }

    /**
     * 仅当准入生命周期仍是调用方冻结的那一代时执行清理并释放许可。
     *
     * <p>条件判断、清理与删除同处一个 {@link ConcurrentHashMap#computeIfPresent} 临界区。
     * 并发追问/恢复要么先换新令牌，使旧回调条件失败；要么等旧回调释放后重新取得许可。
     * 这样不会出现“旧终态回调检查通过后，把新一轮准入删掉”的窗口。</p>
     */
    public boolean releaseBusinessPermitIfCurrent(String runId,
                                                   long expectedEpoch,
                                                   Runnable cleanup) {
        if (runId == null || expectedEpoch < 0L) {
            return false;
        }
        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<RuntimeException> cleanupFailure = new AtomicReference<>();
        synchronized (admissionLock) {
            runs.computeIfPresent(runId, (ignored, state) -> {
                if (state.activeEpoch() != expectedEpoch || !state.reservations().isEmpty()) {
                    return state;
                }
                try {
                    if (cleanup != null) {
                        cleanup.run();
                    }
                } catch (RuntimeException e) {
                    cleanupFailure.set(e);
                }
                releaseTreeAdmission(runId, state.rootRunId());
                released.set(true);
                return state.withoutAdmission();
            });
        }
        RuntimeException failure = cleanupFailure.get();
        if (failure != null) {
            throw failure;
        }
        return released.get();
    }

    public boolean releaseBusinessPermitIfCurrent(String runId, long expectedEpoch) {
        return releaseBusinessPermitIfCurrent(runId, expectedEpoch, null);
    }

    /** 此刻占业务名额的根调用树数；保留等待后代收尾的根树也计算在内。 */
    public int admittedCount() {
        synchronized (admissionLock) {
            return permittedRoots.size();
        }
    }

    public boolean rootCapacityRecoveryBlocked() {
        return rootCapacityRecoveryBlocked;
    }

    public String rootCapacityRecoveryReason() {
        return rootCapacityRecoveryReason;
    }

    public Set<String> snapshotRunIds() {
        Set<String> activeRunIds = new LinkedHashSet<>();
        runs.forEach((runId, state) -> {
            if (state.active()) {
                activeRunIds.add(runId);
            }
        });
        return Set.copyOf(activeRunIds);
    }

    private boolean activateNewRun(String runId) {
        AtomicBoolean admitted = new AtomicBoolean();
        synchronized (admissionLock) {
            runs.compute(runId, (ignored, current) -> {
                if (current != null && current.admitted()) {
                    admitted.set(true);
                    return current;
                }
                String root = acquireTreeAdmission(runId, current);
                if (root == null) {
                    return current;
                }
                long nextEpoch = admissionEpochSequence.incrementAndGet();
                admitted.set(true);
                return current == null
                        ? new RunState(null, nextEpoch, Set.of(), root)
                        : current.withRoot(root).withEpoch(nextEpoch, Set.of());
            });
        }
        return admitted.get();
    }

    /** 已受理的 Run 的根身份供两个提示队列分桶；缺失身份不能放行提示。 */
    public String rootRunIdForAdmitted(String runId) {
        RunState state = runId == null ? null : runs.get(runId);
        if (state == null || !state.active() || state.rootRunId() == null) {
            throw new IllegalStateException("admitted_root_identity_missing:" + runId);
        }
        return state.rootRunId();
    }

    /** 创建意图或最后一个子任务收尾后可调用；无活跃成员且持久后代均收尾才还根树许可。 */
    public void reconcileRootPermit(String rootRunId) {
        synchronized (admissionLock) {
            maybeReleaseTreePermit(rootRunId);
        }
    }

    /** 持久创建意图稍后收尾或查询短暂失败时，自动复查空闲根树。 */
    @Scheduled(fixedDelayString = "${agent.langchain.dual-pool.scan.interval-ms:1000}")
    public void reconcileRetainedRootPermits() {
        synchronized (admissionLock) {
            for (String root : List.copyOf(permittedRoots)) {
                if (!admittedRunsByRoot.containsKey(root)) {
                    maybeReleaseTreePermit(root);
                }
            }
        }
        if (rootCapacityRecoveryBlocked) {
            restorePersistedRootPermits();
        }
    }

    /** 先恢复持久子工作预留，再让启动残留扫描为每条 Run 单独取得服务资格。 */
    void restorePersistedRootPermits() {
        synchronized (admissionLock) {
            final int pageSize = 100;
            String after = "";
            try {
                while (true) {
                    List<String> roots = rootRunResolver.listReservedRootRunIds(after, pageSize);
                    if (roots == null) {
                        throw new IllegalStateException("reserved_root_page_missing");
                    }
                    if (roots.size() > pageSize) {
                        throw new IllegalStateException("reserved_root_page_too_large");
                    }
                    if (roots.isEmpty()) {
                        rootCapacityRecoveryBlocked = false;
                        rootCapacityRecoveryReason = "none";
                        return;
                    }
                    for (String root : roots) {
                        if (root == null || root.isBlank() || root.compareTo(after) <= 0) {
                            throw new IllegalStateException("reserved_root_page_out_of_order");
                        }
                        if (!permittedRoots.contains(root)) {
                            if (!permitLedger.tryAcquire(SchedulerPermitLayer.BUSINESS_ADMISSION)) {
                                throw new IllegalStateException("reserved_root_capacity_exceeded:" + root);
                            }
                            permittedRoots.add(root);
                        }
                        after = root;
                    }
                }
            } catch (RuntimeException e) {
                rootCapacityRecoveryBlocked = true;
                rootCapacityRecoveryReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error("持久根树名额恢复失败，拒绝新的根树准入: reason={}", rootCapacityRecoveryReason, e);
            }
        }
    }

    private String acquireTreeAdmission(String runId, RunState current) {
        String root = current == null ? null : current.rootRunId();
        if (root == null) {
            try {
                root = rootRunResolver.rootRunId(runId);
            } catch (RuntimeException e) {
                log.error("根树身份查询失败，拒绝 Run 准入: runId={}", runId, e);
                return null;
            }
        }
        if (root == null || root.isBlank()) {
            log.error("根树身份缺失，拒绝 Run 准入: runId={}", runId);
            return null;
        }
        if (rootCapacityRecoveryBlocked && !permittedRoots.contains(root)) {
            log.error("持久根树名额尚未完整恢复，拒绝新根树准入: runId={} rootRunId={} reason={}",
                    runId, root, rootCapacityRecoveryReason);
            return null;
        }
        if (!permittedRoots.contains(root)) {
            if (!permitLedger.tryAcquire(SchedulerPermitLayer.BUSINESS_ADMISSION)) {
                return null;
            }
            permittedRoots.add(root);
        }
        admittedRunsByRoot.computeIfAbsent(root, ignored -> new LinkedHashSet<>()).add(runId);
        return root;
    }

    private void releaseTreeAdmission(String runId, String root) {
        if (root == null) {
            throw new IllegalStateException("admitted_root_identity_missing:" + runId);
        }
        Set<String> members = admittedRunsByRoot.get(root);
        if (members == null || !members.remove(runId)) {
            throw new IllegalStateException("root_admission_member_missing:" + runId);
        }
        if (members.isEmpty()) {
            admittedRunsByRoot.remove(root);
        }
        maybeReleaseTreePermit(root);
    }

    private void maybeReleaseTreePermit(String root) {
        if (!permittedRoots.contains(root) || admittedRunsByRoot.containsKey(root)) {
            return;
        }
        try {
            if (rootRunResolver.hasUnsettledDescendants(root)) {
                return;
            }
        } catch (RuntimeException e) {
            // 根树还有没有子工作不能证实时，保留业务许可，等待持久事实恢复后重查。
            log.error("根树收尾状态查询失败，保留业务许可: rootRunId={}", root, e);
            return;
        }
        permittedRoots.remove(root);
        permitLedger.release(SchedulerPermitLayer.BUSINESS_ADMISSION);
    }
}
