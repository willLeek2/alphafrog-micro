package world.willfrog.agentlangchain.tooljob;

import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

/** 已持久保存完整请求的等待成员，按原操作号查询并在确实未找到时原样重发。 */
@Slf4j
final class WaitMemberDurableRequestResolver {
    private WaitMemberDurableRequestResolver() {
    }

    static boolean hasValidRequest(WaitMemberDispatchProof proof) {
        return proof != null && proof.replayable() && parseRequest(proof) != null;
    }

    static String resolve(WaitMemberDispatchProof proof, PythonSandboxService sandbox) {
        return resolveOutcome(proof, sandbox).taskId();
    }

    record Resolution(String taskId, String workspaceRefusalCode) {
        static Resolution unavailable() { return new Resolution(null, null); }
        static Resolution task(String taskId) { return new Resolution(taskId, null); }
        static Resolution refused(String code) { return new Resolution(null, code); }
    }

    static Resolution resolveOutcome(WaitMemberDispatchProof proof, PythonSandboxService sandbox) {
        if (proof == null || !proof.replayable()) {
            return Resolution.unavailable();
        }
        if (proof.workspaceRefused()) {
            return Resolution.refused(proof.workspaceRefusalCode());
        }
        ExecuteRequest request = parseRequest(proof);
        if (request == null) {
            log.error("等待成员的完整创建请求与持久身份不一致: operation={}", proof.operationId());
            return Resolution.unavailable();
        }
        try {
            GetTaskByOperationIdResponse lookup = sandbox.getTaskByOperationId(
                    GetTaskByOperationIdRequest.newBuilder()
                            .setOperationId(proof.operationId()).build());
            if (lookup == null || lookup.hasErrorDetail() || !lookup.getError().isBlank()) {
                return Resolution.unavailable();
            }
            if (lookup.getFound()) {
                String task = matchingTask(proof, lookup.getTaskId(), lookup.getRequestFingerprint());
                return task == null ? Resolution.unavailable() : Resolution.task(task);
            }
            // 只有权威的“未找到”才能重发；不完整或矛盾的查询结果保留待下一轮核对。
            if (!lookup.getTaskId().isBlank() || !lookup.getRequestFingerprint().isBlank()) {
                return Resolution.unavailable();
            }
            ExecuteResponse created = sandbox.createTask(request);
            if (created != null && created.hasWorkspaceResult()) {
                WorkspaceResult result = created.getWorkspaceResult();
                if (!WaitMemberDispatchProof.isWorkspaceRefusalCode(result.name())
                        || created.hasErrorDetail() || !created.getError().isBlank()
                        || !created.getTaskId().isBlank()
                        || !created.getRequestFingerprint().isBlank()
                        || !created.getStatus().isBlank()) {
                    return Resolution.unavailable();
                }
                // 首轮查询在 createTask 之前；另一次相同操作可能已受理，拒绝后必须再查。
                GetTaskByOperationIdResponse after = sandbox.getTaskByOperationId(
                        GetTaskByOperationIdRequest.newBuilder()
                                .setOperationId(proof.operationId()).build());
                if (after == null || after.hasErrorDetail() || !after.getError().isBlank()) {
                    return Resolution.unavailable();
                }
                if (after.getFound()) {
                    String task = matchingTask(proof, after.getTaskId(), after.getRequestFingerprint());
                    return task == null ? Resolution.unavailable() : Resolution.task(task);
                }
                return after.getTaskId().isBlank() && after.getRequestFingerprint().isBlank()
                        ? Resolution.refused(result.name()) : Resolution.unavailable();
            }
            if (created == null || created.hasErrorDetail() || !created.getError().isBlank()) {
                return Resolution.unavailable();
            }
            String task = matchingTask(proof, created.getTaskId(), created.getRequestFingerprint());
            return task == null ? Resolution.unavailable() : Resolution.task(task);
        } catch (RuntimeException remoteFailure) {
            log.warn("等待成员查询或重发暂不可用: operation={} reason={}",
                    proof.operationId(), remoteFailure.getMessage());
            return Resolution.unavailable();
        }
    }

    private static String matchingTask(WaitMemberDispatchProof proof,
                                       String taskId, String fingerprint) {
        if (taskId == null || taskId.isBlank()
                || !proof.requestFingerprint().equals(fingerprint)) {
            log.error("等待成员取回的任务身份与持久请求不一致: operation={}", proof.operationId());
            return null;
        }
        return taskId;
    }

    private static ExecuteRequest parseRequest(WaitMemberDispatchProof proof) {
        try {
            ExecuteRequest.Builder builder = ExecuteRequest.newBuilder();
            JsonFormat.parser().merge(proof.createRequestJson(), builder);
            ExecuteRequest request = builder.build();
            if (!proof.operationId().equals(request.getOperationId())
                    || !proof.requestFingerprint().equals(request.getRequestFingerprint())) {
                return null;
            }
            return request;
        } catch (Exception invalidRequest) {
            return null;
        }
    }
}
