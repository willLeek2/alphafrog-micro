package world.willfrog.agent.platform.wait;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisWaitGroupStore} 对语句返回值的解读。
 *
 * <p>纯 mock 测试，不连数据库：这里验的是「数据库说写进去几行、这个存储层判成哪种结果」这段判断，
 * 真实的条件更新与唯一约束行为由 PostgreSQL 集成测试那一层负责。</p>
 */
@ExtendWith(MockitoExtension.class)
class MybatisWaitGroupStoreTest {

    private static final NodeWorkItemIdentity SEGMENT = new NodeWorkItemIdentity("run-1", 4, "node-9", 1, 2);
    private static final NodeWorkItemVersions VERSIONS = new NodeWorkItemVersions(7L, 3L, 5);

    @Mock
    private WaitGroupMapper mapper;

    private MybatisWaitGroupStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisWaitGroupStore(mapper);
    }

    // ===== 整组挂起 =====

    @Test
    void suspensionReportsThreeOutcomesWithoutGuessing() {
        WaitSuspensionRequest request = suspensionRequest();

        when(mapper.suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString()))
                .thenReturn(suspensionRow(1, 77L, null, 3, 1, 3));
        WaitSuspensionResult suspended = store.suspendSegment(request);
        assertThat(suspended.outcome()).isEqualTo(WaitSuspensionOutcome.SUSPENDED);
        assertThat(suspended.groupId()).isEqualTo(77L);
        assertThat(suspended.nextSegmentSequence()).isEqualTo(3);
        assertThat(suspended.suspended()).isTrue();

        when(mapper.suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString()))
                .thenReturn(suspensionRow(0, null, 77L, 0, 0, 3));
        WaitSuspensionResult already = store.suspendSegment(request);
        assertThat(already.outcome()).isEqualTo(WaitSuspensionOutcome.ALREADY_SUSPENDED);
        assertThat(already.groupId()).isEqualTo(77L);

        when(mapper.suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString()))
                .thenReturn(suspensionRow(0, null, null, 0, 0, null));
        WaitSuspensionResult unmatched = store.suspendSegment(request);
        assertThat(unmatched.outcome()).isEqualTo(WaitSuspensionOutcome.SEGMENT_NOT_MATCHED);
        assertThat(unmatched.suspended()).isFalse();
        assertThat(unmatched.groupId()).isNull();
    }

    @Test
    void suspensionRefusesImpossibleCombination() {
        when(mapper.suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString()))
                .thenReturn(suspensionRow(1, null, 77L, 0, 0, 3));
        assertThatThrownBy(() -> store.suspendSegment(suspensionRequest()))
                .as("结束分段的同时组已经存在，这种组合说明数据被改坏了")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("等待组已经存在");
    }

    @Test
    void suspensionPassesStableIdentitiesAndClaimFenceToTheStatement() {
        when(mapper.suspendSegment(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), any(), anyString(), anyString()))
                .thenReturn(suspensionRow(1, 77L, null, 2, 1, 3));
        store.suspendSegment(suspensionRequest());

        ArgumentCaptor<List<WaitMember>> members = ArgumentCaptor.forClass(List.class);
        verify(mapper).suspendSegment(eq("run-1"), eq(4), eq("node-9"), eq(1), eq(2), eq(5),
                eq("worker-1"), eq(7L), eq(3L), eq(0), eq(SchedulerVersion.DUAL_POOL_V2.name()),
                members.capture(), anyString(), anyString());
        assertThat(members.getValue()).hasSize(2);
        assertThat(members.getValue().get(0).getMemberIdentity())
                .isEqualTo("call_1");
        assertThat(members.getValue().get(1).getMemberIdentity())
                .as("模型没给身份时用派生身份，重试算出来必须一样")
                .isEqualTo(WaitMemberIdentity.stableIdentity(null, groupIdentity(0), 1));
    }

    // ===== 成员结束 =====

    @Test
    void memberCompletionReportsAppliedAndReadyFlag() {
        when(mapper.completeMember(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(memberRow(1, "SUCCEEDED", "READY", 3, 3, 1, 9));
        MemberCompletionResult ready = store.completeMember(completion("SUCCEEDED"));
        assertThat(ready.applied()).isTrue();
        assertThat(ready.groupBecameReady()).isTrue();
        assertThat(ready.groupComplete()).isTrue();
        assertThat(ready.memberState()).isEqualTo(WaitMemberState.SUCCEEDED);
        assertThat(ready.groupState()).isEqualTo(WaitGroupState.READY);
        assertThat(ready.notificationId()).isEqualTo(9L);

        when(mapper.completeMember(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(memberRow(0, "SUCCEEDED", "WAITING", 2, 3, null, null));
        MemberCompletionResult duplicate = store.completeMember(completion("SUCCEEDED"));
        assertThat(duplicate.applied()).isFalse();
        assertThat(duplicate.groupBecameReady()).isFalse();
        assertThat(duplicate.groupComplete()).isFalse();
    }

    @Test
    void failureAlsoCountsAsOneCompletion() {
        when(mapper.completeMember(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(memberRow(1, "FAILED", "WAITING", 1, 3, null, null));
        MemberCompletionResult result = store.completeMember(completion("FAILED"));
        assertThat(result.applied()).isTrue();
        assertThat(result.memberState()).isEqualTo(WaitMemberState.FAILED);
        assertThat(result.groupState()).isEqualTo(WaitGroupState.WAITING);
    }

    @Test
    void unknownStateFromDatabaseFailsClosed() {
        when(mapper.completeMember(anyLong(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(memberRow(1, "SUCCEEDED", "DONE", 1, 3, null, null));
        assertThatThrownBy(() -> store.completeMember(completion("SUCCEEDED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DONE");
    }

    @Test
    void lateResultStopsTheChainAndKeepsOnlyAudit() {
        when(mapper.reportLateMember(anyLong(), anyString(), anyString(), any()))
                .thenReturn(memberRow(1, "LATE", "CANCELED", 0, 3, null, null));
        MemberCompletionResult result = store.reportLateMember(
                new LateMemberRequest(77L, "call_1", "{\"kind\":\"LATE\"}", null));
        assertThat(result.applied()).isTrue();
        assertThat(result.memberState()).isEqualTo(WaitMemberState.LATE);
        assertThat(result.groupState()).isEqualTo(WaitGroupState.CANCELED);
        assertThat(result.groupBecameReady()).isFalse();
    }

    // ===== 恢复消费 =====

    @Test
    void recoveryConsumptionDistinguishesNotMineFromHalfDone() {
        when(mapper.consumeRecovery(eq(5L), anyString(), eq(3L)))
                .thenReturn(consumption(1, 1, 77L, 3));
        RecoveryConsumptionResult done = store.consumeRecovery(5L, "dispatcher-1", 3L);
        assertThat(done.succeeded()).isTrue();
        assertThat(done.inconsistent()).isFalse();

        when(mapper.consumeRecovery(eq(5L), anyString(), eq(3L)))
                .thenReturn(consumption(0, 0, null, null));
        RecoveryConsumptionResult skipped = store.consumeRecovery(5L, "dispatcher-1", 3L);
        assertThat(skipped.succeeded()).isFalse();
        assertThat(skipped.inconsistent())
                .as("什么都没做不算不一致，只有取走了通知却没放行下一段才算")
                .isFalse();

        when(mapper.consumeRecovery(eq(5L), anyString(), eq(3L)))
                .thenReturn(consumption(1, 0, 77L, null));
        assertThat(store.consumeRecovery(5L, "dispatcher-1", 3L).inconsistent()).isTrue();
    }

    // ===== 取消与读取 =====

    @Test
    void cancelReportsEachActionCount() {
        WaitChainCancelRow row = new WaitChainCancelRow();
        row.setGroupsCanceled(1);
        row.setMembersCanceled(2);
        row.setSegmentsCanceled(1);
        row.setNotificationsCanceled(1);
        when(mapper.cancelChain(77L)).thenReturn(row);
        WaitChainCancelResult result = store.cancelChain(77L);
        assertThat(result.canceled()).isTrue();
        assertThat(result.membersCanceled()).isEqualTo(2);
        assertThat(result.segmentsCanceled()).isEqualTo(1);
    }

    @Test
    void rescheduleOnlyReportsWhetherTheMemberWasStillRunning() {
        when(mapper.rescheduleMember(eq(77L), eq("call_1"), any(), eq(9))).thenReturn(1);
        assertThat(store.rescheduleMember(77L, "call_1", OffsetDateTime.now(), 9)).isTrue();
        when(mapper.rescheduleMember(eq(77L), anyString(), any(), eq(9))).thenReturn(0);
        assertThat(store.rescheduleMember(77L, "call_2", OffsetDateTime.now(), 9)).isFalse();
    }

    @Test
    void readsReturnEmptyInsteadOfNull() {
        when(mapper.findGroupById(77L)).thenReturn(null);
        assertThat(store.findGroup(77L)).isEmpty();
        when(mapper.findMemberByOperation("run-1", "op-1")).thenReturn(null);
        assertThat(store.findMemberByOperation("run-1", "op-1")).isEmpty();
        WaitMember member = new WaitMember();
        member.setMemberIdentity("call_1");
        when(mapper.findMemberByIdentity(77L, "call_1")).thenReturn(member);
        assertThat(store.findMemberByIdentity(77L, "call_1")).contains(member);
    }

    // ===== 入参自检：明显不对的输入不许走到 SQL =====

    @Test
    void invalidRequestsNeverReachTheMapper() {
        assertThatThrownBy(() -> store.consumeRecovery(0L, "dispatcher-1", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.cancelChain(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.rescheduleMember(77L, "call_1", null, 9))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findMemberByOperation("run-1", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberCompletionRequest(77L, "call_1",
                WaitMemberState.LATE, "{}", null, 3L))
                .as("正常结束只允许成功或失败")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberCompletionRequest(77L, "call_1",
                WaitMemberState.SUCCEEDED, " ", null, 3L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LateMemberRequest(77L, " ", "{}", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).cancelChain(anyLong());
    }

    // ===== 夹具 =====

    private static WaitGroupIdentity groupIdentity(int modelTurn) {
        return new WaitGroupIdentity(SEGMENT, modelTurn);
    }

    private static WaitSuspensionRequest suspensionRequest() {
        List<WaitMemberDraft> members = List.of(
                new WaitMemberDraft(0, "call_1", "executePython", "op-1"),
                new WaitMemberDraft(1, null, "executePython", null));
        return new WaitSuspensionRequest(SEGMENT, VERSIONS, "worker-1", 0,
                SchedulerVersion.DUAL_POOL_V2, members,
                "{\"waitSuspension\":{\"modelTurn\":0}}", "{\"checkpoint\":\"ck-1\"}");
    }

    private static WaitSuspensionRow suspensionRow(Integer closed, Long created, Long existing,
                                                   Integer writtenMembers, Integer writtenNext,
                                                   Integer nextSequence) {
        WaitSuspensionRow row = new WaitSuspensionRow();
        row.setClosedSegments(closed);
        row.setCreatedGroupId(created);
        row.setExistingGroupId(existing);
        row.setWrittenMembers(writtenMembers);
        row.setWrittenNextSegments(writtenNext);
        row.setNextSegmentSequence(nextSequence);
        return row;
    }

    private static MemberCompletionRequest completion(String state) {
        return new MemberCompletionRequest(77L, "call_1", WaitMemberState.fromWire(state),
                "{\"kind\":\"INLINE\"}", "op-1", 3L);
    }

    private static WaitMemberCompletionRow memberRow(Integer written, String memberState, String groupState,
                                                     Integer completed, Integer expected,
                                                     Integer generation, Integer notificationId) {
        WaitMemberCompletionRow row = new WaitMemberCompletionRow();
        row.setMemberId(1L);
        row.setMemberSeq(0);
        row.setMemberState(memberState);
        row.setGroupId(77L);
        row.setGroupState(groupState);
        row.setCompletedMembers(completed);
        row.setExpectedMembers(expected);
        row.setRecoveryGeneration(generation);
        row.setNotificationId(notificationId);
        row.setWrittenMembers(written);
        return row;
    }

    private static RecoveryConsumptionRow consumption(Integer consumed, Integer promoted,
                                                      Long groupId, Integer nextSequence) {
        RecoveryConsumptionRow row = new RecoveryConsumptionRow();
        row.setConsumed(consumed);
        row.setPromoted(promoted);
        row.setGroupId(groupId);
        row.setNextSegmentSequence(nextSequence);
        return row;
    }
}
