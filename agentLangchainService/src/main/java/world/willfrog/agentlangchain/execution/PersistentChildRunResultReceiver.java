package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.LateMemberRequest;
import world.willfrog.agent.platform.wait.MemberCompletionRequest;
import world.willfrog.agent.platform.wait.MemberCompletionResult;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberState;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Polls durable child results and returns ordinary tool messages to the original parent segment. */
@Component
@Slf4j
public class PersistentChildRunResultReceiver {
    private static final String PROOF_VERSION = "sub_agent_wait_v1";

    private final WaitGroupStore waitGroups;
    private final ChildRunIntentStore intents;
    private final AgentRunMapper runs;
    private final ObjectMapper json;
    private final int batchSize;
    private final int maxResultChars;
    private long spawnScanCursor;

    public PersistentChildRunResultReceiver(WaitGroupStore waitGroups,
                                            ChildRunIntentStore intents,
                                            AgentRunMapper runs,
                                            ObjectMapper json,
                                            @Value("${agent.langchain.child-run.result-batch-size:64}") int batchSize,
                                            @Value("${agent.langchain.dual-pool.wait-group.max-member-result-chars:1048576}") int maxResultChars) {
        this.waitGroups = waitGroups;
        this.intents = intents;
        this.runs = runs;
        this.json = json;
        this.batchSize = Math.max(1, Math.min(batchSize, 256));
        this.maxResultChars = Math.max(1024, maxResultChars);
    }

    @Scheduled(fixedDelayString = "${agent.langchain.child-run.result-poll-ms:1000}")
    public void collectDueResults() {
        List<WaitMember> due;
        try {
            due = waitGroups.scanDueSubAgentMembers(OffsetDateTime.now(), batchSize);
        } catch (RuntimeException failure) {
            log.error("子代理等待成员补扫失败，保留持久成员待重试", failure);
            return;
        }
        for (WaitMember member : due) {
            try {
                collect(member);
            } catch (RuntimeException failure) {
                log.error("子代理等待成员仍待接回: groupId={} member={}",
                        member.getGroupId(), member.getMemberIdentity(), failure);
                try {
                    waitGroups.rescheduleMember(member.getGroupId(), member.getMemberIdentity(),
                            OffsetDateTime.now().plusSeconds(2), 8);
                } catch (RuntimeException retryFailure) {
                    log.error("子代理等待成员退避写入失败", retryFailure);
                }
            }
        }
    }

    /** Covers accepted outbox commits even if a member's poll hint was lost. */
    @Scheduled(fixedDelayString = "${agent.langchain.child-run.spawn-repair-ms:5000}")
    public synchronized void repairAcceptedSpawnResults() {
        List<ChildRunIntentView> page;
        try {
            page = intents.listAcceptedSpawnMembersPending(spawnScanCursor, batchSize);
        } catch (RuntimeException failure) {
            log.error("已受理子 Run 的父工具结果补扫失败", failure);
            return;
        }
        if (page.isEmpty()) {
            spawnScanCursor = 0L;
            return;
        }
        for (ChildRunIntentView view : page) {
            spawnScanCursor = view.intentId();
            try {
                WaitMember member = waitGroups.findMemberByIdentity(
                        view.parentWaitGroupId(), view.parentMemberIdentity())
                        .orElseThrow(() -> new IllegalStateException("accepted spawn member missing"));
                if (member.stateEnum() != WaitMemberState.RUNNING) {
                    throw new IllegalStateException("accepted spawn member is not dispatched");
                }
                collect(member);
            } catch (RuntimeException failure) {
                log.error("已受理子 Run 的父工具结果尚未接回: intentId={}", view.intentId(), failure);
            }
        }
        if (page.size() < batchSize) {
            spawnScanCursor = 0L;
        }
    }

    private void collect(WaitMember member) {
        JsonNode proof = parseObject(member.getDispatchProofJson());
        if (!PROOF_VERSION.equals(proof.path("schemaVersion").asText())) {
            throw new IllegalStateException("unknown child wait proof version");
        }
        if (!member.getRunId().equals(proof.path("parentRunId").asText())
                || !member.getMemberIdentity().equals(proof.path("memberIdentity").asText())
                || !Objects.equals(member.getExternalOperationId(), proof.path("operationId").asText())) {
            throw new IllegalStateException("child wait proof identity mismatch");
        }
        String tool = member.getToolName();
        if ("spawnSubAgent".equals(tool)) {
            collectSpawn(member, proof);
        } else if ("waitForSubAgent".equals(tool)) {
            collectWait(member, proof);
        } else {
            throw new IllegalStateException("unexpected tool in child result receiver: " + tool);
        }
    }

