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

    List<WaitGroup> listOpenGroupsByRun(@Param("runId") String runId,
                                        @Param("afterGroupId") long afterGroupId,
                                        @Param("limit") int limit);

    List<WaitGroup> scanOpenGroupsWithStoppedRun(@Param("afterGroupId") long afterGroupId,
                                                  @Param("limit") int limit);

    List<WaitGroup> scanCanceledGroupsMissingStopTasks(@Param("afterGroupId") long afterGroupId,
                                                       @Param("limit") int limit);

    List<WaitMember> scanPendingPythonMembersWithProof(@Param("deploymentId") String deploymentId,
                                                        @Param("deploymentGenerationId") String deploymentGenerationId,
                                                        @Param("afterMemberId") long afterMemberId,
                                                        @Param("limit") int limit);

    int countSafePendingPythonRecovery(@Param("memberId") long memberId,
                                       @Param("workItemId") long workItemId,
                                       @Param("claimEpoch") int claimEpoch,
                                       @Param("claimedBy") String claimedBy,
                                       @Param("operationId") String operationId,
                                       @Param("requestFingerprint") String requestFingerprint);

    int recoverPendingPythonMember(@Param("memberId") long memberId,
                                   @Param("workItemId") long workItemId,
                                   @Param("claimEpoch") int claimEpoch,
                                   @Param("claimedBy") String claimedBy,
                                   @Param("operationId") String operationId,
                                   @Param("requestFingerprint") String requestFingerprint);

    List<WaitMember> listMembers(@Param("groupId") long groupId);

    WaitMember findMemberByOperation(@Param("runId") String runId,
                                     @Param("externalOperationId") String externalOperationId);

    WaitMember findMemberByIdentity(@Param("groupId") long groupId,
                                    @Param("memberIdentity") String memberIdentity);

    List<RecoveryNotification> listNotifications(@Param("groupId") long groupId);

    /** 按编号读一条恢复通知；内存提示只带编号，取之前必须回库里读一次权威状态。 */
    RecoveryNotification findNotificationById(@Param("notificationId") long notificationId);

    /**
     * 到期的恢复通知：还没被取走、已经到了下次可见时间，等得最久的排最前。
     *
     * <p>不预判能不能取走：那是消费语句里那些条件的事。扫描只负责把候选按顺序取出来，
     * 逐条去试，试不成的由调用方按退避推后下次可见时间。</p>
     */
    List<RecoveryNotification> scanDueRecoveryNotifications(@Param("limit") int limit);

    /**
     * 把一条通知推后：只对还在等待态、且新时间确实更晚的才写。
     * 已经取走或取消的通知影响 0 行，迟到的延期写入也不会把时间拉回来。
     */
    int deferRecoveryNotification(@Param("notificationId") long notificationId,
                                  @Param("nextVisibleAt") OffsetDateTime nextVisibleAt);

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
     * <p>身份与版本在一条语句里一起核对：组必须属于这条 Run、计划代际与这一段一直在用的上下文版本
     * 都必须对得上，外部作业身份要求逐字一致（两边都是空也算一致）。</p>
     */
    WaitMemberCompletionRow completeMember(@Param("groupId") long groupId,
                                           @Param("memberIdentity") String memberIdentity,
                                           @Param("memberState") String memberState,
                                           @Param("resultRefJson") String resultRefJson,
                                           @Param("planGeneration") int planGeneration,
                                           @Param("contextVersion") long contextVersion,
                                           @Param("externalOperationId") String externalOperationId,
                                           @Param("runControlVersion") long runControlVersion);

    /**
     * 迟到结果留档：成员改成迟到，并把这条链一起停下。
     *
     * <p>已经在等的成员改成迟到并停链；已经被取消的成员只补审计字段（结果引用、外部作业身份、
     * 结束时间只在还是空的时候写一次）；身份对不上的上报什么都不做。</p>
     */
    WaitMemberCompletionRow reportLateMember(@Param("groupId") long groupId,
                                             @Param("memberIdentity") String memberIdentity,
                                             @Param("resultRefJson") String resultRefJson,
                                             @Param("externalOperationId") String externalOperationId,
                                             @Param("runControlVersion") long runControlVersion);

    // ===== 恢复消费 =====

    /**
     * 放行下一段、把组改成已恢复、再把通知改成已消费；三步用 RETURNING 串起来，只有全成或全不写。
     *
     * <p>要成立的条件：这条 Run 的服务所有权还在这位持有者手上（持有者与代际都对得上、租约还没过期）、
     * Run 状态、计划代际、控制版本、通知的恢复代际、下一段自身的控制版本与等待态。租约行与 Run 行
     * 在同一句里一起锁住，所以判断和写入之间不会被人接手。</p>
     *
     * <p>没消费成时返回明确原因，调用方按原因决定推后还是收口，不用拿自己手上的旧快照猜。</p>
     */
    RecoveryConsumptionRow consumeRecovery(@Param("notificationId") long notificationId,
                                           @Param("dispatcherId") String dispatcherId,
                                           @Param("runControlVersion") long runControlVersion,
                                           @Param("ownerInstanceId") String ownerInstanceId,
                                           @Param("fencingToken") long fencingToken);

    /**
     * 把一条不可能再被服务的通知收口：只对还在等待态、且给了原因的才写。
     *
     * <p>已经取走、已经关闭或者随组取消的通知影响 0 行——收口不是覆盖，抢不到就是别人先处理了。</p>
     */
    int closeRecoveryNotification(@Param("notificationId") long notificationId,
                                  @Param("reason") String reason);

    // ===== 取消 =====

    /** 组、还没结束的成员、下一段与还没被取走的通知一起停。 */
    WaitChainCancelRow cancelChain(@Param("groupId") long groupId);

    Integer ensureCanceledMemberStopTasks(@Param("groupId") long groupId);

    // ===== 成员派发 =====

    int recordMemberPreparing(@Param("groupId") long groupId,
                              @Param("memberIdentity") String memberIdentity,
                              @Param("externalOperationId") String externalOperationId,
                              @Param("dispatchProofJson") String dispatchProofJson);

    /**
     * 派发成功：成员从待派发进入执行中，并把外部作业身份与派发证明写上。
     *
     * <p>只对还没派发的成员生效，重复派发影响零行，调用方据此判断这一次是不是自己送出去的。</p>
     */
    int markMemberDispatched(@Param("groupId") long groupId,
                             @Param("memberIdentity") String memberIdentity,
                             @Param("externalOperationId") String externalOperationId,
                             @Param("dispatchProofJson") String dispatchProofJson,
                             @Param("nextPollAt") OffsetDateTime nextPollAt,
                             @Param("runControlVersion") long runControlVersion);

    // ===== 成员轮询 =====

    /** 到点该查询外部作业状态的成员：还在执行中、下次查询时间已到。 */
    List<WaitMember> scanDueMembers(@Param("now") OffsetDateTime now,
                                   @Param("limit") int limit);

    List<WaitMember> scanDueMembersForDeployment(@Param("deploymentId") String deploymentId,
                                                  @Param("deploymentGenerationId") String deploymentGenerationId,
                                                  @Param("now") OffsetDateTime now,
                                                  @Param("limit") int limit);

    List<WaitMember> scanUnresolvedPythonMembersForCapacity(@Param("deploymentId") String deploymentId,
                                                             @Param("deploymentGenerationId") String deploymentGenerationId,
                                                             @Param("afterMemberId") long afterMemberId,
                                                             @Param("limit") int limit);

    List<WaitMember> scanDueSubAgentMembers(@Param("now") OffsetDateTime now,
                                            @Param("limit") int limit);

    /** 成员还在执行中时推后下次查询时间；已经落终态的成员不会被改。 */
    int rescheduleMember(@Param("groupId") long groupId,
                         @Param("memberIdentity") String memberIdentity,
                         @Param("nextPollAt") OffsetDateTime nextPollAt,
                         @Param("maxBackoffStep") int maxBackoffStep);

    /** 按验收夹具的放行策略压住成员：只推下次查询时间，不动轮询次数与退避步数。 */
    int holdMember(@Param("groupId") long groupId,
                   @Param("memberIdentity") String memberIdentity,
                   @Param("nextPollAt") OffsetDateTime nextPollAt);
}
