package world.willfrog.agentlangchain.workspace;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ListWorkspaceExpiryCandidatesRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ListWorkspaceExpiryCandidatesResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceDeleteOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceExpiryCandidate;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceInfo;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceQueryOutcome;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 将沙箱网关的只读候选、按 Run 查询和条件删盘结果转换成清理器的窄合同。 */
@Service
public class DubboPythonWorkspaceRetentionPort implements PythonWorkspaceRetentionPort {
    private static final Set<String> LIVE_STATUSES = Set.of("active", "dirty", "deleting", "sealed");

    @DubboReference
    private PythonSandboxService sandbox;

    @Override
    public List<Workspace> listCandidates(String afterWorkspaceId, int limit) {
        if (afterWorkspaceId == null || limit <= 0 || limit > 500) {
            throw new IllegalArgumentException("沙箱工作区分页参数无效");
        }
        ListWorkspaceExpiryCandidatesResponse response = sandbox.listWorkspaceExpiryCandidates(
                ListWorkspaceExpiryCandidatesRequest.newBuilder()
                        .setPageSize(limit)
                        .setPageToken(afterWorkspaceId)
                        .build());
        requireHealthy(response != null && !response.hasErrorDetail() && response.getError().isBlank(),
                "沙箱没有确认工作区候选页");
        requireHealthy(response.getCandidatesCount() <= limit, "沙箱候选页超过请求上限");

        List<Workspace> result = new ArrayList<>(response.getCandidatesCount());
        String previous = afterWorkspaceId;
        for (WorkspaceExpiryCandidate candidate : response.getCandidatesList()) {
            requireHealthy(!candidate.getRunId().isBlank()
                            && !candidate.getWorkspaceId().isBlank()
                            && candidate.getWorkspaceId().compareTo(previous) > 0
                            && !candidate.getWorkspaceGeneration().isBlank()
                            && LIVE_STATUSES.contains(candidate.getStatus()),
                    "沙箱候选页的身份、状态或排序无效");
            result.add(new Workspace(candidate.getRunId(), candidate.getWorkspaceId(),
                    candidate.getWorkspaceGeneration(), parseTime(candidate.getLastActiveAt())));
            previous = candidate.getWorkspaceId();
        }
        String next = response.getNextPageToken();
        requireHealthy(next.isBlank() || (!result.isEmpty() && next.equals(previous)),
                "沙箱候选页的继续令牌无效");
        return List.copyOf(result);
    }

    @Override
    public Lookup findByRun(String runId) {
        requireId(runId, "Run");
        QueryWorkspaceResponse response = sandbox.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId(runId).build());
        requireHealthy(response != null && !response.hasErrorDetail() && response.getError().isBlank(),
                "沙箱没有确认按 Run 查盘结果");
        WorkspaceQueryOutcome outcome = response.getOutcome();
        if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND) {
            requireHealthy(!response.hasWorkspace() && !response.hasLastActiveAt()
                            && !response.hasDeletedAt(), "沙箱空结果携带了工作区事实");
            return new Lookup(LookupState.NOT_FOUND, null);
        }
        if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND && !response.hasWorkspace()) {
            // 已封口但从未建盘。自动清理不会把它当作已删审计。
            requireHealthy(response.hasLastActiveAt() && !response.hasDeletedAt(),
                    "沙箱无盘封口记录缺少时间或误带删除时刻");
            parseTime(response.getLastActiveAt());
            return new Lookup(LookupState.FOUND, null);
        }
        requireHealthy(outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND
                        || outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED,
                "沙箱返回未知的查盘结果");
        requireHealthy(response.hasWorkspace() && response.hasLastActiveAt(),
                "沙箱查盘结果缺少工作区身份或活动时间");
        WorkspaceInfo info = response.getWorkspace();
        requireHealthy(runId.equals(info.getOwnedByRunId())
                        && !info.getWorkspaceId().isBlank()
                        && !info.getWorkspaceGeneration().isBlank(),
                "沙箱查盘结果与 Run 身份不符");
        if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED) {
            requireHealthy("deleted".equals(info.getStatus()) && response.hasDeletedAt(),
                    "沙箱删除审计不完整");
            parseTime(response.getDeletedAt());
            parseTime(response.getLastActiveAt());
            return new Lookup(LookupState.DELETED, null);
        }
        requireHealthy(LIVE_STATUSES.contains(info.getStatus()) && !response.hasDeletedAt(),
                "沙箱在盘状态与查询结果矛盾");
        return new Lookup(LookupState.FOUND, new Workspace(runId, info.getWorkspaceId(),
                info.getWorkspaceGeneration(), parseTime(response.getLastActiveAt())));
    }

    @Override
    public DeleteResult deleteIfUnchanged(Workspace workspace) {
        requireHealthy(workspace != null, "待删工作区为空");
        requireId(workspace.runId(), "Run");
        requireId(workspace.workspaceId(), "工作区");
        requireId(workspace.workspaceGeneration(), "工作区代际");
        requireHealthy(workspace.lastActiveAt() != null, "待删工作区缺少活动时间");
        DeleteWorkspaceResponse response = sandbox.deleteWorkspace(DeleteWorkspaceRequest.newBuilder()
                .setWorkspaceId(workspace.workspaceId())
                .setIdempotencyKey("run-workspace-expire:" + workspace.runId() + ":" + workspace.workspaceId())
                .setExpectedLastActiveAt(workspace.lastActiveAt().toInstant().toString())
                .build());
        requireHealthy(response != null && !response.hasErrorDetail() && response.hasOutcome(),
                "沙箱没有确认条件删盘结果");
        WorkspaceDeleteOutcome outcome = response.getOutcome();
        if (outcome == WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED) {
            requireHealthy(response.getDeleted() && !response.getRetryableFailure(),
                    "沙箱已删结果与布尔字段矛盾");
            return DeleteResult.DELETED;
        }
        if (outcome == WorkspaceDeleteOutcome.WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY) {
            requireHealthy(!response.getDeleted() && !response.getRetryableFailure(),
                    "沙箱活动变化结果与布尔字段矛盾");
            return DeleteResult.ACTIVITY_CHANGED;
        }
        if (outcome == WorkspaceDeleteOutcome.WORKSPACE_DELETE_TEMPORARILY_UNAVAILABLE) {
            requireHealthy(!response.getDeleted() && response.getRetryableFailure(),
                    "沙箱暂不可用结果与布尔字段矛盾");
            return DeleteResult.RETRYABLE_FAILURE;
        }
        throw new IllegalStateException("沙箱返回未知的条件删盘结果");
    }

    private static OffsetDateTime parseTime(String value) {
        requireHealthy(value != null && !value.isBlank(), "沙箱工作区时间缺失");
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("沙箱工作区时间格式无效", invalid);
        }
    }

    private static void requireId(String id, String kind) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(kind + "编号缺失");
        }
    }

    private static void requireHealthy(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
