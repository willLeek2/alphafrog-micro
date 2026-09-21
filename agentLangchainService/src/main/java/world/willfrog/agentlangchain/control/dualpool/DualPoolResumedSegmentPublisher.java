package world.willfrog.agentlangchain.control.dualpool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.execution.ResumedSegmentPublisher;

/**
 * 「提交之后马上唤醒」这一条恢复路径：整组在同一次领取里就齐备时，由写结果的那个线程把通知消费掉，
 * 下一段立刻重新进入节点池。
 *
 * <p>能不能消费由 {@link WaitGroupRecoveryIntake} 说了算，与周期补扫、启动扫描同一条口径：先拿服务
 * 所有权与业务名额，再取走那条不可逆的恢复资格。这里不再自己判断条件，也不再用调用方手上的分段身份
 * 去投递——投递只用消费语句返回的那一段。调用方给的段与库里的不一致时按库里的走，并把不一致报出来：
 * 调用方看到的那一段可能是它自己算出来的，真正被放行的是数据库改的那一行。</p>
 *
 * <p>只有这一条路是不够的：进程退出、通知写出来时没人守着、内存提示丢了这些情况都需要周期补扫与
 * 启动扫描，那一套在 {@link DualPoolRecoveryDispatcher}。这里没消费成时叫它一声，让它下一轮先试这条
 * 通知；它自己也会按数据库把到期的通知重新发现。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DualPoolResumedSegmentPublisher implements ResumedSegmentPublisher {

    private static final String DISPATCHER_ID = "dual-pool-wait-group-online";

    private final WaitGroupStore waitGroupStore;
    private final AgentRunMapper runMapper;
    private final DualPoolDispatcher dispatcher;
    private final DualPoolRecoveryDispatcher recoveryDispatcher;
    private final WaitGroupRecoveryIntake intake;

    @Override
    public boolean publish(long notificationId, long runControlVersion, NodeWorkItemIdentity nextSegment) {
        if (nextSegment == null) {
            throw new IllegalArgumentException("放行下一段必须给出分段身份");
        }
        RecoveryNotification notification = waitGroupStore.findNotification(notificationId).orElse(null);
        if (notification == null) {
            log.warn("这条恢复通知读不回来，先不动它：notification={}", notificationId);
            return false;
        }
        AgentRun run = runMapper.findById(notification.getRunId());
        if (run != null && run.getRunControlVersion() != null
                && run.getRunControlVersion() != runControlVersion) {
            // 调用方手上的控制版本已经过期：以数据库为准往下走，这里只留一笔。
            log.warn("调用方手上的控制版本与库里不一致：notification={} 调用方={} 库里={}",
                    notificationId, runControlVersion, run.getRunControlVersion());
        }
        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification, run, DISPATCHER_ID);
        if (!result.consumed()) {
            if (result.outcome() == WaitGroupRecoveryIntake.Outcome.DEFERRED) {
                // 叫一次周期补扫的「马上再试一次」，比等下一个扫描节拍快；叫不动也不影响结果落库。
                recoveryDispatcher.wake(notificationId);
            }
            log.info("这条恢复通知没有立刻放行下一段：notification={} outcome={} reason={}",
                    notificationId, result.outcome(),
                    result.rejection() == null ? result.detail() : result.rejection().name());
            return false;
        }
        NodeWorkItemIdentity authoritative = result.nextSegment();
        if (!authoritative.equals(nextSegment)) {
            log.error("调用方给出的下一段与数据库放行的不是同一段，按数据库放行的那一段投递："
                            + "notification={} 调用方={} 权威={}",
                    notificationId, nextSegment.describe(), authoritative.describe());
        }
        return dispatcher.offerNode(authoritative);
    }
}
