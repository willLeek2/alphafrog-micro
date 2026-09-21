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
    RecoveryConsumptionResult consumeRecovery(long notificationId, String dispatcherId, long runControlVersion);

    /** 取消一条等待链：组、还没结束的成员、下一段与还没被取走的通知一起停。 */
    WaitChainCancelResult cancelChain(long groupId);

    /**
     * 派发成功：成员从「已保存待派发」进入「已派发执行中」，并写上外部作业身份与下次查询时间。
     *
     * <p>只对还没派发的成员生效，重复派发返回 false。已经落终态的成员不会被改。</p>
     */
    boolean markMemberDispatched(long groupId,
                                 String memberIdentity,
                                 String externalOperationId,
                                 OffsetDateTime nextPollAt,
                                 long runControlVersion);

    /** 成员还在执行中时推后它的下次查询时间并累加轮询次数；已经落终态的成员不会被改。 */
    boolean rescheduleMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt, int maxBackoffStep);

    Optional<WaitGroup> findGroup(WaitGroupIdentity identity);

    Optional<WaitGroup> findGroup(long groupId);

    List<WaitMember> listMembers(long groupId);

    /** 按外部作业身份找成员：结果接收路径的入口，同一个 Run 内唯一。 */
    Optional<WaitMember> findMemberByOperation(String runId, String externalOperationId);

    Optional<WaitMember> findMemberByIdentity(long groupId, String memberIdentity);

    List<RecoveryNotification> listNotifications(long groupId);
}
