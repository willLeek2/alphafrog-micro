package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.childrun.ChildRunReservation;
import world.willfrog.agent.platform.childrun.ChildRunReserveRequest;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 父等待成员与持久子 Run 意图之间的适配层；创建和等待都在父挂起事务内留下可扫描的事实。 */
@Component
public class DatabasePersistentSubAgentToolBridge implements PersistentSubAgentToolBridge {

    static final String PROOF_SCHEMA = "sub_agent_wait_v1";
    private static final String SPAWN = "spawnSubAgent";
    private static final String WAIT = "waitForSubAgent";
    private static final int GOAL_MAX_CHARS = 2_000;
    private static final int CONTEXT_MAX_CHARS = 2_000;

    private final AgentRunMapper runMapper;
    private final ChildRunIntentStore childIntentStore;
    private final WaitGroupStore waitGroupStore;
    private final AgentPromptService promptService;
    private final ObjectMapper objectMapper;
    private final long maxWaitMillis;
    private final long defaultWaitMillis;

    public DatabasePersistentSubAgentToolBridge(AgentRunMapper runMapper,
                                                ChildRunIntentStore childIntentStore,
                                                WaitGroupStore waitGroupStore,
                                                AgentPromptService promptService,
                                                ObjectMapper objectMapper,
                                                @Value("${agent.sub-agent.max-wait-millis:30000}") long maxWaitMillis,
                                                @Value("${agent.sub-agent.default-wait-millis:5000}") long defaultWaitMillis) {
        this.runMapper = runMapper;
        this.childIntentStore = childIntentStore;
        this.waitGroupStore = waitGroupStore;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
        this.maxWaitMillis = Math.max(1L, maxWaitMillis);
        this.defaultWaitMillis = Math.max(1L, Math.min(defaultWaitMillis, this.maxWaitMillis));
    }

    @Override
    public boolean availableForRun(String runId) {
        if (runId == null || runId.isBlank() || !promptService.subAgentEnabled()) {
            return false;
        }
        AgentRun run = runMapper.findById(runId);
        return run != null && SchedulerVersion.DUAL_POOL_V2.name().equals(run.getSchedulerVersion())
                && !childFlag(run);
    }

