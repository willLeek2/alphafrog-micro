package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.wait.RecoveryConsumptionRow;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.WaitChainCancelRow;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberCompletionRow;
import world.willfrog.agent.platform.wait.WaitSuspensionRow;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 等待组、等待成员与持久恢复通知的 SQL 入口。
 *
 * <p>三条写入路径各是一条语句：整组挂起、成员结束、恢复消费。它们都用「带条件的更新」表达，
 * 调用方只看返回的计数与状态，不先读后写。</p>
 *
 * <p>每条写入语句都先锁 Run（{@code FOR UPDATE}）再动子行，与工作项上的两条长工具语句保持同一顺序。</p>
 */
@Mapper
public interface WaitGroupMapper {

    // ===== 读 =====

    WaitGroup findGroup(@Param("runId") String runId,
                        @Param("planGeneration") int planGeneration,
                        @Param("nodeId") String nodeId,
                        @Param("nodeAttempt") int nodeAttempt,
                        @Param("segmentSequence") int segmentSequence,
                        @Param("modelTurn") int modelTurn);

    WaitGroup findGroupById(@Param("groupId") long groupId);

    List<WaitMember> listMembers(@Param("groupId") long groupId);

    WaitMember findMemberByOperation(@Param("runId") String runId,
                                     @Param("externalOperationId") String externalOperationId);

    WaitMember findMemberByIdentity(@Param("groupId") long groupId,
                                    @Param("memberIdentity") String memberIdentity);

    List<RecoveryNotification> listNotifications(@Param("groupId") long groupId);

    // ===== 整组挂起 =====

    /**
     * 结束当前执行分段、保存等待组与全部成员、把下一段建成等待态。
     *
     * <p>{@code members} 是已经算好稳定身份的成员行；语句按参数逐条插入，不生成任何随机值。</p>
     */
    WaitSuspensionRow suspendSegment(@Param("runId") String runId,
                                     @Param("planGeneration") int planGeneration,
                                     @Param("nodeId") String nodeId,
                                     @Param("nodeAttempt") int nodeAttempt,
                                     @Param("segmentSequence") int segmentSequence,
                                     @Param("claimEpoch") int claimEpoch,
                                     @Param("claimedBy") String claimedBy,
                                     @Param("contextVersion") long contextVersion,
                                     @Param("runControlVersion") long runControlVersion,
                                     @Param("modelTurn") int modelTurn,
                                     @Param("schedulerVersion") String schedulerVersion,
                                     @Param("members") List<WaitMember> members,
                                     @Param("suspensionPayloadJson") String suspensionPayloadJson,
                                     @Param("nextSegmentPayloadJson") String nextSegmentPayloadJson);

    // ===== 成员结束 =====

    /**
     * 成员落终态一次，并在它让整组刚好齐备时写出恢复通知。
     *
     * <p>{@code externalOperationId} 非空时会额外要求与库里的值一致，防止别的作业的结果接到这个成员上。</p>
     */
    WaitMemberCompletionRow completeMember(@Param("groupId") long groupId,
                                           @Param("memberIdentity") String memberIdentity,
                                           @Param("memberState") String memberState,
                                           @Param("resultRefJson") String resultRefJson,
                                           @Param("externalOperationId") String externalOperationId,
                                           @Param("runControlVersion") long runControlVersion);

    /** 迟到结果留档：成员改成迟到，并把这条链一起停下。 */
    WaitMemberCompletionRow reportLateMember(@Param("groupId") long groupId,
                                             @Param("memberIdentity") String memberIdentity,
                                             @Param("resultRefJson") String resultRefJson,
                                             @Param("externalOperationId") String externalOperationId);

    // ===== 恢复消费 =====

    /** 取走一条通知并把下一段放成可恢复；Run 状态或控制版本不符时整条语句什么都不做。 */
    RecoveryConsumptionRow consumeRecovery(@Param("notificationId") long notificationId,
                                           @Param("dispatcherId") String dispatcherId,
                                           @Param("runControlVersion") long runControlVersion);

    // ===== 取消 =====

    /** 组、还没结束的成员、下一段与还没被取走的通知一起停。 */
    WaitChainCancelRow cancelChain(@Param("groupId") long groupId);

    // ===== 成员派发 =====

    /**
     * 派发成功：成员从待派发进入执行中，并把外部作业身份写上。
     *
     * <p>只对还没派发的成员生效，重复派发影响零行，调用方据此判断这一次是不是自己送出去的。</p>
     */
    int markMemberDispatched(@Param("groupId") long groupId,
                             @Param("memberIdentity") String memberIdentity,
                             @Param("externalOperationId") String externalOperationId,
                             @Param("nextPollAt") OffsetDateTime nextPollAt,
                             @Param("runControlVersion") long runControlVersion);

    // ===== 成员轮询 =====

    /** 成员还在执行中时推后下次查询时间；已经落终态的成员不会被改。 */
    int rescheduleMember(@Param("groupId") long groupId,
                         @Param("memberIdentity") String memberIdentity,
                         @Param("nextPollAt") OffsetDateTime nextPollAt,
                         @Param("maxBackoffStep") int maxBackoffStep);
}
