package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.MemberCompletionResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePointStore;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
import world.willfrog.agentlangchain.acceptance.AcceptanceRunPolicyRegistry;
import world.willfrog.agentlangchain.acceptance.FixtureRuleHitStore;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.control.dualpool.RecoveryBackoff;
import world.willfrog.agentlangchain.execution.WaitMemberResultPayload;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 等待成员的结果接收：把已经转后台的 {@code executePython} 的终态接回成员行。
 *
 * <p>成员转后台之后，原来的执行线程已经交还了名额、不再守着这个工具调用，所以结果必须有别人去收。
 * 这一路按成员行的下次查询时间有界地取一批，逐个去问沙箱那个作业的终态：确认终态就把结果写成
 * 成员行的终态（与同步执行时同一份载荷），整组齐备时叫一声恢复分发器，让下一段接着跑。</p>
 *
 * <p>写成员终态之前先把归属核对齐：这条成员属于哪个组、哪个计划代际、哪个节点的哪一段，以及那一段
 * 挂起时的上下文版本与上报时的 Run 控制版本。核对写在 {@code completeMember} 那条语句里，条件不成立
 * 就一行都不写——旧 Worker、作废的计划代际、已经换段的成员都不该把等待链往前推。</p>
 *
 * <p>还没到终态就按退避推后下次查询时间：短任务几秒内就问到了，长任务稀疏下来，不让一批长任务
 * 每一轮都占满查询名额。推后只写成员行自己的时间，不影响别的成员。</p>
 *
 * <p>写成员终态之前先把这次后台作业的名额与用量收尾（{@link WaitMemberSettlement}）：名额还回去、
 * 用量记下来。收尾没成就不写终态、把这条成员按退避推后，下一轮拿同一份证明重来——反过来先写终态
 * 再收尾的话，进程在两步之间退出就再也没人回来收尾了（落了终态的成员不在「执行中」的扫描口径里）。</p>
 */
@Component
@Slf4j
public class WaitMemberResultReceiver {

    /** 沙箱任务的三个规范终态，加上「结果已永久丢失」这一种：四种都算有结论。 */
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String CANCELED = "CANCELED";
    private static final String RESULT_LOST = "RESULT_LOST";
    /** 夹具策略点了同一个等待组里不存在的成员时写的错误码。 */
    private static final String POLICY_PEER_UNKNOWN = "acceptance_fixture_policy_peer_unknown";
    /**
     * 夹具这一侧读不出来（身份中途变了、本进程记住的夹具 Run 满了）时写的错误码。
     *
     * <p>这类结论是「这次验收说不下去了」，不是「再等等就会好」：推后重试只会一轮一轮地重来，
     * 永远不给这条成员一个结论。按失败收尾、原因照实写，人和验收证据当场看得见。</p>
     */
    private static final String POLICY_UNREADABLE = "acceptance_fixture_policy_unreadable";
    /**
     * 被夹具压住的成员，下一次查询时间往前推多少。
     *
     * <p>比一轮的轮询间隔长：压住的成员每一轮都会被扫到，推得太短会让一批被压住的成员把每一轮的
     * 扫描名额占满，同一批里别的 Run 的成员就排不上（扫描按下次查询时间排序取一批）。推后这么久
     * 的代价是放行之后最多等这么久才被接回来，验收看得见、几秒钟的延迟可以接受。</p>
     */
    private static final long HOLD_POLL_DELAY_MS = 5000L;

    private final WaitGroupStore waitGroupStore;
    private final RunOwnershipGateway ownership;
    private final NodeWorkItemStore nodeWorkItemStore;
    private final PythonSandboxService sandboxService;
    private final PythonSandboxTools pythonSandboxTools;
    private final WaitMemberSettlement settlement;
    private final DualPoolRecoveryDispatcher recoveryDispatcher;
    private final ObjectMapper objectMapper;
    /** 每轮读一次的参数：批次、退避与最多翻几步都允许在运行期改，改完下一轮生效。 */
    private final DualPoolSchedulerSettings settings;
    /** 最近一次构造退避用的初值与上限；配置变了就按新值重建，不用重启。 */
    private volatile RecoveryBackoff backoff;
    private volatile long backoffBaseMs;
    private volatile long backoffMaxMs;
    private final int maxMemberResultChars;
    private final long pollIntervalMs;
    /** 验收夹具的结果放行策略与放行点：不带夹具编号的 Run 拿到空，成员照原来的方式立刻收尾。 */
    private final AcceptanceRunPolicyRegistry acceptancePolicies;
    private final AcceptanceReleasePointStore releasePoints;
    /** 放行策略的规则命中记录：一条规则真的打中了哪一条成员，终态核对着它点名。 */
    private final FixtureRuleHitStore ruleHitStore;
    /** 这个进程已经往库里记过的命中（Run|规则|组|成员）：同一件事不必每一轮都去问一次库。 */
    private final Set<String> recordedRuleHits = ConcurrentHashMap.newKeySet();
    /**
     * {@code uniqueExternalTask} 越界命中的成员键：已经记过越界之后每一轮都要照常接结果，
     * 不能因为「命中已经记过」就回头去压住。
     */
    private final Set<String> overHitRuleMembers = ConcurrentHashMap.newKeySet();

