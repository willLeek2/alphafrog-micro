package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
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
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePointStore;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
import world.willfrog.agentlangchain.acceptance.AcceptanceRunPolicyRegistry;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.control.dualpool.RecoveryBackoff;
import world.willfrog.agentlangchain.execution.WaitMemberResultPayload;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** 按外部作业身份回查、权威地说「这个后台作业不存在」时给成员写的错误码。 */
    private static final String TASK_NOT_FOUND = "wait_member_task_not_found";
    /** 夹具策略点了同一个等待组里不存在的成员时写的错误码。 */
    private static final String POLICY_PEER_UNKNOWN = "acceptance_fixture_policy_peer_unknown";

    private final WaitGroupStore waitGroupStore;
    private final AgentRunMapper runMapper;
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

    /** 每压住一轮记一次（同一条成员被压住多轮就记多笔），不是「压住过几条成员」。 */
    private final AtomicLong holdPushes = new AtomicLong();
    private final AtomicLong releasedOnHoldTimeout = new AtomicLong();
    private final AtomicLong designatedFailures = new AtomicLong();
    private final AtomicLong policyRefusals = new AtomicLong();
    /** 被压住的成员从哪一刻起被压：兜底放行按这个时刻算；不再被压时清掉，只留正在压的那些。 */
    private final Map<String, OffsetDateTime> heldSinceByMember = new ConcurrentHashMap<>();

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
            AgentRunMapper runMapper,
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
            AcceptanceReleasePointStore releasePoints) {
        this.waitGroupStore = waitGroupStore;
        this.runMapper = runMapper;
        this.nodeWorkItemStore = nodeWorkItemStore;
        this.sandboxService = sandboxService;
        this.pythonSandboxTools = pythonSandboxTools;
        this.settlement = settlement;
        this.recoveryDispatcher = recoveryDispatcher;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.acceptancePolicies = acceptancePolicies;
        this.releasePoints = releasePoints;
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
        List<WaitMember> due = waitGroupStore.scanDueMembers(now,
                settings.memberReceiverBatchSize().intValue());
        scanned.addAndGet(due.size());
        int handled = 0;
        for (WaitMember member : due) {
            try {
                collect(member, now);
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
    private void collect(WaitMember member, OffsetDateTime now) {
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

        AgentRun run = runMapper.findById(member.getRunId());
        if (run == null) {
            isolate(member, now, "run_missing");
            return;
        }
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
        Optional<AcceptanceReleasePolicy> policy = acceptancePolicies.policyForRun(run);
        if (policy.isPresent() && holdByPolicy(policy.get(), member, group, segment, run, proof)) {
            return;
        }

        String taskId = proof.taskId();
        if (taskId == null) {
            TaskLookup lookup = lookupByOperation(proof.operationId());
            if (lookup.taskId() == null) {
                if (lookup.notFound()) {
                    // 权威地说「没建出来」：这次后台作业不存在，成员按失败落终态，
                    // 否则等待链会一直等一个永远不会有的结果。
                    finish(member, group, segment, run, proof, null, TASK_NOT_FOUND, "task_not_found",
                            policy.orElse(null));
                    return;
                }
                defer(member, now, "task_lookup_unavailable");
                return;
            }
            taskId = lookup.taskId();
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
        finish(member, group, segment, run, proof, new Terminal(taskId, statusName, result), null, null,
                policy.orElse(null));
    }

    /**
     * 这条成员现在该不该被夹具的放行策略压住。
     *
     * <p>压住 = 这一轮不去接它的结果：成员保持执行中，只把下次查询时间推一小步，下一轮再看。
     * 两种放行条件：等同一个等待组里的另外几条成员先落终态，或者等某个放行点被控制面标成已放行。
     * 成员没有被点名时立刻放行；策略点了这个组里不存在的成员，按成员失败收尾并把原因写清楚——
     * 那种情况是夹具写错了，压住不动只会让人以为结果还没回来。</p>
     *
     * @return true 表示这一轮已经处理完这条成员（压住、或者按策略让它失败）
     */
    private boolean holdByPolicy(AcceptanceReleasePolicy policy,
                                 WaitMember member,
                                 WaitGroup group,
                                 NodeWorkItem segment,
                                 AgentRun run,
                                 WaitMemberDispatchProof proof) {
        String toolCallId = member.getToolCallId();
        String memberKey = member.getMemberIdentity();
        if (toolCallId == null || toolCallId.isBlank() || !policy.covers(toolCallId)) {
            heldSinceByMember.remove(memberKey);
            return false;
        }
        List<String> waitingFor = new ArrayList<>();
        List<String> unknownPeers = new ArrayList<>();
        collectWaitingPeers(policy.releaseAfter(toolCallId), group, waitingFor, unknownPeers);
        if (!unknownPeers.isEmpty()) {
            policyRefusals.incrementAndGet();
            log.warn("夹具策略点了这个等待组里没有的成员，这条成员按失败收尾：group={} member={} peers={}",
                    group.getId(), memberKey, String.join(",", unknownPeers));
            finish(member, group, segment, run, proof, null, POLICY_PEER_UNKNOWN,
                    "策略里点名的成员不在这个等待组里：" + String.join(",", unknownPeers), policy);
            return true;
        }
        if (!waitingFor.isEmpty()) {
            return hold(member, "waiting_for:" + String.join(",", waitingFor));
        }
        Optional<String> releaseKey = policy.releasePointKey(toolCallId);
        if (releaseKey.isPresent() && !releasePoints.isOpened(run.getId(), releaseKey.get())) {
            if (heldTooLong(member, memberKey, policy)) {
                // 兜底：压过时限还没人放行就照常收尾，读数里单独记一笔，别把「没人放行」当成「被放行」。
                releasedOnHoldTimeout.incrementAndGet();
                heldSinceByMember.remove(memberKey);
                log.warn("夹具策略压住这条成员超过兜底时限，照常收尾：group={} member={} key={}",
                        group.getId(), memberKey, releaseKey.get());
                return false;
            }
            return hold(member, "waiting_release_point:" + releaseKey.get());
        }
        heldSinceByMember.remove(memberKey);
        return false;
    }

    /** 按名字找出「还没落终态」的成员，以及这个组里根本没有的名字。 */
    private void collectWaitingPeers(List<String> names,
                                     WaitGroup group,
                                     List<String> waitingFor,
                                     List<String> unknownPeers) {
        if (names.isEmpty()) {
            return;
        }
        Map<String, WaitMemberState> statesByToolCallId = new LinkedHashMap<>();
        for (WaitMember peer : waitGroupStore.listMembers(group.getId())) {
            if (peer.getToolCallId() != null && !peer.getToolCallId().isBlank()) {
                statesByToolCallId.put(peer.getToolCallId(), peer.stateEnum());
            }
        }
        for (String name : names) {
            WaitMemberState state = statesByToolCallId.get(name);
            if (state == null) {
                unknownPeers.add(name);
            } else if (state != WaitMemberState.SUCCEEDED && state != WaitMemberState.FAILED) {
                waitingFor.add(name);
            }
        }
    }

    /** 压住这条成员：只推下次查询时间，不动退避计数；读数里记一笔。 */
    private boolean hold(WaitMember member, String reason) {
        heldSinceByMember.putIfAbsent(member.getMemberIdentity(), OffsetDateTime.now());
        boolean pushed = waitGroupStore.holdMember(member.getGroupId(), member.getMemberIdentity(),
                OffsetDateTime.now().plus(Duration.ofMillis(pollIntervalMs)));
        holdPushes.incrementAndGet();
        log.debug("夹具策略压住这条成员，等放行：group={} member={} reason={} pushed={}",
                member.getGroupId(), member.getMemberIdentity(), reason, pushed);
        return true;
    }

    /**
     * 压住的时间超过策略给的兜底时限了吗；策略没写兜底就一直压着。
     *
     * <p>计时从本进程第一次压住这条成员那一刻算起，压在库里的只有「下次查询时间」——进程重启会从零
     * 重新计时。夹具的压住是一场验收里的短时行为，兜底是防止没人放行时整条链停在那里；真要卡
     * 「压了多久」的硬上限，起点就得写进库里，那是另一件事。</p>
     */
    private boolean heldTooLong(WaitMember member, String memberKey, AcceptanceReleasePolicy policy) {
        if (policy.maxHoldSeconds() <= 0) {
            return false;
        }
        OffsetDateTime since = heldSinceByMember.computeIfAbsent(memberKey, key -> OffsetDateTime.now());
        return since.plusSeconds(policy.maxHoldSeconds()).isBefore(OffsetDateTime.now());
    }

    /**
     * 写成员终态并把放行交给恢复分发器。
     *
     * <p>{@code terminal} 为空表示「作业本身不存在」这一种结论：这时没有结果体，成员按失败记，
     * 错误码由 {@code failureCode} 给。两种情形用的是同一条写入语句，归属核对也同一份。</p>
     */
    private void finish(WaitMember member,
                        WaitGroup group,
                        NodeWorkItem segment,
                        AgentRun run,
                        WaitMemberDispatchProof proof,
                        Terminal terminal,
                        String failureCode,
                        String reason,
                        AcceptanceReleasePolicy policy) {
        String output = terminal == null ? "" : pythonSandboxTools.formatTerminalResult(
                terminal.statusName(), terminal.result());
        // 结果太大时载荷会把它改写成失败：成员行也跟着落失败，两处结论必须一致。
        boolean success = terminal != null && SUCCEEDED.equals(terminal.statusName())
                && terminal.result().getExitCode() == 0
                && !WaitMemberResultPayload.tooLarge(output, maxMemberResultChars);
        Map<String, Object> extra = new LinkedHashMap<>();
        if (terminal != null) {
            extra.put("taskId", terminal.taskId());
        }
        String designated = policy == null || member.getToolCallId() == null
                ? null : policy.designatedFailure(member.getToolCallId()).orElse(null);
        if (designated != null) {
            // 夹具点名这条成员按失败收尾：照常走失败路径（名额与用量照旧结算），
            // 已经把结果拿回来的话也把结果留在成员行里，失败原因单独写清楚。
            success = false;
            designatedFailures.incrementAndGet();
            extra.put("errorCode", AcceptanceReleasePolicy.DESIGNATED_FAILURE_CODE);
            extra.put("errorDetail", designated);
        } else if (!success) {
            extra.put("errorCode", failureCode != null ? failureCode : errorCodeOf(terminal.statusName()));
            if (reason != null) {
                extra.put("errorDetail", reason);
            }
        }
        String resultJson = WaitMemberResultPayload.encode(objectMapper, member.getToolName(),
                member.getToolCallId(), success, output, extra, maxMemberResultChars);

        // 先把这次后台作业的账收干净再写成员终态：名额还回去、用量记下来。收尾没成时这条成员
        // 保持执行中，下一轮拿同一份证明重来（两步都是幂等的），绝不出现「成员已经落终态、
        // 名额还挂在账上」这种没人会再回来处理的状态。
        WaitMemberSettlement.Outcome settled = settlement.settle(member, proof,
                terminal == null ? null : terminal.statusName(),
                terminal == null ? null : terminal.result(),
                output);
        if (!settled.ok()) {
            settlementFailures.incrementAndGet();
            log.warn("成员的结果已经拿到，但名额与用量还没收干净，先把这条成员推后：member={} reason={}",
                    member.getMemberIdentity(), settled.reason());
            defer(member, OffsetDateTime.now(), "settlement:" + settled.reason());
            return;
        }

        MemberCompletionResult result = waitGroupStore.completeMember(new MemberCompletionRequest(
                member.getGroupId(),
                member.getMemberIdentity(),
                success ? WaitMemberState.SUCCEEDED : WaitMemberState.FAILED,
                resultJson,
                member.getExternalOperationId(),
                group.getPlanGeneration(),
                segment.getContextVersion(),
                run.getRunControlVersion()));
        // 这条成员有结论了，压住的计时不再需要。成员终态只落一次，这一条在上面那条语句返回 0 行时
        // 也照样清掉：那时它已经在别处落过终态，计时留着只会白占内存。
        heldSinceByMember.remove(member.getMemberIdentity());
        if (!result.applied()) {
            // 已经被别人写过（重复上报、或者这条成员已经落过终态）：不重复放行下一段。
            duplicates.incrementAndGet();
            log.info("这个成员的结果没有写进去（多半已经落过终态）：group={} member={}",
                    member.getGroupId(), member.getMemberIdentity());
            return;
        }
        completed.incrementAndGet();
        log.info("等待成员结果已接回：group={} member={} seq={} state={} 报告={}",
                member.getGroupId(), member.getMemberIdentity(), member.getMemberSeq(),
                result.memberState(), terminal == null ? reason : terminal.describe());
        if (result.groupBecameReady()) {
            // 组齐备了：叫一次恢复分发器，让它尽快把下一段放出去。提醒丢了也不要紧，
            // 它自己的周期补扫与启动扫描会按数据库把这条通知重新发现。
            recoveryDispatcher.wake(result.notificationId());
            wakeups.incrementAndGet();
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
        defer(member, now, reason);
    }

    /** 按外部作业身份回查沙箱任务号：三种结论分开，只有权威的「没建出来」才当失败。 */
    private TaskLookup lookupByOperation(String operationId) {
        try {
            GetTaskByOperationIdResponse lookup = sandboxService.getTaskByOperationId(
                    GetTaskByOperationIdRequest.newBuilder().setOperationId(operationId).build());
            String taskId = lookup.getTaskId();
            if (taskId != null && !taskId.isBlank()) {
                return new TaskLookup(taskId, false);
            }
            return new TaskLookup(null, true);
        } catch (Exception e) {
            log.warn("按外部作业身份回查沙箱任务暂时不可用：operationId={} reason={}",
                    operationId, e.getMessage());
            return new TaskLookup(null, false);
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
    private record Terminal(String taskId, String statusName, TaskResultResponse result) {
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
