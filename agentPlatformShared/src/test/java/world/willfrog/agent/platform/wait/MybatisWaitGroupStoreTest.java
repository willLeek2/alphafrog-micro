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




    // ===== 成员结束 =====





    // ===== 恢复消费 =====



    // ===== 取消与读取 =====





    // ===== 入参自检：明显不对的输入不许走到 SQL =====


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
                "{\"kind\":\"INLINE\"}", "op-1", 0, 3L, 3L);
    }

    private static WaitMemberCompletionRow memberRow(Integer written, String memberState, String groupState,
                                                     Integer completed, Integer expected,
                                                     Integer generation, Long notificationId) {
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
        return consumption(consumed, promoted, groupId, nextSequence, null, null);
    }

    private static RecoveryConsumptionRow consumption(Integer consumed, Integer promoted,
                                                      Long groupId, Integer nextSequence,
                                                      String rejection, String rejectionDetail) {
        RecoveryConsumptionRow row = new RecoveryConsumptionRow();
        row.setConsumed(consumed);
        row.setPromoted(promoted);
        row.setGroupId(groupId);
        if (nextSequence != null) {
            // 放行成功时五个身份字段必须齐全，缺一个存储层就要报出来。
            row.setNextRunId("run-1");
            row.setNextPlanGeneration(0);
            row.setNextNodeId("node-1");
            row.setNextNodeAttempt(0);
            row.setNextSegmentSequence(nextSequence);
        }
        row.setRejection(rejection);
        row.setRejectionDetail(rejectionDetail);
        return row;
    }
}
