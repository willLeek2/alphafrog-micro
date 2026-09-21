package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.platform.wait.LateMemberRequest;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.MemberCompletionResult;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryNotificationState;
import world.willfrog.agent.platform.wait.RecoveryRejection;
import world.willfrog.agent.platform.wait.WaitChainCancelResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupIdentity;
import world.willfrog.agent.platform.wait.WaitGroupState;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDraft;
import world.willfrog.agent.platform.wait.WaitMemberIdentity;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.wait.WaitSuspensionOutcome;
import world.willfrog.agent.platform.wait.WaitSuspensionRequest;
import world.willfrog.agent.platform.wait.WaitSuspensionResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内存版等待组存储，只服务节点执行器的本机自检。
 *
 * <p>它照抄真存储里那几条语义：整组挂起只在分段还归调用方所有时才写、成员只结束一次、
 * 计数加满才写出恢复通知、恢复消费只对同一代际成功一次。这样执行器的行为（先落库再派发、
 * 按原始序号接回结果、连续多个等待组）可以在本机跑出结论。</p>
 *
 * <p>它不能替代真库证据：并发条件更新、唯一约束与锁顺序只在真 PostgreSQL 上成立。
 * 那一份证据由 {@code Stage3WaitContractPostgresTest} 在给了外部连接串的环境里回答。</p>
 */
class InMemoryWaitGroupStore implements WaitGroupStore {

    static final class FakeMember {
        long groupId;
        String runId;
        int memberSeq;
        String memberIdentity;
        String toolCallId;
        String toolName;
        String externalOperationId;
        String state = WaitMemberState.PENDING.name();
        String resultRefJson;
        String dispatchProofJson;
        OffsetDateTime finishedAt;
        OffsetDateTime nextPollAt;
        int pollCount;
        int backoffStep;
        /** 派发顺序记录：用例据此核对「先落库、后派发、按原始序号派发」。 */
        final List<String> timeline = new ArrayList<>();
    }

    static final class FakeGroup {
        long id;
        WaitGroupIdentity identity;
        String state = WaitGroupState.WAITING.name();
        int expectedMembers;
        int completedMembers;
        int recoveryGeneration;
        int nextSegmentSequence;
        OffsetDateTime readyAt;
    }

    private final Map<Long, FakeGroup> groups = new LinkedHashMap<>();
    private final Map<Long, List<FakeMember>> members = new LinkedHashMap<>();
    private final Map<Long, RecoveryNotification> notifications = new LinkedHashMap<>();
    /** 已经交出去的分段身份 → 下一段载荷。 */
    private final Map<String, String> handedOverSegments = new LinkedHashMap<>();
    /** 已经被放成可恢复的分段身份。 */
    private final Set<String> promotedSegments = new LinkedHashSet<>();
    private long sequence;

    /** 派发那一刻的回调：用例在这里检查库里已经有什么。 */
    private final List<String> events = new ArrayList<>();

    List<String> events() {
        return List.copyOf(events);
    }

