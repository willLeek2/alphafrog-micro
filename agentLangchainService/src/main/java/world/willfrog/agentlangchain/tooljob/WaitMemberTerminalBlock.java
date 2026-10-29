package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.util.JsonFormat;
import java.util.Map;
import world.willfrog.alphafrogmicro.common.racing.error.*;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.agent.platform.wait.WaitMember;

/** 成员的清理凭证仅保存已验证终态事实，绝不复制 stdout 或生成模型正文。 */
final class WaitMemberTerminalBlock {
    static final String BLOCK = "terminalModelSendBlock";
    static final String CLEANUP = "terminalCleanup";
    record Saved(ModelSendBlockedException blocked, String status, TaskResultResponse result, String finishedAt) { }

    static String capture(ObjectMapper json, String originalProof, ModelSendBlocked blocked,
                          String runId, String status, TaskResultResponse result, String finishedAt) {
        try {
            ObjectNode proof = (ObjectNode) json.readTree(originalProof);
            TaskResultResponse.Builder minimal = TaskResultResponse.newBuilder()
                    .setTaskId(result.getTaskId()).setStatus(result.getStatus())
                    .setExitCode(result.getExitCode()).setResourceUsage(result.getResourceUsage());
            if (result.hasRetryable()) minimal.setRetryable(result.getRetryable());
            ObjectNode cleanup = json.createObjectNode();
            cleanup.put("runId", runId); cleanup.put("status", status); cleanup.put("finishedAt", finishedAt);
            cleanup.put("resultJson", JsonFormat.printer().omittingInsignificantWhitespace().print(minimal.build()));
            if (cleanup.toString().length() > 32768) throw new IllegalArgumentException("terminal cleanup proof too large");
            proof.set(BLOCK, json.valueToTree(ModelSendBlocks.failureMetadata(blocked)));
            proof.set(CLEANUP, cleanup);
            return proof.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("terminal block proof cannot be encoded", failure);
        }
    }

    static Saved read(ObjectMapper json, WaitMember member) {
        try {
            var proof = json.readTree(member.getDispatchProofJson());
            if (proof == null || !proof.has(BLOCK)) return null;
            var blocked = ModelSendBlocks.fromFailureMetadata(json.convertValue(proof.get(BLOCK), Map.class)).orElseThrow();
            var saved = proof.path(CLEANUP);
            TaskResultResponse.Builder result = TaskResultResponse.newBuilder();
            JsonFormat.parser().merge(saved.path("resultJson").asText(), result);
            var terminal = result.build();
            String status = saved.path("status").asText();
            String finishedAt = saved.path("finishedAt").asText();
            if (!member.getRunId().equals(saved.path("runId").asText()) || terminal.getTaskId().isBlank()
                    || status.isBlank() || !status.equals(terminal.getStatus()) || finishedAt.isBlank()) {
                throw new IllegalArgumentException("terminal cleanup identity mismatch");
            }
            return new Saved(blocked, status, terminal, finishedAt);
        } catch (Exception failure) {
            throw new IllegalStateException("terminal block proof cannot be restored", failure);
        }
    }
}
