package world.willfrog.agent.platform.wait;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link WaitGroupStore} 的 MyBatis 实现。
 *
 * <p>每条写入语句都自带条件，返回的计数就是事实：写进去就是一次，没写进去就没有。这一层只把库里的
 * 计数与状态翻成调用方能判断的结果，不做「先读后写」的补救，也不把可疑的组合当成成功——库里出现
 * 这段代码认为不可能的组合时直接抛错，让问题在写入那一刻暴露，不留给恢复路径。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MybatisWaitGroupStore implements WaitGroupStore {

    private final WaitGroupMapper mapper;

    @Override
    public WaitSuspensionResult suspendSegment(WaitSuspensionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("整组挂起请求不能为空");
        }
        WaitSuspensionRow row = mapper.suspendSegment(
                request.segment().runId(),
                request.segment().planGeneration(),
                request.segment().nodeId(),
                request.segment().nodeAttempt(),
                request.segment().segmentSequence(),
                request.versions().claimEpoch(),
                request.claimant(),
                request.versions().contextVersion(),
                request.versions().runControlVersion(),
                request.modelTurn(),
                request.schedulerVersion().name(),
                memberRows(request),
                request.suspensionPayloadJson(),
                request.nextSegmentPayloadJson());
        if (row == null) {
            throw new IllegalStateException("整组挂起语句没有返回结果行：" + request.segment().describe());
        }
        int closed = value(row.getClosedSegments());
        if (closed == 1 && row.getCreatedGroupId() != null) {
            // 新写一条等待组时，成员与下一段必须一条不少地跟着落库：语句里它们是同一个 WITH 的三段，
            // 计数不对说明写进去的不是我们以为的那份东西，这种「成功」不能交给调用方。
            int expectedMembers = request.members().size();
            if (value(row.getWrittenMembers()) != expectedMembers
                    || value(row.getWrittenNextSegments()) != 1
                    || row.getNextSegmentSequence() == null) {
                throw new IllegalStateException("整组挂起写出的成员或下一段数量不对："
                        + "期望成员 " + expectedMembers + "、下一段 1，实际成员 "
                        + row.getWrittenMembers() + "、下一段 " + row.getWrittenNextSegments()
                        + "，分段 " + request.segment().describe());
            }
            return new WaitSuspensionResult(
                    WaitSuspensionOutcome.SUSPENDED, row.getCreatedGroupId(), row.getNextSegmentSequence());
        }
        if (closed == 0 && row.getExistingGroupId() != null) {
            // 幂等重试：组是上一次就写好的，成员与下一段这一次都不会再插一次，所以不看那两个计数。
            log.info("同一次模型回合的等待组已经保存过，这次不重复写入：{} turn={}",
                    request.segment().describe(), request.modelTurn());
            if (row.getNextSegmentSequence() == null) {
                throw new IllegalStateException("已有的等待组上没有下一段序号："
                        + request.segment().describe());
            }
            return new WaitSuspensionResult(
                    WaitSuspensionOutcome.ALREADY_SUSPENDED, row.getExistingGroupId(),
                    row.getNextSegmentSequence());
        }
        if (closed == 0) {
            log.warn("整组挂起没有生效：当前分段已经不在调用方手里 {} {}", request.segment().describe(),
                    request.versions().describe());
            return new WaitSuspensionResult(WaitSuspensionOutcome.SEGMENT_NOT_MATCHED, null, null);
        }
        // 分段结束成功但组已经存在：同一个身份不可能既被结束又已经建过组，两条路都要先锁同一条 Run 行，
        // 所以只能是数据被改坏了。这种组合不能当成成功，也不能当成「重复上报」。
        throw new IllegalStateException("分段已结束但同身份的等待组已经存在：" + request.segment().describe());
    }

    @Override
    public MemberCompletionResult completeMember(MemberCompletionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("成员结束请求不能为空");
        }
        WaitMemberCompletionRow row = mapper.completeMember(
                request.groupId(),
                request.memberIdentity(),
                request.memberState().name(),
                request.resultRefJson(),
                request.planGeneration(),
                request.contextVersion(),
                request.externalOperationId(),
                request.runControlVersion());
        int written = value(row == null ? null : row.getWrittenMembers());
        if (written == 0) {
            log.info("成员结束没有写进去（重复上报或成员已落终态）：group={} member={}",
                    request.groupId(), request.memberIdentity());
        }
        return toCompletionResult(row, written > 0);
    }

    @Override
    public MemberCompletionResult reportLateMember(LateMemberRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("迟到结果请求不能为空");
        }
        WaitMemberCompletionRow row = mapper.reportLateMember(
                request.groupId(),
                request.memberIdentity(),
                request.resultRefJson(),
                request.externalOperationId(),
                request.runControlVersion());
        int written = value(row == null ? null : row.getWrittenMembers());
        if (written > 0) {
            log.warn("迟到结果只留档，这条等待链已经停下：group={} member={} 连带停掉的兄弟成员={}",
                    request.groupId(), request.memberIdentity(), value(row.getWrittenSiblings()));
        } else {
            log.info("迟到结果的身份或版本对不上，什么都没有改：group={} member={}",
                    request.groupId(), request.memberIdentity());
        }
        return toCompletionResult(row, written > 0);
    }

    @Override
    public RecoveryConsumptionResult consumeRecovery(long notificationId,
                                                     String dispatcherId,
                                                     long runControlVersion) {
        if (notificationId <= 0) {
            throw new IllegalArgumentException("恢复通知编号必须为正数：" + notificationId);
        }
        if (dispatcherId == null || dispatcherId.isBlank()) {
            throw new IllegalArgumentException("恢复消费必须留下消费方标识");
        }
        RecoveryConsumptionRow row = mapper.consumeRecovery(notificationId, dispatcherId, runControlVersion);
        if (row == null) {
            throw new IllegalStateException("恢复消费语句没有返回结果行：notification=" + notificationId);
        }
        RecoveryConsumptionResult result = new RecoveryConsumptionResult(
                value(row.getConsumed()) > 0,
                value(row.getPromoted()) > 0,
                row.getGroupId(),
                row.nextSegment());
        if (result.inconsistent()) {
            // 三条写入在语句里是用 RETURNING 串起来的，只可能全成或全不写。出现半成品说明库里的
            // 事实与这段代码的假设对不上，必须当场报出来——恢复资格只有一条，放过去就再也找不回。
            throw new IllegalStateException("恢复消费出现半成品：notification=" + notificationId
                    + " group=" + row.getGroupId() + " consumed=" + row.getConsumed()
                    + " promoted=" + row.getPromoted());
        }
        return result;
    }

    @Override
    public WaitChainCancelResult cancelChain(long groupId) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        WaitChainCancelRow row = mapper.cancelChain(groupId);
        if (row == null) {
            throw new IllegalStateException("取消等待链语句没有返回结果行：group=" + groupId);
        }
        return new WaitChainCancelResult(
                value(row.getGroupsCanceled()),
                value(row.getMembersCanceled()),
                value(row.getSegmentsCanceled()),
                value(row.getNotificationsCanceled()));
    }

    @Override
    public boolean markMemberDispatched(long groupId,
                                        String memberIdentity,
                                        String externalOperationId,
                                        String dispatchProofJson,
                                        OffsetDateTime nextPollAt,
                                        long runControlVersion) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        if (memberIdentity == null || memberIdentity.isBlank()) {
            throw new IllegalArgumentException("成员稳定身份不能为空");
        }
        if (runControlVersion < 0) {
            throw new IllegalArgumentException("控制版本不能是负数：" + runControlVersion);
        }
        return mapper.markMemberDispatched(groupId, memberIdentity, externalOperationId,
                dispatchProofJson, nextPollAt, runControlVersion) > 0;
    }

    @Override
    public boolean rescheduleMember(long groupId,
                                    String memberIdentity,
                                    OffsetDateTime nextPollAt,
                                    int maxBackoffStep) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        if (memberIdentity == null || memberIdentity.isBlank()) {
            throw new IllegalArgumentException("成员稳定身份不能为空");
        }
        if (nextPollAt == null) {
            throw new IllegalArgumentException("推后查询必须给出下次查询时间");
        }
        if (maxBackoffStep <= 0) {
            throw new IllegalArgumentException("退避步数上限必须为正数：" + maxBackoffStep);
        }
        return mapper.rescheduleMember(groupId, memberIdentity, nextPollAt, maxBackoffStep) > 0;
    }

    @Override
    public Optional<WaitGroup> findGroup(WaitGroupIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("等待组身份不能为空");
        }
        return Optional.ofNullable(mapper.findGroup(
                identity.runId(), identity.planGeneration(), identity.nodeId(),
                identity.nodeAttempt(), identity.segmentSequence(), identity.modelTurn()));
    }

    @Override
    public Optional<WaitGroup> findGroup(long groupId) {
        return Optional.ofNullable(mapper.findGroupById(groupId));
    }

    @Override
    public List<WaitMember> listMembers(long groupId) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        return mapper.listMembers(groupId);
    }

    @Override
    public Optional<WaitMember> findMemberByOperation(String runId, String externalOperationId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("外部作业身份查询必须带 Run");
        }
        if (externalOperationId == null || externalOperationId.isBlank()) {
            throw new IllegalArgumentException("外部作业身份不能为空");
        }
        return Optional.ofNullable(mapper.findMemberByOperation(runId, externalOperationId));
    }

    @Override
    public Optional<WaitMember> findMemberByIdentity(long groupId, String memberIdentity) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        if (memberIdentity == null || memberIdentity.isBlank()) {
            throw new IllegalArgumentException("成员稳定身份不能为空");
        }
        return Optional.ofNullable(mapper.findMemberByIdentity(groupId, memberIdentity));
    }

    @Override
    public Optional<RecoveryNotification> findNotification(long notificationId) {
        if (notificationId <= 0) {
            throw new IllegalArgumentException("恢复通知编号必须为正数：" + notificationId);
        }
        return Optional.ofNullable(mapper.findNotificationById(notificationId));
    }

    @Override
    public List<RecoveryNotification> scanDueRecoveryNotifications(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("扫描条数必须为正数：" + limit);
        }
        return mapper.scanDueRecoveryNotifications(limit);
    }

    @Override
    public boolean deferRecoveryNotification(long notificationId, OffsetDateTime nextVisibleAt) {
        if (notificationId <= 0) {
            throw new IllegalArgumentException("恢复通知编号必须为正数：" + notificationId);
        }
        if (nextVisibleAt == null) {
            throw new IllegalArgumentException("推后恢复通知必须给出下次可见时间");
        }
        return mapper.deferRecoveryNotification(notificationId, nextVisibleAt) > 0;
    }

    @Override
    public List<RecoveryNotification> listNotifications(long groupId) {
        if (groupId <= 0) {
            throw new IllegalArgumentException("等待组编号必须为正数：" + groupId);
        }
        return mapper.listNotifications(groupId);
    }

    /**
     * 把整组请求里的成员转成待写入的行，并在这里一次算好稳定身份。
     *
     * <p>身份只在服务端算一次：模型给了工具调用身份就用它，没给就按「等待组身份加原始序号」派生。
     * 调用方拿不到也改不了这个值，重试时同一组同一序号算出来永远一样。</p>
     */
    private List<WaitMember> memberRows(WaitSuspensionRequest request) {
        WaitGroupIdentity groupIdentity = new WaitGroupIdentity(request.segment(), request.modelTurn());
        return request.members().stream()
                .map(draft -> {
                    WaitMember member = new WaitMember();
                    member.setRunId(request.segment().runId());
                    member.setMemberSeq(draft.getMemberSeq());
                    member.setMemberIdentity(WaitMemberIdentity.stableIdentity(
                            draft.getToolCallId(), groupIdentity, draft.getMemberSeq()));
                    member.setToolCallId(draft.getToolCallId());
                    member.setToolName(draft.getToolName());
                    member.setExternalOperationId(draft.getExternalOperationId());
                    return member;
                })
                .toList();
    }

    private MemberCompletionResult toCompletionResult(WaitMemberCompletionRow row, boolean applied) {
        if (row == null) {
            throw new IllegalStateException("成员结束语句没有返回结果行");
        }
        WaitMemberState memberState = WaitMemberState.fromWire(row.getMemberState());
        WaitGroupState groupState = WaitGroupState.fromWire(row.getGroupState());
        int completed = value(row.getCompletedMembers());
        int expected = value(row.getExpectedMembers());
        Long notificationId = row.getNotificationId();
        return new MemberCompletionResult(
                applied, memberState, groupState, completed, expected, notificationId);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