    private void collectSpawn(WaitMember member, JsonNode proof) {
        String childId = proof.path("childRunId").asText("");
        ChildRunIntentView intent = intents.findByChildRunId(childId).orElse(null);
        if (intent == null || !intent.parentRunId().equals(member.getRunId())
                || intent.parentWaitGroupId() != member.getGroupId()
                || !intent.parentMemberIdentity().equals(member.getMemberIdentity())) {
            throw new IllegalStateException("spawn wait member has no matching child intent");
        }
        if (intent.acceptedAt() == null) {
            if ("CANCELED_BEFORE_ACCEPT".equals(intent.intentState())) {
                finish(member, proof, false, error("spawnSubAgent", "SUB_AGENT_CREATION_CANCELED"),
                        "SUB_AGENT_CREATION_CANCELED");
            } else {
                later(member);
            }
            return;
        }
        if (intent.childRunStatus() == null) {
            throw new IllegalStateException("accepted child intent has no Run record");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subAgentId", childId);
        data.put("runId", member.getRunId());
        data.put("status", "ACCEPTED");
        finish(member, proof, true, success("spawnSubAgent", data), null);
    }

    private void collectWait(WaitMember member, JsonNode proof) {
        JsonNode requested = proof.path("requestedChildRunIds");
        if (!requested.isArray() || requested.isEmpty()) {
            throw new IllegalStateException("wait proof has no requested child ids");
        }
        OffsetDateTime deadline = OffsetDateTime.parse(proof.path("deadlineAt").asText());
        boolean expired = !OffsetDateTime.now().isBefore(deadline);
        boolean allTerminal = true;
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode idNode : requested) {
            String childId = idNode.asText("");
            ChildRunIntentView intent = intents.findByChildRunId(childId).orElse(null);
            if (intent == null || !member.getRunId().equals(intent.parentRunId())) {
                finish(member, proof, false, error("waitForSubAgent", "SUB_AGENT_NOT_FOUND"),
                        "SUB_AGENT_NOT_FOUND");
                return;
            }
            AgentRun child = runs.findById(childId);
            if (child == null) {
                allTerminal = false;
                results.add(Map.of("subAgentId", childId,
                        "status", expired ? "WAIT_TIMEOUT" : "ACCEPTED"));
            } else if (terminal(child.getStatus())) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("subAgentId", childId);
                state.put("status", childStatus(child.getStatus()));
                state.put("result", answer(child));
                state.put("error", child.getLastError() == null ? "" : child.getLastError());
                results.add(state);
            } else {
                allTerminal = false;
                results.add(Map.of("subAgentId", childId,
                        "status", expired ? "WAIT_TIMEOUT" : "RUNNING"));
            }
        }
        if (allTerminal || expired) {
            finish(member, proof, true, success("waitForSubAgent", Map.of("results", results)), null);
        } else {
            later(member);
        }
    }

    private void finish(WaitMember member, JsonNode proof, boolean success,
                        String output, String errorCode) {
        String payload = WaitMemberResultPayload.encode(json, member.getToolName(), member.getToolCallId(),
                success, output, errorCode == null ? Map.of() : Map.of("errorCode", errorCode), maxResultChars);
        MemberCompletionResult completed = waitGroups.completeMember(new MemberCompletionRequest(
                member.getGroupId(), member.getMemberIdentity(),
                success ? WaitMemberState.SUCCEEDED : WaitMemberState.FAILED,
                payload, member.getExternalOperationId(),
                proof.path("planGeneration").asInt(-1),
                proof.path("contextVersion").asLong(-1L),
                proof.path("runControlVersion").asLong(-1L)));
        if (!completed.applied() && !member.terminal()) {
            waitGroups.reportLateMember(new LateMemberRequest(member.getGroupId(),
                    member.getMemberIdentity(), payload, member.getExternalOperationId(),
                    proof.path("runControlVersion").asLong(-1L)));
        }
        // The durable recovery notification is picked up by DualPoolRecoveryDispatcher.
    }

    private void later(WaitMember member) {
        waitGroups.rescheduleMember(member.getGroupId(), member.getMemberIdentity(),
                OffsetDateTime.now().plusSeconds(1), 8);
    }

    private JsonNode parseObject(String text) {
        try {
            JsonNode node = json.readTree(text);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // Bad durable data must remain visible and must never be guessed into a tool result.
        }
        throw new IllegalStateException("invalid child wait proof");
    }

    private String success(String tool, Map<String, Object> data) {
        return write(Map.of("ok", true, "tool", tool, "data", data));
    }

    private String error(String tool, String code) {
        return write(Map.of("ok", false, "tool", tool,
                "error", Map.of("code", code, "message", code)));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("child tool response could not be serialized", failure);
        }
    }

    private String answer(AgentRun child) {
        try {
            JsonNode snapshot = json.readTree(child.getSnapshotJson());
            return snapshot.path("answer").asText("");
        } catch (Exception malformed) {
            return "";
        }
    }

    private static String childStatus(AgentRunStatus status) {
        return switch (status) {
            case COMPLETED, PARTIAL -> "SUCCEEDED";
            case CANCELED -> "CANCELED";
            case EXPIRED -> "TIMEOUT";
            default -> "FAILED";
        };
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }
}
