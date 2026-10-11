package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

/**
 * 组齐备之后把下一段放成可恢复，并把它投给节点池。
 *
 * <p>调用时机只有一个：某一个分段在同一次领取里就让整组工具都落了终态（同步工具就是这种情形），
 * 这时刚写出的恢复通知还热着，本线程直接把它消费掉，下一段立刻可以再被领取。周期补扫、启动扫描、
 * 每轮处理上限与退避属于后面那一组要做的事，这一层只负责「提交之后马上唤醒」这一条。</p>
 */
public interface ResumedSegmentPublisher {

    /**
     * 消费一条恢复通知并把下一段投给节点池。
     *
     * @param notificationId    这次刚写出的恢复通知编号
     * @param runControlVersion 当前 Run 的控制版本
     * @param nextSegment       要放成可恢复的分段身份
     * @return 通知被取走且下一段已经放行时为真
     */
    boolean publish(long notificationId, long runControlVersion, NodeWorkItemIdentity nextSegment);
}
