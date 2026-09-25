package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.ArrayList;
import java.util.List;

/** 把用户的一次删除扩展到已结清的整棵调用树，避免留下可见的孤立子 Run。 */
@Service
@RequiredArgsConstructor
public class AgentRunFamilyDeletionService {
    private final AgentRunMapper runs;
    private final ChildRunIntentStore children;

    /** 返回已删除的 Run 编号；调用方在事务提交后清除对应的缓存状态。 */
    @Transactional
    public List<String> deleteRoot(String rootRunId, String userId) {
        AgentRun root = runs.findByIdAndUserForUpdate(rootRunId, userId);
        if (root == null) {
            throw new IllegalArgumentException("run not found");
        }
        if (runs.isChildRun(rootRunId)) {
            throw new IllegalStateException("子代理是内部执行记录，请通过父 Run 管理");
        }
        if (running(root.getStatus())) {
            throw new IllegalStateException("run is running, cancel/pause first");
        }
        if (children.hasUnsettledDescendants(rootRunId)) {
            throw new IllegalStateException("子代理或外部任务仍在收尾，稍后再删除父 Run");
        }

        List<AgentRun> childRuns = runs.listChildRunsByRootForUpdate(rootRunId);
        if (!childRuns.isEmpty() && !terminal(root.getStatus())) {
            throw new IllegalStateException("父 Run 尚未结束，请先取消后再删除父子记录");
        }
        for (AgentRun child : childRuns) {
            if (!userId.equals(child.getUserId()) || !terminal(child.getStatus())) {
                throw new IllegalStateException("子 Run 身份或终态尚未确认，不能删除父子记录");
            }
        }
        // 嵌套子 Run 可能仍被下一层意图的 parent_run_id 引用；先删关系，后删 Run。
        // 两步在同一事务中，任何一条删除失败都会恢复整棵树的原状。
        runs.deleteChildIntentsByRoot(rootRunId);
        List<String> deleted = new ArrayList<>(childRuns.size() + 1);
        for (AgentRun child : childRuns) {
            if (runs.deleteByIdAndUser(child.getId(), userId) != 1) {
                throw new IllegalStateException("删除子 Run 时状态已变化：" + child.getId());
            }
            deleted.add(child.getId());
        }
        // 零占用行的根 Run 外键也必须在根 Run 删除前清理。
        runs.deleteEmptyTreeCapacityByRoot(rootRunId);
        if (runs.deleteByIdAndUser(rootRunId, userId) != 1) {
            throw new IllegalStateException("删除父 Run 时状态已变化：" + rootRunId);
        }
        deleted.add(rootRunId);
        return List.copyOf(deleted);
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }

    private static boolean running(AgentRunStatus status) {
        return status == AgentRunStatus.RECEIVED || status == AgentRunStatus.PLANNING
                || status == AgentRunStatus.EXECUTING || status == AgentRunStatus.SUMMARIZING;
    }
}
