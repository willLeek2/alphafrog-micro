package world.willfrog.agentlangchain.control.dualpool;

import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.List;

/**
 * Run 协调回合准备写入数据库的节点工作项。
 *
 * <p>LINEAR 与 DAG 都生成这个对象；区别只体现在依赖集合，节点池不据此解释编排规则。</p>
 */
public record NodeWorkDraft(NodeWorkItemIdentity identity,
                            String workflow,
                            List<String> dependencyNodeIds,
                            long contextVersion,
                            long runControlVersion,
                            String schedulerVersion,
                            TodoItem item) {

    public NodeWorkDraft {
        if (identity == null || item == null) {
            throw new IllegalArgumentException("node_work_draft_identity_and_item_required");
        }
        dependencyNodeIds = dependencyNodeIds == null ? List.of() : List.copyOf(dependencyNodeIds);
        if (contextVersion < 0 || runControlVersion < 0) {
            throw new IllegalArgumentException("node_work_draft_negative_version");
        }
        if (!SchedulerVersionPolicy.DUAL_POOL_V1.equals(schedulerVersion)) {
            throw new IllegalArgumentException("node_work_draft_scheduler_version_required");
        }
    }
}