    /** 每压住一轮记一次（同一条成员被压住多轮就记多笔），不是「压住过几条成员」。 */
    private final AtomicLong holdPushes = new AtomicLong();
    private final AtomicLong releasedOnHoldTimeout = new AtomicLong();
    private final AtomicLong designatedFailures = new AtomicLong();
    private final AtomicLong policyRefusals = new AtomicLong();
    /** 被压住的成员从哪一刻起被压：兜底放行按这个时刻算；不再被压、成员落终态、Run 进终态时清掉。 */
    private final Map<String, HeldSince> heldSinceByMember = new ConcurrentHashMap<>();

    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong isolated = new AtomicLong();
    private final AtomicLong wakeups = new AtomicLong();
    private final AtomicLong settlementFailures = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public WaitMemberResultReceiver(
            WaitGroupStore waitGroupStore,
            RunOwnershipGateway ownership,
            NodeWorkItemStore nodeWorkItemStore,
            PythonSandboxService sandboxService,
            PythonSandboxTools pythonSandboxTools,
            WaitMemberSettlement settlement,
            DualPoolRecoveryDispatcher recoveryDispatcher,
            ObjectMapper objectMapper,
            DualPoolSchedulerSettings settings,
            @Value("${agent.langchain.dual-pool.wait-group.max-member-result-chars:1048576}")
            int maxMemberResultChars,
            @Value("${agent.langchain.wait-member.receiver.poll-interval-ms:1000}") long pollIntervalMs,
            FrozenEffectiveSettings frozenEffectiveSettings,
            AcceptanceRunPolicyRegistry acceptancePolicies,
            AcceptanceReleasePointStore releasePoints,
            FixtureRuleHitStore ruleHitStore) {
        this.waitGroupStore = waitGroupStore;
        this.ownership = ownership;
        this.nodeWorkItemStore = nodeWorkItemStore;
        this.sandboxService = sandboxService;
        this.pythonSandboxTools = pythonSandboxTools;
        this.settlement = settlement;
        this.recoveryDispatcher = recoveryDispatcher;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.acceptancePolicies = acceptancePolicies;
        this.releasePoints = releasePoints;
        this.ruleHitStore = ruleHitStore;
        this.maxMemberResultChars = Math.max(1, maxMemberResultChars);
        this.pollIntervalMs = Math.max(1L, pollIntervalMs);
        // 登记归一化之后真正在用的值；轮询间隔与 @Scheduled 上那个属性名在启动时各解析一次，
        // 取到的是同一个数。
        String component = "WaitMemberResultReceiver";
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_WAIT_GROUP_MAX_MEMBER_RESULT_CHARS,
                component, this.maxMemberResultChars);
        frozenEffectiveSettings.register(DualPoolSchedulerSettings.KEY_MEMBER_RECEIVER_POLL_INTERVAL_MS,
                component, this.pollIntervalMs);
    }

    /** 退避参数按当前配置取；配置改了就用新值重建一个，读数与推后用的是同一个。 */
    private RecoveryBackoff backoff() {
        // 初值与上限从同一份解析结果里取：热更新落在两次读之间也不会拼出谁都没配过的组合。
        DualPoolSchedulerSettings.RoundSettings round = settings.round();
        long base = round.memberReceiverBackoffBaseMs().longValue();
        long max = round.memberReceiverBackoffMaxMs().longValue();
        RecoveryBackoff current = backoff;
        if (current == null || base != backoffBaseMs || max != backoffMaxMs) {
            current = new RecoveryBackoff(Duration.ofMillis(base), Duration.ofMillis(max));
            backoff = current;
            backoffBaseMs = base;
            backoffMaxMs = max;
        }
        return current;
    }

    /** 周期接结果：按成员行的下次查询时间取一批到点的成员。 */
    @Scheduled(fixedDelayString = "${agent.langchain.wait-member.receiver.poll-interval-ms:1000}")
    public void pollDueMembers() {
        safeRound();
    }

    /** 一轮；出错只记日志，不带走调度线程。返回这一轮处理过的成员数。 */
    public int safeRound() {
        try {
            return round();
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("等待成员结果接收这一轮失败，下一轮接着来: reason={}", e.getMessage(), e);
            return 0;
        }
    }

    /** 取一批到点的成员，逐个接结果。 */
    public int round() {
        rounds.incrementAndGet();
        OffsetDateTime now = OffsetDateTime.now();
        DeploymentIdentity deployment = ownership.requireIdentity();
        List<WaitMember> due = waitGroupStore.scanDueMembers(deployment.deploymentId(),
                deployment.generationId(), now,
                settings.memberReceiverBatchSize().intValue());
        scanned.addAndGet(due.size());
        int handled = 0;
        for (WaitMember member : due) {
            try {
                AgentRun run = ownership.findOwnedRun(member.getRunId());
                if (run == null) {
                    continue;
                }
                LaneScopeGateway.wrap(run, () -> collect(member, now, run)).run();
                handled++;
            } catch (RuntimeException e) {
                // 一个成员上的意外不带走这一批：推后它、记一笔，别的成员照旧。
                failures.incrementAndGet();
                log.error("接这个成员的结果时出错，推后下一轮再问: group={} member={} reason={}",
                        member.getGroupId(), member.getMemberIdentity(), e.getMessage(), e);
                defer(member, now, "unexpected_error");
            }
        }
        return handled;
    }

    /** 一个成员：核对归属、问一次外部作业、按结论写终态或推后。 */
    private void collect(WaitMember member, OffsetDateTime now, AgentRun run) {
        if (member.stateEnum() != WaitMemberState.RUNNING) {
            return;
        }
        Optional<WaitMemberDispatchProof> proofOpt =
                WaitMemberDispatchProof.fromJson(objectMapper, member.getDispatchProofJson());
        if (proofOpt.isEmpty()) {
            // 没有可用证明就不许猜：既不知道问哪个作业，也不许释放名额。
            isolate(member, now, "dispatch_proof_unreadable");
            return;
        }
        WaitMemberDispatchProof proof = proofOpt.get();

        if (run.getStatus() != AgentRunStatus.EXECUTING) {
            // Run 不在执行中：取消、暂停、终态都由它们自己的路径收尾，这里不动它。
            isolate(member, now, "run_not_executing:" + run.getStatus());
            return;
        }
        SchedulerVersion version;
        try {
            version = SchedulerVersion.fromWire(run.getSchedulerVersion());
        } catch (RuntimeException e) {
            isolate(member, now, "unknown_scheduler_version");
            return;
        }
        if (!version.usesWaitGroups()) {
            isolate(member, now, "version_without_wait_groups");
            return;
        }

        WaitGroup group = waitGroupStore.findGroup(member.getGroupId()).orElse(null);
        if (group == null) {
            isolate(member, now, "group_missing");
            return;
        }
        NodeWorkItem segment = nodeWorkItemStore.findByIdentity(new NodeWorkItemIdentity(
                group.getRunId(), group.getPlanGeneration(), group.getNodeId(),
                group.getNodeAttempt(), group.getSegmentSequence())).orElse(null);
        if (segment == null || segment.getContextVersion() == null) {
            // 找不到这一段（或者它没有上下文版本）就没法核对归属，补齐之前不动成员。
            isolate(member, now, "segment_missing");
            return;
        }

        // 验收夹具的放行策略：被压住的成员这一轮不去接结果，放行点被控制面标成已放行之后才继续。
        // 放在问沙箱之前，免得压住的每一轮都去拉一次已经有结论的结果。
        AcceptanceReleasePolicy policy = null;
        PolicyOutcome outcome = PolicyOutcome.none();
        try {
            policy = acceptancePolicies.policyForRun(run).orElse(null);
            if (policy != null) {
                PolicyAction action = policyAction(policy, member, group);
                if (action.hold()) {
                    return;
                }
                outcome = action.outcome();
            }
        } catch (AcceptanceFixtureExecutionException e) {
            // 策略读不出来：这条成员按失败收尾，原因写成夹具这一侧给的错误码。**照样走正常那条路**，
            // 先问沙箱拿到真实终态、把名额与用量收干净，再落失败——绕开真实终态收尾会让已经派发的
            // 名额永远还回去，这条成员也就永远停在这里。
            outcome = PolicyOutcome.refused(new ForcedFailure(POLICY_UNREADABLE, e.getMessage()));
            log.warn("夹具这一侧读不出来，这条成员按失败收尾：group={} member={} 原因={}",
                    group.getId(), memberKey(member), e.getMessage());
        }

        String taskId = proof.taskId();
        if (taskId == null) {
            TaskLookup lookup = lookupByOperation(proof);
            if (lookup.taskId() == null) {
                if (lookup.notFound()) {
                    // createTask 的网络请求可能尚未抵达 Sandbox。先写持久取消墓碑，
                    // 再确认相同操作身份，才能排除迟到创建；仅凭此刻 found=false 不释放容量。
                    taskId = tombstoneAbsentOperation(member, proof);
                    if (taskId == null) {
                        defer(member, now, "cancel_tombstone_unavailable");
                        return;
                    }
                } else {
                    defer(member, now, "task_lookup_unavailable");
                    return;
                }
            } else {
                taskId = lookup.taskId();
            }
        }

        TaskStatusResponse status = queryStatus(taskId);
        if (status == null) {
            defer(member, now, "status_unavailable");
            return;
        }
        String statusName = status.getStatus();
        if (!terminal(statusName)) {
            defer(member, now, "still_running:" + statusName);
            return;
        }
        TaskResultResponse result = fetchResult(taskId, member.getRunId(), statusName);
        if (result == null) {
            defer(member, now, "result_unavailable");
            return;
        }
        finish(member, group, segment, run, proof,
                new Terminal(taskId, statusName, result, status.getFinishedAt()),
                policy, outcome);
    }

    /** 这一轮对一条成员的处置：压住、照常接结果、或者照常接结果但按策略拒绝收成失败。 */
    private record PolicyAction(boolean hold, PolicyOutcome outcome) {

        static PolicyAction proceed() {
            return new PolicyAction(false, PolicyOutcome.none());
        }

        static PolicyAction held() {
            return new PolicyAction(true, PolicyOutcome.none());
        }

        static PolicyAction refuse(String code, String detail) {
            return new PolicyAction(false, PolicyOutcome.refused(new ForcedFailure(code, detail)));
        }
    }

    /**
     * 策略这一轮定下的事，等结果真的写进库里再记读数。
     *
     * <p>读数是给验收看板看的「发生过几次」，所以按落库成功计数：拒绝或压过时限之后如果收尾没成、
     * 这条成员下一轮还会被同一条规则再判一次，决策处就计数会把一次事故记成好几次。</p>
     */
    private record PolicyOutcome(ForcedFailure refusal, boolean holdTimeoutRelease) {

        static PolicyOutcome none() {
            return new PolicyOutcome(null, false);
        }

        static PolicyOutcome refused(ForcedFailure refusal) {
            return new PolicyOutcome(refusal, false);
        }

        static PolicyOutcome holdTimeout() {
            return new PolicyOutcome(null, true);
        }
    }

    /** 按策略把一条成员收成失败：错误码与说明由策略拒绝给出。 */
    private record ForcedFailure(String code, String detail) {
    }

    /**
     * 一条被压住的成员在计时表里的键。
     *
     * <p>成员身份按现有合同只在同一个等待组内唯一，所以键要带上组：不同 Run、不同等待组复用同一个
     * 工具调用编号时，只用成员身份做键会让两条成员的计时互相覆盖或互相删掉，兜底放行就会提前或延后。</p>
     */
    private static String memberKey(WaitMember member) {
        return member.getGroupId() + "|" + member.getMemberIdentity();
    }

    /**
     * 这条成员这一轮该怎么办：压住、照常接结果、还是照常接结果但收成失败。
     *
     * <p>压住 = 这一轮不去接它的结果：成员保持执行中，只把下次查询时间推一小步，下一轮再看。
     * 两种放行条件：等同一个等待组里的另外几条成员先落终态，或者等某个放行点被控制面标成已放行；
     * 两种都受 {@code maxHoldSeconds} 兜底，压过时限还没人放行就照常接结果，读数里单独记一笔。</p>
     *
     * <p>策略点名一个在这个等待组里对不上的成员（选择器写错、组里没有这条成员、或者一个选择器同时
     * 对上两条），说明夹具写错了：这条成员要按失败收尾并把原因写清楚，压住不动只会让人以为结果
     * 还没回来。**它仍然要走「先问沙箱、再结算、再写终态」那条正常路**——已经派发的成员名额挂在沙箱
     * 任务上，绕开真实终态去收尾会让名额永远还不回去，这条成员也就永远停在这里。所以这里只把
     * 「要收成失败」这个决定交回调用方，真正的收尾还是那条正常路。</p>
     *
     * <p>点名对了就把这笔命中记下来：规则写错字段名时一声不响、一条成员都不打中，跑到终态核对时
     * 要说得清是哪条规则。</p>
     */
    private PolicyAction policyAction(AcceptanceReleasePolicy policy, WaitMember member, WaitGroup group) {
        String key = memberKey(member);
        List<WaitMember> members = waitGroupStore.listMembers(group.getId());
        List<AcceptanceReleasePolicy.MemberFacts> facts = groupFacts(group, members);
        Optional<AcceptanceReleasePolicy.Rule> matched =
                policy.ruleAt(policy.match(facts), memberFactsOf(group, member));
        if (matched.isEmpty()) {
            matched = recordedHitRule(policy, group, member);
        }
        if (matched.isEmpty()) {
            heldSinceByMember.remove(key);
            return PolicyAction.proceed();
        }
        AcceptanceReleasePolicy.Rule rule = matched.get();
        if (!recordRuleHit(group, member, rule)) {
            heldSinceByMember.remove(key);
            return PolicyAction.proceed();
        }
        List<AcceptanceReleasePolicy.MemberFacts> peers;
        try {
            peers = policy.peersOf(rule, memberFactsOf(group, member), facts);
        } catch (AcceptanceFixtureExecutionException e) {
            log.warn("夹具策略点名的成员在这个等待组里对不上，这条成员按失败收尾：group={} member={} 原因={}",
                    group.getId(), key, e.getMessage());
            heldSinceByMember.remove(key);
            return PolicyAction.refuse(POLICY_PEER_UNKNOWN, e.getMessage());
        }
        List<String> waitingFor = new ArrayList<>();
        for (AcceptanceReleasePolicy.MemberFacts peer : peers) {
            WaitMember peerMember = memberOf(group, members, peer);
            if (peerMember != null && !peerMember.stateEnum().isTerminal()) {
                // 「还会不会再变」与「算不算组的一次有效结束」是两个判据：这里问的是前者。
                // 只看成功与失败会把已经取消、已经迟到的成员当成还在跑，那条等待就永远等不到头。
                waitingFor.add(peer.describe());
            }
        }
        String releaseKey = rule.holdReleaseKey() == null ? "<无>" : rule.holdReleaseKey();
        boolean waitingForPeers = !waitingFor.isEmpty();
        boolean waitingForPoint = false;
        if (!waitingForPeers && rule.holds()) {
            waitingForPoint = !releasePoints.isOpened(member.getRunId(), rule.holdReleaseKey());
        }
        if (waitingForPeers || waitingForPoint) {
            if (heldTooLong(member, policy)) {
                // 兜底：压过时限还没人放行就照常接结果，读数里单独记一笔，别把「没人放行」当成「被放行」。
                heldSinceByMember.remove(key);
                log.warn("夹具策略压住这条成员超过兜底时限，照常接结果：group={} member={} 等兄弟={} 等放行点={}",
                        group.getId(), key, String.join(",", waitingFor), releaseKey);
                return new PolicyAction(false, PolicyOutcome.holdTimeout());
            }
            String reason = waitingForPeers
                    ? "waiting_for:" + String.join(",", waitingFor)
                    : "waiting_release_point:" + releaseKey;
            return hold(member, rule, waitingForPeers
                    ? FixtureRuleHitStore.APPLIED_PEER : FixtureRuleHitStore.APPLIED_HOLD, reason);
        }
        heldSinceByMember.remove(key);
        return PolicyAction.proceed();
    }

    /** 这条成员身上的规则（按选择器匹配，一个等待组里一条成员最多被一条规则点名）。 */
    private Optional<AcceptanceReleasePolicy.Rule> ruleFor(AcceptanceReleasePolicy policy,
                                                          WaitGroup group,
                                                          WaitMember member) {
        List<WaitMember> members = waitGroupStore.listMembers(group.getId());
        Optional<AcceptanceReleasePolicy.Rule> matched =
                policy.ruleAt(policy.match(groupFacts(group, members)), memberFactsOf(group, member));
        if (matched.isPresent()) {
            return matched;
        }
        return recordedHitRule(policy, group, member);
    }

    /**
     * 派发时已经按 {@code uniqueExternalTask} 绑过的命中：结果接收方没有参数正文，按命中行找回。
     */
    private Optional<AcceptanceReleasePolicy.Rule> recordedHitRule(AcceptanceReleasePolicy policy,
                                                                  WaitGroup group,
                                                                  WaitMember member) {
        List<FixtureRuleHitStore.RuleHit> hits = ruleHitStore.hitsOf(member.getRunId());
        if (hits == null || hits.isEmpty()) {
            return Optional.empty();
        }
        for (FixtureRuleHitStore.RuleHit hit : hits) {
            if (hit.groupId() != group.getId()) {
                continue;
            }
            if (hit.target() == null || hit.target().memberSeq() != member.getMemberSeq()) {
                continue;
            }
            for (AcceptanceReleasePolicy.Rule rule : policy.rules()) {
                if (rule.index() == hit.ruleIndex()) {
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }

    /** 这个等待组里的成员在策略眼里的样子：组一级的身份来自等待组，成员一级来自成员记录。 */
    private static List<AcceptanceReleasePolicy.MemberFacts> groupFacts(WaitGroup group,
                                                                       List<WaitMember> members) {
        List<AcceptanceReleasePolicy.MemberFacts> facts = new ArrayList<>();
        for (WaitMember member : members) {
            facts.add(memberFactsOf(group, member));
        }
        return facts;
    }

    private static AcceptanceReleasePolicy.MemberFacts memberFactsOf(WaitGroup group, WaitMember member) {
        return new AcceptanceReleasePolicy.MemberFacts(group.getPlanGeneration(), group.getNodeId(),
                group.getNodeAttempt(), group.getSegmentSequence(), group.getModelTurn(),
                member.getMemberSeq(), member.getToolCallId(),
                member.getExternalOperationId(), member.getToolName(), null);
    }

    /** 这个等待组里与某条成员身份对上的那一行；对不上时为空。 */
    private static WaitMember memberOf(WaitGroup group,
                                       List<WaitMember> members,
                                       AcceptanceReleasePolicy.MemberFacts facts) {
        for (WaitMember member : members) {
            if (memberFactsOf(group, member).equals(facts)) {
                return member;
            }
        }
        return null;
    }

    /**
     * 记一笔「这条规则真的打中了这条成员」。
     *
     * <p>同一件事只往库里记一次：这条成员被压住时会一轮一轮地走到这里，每一轮都写一遍没有意义。
     * 记失败时不留下「已经记过」的记号，下一轮再试。返回 false 表示这条规则已经绑了别人：
     * {@code uniqueExternalTask} 越界命中，调用方应照常接结果，不要压住也不要改写成失败。
     * {@code allExternalTasks} 的其余成员返回 true，动作仍按内存命中生效。</p>
     */
    private boolean recordRuleHit(WaitGroup group, WaitMember member, AcceptanceReleasePolicy.Rule rule) {
        String key = member.getRunId() + "|" + rule.index() + "|" + group.getId() + "|" + member.getMemberSeq();
        if (overHitRuleMembers.contains(key)) {
            return false;
        }
        if (!recordedRuleHits.add(key)) {
            return true;
        }
        try {
            FixtureRuleHitStore.MatchBinding binding = ruleHitStore.recordMatch(member.getRunId(), rule,
                    group.getId(), memberFactsOf(group, member));
            if (binding == FixtureRuleHitStore.MatchBinding.ALREADY_BOUND_OTHER) {
                if (AcceptanceReleasePolicy.MATCH_ALL_EXTERNAL_TASKS.equals(rule.match())) {
                    return true;
                }
                AcceptanceReleasePolicy.MemberFacts bound = ruleHitStore.hitOf(member.getRunId(), rule.index())
                        .map(FixtureRuleHitStore.RuleHit::target)
                        .orElse(null);
                ruleHitStore.recordOverHit(member.getRunId(), rule, memberFactsOf(group, member), bound);
                overHitRuleMembers.add(key);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            recordedRuleHits.remove(key);
            throw e;
        }
    }

    /**
     * 压住这条成员：只推下次查询时间，不动退避计数；真的推后了就在读数里记一笔。
     *
     * <p>推后成功这一刻才算「点名的动作落到了这条成员身上」，所以证据也在这一刻写：成员已经不是
     * 执行中时这条语句一行都不改，那次不算压住，动作结果也就不会记成生效。</p>
     */
    private PolicyAction hold(WaitMember member,
                             AcceptanceReleasePolicy.Rule rule,
                             String appliedKind,
                             String reason) {
        heldSinceByMember.putIfAbsent(memberKey(member),
                new HeldSince(member.getRunId(), OffsetDateTime.now()));
        boolean pushed = waitGroupStore.holdMember(member.getGroupId(), member.getMemberIdentity(),
                OffsetDateTime.now().plus(Duration.ofMillis(HOLD_POLL_DELAY_MS)));
        if (pushed) {
            // 只记真的推后的那几次：成员已经不是执行中时这条语句一行都不改，那不是一次压住。
            holdPushes.incrementAndGet();
            ruleHitStore.recordAction(member.getRunId(), rule.index(), appliedKind, reason);
        }
        log.debug("夹具策略压住这条成员，等放行：group={} member={} reason={} pushed={}",
                member.getGroupId(), member.getMemberIdentity(), reason, pushed);
        return PolicyAction.held();
    }

    /** 一条被压住的成员：属于哪条 Run、从哪一刻起被压。 */
    private record HeldSince(String runId, OffsetDateTime since) {
    }

    /**
     * 压住的时间超过策略给的兜底时限了吗；策略没写兜底就一直压着。
     *
     * <p>计时从本进程第一次压住这条成员那一刻算起，压在库里的只有「下次查询时间」——进程重启会从零
     * 重新计时。夹具的压住是一场验收里的短时行为，兜底是防止没人放行时整条链停在那里；真要卡
     * 「压了多久」的硬上限，起点就得写进库里，那是另一件事。</p>
     */
    private boolean heldTooLong(WaitMember member, AcceptanceReleasePolicy policy) {
        if (policy.maxHoldSeconds() <= 0) {
            return false;
        }
        HeldSince held = heldSinceByMember.computeIfAbsent(memberKey(member),
                key -> new HeldSince(member.getRunId(), OffsetDateTime.now()));
        return held.since().plusSeconds(policy.maxHoldSeconds()).isBefore(OffsetDateTime.now());
    }

    /** Run 走到终态就把它的压住计时与「已经记过的命中」放掉；没有这条 Run 的记载就是空动作。 */
    @EventListener
    public void onRunFinalized(AgentRunFinalizedEvent event) {
        if (event == null) {
            return;
        }
        heldSinceByMember.entrySet()
                .removeIf(entry -> entry.getValue().runId().equals(event.runId()));
        String prefix = event.runId() + "|";
        recordedRuleHits.removeIf(key -> key.startsWith(prefix));
        overHitRuleMembers.removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 写成员终态并把放行交给恢复分发器。
     *
     * <p>三种「按失败记」的来源有先后：{@code refusal}（夹具策略拒绝，例如点名了组里没有的成员）
     * 优先，其次是夹具点名按失败收尾，最后才是沙箱自己给的失败。夹具点名的是「这次调用按失败算」，
     * 而这条成员本来就已经失败时留真实原因、把夹具那句另记一处——把真因覆盖掉，排查时会误以为是
     * 夹具把它弄失败的。</p>
     *
     * <p>用量记录按沙箱自己的终态写（见 {@link WaitMemberSettlement#settle}）：夹具点名失败只是
     * 验收要看的结果，这一次外部调用真的发生过、真的占了名额，账要照实记。</p>
     */
    private void finish(WaitMember member,
                        WaitGroup group,
                        NodeWorkItem segment,
                        AgentRun run,
                        WaitMemberDispatchProof proof,
                        Terminal terminal,
                        AcceptanceReleasePolicy policy,
                        PolicyOutcome outcome) {
        ForcedFailure refusal = outcome == null ? null : outcome.refusal();
        String output = pythonSandboxTools.formatTerminalResult(
                terminal.statusName(), terminal.result());
        // 结果太大时载荷会把它改写成失败：成员行也跟着落失败，两处结论必须一致。
        boolean success = SUCCEEDED.equals(terminal.statusName())
                && terminal.result().getExitCode() == 0
                && !WaitMemberResultPayload.tooLarge(output, maxMemberResultChars);
        boolean failedAlready = !success;
        boolean designatedApplied = false;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("taskId", terminal.taskId());
        AcceptanceReleasePolicy.Rule designatedRule = policy == null
                ? null
                : ruleFor(policy, group, member)
                        .filter(AcceptanceReleasePolicy.Rule::fails)
                        .orElse(null);
        String designated = designatedRule == null ? null : designatedRule.failureDetail();
        if (refusal != null) {
            success = false;
            extra.put("errorCode", refusal.code());
            extra.put("errorDetail", refusal.detail());
        } else if (designated != null) {
            // 夹具点名这条成员按失败收尾：照常走失败路径（名额与用量照旧结算），
            // 已经把结果拿回来的话也把结果留在成员行里，失败原因单独写清楚。
            success = false;
            designatedApplied = true;
            if (failedAlready) {
                extra.put("errorCode", errorCodeOf(terminal.statusName()));
                extra.put("designatedFailure", designated);
            } else {
                extra.put("errorCode", AcceptanceReleasePolicy.DESIGNATED_FAILURE_CODE);
                extra.put("errorDetail", designated);
            }
        } else if (failedAlready) {
            extra.put("errorCode", errorCodeOf(terminal.statusName()));
        }
        String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                member.getToolCallId(), success, output, extra, maxMemberResultChars);

        // 先把这次后台作业的账收干净再写成员终态：名额还回去、用量记下来。收尾没成时这条成员
        // 保持执行中，下一轮拿同一份证明重来（两步都是幂等的），绝不出现「成员已经落终态、
        // 名额还挂在账上」这种没人会再回来处理的状态。
        WaitMemberSettlement.Outcome settled = settlement.settle(member, proof,
                terminal.statusName(), terminal.result(),
                output, terminal.finishedAt());
        if (!settled.ok()) {
            settlementFailures.incrementAndGet();
            log.warn("成员的结果已经拿到，但名额与用量还没收干净，先把这条成员推后：member={} reason={}",
                    member.getMemberIdentity(), settled.reason());
            defer(member, OffsetDateTime.now(), "settlement:" + settled.reason());
            return;
        }

        MemberCompletionResult result = persistMemberCompletion(new MemberCompletionRequest(
                member.getGroupId(),
                member.getMemberIdentity(),
                success ? WaitMemberState.SUCCEEDED : WaitMemberState.FAILED,
                resultJson,
                member.getExternalOperationId(),
                group.getPlanGeneration(),
                segment.getContextVersion(),
                run.getRunControlVersion()), member, terminal.taskId());
        // 这条成员有结论了，压住的计时不再需要。成员终态只落一次，这一条在上面那条语句返回 0 行时
        // 也照样清掉：那时它已经在别处落过终态，计时留着只会白占内存。
        heldSinceByMember.remove(memberKey(member));
        if (!result.applied()) {
            // 已经被别人写过（重复上报、或者这条成员已经落过终态）：不重复放行下一段。
            duplicates.incrementAndGet();
            log.info("这个成员的结果没有写进去（多半已经落过终态）：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity());
            return;
        }
        completed.incrementAndGet();
        // 策略定下的事到这里才真的发生过一次：决策处计数会把收尾失败后的重试也记成新的一次。
        if (refusal != null) {
            policyRefusals.incrementAndGet();
        }
        if (outcome != null && outcome.holdTimeoutRelease()) {
            releasedOnHoldTimeout.incrementAndGet();
        }
        if (designatedApplied && designatedRule != null) {
            designatedFailures.incrementAndGet();
            // 指定失败的终态真的写进去了：这一刻才算「这条规则的动作落到了点名的成员身上」。
            ruleHitStore.recordAction(group.getRunId(), designatedRule.index(),
                    FixtureRuleHitStore.APPLIED_FAILURE, "按失败收尾：" + designated);
        }
        log.info("等待成员结果已接回：group={} member={} seq={} state={} 报告={}",
                member.getGroupId(), member.getMemberIdentity(), member.getMemberSeq(),
                result.memberState(), terminal.describe());
        if (result.groupBecameReady()) {
            // 组齐备了：叫一次恢复分发器，让它尽快把下一段放出去。提醒丢了也不要紧，
            // 它自己的周期补扫与启动扫描会按数据库把这条通知重新发现。
            recoveryDispatcher.wake(result.notificationId());
            wakeups.incrementAndGet();
        }
    }

    /**
     * 先按工具原文写入；写成 jsonb 失败时改用短失败载荷再写一次，让等待组仍能齐备。
     */
    private MemberCompletionResult persistMemberCompletion(MemberCompletionRequest request, WaitMember member,
                                                           String taskId) {
        try {
            return waitGroupStore.completeMember(request);
        } catch (RuntimeException e) {
            log.error("成员结果没能写入等待组，改用短失败载荷再写一次：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity(), e);
            String compact = WaitMemberResultPayload.compactPersistFailure(
                    objectMapper, member.getToolName(), member.getToolCallId(), e.getMessage(), taskId);
            MemberCompletionRequest fallback = new MemberCompletionRequest(
                    request.groupId(),
                    request.memberIdentity(),
                    WaitMemberState.FAILED,
                    compact,
                    request.externalOperationId(),
                    request.planGeneration(),
                    request.contextVersion(),
                    request.runControlVersion());
            try {
                return waitGroupStore.completeMember(fallback);
            } catch (RuntimeException retry) {
                e.addSuppressed(retry);
                throw e;
            }
        }
    }

    private static String errorCodeOf(String statusName) {
        if (statusName == null) {
            return "PYTHON_EXECUTION_FAILED";
        }
        if (CANCELED.equals(statusName)) {
            return "PYTHON_EXECUTION_CANCELED";
        }
        if (RESULT_LOST.equals(statusName)) {
            return "PYTHON_RESULT_LOST";
        }
        return "PYTHON_EXECUTION_FAILED";
    }

    /** 到点的成员还没结论：按退避推后下次查询时间。 */
    private void defer(WaitMember member, OffsetDateTime now, String reason) {
        long memberId = member.getId() == null ? 0L : member.getId();
        OffsetDateTime nextVisibleAt = backoff().nextVisibleAt(now, memberId, member.getCreatedAt());
        boolean pushed = waitGroupStore.rescheduleMember(member.getGroupId(), member.getMemberIdentity(),
                nextVisibleAt, settings.memberReceiverMaxBackoffStep().intValue());
        deferred.incrementAndGet();
        if (pushed) {
            log.debug("成员结果这一轮还没结论，推后到 {}：group={} member={} reason={}",
                    nextVisibleAt, member.getGroupId(), member.getMemberIdentity(), reason);
        } else {
            log.debug("成员已经不是执行中，推后不生效（多半刚落了终态）：group={} member={} reason={}",
                    member.getGroupId(), member.getMemberIdentity(), reason);
        }
    }

    /** 说不清归属或者拿不到证明：不动它，只留一条能查的记录，并把它推远一点，别每一轮都来问。 */
    private void isolate(WaitMember member, OffsetDateTime now, String reason) {
        isolated.incrementAndGet();
        log.warn("这条等待成员没法核对归属，先不接它的结果：group={} member={} reason={}",
                member.getGroupId(), member.getMemberIdentity(), reason);
        // 这一轮没走到放行判断，压住计时就不会自己清；Run 被取消、组没了的成员以后也不会再进扫描，
        // 留着只会让「当前被压住几条」这个读数一直虚高，所以在隔离这一步也清一次。
        heldSinceByMember.remove(memberKey(member));
        defer(member, now, reason);
    }

    /** 按外部作业身份回查沙箱任务号：三种结论分开，只有权威的「没建出来」才当失败。 */
    private TaskLookup lookupByOperation(WaitMemberDispatchProof proof) {
        String operationId = proof.operationId();
        try {
            GetTaskByOperationIdResponse lookup = sandboxService.getTaskByOperationId(
                    GetTaskByOperationIdRequest.newBuilder().setOperationId(operationId).build());
            if (lookup == null || lookup.hasErrorDetail() || !lookup.getError().isBlank()) {
                return new TaskLookup(null, false);
            }
            if (lookup.getFound()) {
                if (lookup.getTaskId().isBlank()
                        || !proof.requestFingerprint().equals(lookup.getRequestFingerprint())) {
                    log.error("按外部作业身份回查的 Sandbox 身份不一致：operationId={}", operationId);
                    return new TaskLookup(null, false);
                }
                return new TaskLookup(lookup.getTaskId(), false);
            }
            // Sandbox 合同只把 found=false、无 errorDetail、无错误文本认作权威不存在。
            // found=false 却带任务身份是矛盾响应，不能据此归还容量。
            return new TaskLookup(null, lookup.getTaskId().isBlank()
                    && lookup.getRequestFingerprint().isBlank());
        } catch (Exception e) {
            log.warn("按外部作业身份回查沙箱任务暂时不可用：operationId={} reason={}",
                    operationId, e.getMessage());
            return new TaskLookup(null, false);
        }
    }

    private String tombstoneAbsentOperation(WaitMember member, WaitMemberDispatchProof proof) {
        if (member.getId() == null) return null;
        try {
            CancelTaskResponse cancel = sandboxService.cancelTask(CancelTaskRequest.newBuilder()
                    .setByOperation(OperationCancelTarget.newBuilder()
                            .setOperationId(proof.operationId())
                            .setRequestFingerprint(proof.requestFingerprint()))
                    .setCancelRequestId("wait-member-" + member.getId())
                    .setReason("CREATE_RESULT_UNCERTAIN")
                    .build());
            if (cancel == null || cancel.hasErrorDetail() || !cancel.getError().isBlank()
                    || cancel.getOutcome() == CancelOutcome.NOT_FOUND
                    || cancel.getOutcome() == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED
                    || cancel.getTaskId().isBlank()) return null;
            TaskLookup verified = lookupByOperation(proof);
            return cancel.getTaskId().equals(verified.taskId()) ? verified.taskId() : null;
        } catch (RuntimeException e) {
            log.warn("创建结果不确定时写 Sandbox 取消墓碑失败：member={} reason={}",
                    member.getId(), e.getMessage());
            return null;
        }
    }

    private TaskStatusResponse queryStatus(String taskId) {
        try {
            return sandboxService.getTaskStatus(
                    GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
        } catch (Exception e) {
            log.warn("查沙箱任务状态暂时不可用：taskId={} reason={}", taskId, e.getMessage());
            return null;
        }
    }

    private TaskResultResponse fetchResult(String taskId, String runId, String expectedStatus) {
        try {
            // 只在已知终态后拉结果，减少大响应。校验器核对任务号与终态，错配的结果不往成员行里写。
            TaskResultResponse resp = sandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            return ToolJobResultValidator.validate(taskId, runId, resp, expectedStatus);
        } catch (Exception e) {
            log.warn("拉沙箱任务结果失败：taskId={} reason={}", taskId, e.getMessage());
            return null;
        }
    }

    private static boolean terminal(String statusName) {
        return SUCCEEDED.equals(statusName) || "FAILED".equals(statusName)
                || CANCELED.equals(statusName) || RESULT_LOST.equals(statusName);
    }

    /** 一次已经确认终态的外部作业：任务号、终态名和结果体。 */
    private record Terminal(String taskId, String statusName, TaskResultResponse result,
                            String finishedAt) {
        private String describe() {
            return "task=" + taskId + " status=" + statusName;
        }
    }

    /** 按外部作业身份回查的三种结论：taskId、权威「没建出来」、暂时问不到。 */
    private record TaskLookup(String taskId, boolean notFound) {
    }

    /** 结果接收的读数：收了多少轮、问到多少成员、接回多少、推后多少、隔离多少。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("waitMemberReceiverRounds", rounds.get());
        snapshot.put("waitMemberReceiverScannedTotal", scanned.get());
        snapshot.put("waitMemberReceiverCompletedTotal", completed.get());
        snapshot.put("waitMemberReceiverDeferredTotal", deferred.get());
        snapshot.put("waitMemberReceiverDuplicateTotal", duplicates.get());
        snapshot.put("waitMemberReceiverIsolatedTotal", isolated.get());
        snapshot.put("waitMemberReceiverWakeupsTotal", wakeups.get());
        snapshot.put("waitMemberReceiverSettlementFailuresTotal", settlementFailures.get());
        snapshot.put("waitMemberReceiverFailuresTotal", failures.get());
        snapshot.put("waitMemberReceiverBatchSize", settings.memberReceiverBatchSize().value());
        snapshot.put("waitMemberReceiverPollIntervalMs", pollIntervalMs);
        snapshot.put("waitMemberReceiverBackoff", backoff().describe());
        snapshot.put("waitMemberReceiverHoldPushesTotal", holdPushes.get());
        snapshot.put("waitMemberReceiverHeldNow", heldSinceByMember.size());
        snapshot.put("waitMemberReceiverReleasedOnHoldTimeoutTotal", releasedOnHoldTimeout.get());
        snapshot.put("waitMemberReceiverDesignatedFailuresTotal", designatedFailures.get());
        snapshot.put("waitMemberReceiverPolicyRefusalsTotal", policyRefusals.get());
        return snapshot;
    }
}
