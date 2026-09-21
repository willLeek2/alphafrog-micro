package world.willfrog.agentlangchain.control.dualpool;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.List;
import java.util.Map;

/**
 * 双池调度器与数据库工作项状态机之间的边界。
 *
 * <p>实现方必须先用数据库条件更新取得本轮所有权，再执行方法中的业务动作。
 * 内存提示队列不授予所有权，租约到期也不能在这里隐式更换领取者。</p>
 */
public interface DualPoolWorkHandler {

    /** 执行一次短促的 Run 协调回合；返回前必须归还协调线程。 */
    void coordinateRun(RunCoordinationHint hint);

    /** 领取并执行一个节点分段；陈旧或重复提示应当由条件更新安全地变成空操作。 */
    void executeNode(NodeWorkItemIdentity identity);

    /** 从数据库重新发现可以协调的 Run；返回值仅用于补发提示。 */
    List<RunCoordinationHint> scanRunnableRuns(int limit);

    /** 从数据库重新发现可以领取的节点工作项；返回值仅用于补发提示。 */
    List<NodeWorkItemIdentity> scanRunnableNodes(int limit);

    /**
     * 候选路由的读数：共享候选里有多少条这一轮没能被接手、有多少条因为版本读不出来被隔离。
     *
     * <p>共享候选对三个调度器版本是同一份，但能不能接手由各版本自己的所有权事实决定；
     * 接不了的条数要能被看到，否则「一直在候选里排队却没被服务」看上去只是还没轮到。</p>
     */
    Map<String, Object> routingSnapshot();

    /**
     * 内存提示没能送出去时留下延期事实：写清楚原因，并把下次可见时间推后。
     *
     * <p>内存队列允许丢提示，因为数据库才是权威；但「这条工作项这一轮没被派发、下次什么时候再看」
     * 必须能被读到，否则排队的图看上去与从没被调度过没有区别。</p>
     */
    void deferHintDelivery(NodeWorkItemIdentity identity);
}