    @Override
    public boolean isChildRun(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            throw new IllegalStateException("工具目录对应的 Run 不存在：" + runId);
        }
        return childFlag(run);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome reserveSpawn(ReservationRequest request) {
        requireReservation(request, SPAWN);
        WaitMember member = requireMember(request, SPAWN);
        if (member.stateEnum() == WaitMemberState.RUNNING) {
            requireSameProof(member, request, SPAWN);
            return ReservationOutcome.RESERVED;
        }
        if (member.stateEnum() != WaitMemberState.PENDING) {
            throw new IllegalStateException("创建子 Run 的等待成员不在待派发状态");
        }
        SpawnArguments arguments = parseSpawn(request.argumentsJson());
        if (arguments == null) {
            return ReservationOutcome.INVALID_REQUEST;
        }
        AgentRun parent = requireEligibleParent(request);
        JsonNode ext = parseExt(parent);
        String rootRunId = childIntentStore.rootRunIdOf(parent.getId())
                .orElseThrow(() -> new IllegalStateException("父 Run 的根调用树身份不存在"));
        String selectedModel = promptService.selectSubAgentModelName(arguments.goal(), arguments.context());
        String modelName = selectedModel == null || selectedModel.isBlank()
                ? ext.path("model_name").asText("") : selectedModel.strip();
        String selectedEndpoint = promptService.subAgentEndpointName();
        String endpointName = selectedEndpoint == null || selectedEndpoint.isBlank()
                ? ext.path("endpoint_name").asText("") : selectedEndpoint.strip();
        ChildRunReservation reserved = childIntentStore.reserveIntent(new ChildRunReserveRequest(
                rootRunId, parent.getId(), request.groupId(), request.memberIdentity(),
                request.segment().nodeId(), request.toolCallId(), request.segment().planGeneration(),
                request.segment().nodeAttempt(), request.versions().runControlVersion(),
                arguments.goal(), arguments.context(), modelName, endpointName,
                Math.max(1, Math.min(12, promptService.maxSubAgentSteps())), configDigest(ext)),
                Math.max(1, promptService.maxSubAgentCount()));
        if (reserved.outcome() == ChildRunReservation.Outcome.LIMIT_EXCEEDED) {
            return ReservationOutcome.LIMIT_EXCEEDED;
        }
        ObjectNode proof = baseProof(request, SPAWN);
        proof.put("childRunId", reserved.childRunId());
        markRunningOrVerify(member, request, proof);
        return ReservationOutcome.RESERVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome reserveWait(ReservationRequest request) {
        requireReservation(request, WAIT);
        WaitMember member = requireMember(request, WAIT);
        if (member.stateEnum() == WaitMemberState.RUNNING) {
            requireSameProof(member, request, WAIT);
            return ReservationOutcome.RESERVED;
        }
        if (member.stateEnum() != WaitMemberState.PENDING) {
            throw new IllegalStateException("等待子 Run 的成员不在待派发状态");
        }
        if (!availableForRun(request.parentRunId())) {
            throw new IllegalStateException("当前 Run 无权等待子 Run");
        }
        WaitArguments arguments = parseWait(request.argumentsJson());
        if (arguments == null) {
            return ReservationOutcome.INVALID_REQUEST;
        }
        if (arguments.childRunIds().size() > Math.max(1, promptService.maxSubAgentCount())) {
            return ReservationOutcome.LIMIT_EXCEEDED;
        }
        for (String childRunId : arguments.childRunIds()) {
            Optional<ChildRunIntentView> child = childIntentStore.findByChildRunId(childRunId);
            if (child.isEmpty() || !request.parentRunId().equals(child.get().parentRunId())
                    || child.get().acceptedAt() == null
                    || child.get().childRunStatus() == null) {
                return ReservationOutcome.NOT_FOUND;
            }
        }
        ObjectNode proof = baseProof(request, WAIT);
        proof.putArray("requestedChildRunIds").addAll(arguments.childRunIds().stream()
                .map(objectMapper.getNodeFactory()::textNode).toList());
        proof.put("timeoutMillis", arguments.timeoutMillis());
        proof.put("deadlineAt", OffsetDateTime.now(ZoneOffset.UTC)
                .plus(Duration.ofMillis(arguments.timeoutMillis())).toString());
        markRunningOrVerify(member, request, proof);
        return ReservationOutcome.RESERVED;
    }

    @Override
    public NodeToolDispatcher.DispatchOutcome dispatch(NodeToolDispatcher.DispatchRequest request) {
        if (request == null || !availableForRun(request.runId())
                || (!SPAWN.equals(request.toolName()) && !WAIT.equals(request.toolName()))) {
            return new NodeToolDispatcher.DispatchOutcome.Failed("sub_agent_tool_not_available");
        }
        WaitMember member = waitGroupStore.findMemberByIdentity(request.groupId(), request.memberIdentity())
                .orElse(null);
        if (member == null || member.stateEnum() != WaitMemberState.RUNNING
                || !request.runId().equals(member.getRunId())) {
            return new NodeToolDispatcher.DispatchOutcome.Failed("sub_agent_wait_member_missing");
        }
        JsonNode proof = parseProof(member.getDispatchProofJson());
        if (!sameProofIdentity(proof, member.getExternalOperationId(), request.runId(),
                request.groupId(), request.memberIdentity(), request.toolName())) {
            return new NodeToolDispatcher.DispatchOutcome.Failed("sub_agent_dispatch_proof_mismatch");
        }
        if (SPAWN.equals(request.toolName())) {
            String childRunId = proof.path("childRunId").asText("");
            Optional<ChildRunIntentView> view = childIntentStore.findByChildRunId(childRunId);
            if (view.isEmpty() || !request.runId().equals(view.get().parentRunId())
                    || view.get().parentWaitGroupId() != request.groupId()
                    || !request.memberIdentity().equals(view.get().parentMemberIdentity())) {
                return new NodeToolDispatcher.DispatchOutcome.Failed("sub_agent_creation_intent_missing");
            }
            if ("ACCEPTED".equals(view.get().intentState()) && view.get().childRunStatus() != null) {
                String output = json(Map.of("ok", true, "tool", SPAWN,
                        "data", Map.of("subAgentId", childRunId, "runId", request.runId(),
                                "status", "ACCEPTED"), "error", Map.of()));
                return new NodeToolDispatcher.DispatchOutcome.Completed(output);
            }
            return new NodeToolDispatcher.DispatchOutcome.Pending(member.getExternalOperationId(),
                    childRunId, member.getDispatchProofJson());
        }
        return new NodeToolDispatcher.DispatchOutcome.Pending(member.getExternalOperationId(),
                null, member.getDispatchProofJson());
    }

    private AgentRun requireEligibleParent(ReservationRequest request) {
        AgentRun parent = runMapper.findById(request.parentRunId());
        if (parent == null || !promptService.subAgentEnabled()
                || !SchedulerVersion.DUAL_POOL_V2.name().equals(parent.getSchedulerVersion())
                || childFlag(parent)) {
            throw new IllegalStateException("当前 Run 无权创建子 Run");
        }
        return parent;
    }

    private WaitMember requireMember(ReservationRequest request, String tool) {
        WaitMember member = waitGroupStore.findMemberByIdentity(request.groupId(), request.memberIdentity())
                .orElseThrow(() -> new IllegalStateException("子代理等待成员不存在"));
        if (!request.parentRunId().equals(member.getRunId())
                || !request.operationId().equals(member.getExternalOperationId())
                || !request.toolCallId().equals(member.getToolCallId())
                || !tool.equals(member.getToolName())) {
            throw new IllegalStateException("子代理等待成员身份与预留请求不一致");
        }
        return member;
    }

    private static void requireReservation(ReservationRequest request, String tool) {
        if (request == null || request.segment() == null || request.versions() == null
                || request.groupId() <= 0 || request.parentRunId() == null || request.parentRunId().isBlank()
                || request.memberIdentity() == null || request.memberIdentity().isBlank()
                || request.toolCallId() == null || request.toolCallId().isBlank()
                || request.operationId() == null || request.operationId().isBlank()
                || !request.parentRunId().equals(request.segment().runId())) {
            throw new IllegalArgumentException("子代理 " + tool + " 预留缺少稳定父等待成员身份");
        }
    }

    private void markRunningOrVerify(WaitMember member, ReservationRequest request, ObjectNode proof) {
        String text = json(proof);
        if (waitGroupStore.markMemberDispatched(request.groupId(), request.memberIdentity(),
                request.operationId(), text, OffsetDateTime.now(ZoneOffset.UTC),
                request.versions().runControlVersion())) {
            return;
        }
        WaitMember current = requireMember(request, proof.path("tool").asText());
        if (current.stateEnum() != WaitMemberState.RUNNING
                || !sameProofIdentity(parseProof(current.getDispatchProofJson()), request.operationId(),
                request.parentRunId(), request.groupId(), request.memberIdentity(),
                proof.path("tool").asText(""))) {
            throw new IllegalStateException("子代理等待成员不能转为可恢复的执行中状态");
        }
        JsonNode existing = parseProof(current.getDispatchProofJson());
        if (WAIT.equals(proof.path("tool").asText())
                && !existing.path("requestedChildRunIds").equals(proof.path("requestedChildRunIds"))) {
            throw new IllegalStateException("相同等待操作的子 Run 顺序发生变化");
        }
        if (SPAWN.equals(proof.path("tool").asText())
                && !existing.path("childRunId").equals(proof.path("childRunId"))) {
            throw new IllegalStateException("相同创建操作的子 Run 身份发生变化");
        }
        if (!sameProofVersions(existing, request)) {
            throw new IllegalStateException("相同子代理操作的父版本发生变化");
        }
    }

    private void requireSameProof(WaitMember member, ReservationRequest request, String tool) {
        JsonNode proof = parseProof(member.getDispatchProofJson());
        if (!sameProofIdentity(proof, request.operationId(),
                request.parentRunId(), request.groupId(), request.memberIdentity(), tool)) {
            throw new IllegalStateException("重复子代理操作的派发证明身份不一致");
        }
        if (!sameProofVersions(proof, request)) {
            throw new IllegalStateException("重复子代理操作的父版本或参数发生变化");
        }
    }

    private ObjectNode baseProof(ReservationRequest request, String tool) {
        ObjectNode proof = objectMapper.createObjectNode();
        proof.put("schemaVersion", PROOF_SCHEMA);
        proof.put("tool", tool);
        proof.put("operationId", request.operationId());
        proof.put("parentRunId", request.parentRunId());
        proof.put("groupId", request.groupId());
        proof.put("memberIdentity", request.memberIdentity());
        proof.put("contextVersion", request.versions().contextVersion());
        proof.put("planGeneration", request.segment().planGeneration());
        proof.put("runControlVersion", request.versions().runControlVersion());
        proof.put("argumentsDigest", digest(request.argumentsJson() == null ? "" : request.argumentsJson()));
        return proof;
    }

    private boolean sameProofVersions(JsonNode proof, ReservationRequest request) {
        return proof.path("contextVersion").asLong(-1) == request.versions().contextVersion()
                && proof.path("planGeneration").asInt(-1) == request.segment().planGeneration()
                && proof.path("runControlVersion").asLong(-1) == request.versions().runControlVersion()
                && digest(request.argumentsJson() == null ? "" : request.argumentsJson())
                .equals(proof.path("argumentsDigest").asText());
    }

    private boolean sameProofIdentity(JsonNode proof, String operationId, String runId,
                                      long groupId, String memberIdentity, String tool) {
        return proof != null && PROOF_SCHEMA.equals(proof.path("schemaVersion").asText())
                && tool.equals(proof.path("tool").asText())
                && operationId.equals(proof.path("operationId").asText())
                && runId.equals(proof.path("parentRunId").asText())
                && groupId == proof.path("groupId").asLong(-1)
                && memberIdentity.equals(proof.path("memberIdentity").asText());
    }

    private JsonNode parseProof(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode proof = objectMapper.readTree(text);
            return proof != null && proof.isObject() ? proof : null;
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    private static boolean childFlag(AgentRun run) {
        JsonNode ext = parseExt(run);
        JsonNode flag = ext.get("child_run");
        if (flag == null) {
            return false;
        }
        if (!flag.isBoolean()) {
            throw new IllegalStateException("Run 的 child_run 标志不是布尔值：" + run.getId());
        }
        return flag.booleanValue();
    }

    private static JsonNode parseExt(AgentRun run) {
        if (run.getExt() == null || run.getExt().isBlank()) {
            throw new IllegalStateException("Run 缺少配置快照：" + run.getId());
        }
        try {
            JsonNode ext = new ObjectMapper().readTree(run.getExt());
            if (ext == null || !ext.isObject()) {
                throw new IllegalStateException("Run 配置快照不是 JSON 对象：" + run.getId());
            }
            return ext;
        } catch (JsonProcessingException malformed) {
            throw new IllegalStateException("Run 配置快照不能解析：" + run.getId(), malformed);
        }
    }

    private SpawnArguments parseSpawn(String text) {
        JsonNode root = parseArguments(text);
        if (root == null || root.size() > 2 || !root.has("goal") || !root.path("goal").isTextual()
                || (root.has("context") && !root.path("context").isTextual())) {
            return null;
        }
        String goal = root.path("goal").asText().strip();
        String context = root.path("context").asText("").strip();
        if (goal.isEmpty() || goal.length() > GOAL_MAX_CHARS || context.length() > CONTEXT_MAX_CHARS) {
            return null;
        }
        for (var field = root.fieldNames(); field.hasNext();) {
            if (!List.of("goal", "context").contains(field.next())) {
                return null;
            }
        }
        return new SpawnArguments(goal, context);
    }

    private WaitArguments parseWait(String text) {
        JsonNode root = parseArguments(text);
        if (root == null || !root.path("subAgentIds").isArray()
                || root.size() > 2 || (root.has("timeoutMillis")
                && !root.path("timeoutMillis").canConvertToLong())) {
            return null;
        }
        for (var field = root.fieldNames(); field.hasNext();) {
            if (!List.of("subAgentIds", "timeoutMillis").contains(field.next())) {
                return null;
            }
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode node : root.path("subAgentIds")) {
            if (!node.isTextual() || node.asText().isBlank()) {
                return null;
            }
            ids.add(node.asText().strip());
        }
        if (ids.isEmpty()) {
            return null;
        }
        long requested = root.has("timeoutMillis") ? root.path("timeoutMillis").asLong()
                : defaultWaitMillis;
        return new WaitArguments(new ArrayList<>(ids), Math.max(1L, Math.min(requested, maxWaitMillis)));
    }

    private JsonNode parseArguments(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    private String configDigest(JsonNode ext) {
        try {
            return digest(objectMapper.writeValueAsString(ext));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("父 Run 配置快照摘要不能计算", failure);
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("子代理工具结果不能序列化", failure);
        }
    }

    private record SpawnArguments(String goal, String context) {
    }

    private record WaitArguments(List<String> childRunIds, long timeoutMillis) {
    }
}
