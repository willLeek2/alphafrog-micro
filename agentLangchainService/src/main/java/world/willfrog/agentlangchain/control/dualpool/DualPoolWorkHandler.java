package world.willfrog.agentlangchain.control.dualpool;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.List;

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
}
