package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 在结果保存后发布安全的展示通知，不参与成员完成、恢复或计费。 */
@Component
@Slf4j
public class ToolResultRecordedEventPublisher {

    public static final String EVENT_TYPE = "TOOL_RESULT_RECORDED";
    private static final Set<String> TEXT_KEYS = Set.of(
            "todo_id", "group_id", "member_id", "member_identity", "tool_name", "tool_call_id",
            "operation_id", "member_state", "task_status", "task_id");
    private static final Set<String> NUMBER_KEYS = Set.of(
            "plan_generation", "node_attempt", "segment_sequence", "model_turn", "attempt");
    private static final Set<String> SOURCES = Set.of("wait_member", "tool_job_anchor", "synchronous");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{N}._:@/\\-]{1,256}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,95}");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:sk|ak|pk|rk|ghp|xox[baprs]|ya29)[_-][A-Za-z0-9._-]{12,}|AKIA[0-9A-Z]{16}");

    private final WaitGroupStore waitGroupStore;
    private final AgentRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate notificationTransaction;

    public ToolResultRecordedEventPublisher(WaitGroupStore waitGroupStore,
                                            AgentRunEventService eventService,
                                            ObjectMapper objectMapper) {
        this(waitGroupStore, eventService, objectMapper, null);
    }

    @Autowired
    public ToolResultRecordedEventPublisher(WaitGroupStore waitGroupStore,
                                            AgentRunEventService eventService,
                                            ObjectMapper objectMapper,
                                            PlatformTransactionManager transactionManager) {
        this.waitGroupStore = waitGroupStore;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.notificationTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (this.notificationTransaction != null) {
            this.notificationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    /** 活动事务只登记提交后通知；回滚不发布，非事务调用直接重读成员。 */
    public void publishMemberAfterCommit(String runId, String userId, long groupId, String memberIdentity) {
        try {
            afterCommit(runId, () -> publishMember(runId, userId, groupId, memberIdentity));
        } catch (RuntimeException failure) {
            log.warn("成员结果已保存但展示通知未登记: runId={} groupId={} exception={}",
                    runId, groupId, failure.getClass().getSimpleName());
        }
    }

    private void afterCommit(String runId, Runnable notification) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            notification.run();
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive() || notificationTransaction == null) {
            log.warn("工具结果事务无法登记提交后展示通知，保留原结果: runId={}", runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // 原事务的连接和会话此时仍可能绑定在线程上，独立事务才能重读并提交通知。
                    notificationTransaction.executeWithoutResult(status -> notification.run());
                } catch (RuntimeException failure) {
                    log.warn("工具结果已提交但展示通知未保存: runId={} exception={}",
                            runId, failure.getClass().getSimpleName());
                }
            }
        });
    }

    /** completeMember 成功之后重新读取，避免把写入前或降级前的局部结果发给页面。 */
    public void publishMember(String runId, String userId, long groupId, String memberIdentity) {
        try {
            WaitGroup group = waitGroupStore.findGroup(groupId).orElse(null);
            WaitMember member = waitGroupStore.findMemberByIdentity(groupId, memberIdentity).orElse(null);
            if (group == null || member == null || member.getId() == null
                    || !same(runId, group.getRunId()) || !same(runId, member.getRunId())
                    || !Long.valueOf(groupId).equals(member.getGroupId()) || !member.terminal()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "wait_member");
            payload.put("plan_generation", group.getPlanGeneration());
            payload.put("todo_id", group.getNodeId());
            payload.put("node_attempt", group.getNodeAttempt());
            payload.put("segment_sequence", group.getSegmentSequence());
            payload.put("model_turn", group.getModelTurn());
            payload.put("group_id", Long.toString(groupId));
            payload.put("member_id", member.getId().toString());
            payload.put("member_identity", member.getMemberIdentity());
            payload.put("tool_name", member.getToolName());
            payload.put("tool_call_id", persistentCallId(group, member));
            payload.put("operation_id", member.getExternalOperationId());
            payload.put("member_state", member.getState());
            JsonNode result = readObject(member.getResultRefJson());
            if (result != null) {
                String status = text(result, "status");
                if ("SUCCEEDED".equals(status) && "SUCCEEDED".equals(member.getState())) {
                    payload.put("business_success", true);
                } else if ("FAILED".equals(status) && "FAILED".equals(member.getState())) {
                    payload.put("business_success", false);
                }
                payload.put("error_code", text(result, "errorCode"));
                payload.put("task_id", text(result, "taskId"));
            }
            WaitMemberDispatchProof proof = WaitMemberDispatchProof
                    .fromJson(objectMapper, member.getDispatchProofJson()).orElse(null);
            if (proof != null && proof.workspaceRefused()) {
                payload.put("executed", false);
                payload.remove("task_id");
            } else if (proof != null && proof.taskConfirmed()) {
                payload.put("task_id", proof.taskId());
            }
            eventService.appendOnce(runId, userId, EVENT_TYPE,
                    "tool_result_recorded:member:" + member.getId(), safeResult(payload));
        } catch (Exception e) {
            log.warn("工具成员展示通知未保存，保留原调度结果: runId={} groupId={} exception={}",
                    runId, groupId, e.getClass().getSimpleName());
        }
    }

    /** 只读取原锚点的身份，不能用当前 Run 的新计划代次替旧结果补归属。 */
    public void publishAnchor(String runId, String userId, ToolJobAnchor anchor) {
        if (anchor == null || anchor.getOperationId() == null || anchor.getOperationId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "tool_job_anchor");
            payload.put("plan_generation", anchor.getWorkItemPlanGeneration());
            payload.put("todo_id", anchor.getTodoId());
            payload.put("node_attempt", anchor.getWorkItemNodeAttempt());
            payload.put("segment_sequence", anchor.getWorkItemSegmentSequence());
            payload.put("tool_name", anchor.getToolName());
            payload.put("tool_call_id", anchor.getToolCallId());
            payload.put("operation_id", anchor.getOperationId());
            payload.put("attempt", anchor.getAttempt());
            payload.put("task_status", anchor.getTerminalStatus());
            payload.put("business_success", anchor.getTerminalBusinessSuccess());
            payload.put("error_code", anchor.getTerminalErrorCode());
            if (WaitMemberDispatchProof.isWorkspaceRefusalCode(anchor.getWorkspaceRefusalCode())) {
                payload.put("executed", false);
            } else {
                payload.put("task_id", anchor.getTaskId());
            }
            String dedupeKey = "tool_result_recorded:anchor:" + anchor.getOperationId() + ":" + anchor.getAttempt();
            Map<String, Object> savedResult = safeResult(payload);
            afterCommit(runId, () -> publishAnchorResult(runId, userId, dedupeKey, savedResult));
        } catch (Exception e) {
            log.warn("工具锚点展示通知未保存，保留原终态结果: runId={} exception={}",
                    runId, e.getClass().getSimpleName());
        }
    }

    private void publishAnchorResult(String runId, String userId, String dedupeKey,
                                     Map<String, Object> savedResult) {
        try {
            eventService.appendOnce(runId, userId, EVENT_TYPE, dedupeKey, savedResult);
        } catch (Exception failure) {
            log.warn("工具锚点展示通知未保存，保留原终态结果: runId={} exception={}",
                    runId, failure.getClass().getSimpleName());
        }
    }

    /** 通知和只读视图共用安全字段；不传递输出正文、参数、地址或配置。 */
    public static Map<String, Object> safeResult(Map<String, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input == null) {
            return result;
        }
        if (input.get("source") instanceof String source && SOURCES.contains(source)) {
            result.put("source", source);
        }
        for (String key : TEXT_KEYS) {
            if (input.get(key) instanceof String value && safeIdentifier(value)) {
                result.put(key, value);
            }
        }
        for (String key : NUMBER_KEYS) {
            Object value = input.get(key);
            if (value instanceof Number number && number.longValue() >= 0
                    && number.doubleValue() == (double) number.longValue()) {
                result.put(key, number.longValue());
            }
        }
        if (input.get("business_success") instanceof Boolean success) {
            result.put("business_success", success);
        }
        if (Boolean.FALSE.equals(input.get("executed"))) {
            result.put("executed", false);
        }
        if (input.get("error_code") instanceof String code
                && ERROR_CODE.matcher(code).matches() && !CREDENTIAL.matcher(code).matches()) {
            result.put("error_code", code);
        }
        return result;
    }

    private String persistentCallId(WaitGroup group, WaitMember member) {
        if (group.getPlanGeneration() == null || group.getNodeId() == null
                || group.getNodeAttempt() == null || group.getSegmentSequence() == null
                || group.getModelTurn() == null) {
            return null;
        }
        return DurableToolCallIds.forTool(member.getToolName(), member.getToolCallId(),
                group.identity().segment());
    }

    private JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean same(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    private static boolean safeIdentifier(String value) {
        return IDENTIFIER.matcher(value).matches() && !value.contains("://")
                && !CREDENTIAL.matcher(value).matches();
    }
}
