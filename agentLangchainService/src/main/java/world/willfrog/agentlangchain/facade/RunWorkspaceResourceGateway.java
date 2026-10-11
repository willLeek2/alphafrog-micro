package world.willfrog.agentlangchain.facade;

/**
 * 用户删除 Run 时使用的沙箱操作。实现方负责调用沙箱网关并核验业务响应，
 * 只有得到明确的持久确认才能正常返回；超时、未分类响应和暂时故障都必须抛异常。
 */
public interface RunWorkspaceResourceGateway {
    /** 即使 Run 没有工作区，也要确认沙箱已持久禁止该 Run 新建资源。重复调用须安全。 */
    void sealRun(String runId);

    /** 只读查找，不得创建工作区；必须明确区分从未创建、已删除审计和状态不明。 */
    WorkspaceLookup findWorkspaceByRun(String runId);

    /** 重复调用须安全；仅当磁盘删除及不可复活审计均已确认后正常返回。 */
    /** Run 编号用于从持久记录恢复泳道，不能从幂等键或入站请求猜测。 */
    void deleteWorkspace(String runId, String workspaceId, String idempotencyKey);

    enum WorkspaceState {
        NEVER_CREATED,
        ACTIVE,
        DIRTY,
        DELETING,
        DELETED,
        UNKNOWN
    }

    record WorkspaceLookup(WorkspaceState state, String workspaceId, String ownedByRunId) {
    }
}
