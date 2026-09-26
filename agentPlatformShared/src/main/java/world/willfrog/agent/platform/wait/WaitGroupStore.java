package world.willfrog.agent.platform.wait;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 等待组、等待成员与持久恢复通知的存储接口：新调度器版本上「一个节点分段在等外部结果」这件事的唯一权威落点。
 *
 * <p>它要能证明三句话，三条都由条件更新与唯一约束一起保证，不靠调用方自觉：</p>
 * <ul>
 *   <li>成员只结束一次：{@link #completeMember} 只在成员还没落终态时写入。</li>
 *   <li>最后一个成员只产生一次恢复资格：组齐备与写通知在同一条语句里，通知另外还有
 *       「组 + 恢复代际」唯一约束。</li>
 *   <li>旧领取提交为零：{@link #consumeRecovery} 只放行可恢复状态，真正执行还要再按领取代际条件领取。</li>
 * </ul>
 *
 * <p>所有写入都不先读后写：调用方拿到结果再决定下一步，拿到「没写进去」就放弃这一次。</p>
 */
public interface WaitGroupStore {

    /**
     * 整组挂起：结束当前执行分段、保存等待组与全部成员、把下一段建成等待态，一条语句一件事。
     *
     * <p>当前分段不在调用方手里时一行都不写，返回「分段不匹配」。</p>
     */
    WaitSuspensionResult suspendSegment(WaitSuspensionRequest request);

    /** 成员正常结束一次（成功或失败）；重复上报不会写第二次，也不会重复计数。 */
    MemberCompletionResult completeMember(MemberCompletionRequest request);

    /** 迟到结果只留档：成员改成迟到，并把这条链一起停下，避免其他成员一直等下去。 */
    MemberCompletionResult reportLateMember(LateMemberRequest request);

    /**
     * 恢复分发器消费一条通知：取走它并把下一段放成可恢复。
     *
     * <p>Run 的状态或控制版本不符时整条语句什么都不做（通知留在原地），调用方不得把这种情况当成成功。</p>
     */
    /**
     * 消费一条恢复通知：放行下一段、组改成已恢复、通知改成已消费。
     *
     * <p>调用方必须带着自己手上的服务所有权（持有者与代际）进来：语句会把租约行与 Run 行一起锁住，
     * 非所有者、过期代际、以及判断与写入之间被接手的情形都影响 0 行。</p>
     */
    RecoveryConsumptionResult consumeRecovery(long notificationId,
                                              String dispatcherId,
                                              long runControlVersion,
                                              String ownerInstanceId,
                                              long fencingToken);

    /**
     * 把一条不可能再被服务的通知收口成关闭态并写明原因；只对还在等待态的通知生效。
     *
     * <p>用于 Run 已经终态、计划或控制版本已经作废、下一段已经不在等待态这些情形：继续退避只会
     * 让它永久占用固定扫描名额。返回 false 表示这条通知已经被别人取走、关闭或取消。</p>
     */
    boolean closeRecoveryNotification(long notificationId, String reason);

    /** 取消一条等待链：组、还没结束的成员、下一段与还没被取走的通知一起停。 */
    WaitChainCancelResult cancelChain(long groupId);

    /** 已取消的组中，给漏掉的 Python 外部作业幂等补建持久停机任务。 */
    default int ensureCanceledMemberStopTasks(long groupId) {
        throw new UnsupportedOperationException("canceled member stop repair is not implemented");
    }

    /**
     * 在调用 Sandbox 创建任务之前，先保存可恢复的操作身份、请求指纹与容量凭证。
     * 取消若先落库，此操作返回 false，调用方不能再发出外部请求。
     */
    default boolean recordMemberPreparing(long groupId, String memberIdentity,
                                          String externalOperationId, String dispatchProofJson) {
        throw new UnsupportedOperationException("member preparing proof is not implemented");
    }

    /** 按组编号分页找一条 Run 尚未关闭的等待链，供取消与重启恢复逐组收口。 */
    default List<WaitGroup> listOpenGroupsByRun(String runId, long afterGroupId, int limit) {
        throw new UnsupportedOperationException("open wait-group scan is not implemented");
    }

    /**
     * 找出父 Run 已经终止或留下持久取消意图、但等待链仍开放的组。
     * 按组编号分页，供进程重启和取消收尾失败后继续排入外部停机任务。
     */
    default List<WaitGroup> scanOpenGroupsWithStoppedRun(long afterGroupId, int limit) {
        throw new UnsupportedOperationException("stopped run wait-group scan is not implemented");
    }

    /** 按组编号分页找出已取消、但有已派发 Python 成员缺少停机任务的组。 */
    default List<WaitGroup> scanCanceledGroupsMissingStopTasks(long afterGroupId, int limit) {
        throw new UnsupportedOperationException("canceled member stop scan is not implemented");
    }

    /** 旧派发线程退出后，找到仍保留创建前请求证明的 Python 成员。 */
    default List<WaitMember> scanPendingPythonMembersWithProof(String deploymentId,
                                                                String deploymentGenerationId,
                                                                long afterMemberId, int limit) {
        throw new UnsupportedOperationException("pending Python recovery scan is not implemented");
    }

    /** 同时核对旧工作项领取身份和已归还的 ACTIVE_NODE 额度，才可发送稳定取消请求。 */
    default boolean safeToRecoverPendingPython(long memberId, long workItemId, int claimEpoch,
                                               String claimedBy, String operationId, String fingerprint) {
        throw new UnsupportedOperationException("pending Python recovery guard is not implemented");
    }

    /** Sandbox 已持久接纳同一操作的取消墓碑后，才开放结果接收。 */
    default boolean recoverPendingPythonMember(long memberId, long workItemId, int claimEpoch,
                                               String claimedBy, String operationId, String fingerprint) {
        throw new UnsupportedOperationException("pending Python recovery CAS is not implemented");
    }

    /**
     * 派发成功：成员从「已保存待派发」进入「已派发执行中」，并写上外部作业身份、派发证明与下次查询时间。
     *
     * <p>派发证明是这次后台作业的完整事实（canonical 请求规格、预估值、名额预留、后台任务编号），
     * 结果接收方要靠它构造终态信封并释放名额。空值表示这一次不更新已有证明。</p>
     *
     * <p>只对还没派发的成员生效，重复派发返回 false。已经落终态的成员不会被改。</p>
     */
    boolean markMemberDispatched(long groupId,
                                 String memberIdentity,
                                 String externalOperationId,
                                 String dispatchProofJson,
                                 OffsetDateTime nextPollAt,
                                 long runControlVersion);

    /**
     * 到点该查询外部作业状态的那些成员：还在执行中、下次查询时间已经到，等得最久的排最前。
     *
     * <p>结果接收方拿它决定这一轮去问哪些后台作业；查询时间由派发与上一次查询各自写上，
     * 取一批候选、有界。</p>
     */
    List<WaitMember> scanDueMembers(OffsetDateTime now, int limit);

    /** 只返回本部署代际到期的 Python 成员，避免共享队列中的其他部署占满分页。 */
    List<WaitMember> scanDueMembers(String deploymentId, String deploymentGenerationId,
                                    OffsetDateTime now, int limit);

    /** 启动恢复容量账本时分页核对尚未收尾的 Python 成员。 */
    default List<WaitMember> scanUnresolvedPythonMembersForCapacity(String deploymentId,
                                                                      String deploymentGenerationId,
                                                                      long afterMemberId, int limit) {
        throw new UnsupportedOperationException("Python capacity member scan is not implemented");
    }

    /** 到期的持久子代理创建/等待成员，由子代理协调器接回，不能交给 Python 作业接收器。 */
    default List<WaitMember> scanDueSubAgentMembers(OffsetDateTime now, int limit) {
        throw new UnsupportedOperationException("sub-agent member scan is not implemented");
    }

    /** 成员还在执行中时推后它的下次查询时间并累加轮询次数；已经落终态的成员不会被改。 */
    boolean rescheduleMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt, int maxBackoffStep);

    /**
     * 按验收夹具的放行策略压住一条成员：只推下次查询时间，不累加轮询次数与退避步数。
     *
     * <p>被压住与「问不到结论」是两回事：退避步数只在真的问不到结论时才涨，被压住的成员放行之后
     * 最多等一个轮询间隔就会被接回来。只对还在执行中的成员生效。</p>
     */
    boolean holdMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt);

    Optional<WaitGroup> findGroup(WaitGroupIdentity identity);

    Optional<WaitGroup> findGroup(long groupId);

    List<WaitMember> listMembers(long groupId);

    /**
     * 按外部作业身份找成员：结果接收路径的入口。
     *
     * <p>外部作业身份在库里是全局唯一的，这里带上 Run 是额外的身份核对：拿到行之后还要确认它确实
     * 属于这条 Run，不能只凭作业身份就往回接结果。</p>
     */
    Optional<WaitMember> findMemberByOperation(String runId, String externalOperationId);

    Optional<WaitMember> findMemberByIdentity(long groupId, String memberIdentity);

    List<RecoveryNotification> listNotifications(long groupId);

    /** 按编号读一条恢复通知；内存提示只带编号，取之前必须回库里读一次权威状态。 */
    Optional<RecoveryNotification> findNotification(long notificationId);

    /**
     * 到期的恢复通知：还没被取走、已经到了下次可见时间，等得最久的排最前。
     *
     * <p>周期补扫与启动扫描用它把通知重新发现：内存提示可以丢，库里这条通知还在就得有人来取。</p>
     */
    List<RecoveryNotification> scanDueRecoveryNotifications(int limit);

    /**
     * 把一条通知推后到某个时刻：取不走时按退避推迟，避免同一批候选被反复捞。
     *
     * <p>返回 false 表示这条通知已经不在等待态，或者新时间不比原来的晚（迟到的延期写入不许把
     * 时间拉回来）。</p>
     */
    boolean deferRecoveryNotification(long notificationId, OffsetDateTime nextVisibleAt);
}
