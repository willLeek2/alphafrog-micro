package world.willfrog.agent.tools.python;

import world.willfrog.agent.tools.sandboxjob.SandboxCancelOutcomeView;
import world.willfrog.agent.tools.sandboxjob.SandboxCreateVerdict;
import world.willfrog.agent.tools.sandboxjob.SandboxJobRunnerAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobStatusView;
import world.willfrog.agent.tools.sandboxjob.SandboxJobObservability;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import com.google.protobuf.util.JsonFormat;

/**
 * executePython 的运行器适配器：Python 沙箱 Dubbo RPC 面。
 *
 * <p>所有判定逻辑（两档灾后回查严度、创建响应裁决、墓碑三方核验）从原
 * {@code PythonSandboxTools} 逐字搬入，两档差异是既有代码的真实差异，不做合并。</p>
 */
public final class PythonSandboxJobRunnerAdapter
        implements SandboxJobRunnerAdapter<ExecuteRequest, ExecuteResponse, GetTaskByOperationIdResponse> {

    private final PythonSandboxService pythonSandboxService;
    private final SandboxJobObservability observability;

    public PythonSandboxJobRunnerAdapter(
            PythonSandboxService pythonSandboxService, SandboxJobObservability observability) {
        this.pythonSandboxService = pythonSandboxService;
        this.observability = observability;
    }

    @Override
    public ExecuteResponse createTask(ExecuteRequest request) {
        return pythonSandboxService.createTask(request);
    }

    @Override
    public String workspaceRefusalCodeOf(ExecuteResponse response) {
        if (response == null || !response.hasWorkspaceResult()) {
            return null;
        }
        String code = response.getWorkspaceResult().name();
        return WaitMemberDispatchProof.isWorkspaceRefusalCode(code)
                && !response.hasErrorDetail() && response.getError().isBlank()
                && response.getTaskId().isBlank()
                && response.getRequestFingerprint().isBlank()
                && response.getStatus().isBlank() ? code : null;
    }

    @Override
    public GetTaskByOperationIdResponse lookupRaw(String operationId) {
        return pythonSandboxService.getTaskByOperationId(GetTaskByOperationIdRequest.newBuilder()
                .setOperationId(operationId).build());
    }

    /**
     * Run 级持久路径的回查判定：任务号和指纹必须匹配；任何错误详情都不能证明任务不存在。
     */
    @Override
    public SandboxCreateVerdict verdictFromLookupForRunPath(
            GetTaskByOperationIdResponse lookup, String requestFingerprint) {
        if (lookup != null && !lookup.hasErrorDetail() && lookup.getError().isBlank()
                && lookup.getFound()
                && !lookup.getTaskId().isBlank()
                && !lookup.getRequestFingerprint().isBlank()
                && requestFingerprint.equals(lookup.getRequestFingerprint())) {
            return new SandboxCreateVerdict.Confirmed(lookup.getTaskId());
        }
        if (lookup != null && !lookup.hasErrorDetail() && !lookup.getFound()
                && lookup.getError().isBlank()
                && lookup.getTaskId().isBlank()
                && lookup.getRequestFingerprint().isBlank()) {
            return new SandboxCreateVerdict.Absent("operation lookup reports absent");
        }
        return new SandboxCreateVerdict.Unknown("create outcome is ambiguous");
    }

    /**
     * 等待成员路径的回查判定（严格档）：证实额外要求错误详情干净、任务号与响应一致；
     * 不存在要求答复完全空白（无错误详情、无任务号、无指纹、调用方也没拿到任务号）。
     */
    @Override
    public SandboxCreateVerdict verdictFromLookupForMemberPath(
            GetTaskByOperationIdResponse lookup, String requestFingerprint, String responseTaskId) {
        if (lookup != null && lookup.getError().isBlank() && !lookup.hasErrorDetail()
                && lookup.getFound() && !lookup.getTaskId().isBlank()
                && !lookup.getRequestFingerprint().isBlank()
                && requestFingerprint.equals(lookup.getRequestFingerprint())
                && (responseTaskId.isBlank() || responseTaskId.equals(lookup.getTaskId()))) {
            return new SandboxCreateVerdict.Confirmed(lookup.getTaskId());
        }
        if (lookup != null && !lookup.getFound() && lookup.getError().isBlank()
                && !lookup.hasErrorDetail() && lookup.getTaskId().isBlank()
                && lookup.getRequestFingerprint().isBlank() && responseTaskId.isBlank()) {
            return new SandboxCreateVerdict.Absent("operation lookup reports absent");
        }
        return new SandboxCreateVerdict.Unknown("create outcome is ambiguous");
    }

    /**
     * 裁决创建响应（成员路径第一连）：错误、空响应或身份不全时按稳定的 operationId 回读；
     * 有响应任务号时还必须与回读一致，指纹也必须精确且非空。
     */
    @Override
    public SandboxCreateVerdict verdictOf(ExecuteResponse createResp, ExecuteRequest request) {
        String taskId = createResp == null ? "" : createResp.getTaskId();
        if (createResp != null && createResp.getError().isBlank()
                && !createResp.hasErrorDetail() && !taskId.isBlank()
                && !createResp.getRequestFingerprint().isBlank()
                && request.getRequestFingerprint().equals(createResp.getRequestFingerprint())) {
            return new SandboxCreateVerdict.Confirmed(taskId);
        }
        return memberLookupVerdict(request, taskId,
                createResp == null ? "empty create response" : nvl(createResp.getError()));
    }

    /** 建任务抛异常时（成员路径第二连）：RPC 抛异常不代表服务端没建，先按外部作业身份回读。 */
    @Override
    public SandboxCreateVerdict verdictOfFailure(Exception createFailure, ExecuteRequest request) {
        return memberLookupVerdict(request, "", nvl(createFailure.getMessage()));
    }

    private SandboxCreateVerdict memberLookupVerdict(
            ExecuteRequest request, String responseTaskId, String detail) {
        GetTaskByOperationIdResponse lookup;
        try {
            lookup = lookupRaw(request.getOperationId());
        } catch (Exception lookupFailure) {
            return new SandboxCreateVerdict.Unknown("operation lookup failed: " + detail);
        }
        SandboxCreateVerdict verdict = verdictFromLookupForMemberPath(
                lookup, request.getRequestFingerprint(), responseTaskId);
        if (verdict instanceof SandboxCreateVerdict.Absent) {
            return new SandboxCreateVerdict.Absent(detail);
        }
        return verdict;
    }

    @Override
    public ExecuteResponse confirmedResponse(String taskId, String requestFingerprint) {
        return ExecuteResponse.newBuilder()
                .setTaskId(taskId)
                .setRequestFingerprint(requestFingerprint)
                .build();
    }

    /** 取消墓碑三方核验：回查无错误、已找到、任务号与取消确认一致、指纹一致、（可选）与响应任务号一致。 */
    @Override
    public boolean verifyTombstoneIdentity(
            GetTaskByOperationIdResponse lookup, String canceledTaskId,
            String requestFingerprint, String responseTaskId) {
        return lookup != null && !lookup.hasErrorDetail() && lookup.getError().isBlank()
                && lookup.getFound() && canceledTaskId.equals(lookup.getTaskId())
                && requestFingerprint.equals(lookup.getRequestFingerprint())
                && (responseTaskId == null || responseTaskId.isBlank()
                    || responseTaskId.equals(lookup.getTaskId()));
    }

    @Override
    public String taskIdOf(ExecuteResponse response) {
        return response == null ? "" : nvl(response.getTaskId());
    }

    @Override
    public String errorOf(ExecuteResponse response) {
        return response == null ? "" : nvl(response.getError());
    }

    @Override
    public String requestFingerprintOf(ExecuteResponse response) {
        return response == null ? "" : nvl(response.getRequestFingerprint());
    }

    /** 查询沙箱任务当前状态；每次 Dubbo 调用前都会尝试安装调试 attachment。 */
    @Override
    public SandboxJobStatusView statusOf(String taskId) {
        observability.installDebugRpcAttachments();
        TaskStatusResponse statusResp = pythonSandboxService.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build());
        return statusResp == null
                ? null
                : new SandboxJobStatusView(nvl(statusResp.getStatus()), nvl(statusResp.getError()));
    }

    /** 取终态结果：校验失败（taskId/status/payload 完整性）按既有语义抛异常。 */
    @Override
    public SandboxTerminalResultView fetchResult(String runId, String taskId, String statusName) {
        observability.installDebugRpcAttachments();
        TaskResultResponse result = pythonSandboxService.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
        TaskResultResponse validated = SandboxTerminalResultValidator.validate(
                taskId, runId, result, statusName);
        return validated == null ? null : toTerminalView(validated, statusName);
    }

    /** 把 proto 终态响应映射成工具中立视图；nativePayload 保留原响应供 finance 副作用使用。 */
    public SandboxTerminalResultView toTerminalView(TaskResultResponse result, String statusName) {
        String usageJson = null;
        if (result.hasResourceUsage()) {
            try {
                usageJson = JsonFormat.printer().omittingInsignificantWhitespace()
                        .print(result.getResourceUsage());
            } catch (Exception e) {
                throw new IllegalStateException("resource usage could not be serialized", e);
            }
        }
        return new SandboxTerminalResultView(
                statusName,
                result.getExitCode(),
                result.getStdout(),
                result.getStderr(),
                null,
                usageJson,
                result.getError(),
                result.hasRetryable() ? result.getRetryable() : null,
                result.getDatasetDir(),
                result);
    }

    /** 按 operationId 取消并核验取消侧答复；无法确认任务身份时抛异常。 */
    @Override
    public SandboxCancelOutcomeView cancelByOperation(
            String operationId, String requestFingerprint, String cancelRequestId, String reason) {
        CancelTaskResponse canceled = pythonSandboxService.cancelTask(CancelTaskRequest.newBuilder()
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId(operationId)
                        .setRequestFingerprint(requestFingerprint))
                .setCancelRequestId(cancelRequestId)
                .setReason(reason)
                .build());
        if (canceled == null || canceled.hasErrorDetail() || !canceled.getError().isBlank()
                || canceled.getOutcome() == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED
                || canceled.getOutcome() == CancelOutcome.NOT_FOUND
                || canceled.getTaskId().isBlank()) {
            throw new IllegalStateException("Sandbox creation outcome remains uncertain");
        }
        return new SandboxCancelOutcomeView(canceled.getTaskId(), canceled.getOutcome().name());
    }

    private static String nvl(String text) {
        return text == null ? "" : text;
    }
}