    String nextSegmentPayload(WaitGroupIdentity identity) {
        return handedOverSegments.get(key(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), identity.modelTurn()));
    }

    boolean promoted(WaitGroupIdentity identity) {
        return promotedSegments.contains(key(identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), identity.modelTurn()));
    }

    List<FakeMember> memberRows(long groupId) {
        return List.copyOf(members.getOrDefault(groupId, List.of()));
    }

    List<FakeGroup> groupRows() {
        return List.copyOf(groups.values());
    }

    @Override
    public WaitSuspensionResult suspendSegment(WaitSuspensionRequest request) {
        String groupKey = key(request.segment().runId(), request.segment().planGeneration(),
                request.segment().nodeId(), request.segment().nodeAttempt(),
                request.segment().segmentSequence(), request.modelTurn());
        WaitGroupIdentity identity = new WaitGroupIdentity(request.segment(), request.modelTurn());
        if (handedOverSegments.containsKey(groupKey)) {
            FakeGroup existing = groups.values().stream()
                    .filter(group -> group.identity.equals(identity)).findFirst().orElseThrow();
            events.add("already_suspended:" + groupKey);
            return new WaitSuspensionResult(WaitSuspensionOutcome.ALREADY_SUSPENDED,
                    existing.id, existing.nextSegmentSequence);
        }
        if (request.versions().claimEpoch() != expectedClaimEpoch) {
            events.add("segment_not_matched:" + groupKey);
            return new WaitSuspensionResult(WaitSuspensionOutcome.SEGMENT_NOT_MATCHED, null, null);
        }
        FakeGroup group = new FakeGroup();
        group.id = ++sequence;
        group.identity = identity;
        group.expectedMembers = request.members().size();
        group.nextSegmentSequence = request.segment().segmentSequence() + 1;
        groups.put(group.id, group);
        List<FakeMember> rows = new ArrayList<>();
        for (WaitMemberDraft draft : request.members()) {
            FakeMember member = new FakeMember();
            member.groupId = group.id;
            member.runId = request.segment().runId();
            member.memberSeq = draft.getMemberSeq();
            member.memberIdentity = WaitMemberIdentity.stableIdentity(
                    draft.getToolCallId(), identity, draft.getMemberSeq());
            member.toolCallId = draft.getToolCallId();
            member.toolName = draft.getToolName();
            member.externalOperationId = draft.getExternalOperationId();
            rows.add(member);
        }
        members.put(group.id, rows);
        handedOverSegments.put(groupKey, request.nextSegmentPayloadJson());
        events.add("segment_closed:" + request.segment().describe() + " group=" + group.id);
        return new WaitSuspensionResult(WaitSuspensionOutcome.SUSPENDED, group.id, group.nextSegmentSequence);
    }

    /** 用例把它设成与分段执行输入一致，用来模拟「这一段已经不归调用方所有」。 */
    int expectedClaimEpoch = 1;

    @Override
    public MemberCompletionResult completeMember(MemberCompletionRequest request) {
        FakeMember member = memberRows(request.groupId()).stream()
                .filter(row -> row.memberIdentity.equals(request.memberIdentity()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("成员不存在：" + request.memberIdentity()));
        if (member.finishedAt != null) {
            events.add("duplicate_completion:" + member.memberIdentity);
            return describe(request.groupId(), false, null);
        }
        member.state = request.memberState().name();
        member.resultRefJson = request.resultRefJson();
        member.finishedAt = OffsetDateTime.now();
        member.timeline.add("done");
        FakeGroup group = groups.get(request.groupId());
        Long notificationId = null;
        if (request.memberState().countsAsCompleted()) {
            group.completedMembers++;
        }
        events.add("member_done:" + member.memberIdentity + " state=" + member.state);
        if (group.completedMembers >= group.expectedMembers && WaitGroupState.WAITING.name().equals(group.state)) {
            group.state = WaitGroupState.READY.name();
            group.recoveryGeneration++;
            group.readyAt = OffsetDateTime.now();
            RecoveryNotification notification = new RecoveryNotification();
            notification.setId(++sequence);
            notification.setGroupId(group.id);
            notification.setRunId(member.runId);
            notification.setRecoveryGeneration(group.recoveryGeneration);
            notification.setState("WAITING");
            notifications.put(notification.getId(), notification);
            notificationId = notification.getId();
            events.add("group_ready:" + group.id + " notification=" + notificationId);
        }
        return describe(request.groupId(), true, notificationId);
    }

    private MemberCompletionResult describe(long groupId, boolean applied, Long notificationId) {
        FakeGroup group = groups.get(groupId);
        FakeMember member = memberRows(groupId).stream().findFirst().orElseThrow();
        return new MemberCompletionResult(applied, WaitMemberState.fromWire(member.state),
                WaitGroupState.fromWire(group.state), group.completedMembers, group.expectedMembers,
                notificationId);
    }

    @Override
    public MemberCompletionResult reportLateMember(LateMemberRequest request) {
        throw new UnsupportedOperationException("本机自检不使用迟到上报");
    }

    @Override
    public RecoveryConsumptionResult consumeRecovery(long notificationId,
                                                     String dispatcherId,
                                                     long runControlVersion,
                                                     String ownerInstanceId,
                                                     long fencingToken) {
        RecoveryNotification notification = notifications.get(notificationId);
        if (notification == null || !"WAITING".equals(notification.getState())) {
            return new RecoveryConsumptionResult(false, false, null, null,
                    RecoveryRejection.NOTIFICATION_NOT_WAITING, null);
        }
        notification.setState("CONSUMED");
        FakeGroup group = groups.get(notification.getGroupId());
        group.state = WaitGroupState.RESUMED.name();
        promotedSegments.add(key(group.identity.runId(), group.identity.planGeneration(),
                group.identity.nodeId(), group.identity.nodeAttempt(),
                group.identity.segmentSequence() + 1, group.identity.modelTurn()));
        events.add("recovery_consumed:notification=" + notificationId + " group=" + group.id);
        return new RecoveryConsumptionResult(true, true, group.id,
                new NodeWorkItemIdentity(group.identity.runId(), group.identity.planGeneration(),
                        group.identity.nodeId(), group.identity.nodeAttempt(),
                        group.nextSegmentSequence), null, null);
    }

    @Override
    public boolean closeRecoveryNotification(long notificationId, String reason) {
        RecoveryNotification notification = notifications.get(notificationId);
        if (notification == null || !"WAITING".equals(notification.getState())) {
            return false;
        }
        notification.setState(RecoveryNotificationState.CLOSED.name());
        notification.setCloseReason(reason);
        notification.setClosedAt(OffsetDateTime.now());
        events.add("recovery_closed:notification=" + notificationId + " reason=" + reason);
        return true;
    }

    @Override
    public WaitChainCancelResult cancelChain(long groupId) {
        FakeGroup group = groups.get(groupId);
        group.state = WaitGroupState.CANCELED.name();
        int canceled = 0;
        for (FakeMember member : memberRows(groupId)) {
            if (member.finishedAt == null) {
                member.state = WaitMemberState.CANCELED.name();
                member.finishedAt = OffsetDateTime.now();
                canceled++;
            }
        }
        return new WaitChainCancelResult(1, canceled, 1, 0);
    }

    @Override
    public boolean markMemberDispatched(long groupId,
                                        String memberIdentity,
                                        String externalOperationId,
                                        String dispatchProofJson,
                                        OffsetDateTime nextPollAt,
                                        long runControlVersion) {
        FakeMember member = memberRows(groupId).stream()
                .filter(row -> row.memberIdentity.equals(memberIdentity))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("成员不存在：" + memberIdentity));
        if (!WaitMemberState.PENDING.name().equals(member.state)) {
            return false;
        }
        // 与真库语句同一套条件：已经在库里的外部作业身份不能被另一个值替换。
        if (externalOperationId != null && member.externalOperationId != null
                && !externalOperationId.equals(member.externalOperationId)) {
            return false;
        }
        member.state = WaitMemberState.RUNNING.name();
        if (externalOperationId != null) {
            member.externalOperationId = externalOperationId;
        }
        if (dispatchProofJson != null) {
            member.dispatchProofJson = dispatchProofJson;
        }
        member.nextPollAt = nextPollAt;
        member.timeline.add("dispatched");
        events.add("member_dispatched:" + memberIdentity);
        return true;
    }

    @Override
    public List<WaitMember> scanDueMembers(OffsetDateTime now, int limit) {
        return members.values().stream()
                .flatMap(java.util.Collection::stream)
                .filter(row -> WaitMemberState.RUNNING.name().equals(row.state))
                .filter(row -> row.nextPollAt != null && !row.nextPollAt.isAfter(now))
                .sorted(java.util.Comparator
                        .comparing((FakeMember row) -> row.nextPollAt)
                        .thenComparingLong(row -> row.memberSeq))
                .limit(limit)
                .map(this::toMember)
                .toList();
    }

    @Override
    public boolean rescheduleMember(long groupId,
                                    String memberIdentity,
                                    OffsetDateTime nextPollAt,
                                    int maxBackoffStep) {
        FakeMember member = memberRows(groupId).stream()
                .filter(row -> row.memberIdentity.equals(memberIdentity))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("成员不存在：" + memberIdentity));
        if (!WaitMemberState.RUNNING.name().equals(member.state)) {
            return false;
        }
        member.nextPollAt = nextPollAt;
        member.pollCount++;
        member.backoffStep = Math.min(member.backoffStep + 1, maxBackoffStep);
        return true;
    }

    @Override
    public boolean holdMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt) {
        FakeMember member = memberRows(groupId).stream()
                .filter(row -> row.memberIdentity.equals(memberIdentity))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("成员不存在：" + memberIdentity));
        if (!WaitMemberState.RUNNING.name().equals(member.state)) {
            return false;
        }
        // 与库里的语句一致：只推下次查询时间，轮询次数与退避步数都不动。
        member.nextPollAt = nextPollAt;
        return true;
    }

    @Override
    public Optional<WaitGroup> findGroup(WaitGroupIdentity identity) {
        return groups.values().stream()
                .filter(group -> group.identity.equals(identity))
                .findFirst()
                .map(this::toGroup);
    }

    @Override
    public Optional<WaitGroup> findGroup(long groupId) {
        return Optional.ofNullable(groups.get(groupId)).map(this::toGroup);
    }

    @Override
    public List<WaitMember> listMembers(long groupId) {
        return memberRows(groupId).stream().map(this::toMember).toList();
    }

    @Override
    public Optional<WaitMember> findMemberByOperation(String runId, String externalOperationId) {
        return members.values().stream().flatMap(List::stream)
                .filter(member -> runId.equals(member.runId)
                        && externalOperationId.equals(member.externalOperationId))
                .findFirst().map(this::toMember);
    }

    @Override
    public Optional<WaitMember> findMemberByIdentity(long groupId, String memberIdentity) {
        return memberRows(groupId).stream()
                .filter(member -> member.memberIdentity.equals(memberIdentity))
                .findFirst().map(this::toMember);
    }

    @Override
    public java.util.Optional<RecoveryNotification> findNotification(long notificationId) {
        return java.util.Optional.ofNullable(notifications.get(notificationId));
    }

    @Override
    public List<RecoveryNotification> scanDueRecoveryNotifications(int limit) {
        return notifications.values().stream()
                .filter(notification -> "WAITING".equals(notification.getState()))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean deferRecoveryNotification(long notificationId, OffsetDateTime nextVisibleAt) {
        RecoveryNotification notification = notifications.get(notificationId);
        if (notification == null || !"WAITING".equals(notification.getState())) {
            return false;
        }
        if (notification.getNextVisibleAt() != null
                && !nextVisibleAt.isAfter(notification.getNextVisibleAt())) {
            return false;
        }
        notification.setNextVisibleAt(nextVisibleAt);
        events.add("recovery_deferred:notification=" + notificationId);
        return true;
    }

    @Override
    public List<RecoveryNotification> listNotifications(long groupId) {
        return notifications.values().stream()
                .filter(notification -> notification.getGroupId() == groupId)
                .toList();
    }

    private WaitGroup toGroup(FakeGroup group) {
        WaitGroup row = new WaitGroup();
        row.setId(group.id);
        row.setRunId(group.identity.runId());
        row.setPlanGeneration(group.identity.planGeneration());
        row.setNodeId(group.identity.nodeId());
        row.setNodeAttempt(group.identity.nodeAttempt());
        row.setSegmentSequence(group.identity.segmentSequence());
        row.setModelTurn(group.identity.modelTurn());
        row.setSchedulerVersion("DUAL_POOL_V2");
        row.setNextSegmentSequence(group.nextSegmentSequence);
        row.setState(group.state);
        row.setExpectedMembers(group.expectedMembers);
        row.setCompletedMembers(group.completedMembers);
        row.setRecoveryGeneration(group.recoveryGeneration);
        row.setReadyAt(group.readyAt);
        return row;
    }

    private WaitMember toMember(FakeMember member) {
        WaitMember row = new WaitMember();
        row.setId((long) member.memberSeq);
        row.setGroupId(member.groupId);
        row.setRunId(member.runId);
        row.setMemberSeq(member.memberSeq);
        row.setMemberIdentity(member.memberIdentity);
        row.setToolCallId(member.toolCallId);
        row.setToolName(member.toolName);
        row.setExternalOperationId(member.externalOperationId);
        row.setState(member.state);
        row.setResultRefJson(member.resultRefJson);
        row.setNextPollAt(member.nextPollAt);
        row.setPollCount(member.pollCount);
        row.setBackoffStep(member.backoffStep);
        row.setFinishedAt(member.finishedAt);
        return row;
    }

    private static String key(String runId, int planGeneration, String nodeId, int nodeAttempt,
                              int segmentSequence, int modelTurn) {
        return runId + "|" + planGeneration + "|" + nodeId + "|" + nodeAttempt + "|"
                + segmentSequence + "|" + modelTurn;
    }
}
