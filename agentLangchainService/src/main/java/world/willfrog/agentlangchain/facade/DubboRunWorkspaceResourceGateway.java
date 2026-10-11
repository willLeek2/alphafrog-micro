package world.willfrog.agentlangchain.facade;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SealWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.SealWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceDeleteOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceInfo;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceQueryOutcome;

import java.util.Objects;

/** 把手动删除的每一步发到 Run 持久泳道，并且只接受沙箱明确的持久结果。 */
@Service
public class DubboRunWorkspaceResourceGateway implements RunWorkspaceResourceGateway {
    private final AgentRunMapper runs;
    private final DeploymentIdentityProvider identity;

    @DubboReference
    private PythonSandboxService sandbox;

    public DubboRunWorkspaceResourceGateway(AgentRunMapper runs,
                                            DeploymentIdentityProvider identity) {
        this.runs = runs;
        this.identity = identity;
    }

    @Override
    public void sealRun(String runId) {
        AgentRun run = requireRun(runId);
        SealWorkspaceResponse response = LaneScopeGateway.call(run, () -> sandbox.sealWorkspace(
                SealWorkspaceRequest.newBuilder()
                        .setRunId(runId)
                        .setIdempotencyKey("run-workspace-seal:" + runId)
                        .build()));
        if (response == null || response.hasErrorDetail() || !response.getSealed()) {
            throw unconfirmed(runId, "封口");
        }
        if (response.hasWorkspace()) {
            WorkspaceInfo workspace = response.getWorkspace();
            requireWorkspaceOwner(runId, workspace);
            // 有盘封口应已阻止继续取得与受理；active/dirty 仍可写，不能据此删 Run。
            if (!"sealed".equals(workspace.getStatus())
                    && !"deleting".equals(workspace.getStatus())
                    && !"deleted".equals(workspace.getStatus())) {
                throw unconfirmed(runId, "封口状态");
            }
        }
    }

    @Override
    public WorkspaceLookup findWorkspaceByRun(String runId) {
        AgentRun run = requireRun(runId);
        QueryWorkspaceResponse response = LaneScopeGateway.call(run, () -> sandbox.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId(runId).build()));
        if (response == null || response.hasErrorDetail()) {
            throw unconfirmed(runId, "查询");
        }
        WorkspaceQueryOutcome outcome = response.getOutcome();
        if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND) {
            if (!response.hasWorkspace()) {
                return new WorkspaceLookup(WorkspaceState.NEVER_CREATED, null, runId);
            }
            WorkspaceInfo workspace = response.getWorkspace();
            requireWorkspaceOwner(runId, workspace);
            WorkspaceState state = switch (workspace.getStatus()) {
                // This read follows a durable seal. A writable status would
                // contradict it and cannot justify removing the Run row.
                case "sealed" -> WorkspaceState.ACTIVE;
                case "deleting" -> WorkspaceState.DELETING;
                default -> throw unconfirmed(runId, "查询状态");
            };
            return new WorkspaceLookup(state, requireText(workspace.getWorkspaceId(), "workspaceId"), runId);
        }
        if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED) {
            if (!response.hasWorkspace()) {
                throw unconfirmed(runId, "已删除审计身份");
            }
            WorkspaceInfo workspace = response.getWorkspace();
            requireWorkspaceOwner(runId, workspace);
            if (!"deleted".equals(workspace.getStatus())) {
                throw unconfirmed(runId, "已删除审计状态");
            }
            return new WorkspaceLookup(WorkspaceState.DELETED,
                    requireText(workspace.getWorkspaceId(), "workspaceId"), runId);
        }
        // NOT_FOUND does not prove that the Run never had a workspace on another instance.
        throw unconfirmed(runId, "查询状态");
    }

    @Override
    public void deleteWorkspace(String runId, String workspaceId, String idempotencyKey) {
        AgentRun run = requireRun(runId);
        String safeWorkspaceId = requireText(workspaceId, "workspaceId");
        String safeKey = requireText(idempotencyKey, "idempotencyKey");
        DeleteWorkspaceResponse response = LaneScopeGateway.call(run, () -> sandbox.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder()
                        .setWorkspaceId(safeWorkspaceId)
                        .setIdempotencyKey(safeKey)
                        .build()));
        if (response == null || response.hasErrorDetail() || !response.getDeleted()
                || response.getRetryableFailure()
                || (response.hasOutcome()
                    && response.getOutcome() != WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED)) {
            throw unconfirmed(runId, "删盘");
        }
    }

    private AgentRun requireRun(String runId) {
        String safeRunId = requireText(runId, "runId");
        String deploymentId = identity.current().deploymentId();
        AgentRun run = runs.findById(safeRunId);
        if (run == null || !safeRunId.equals(run.getId())
                || !Objects.equals(deploymentId, run.getDeploymentId())) {
            throw new IllegalStateException("Run 不属于当前部署，拒绝操作沙箱资源");
        }
        return run;
    }

    private static void requireWorkspaceOwner(String runId, WorkspaceInfo workspace) {
        if (workspace == null || !runId.equals(workspace.getOwnedByRunId())
                || workspace.getWorkspaceId().isBlank()
                || workspace.getWorkspaceGeneration().isBlank()) {
            throw unconfirmed(runId, "工作区归属");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static IllegalStateException unconfirmed(String runId, String operation) {
        return new IllegalStateException("Run " + runId + " 的沙箱" + operation + "未得到明确确认");
    }
}
