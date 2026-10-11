package world.willfrog.agentlangchain.workspace;

import java.time.OffsetDateTime;
import java.util.List;

/** 沙箱网关的工作区到期查询和条件删除边界；所有方法只经网关访问沙箱。
 * 每次调用都沿用调用线程的可信泳道标签；候选查询只返回该泳道的记录。
 */
public interface PythonWorkspaceRetentionPort {

    /** 按工作区编号分页，不按创建它的 Agent 部署代际或旧到期时刻过滤。 */
    List<Workspace> listCandidates(String afterWorkspaceId, int limit);

    /** 按 Run 编号只读查盘及删除审计；查不到不能新建工作区。 */
    Lookup findByRun(String runId);

    /** Agent 已按本轮 Nacos 保留期核对到期；沙箱在状态锁中比较活动时间并核对容器。 */
    DeleteResult deleteIfUnchanged(Workspace workspace);

    record Workspace(String runId, String workspaceId, String workspaceGeneration,
                     OffsetDateTime lastActiveAt) { }

    enum LookupState { FOUND, DELETED, NOT_FOUND }

    record Lookup(LookupState state, Workspace workspace) { }

    enum DeleteResult { DELETED, ACTIVITY_CHANGED, RETRYABLE_FAILURE }
}
