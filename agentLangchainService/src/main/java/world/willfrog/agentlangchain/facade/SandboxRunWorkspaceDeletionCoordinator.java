package world.willfrog.agentlangchain.facade;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 数据库已标记整棵调用树待删除后，逐个确认沙箱资源不再能新建且已经删净。 */
@Service
public class SandboxRunWorkspaceDeletionCoordinator implements RunWorkspaceDeletionCoordinator {
    @Autowired(required = false)
    private RunWorkspaceResourceGateway gateway;

    @Override
    public void sealFindAndDelete(List<String> runIds) {
        validateRunIds(runIds);
        if (gateway == null) {
            throw new IllegalStateException("沙箱工作区删除接口暂不可用，Run 删除标记已保留，请稍后重试");
        }

        // 整棵调用树先全部持久封口；任一封口失败时，不查盘也不释放任何资源。
        for (String runId : runIds) {
            try {
                gateway.sealRun(runId);
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "Run " + runId + " 的沙箱封口未确认；数据库删除标记已保留，请重试", ex);
            }
        }

        for (String runId : runIds) {
            try {
                // 封口成功前不能把“查不到盘”当作可删除 Run 的证据。
                RunWorkspaceResourceGateway.WorkspaceLookup found = gateway.findWorkspaceByRun(runId);
                if (found == null || found.state() == null
                        || found.state() == RunWorkspaceResourceGateway.WorkspaceState.UNKNOWN) {
                    throw new IllegalStateException("沙箱没有确认工作区查找结果");
                }
                if (!runId.equals(found.ownedByRunId())) {
                    throw new IllegalStateException("沙箱返回的工作区归属与 Run 不一致");
                }
                if (found.state() == RunWorkspaceResourceGateway.WorkspaceState.NEVER_CREATED) {
                    if (found.workspaceId() != null && !found.workspaceId().isBlank()) {
                        throw new IllegalStateException("沙箱返回的空工作区状态与工作区身份矛盾");
                    }
                    continue;
                }
                if (found.workspaceId() == null || found.workspaceId().isBlank()) {
                    throw new IllegalStateException("沙箱没有返回工作区身份");
                }
                if (found.state() == RunWorkspaceResourceGateway.WorkspaceState.DELETED) {
                    continue;
                }
                gateway.deleteWorkspace(runId, found.workspaceId(), deletionKey(runId));
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "Run " + runId + " 的沙箱封口、查找或删盘未全部确认；数据库删除标记已保留，请重试", ex);
            }
        }
    }

    private static String deletionKey(String runId) {
        return "run-workspace-delete:" + runId;
    }

    private static void validateRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            throw new IllegalArgumentException("待删除的 Run 列表不能为空");
        }
        Set<String> seen = new HashSet<>();
        for (String runId : runIds) {
            if (runId == null || runId.isBlank() || !seen.add(runId)) {
                throw new IllegalArgumentException("待删除的 Run 列表包含空值或重复编号");
            }
        }
    }
}
