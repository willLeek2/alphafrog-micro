package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.childrun.ChildRunAcceptanceControls;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 创建 Run 之前的那道验收控制门。
 *
 * <p>请求上下文里带 {@code acceptanceControlId} 时，这里要么确认夹具表里这一行现在能用，
 * 要么直接报错；不存在「这一行不可用就照普通请求跑一遍」这条路。这一行只提供放行策略，
 * 不接管整条 Run 的模型回复。</p>
 *
 * <p>父请求也可以用 {@code childAcceptanceControls} 点名特定的父工具成员，为对应子 Run 指定各自的
 * 控制行。没有编号和成员绑定的请求不查夹具表。夹具门已经处理过 {@code acceptanceFixtureId} 的请求，
 * 这里仍会按自己的编号再查一次。</p>
 */
@Component
@Slf4j
public class AcceptanceControlGate {

    /** 请求上下文里的字段名；只带编号，带不了计划或模型内容。 */
    public static final String CONTEXT_FIELD = "acceptanceControlId";

    /** 与夹具门同一套开关。 */
    public static final String ENV_FLAG = AcceptanceFixtureGate.ENV_FLAG;

    /** 与夹具门同一套泳道范围判断。 */
    public static final String LANE_SCOPE_PROPERTY = AcceptanceFixtureGate.LANE_SCOPE_PROPERTY;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AcceptanceFixtureStore store;
    private final Environment environment;

    public AcceptanceControlGate(AcceptanceFixtureStore store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    /**
     * 确认这次请求可以按控制行执行。
     *
     * @param contextJson          请求上下文原文（未解析）
     * @param deploymentId         本进程所在泳道
     * @param deploymentGenerationId 本进程的部署代际
     * @return 父 Run 没带自己的控制编号时为空；子 Run 的绑定也会校验和登记，但不返回给父 Run
     * @throws LangchainRunRejectedException 带了编号但这一行不可用：调用方必须就此停下，
     *                                       不创建 Run，也不改走普通规划
     */
    public Optional<AcceptanceFixtureRow> admitRequestContext(String contextJson,
                                                              String deploymentId,
                                                              String deploymentGenerationId) {
        if (contextJson == null || contextJson.isBlank()) {
            return Optional.empty();
        }
        JsonNode context = readContext(contextJson);
        List<ChildRunAcceptanceControls.Binding> childBindings;
        try {
            childBindings = ChildRunAcceptanceControls.parse(context);
        } catch (IllegalArgumentException invalid) {
            throw reject("acceptance_control_invalid", invalid.getMessage());
        }
        boolean controlsParent = context.hasNonNull(CONTEXT_FIELD);
        if (!controlsParent && childBindings.isEmpty()) {
            return Optional.empty();
        }
        if (!fixtureControlEnabled()) {
            throw reject("acceptance_control_disabled",
                    "执行控制面没有打开（" + ENV_FLAG + " 或泳道范围 " + LANE_SCOPE_PROPERTY
                            + "），带控制编号的请求一律不创建任务");
        }
        String parentControlId = controlsParent ? textOf(context, CONTEXT_FIELD) : null;
        if (controlsParent && parentControlId == null) {
            throw reject("acceptance_control_invalid", "上下文里的 " + CONTEXT_FIELD + " 是空的");
        }
        if (parentControlId != null && childBindings.stream()
                .anyMatch(binding -> parentControlId.equals(binding.controlId()))) {
            throw reject("acceptance_control_invalid", "父 Run 与子 Run 不能共用同一控制编号；请分别发布控制行");
        }
        Map<String, AcceptanceFixtureRow> rows = new LinkedHashMap<>();
        if (parentControlId != null) {
            rows.put(parentControlId, usableRow(parentControlId, deploymentId, deploymentGenerationId));
            checkScenarioMatches(rows.get(parentControlId), context);
        }
        for (ChildRunAcceptanceControls.Binding binding : childBindings) {
            rows.computeIfAbsent(binding.controlId(), id -> usableRow(id,
                    deploymentId, deploymentGenerationId));
        }
        for (Map.Entry<String, AcceptanceFixtureRow> entry : rows.entrySet()) {
            if (!store.recordUse(deploymentId, deploymentGenerationId, entry.getKey())) {
                throw reject("acceptance_control_expired",
                        "控制行在读取与登记之间失效（被停用或过期）: " + entry.getValue().describe());
            }
            log.info("验收控制行已受理一次请求: {} lane={} generation={}",
                    entry.getValue().describe(), deploymentId, deploymentGenerationId);
        }
        return Optional.ofNullable(parentControlId == null ? null : rows.get(parentControlId));
    }

    private AcceptanceFixtureRow usableRow(String controlId,
                                           String deploymentId,
                                           String deploymentGenerationId) {
        AcceptanceFixtureRow row = store.find(deploymentId, deploymentGenerationId, controlId)
                .orElseThrow(() -> reject("acceptance_control_not_found",
                        "本泳道本代际没有这条控制行: control=" + controlId
                                + " lane=" + deploymentId + " generation=" + deploymentGenerationId));
        if (!row.enabled()) {
            throw reject("acceptance_control_not_enabled", "控制行还没启用: " + row.describe());
        }
        if (row.expiredAt(OffsetDateTime.now())) {
            throw reject("acceptance_control_expired", "控制行授权已过期: " + row.describe());
        }
        return row;
    }

    boolean fixtureControlEnabled() {
        return AcceptanceFixtureGate.controlSurfaceEnabled(environment);
    }

    /**
     * 请求里指定的执行模式与控制行写明的模式要对得上。
     *
     * <p>控制行可以不带冻结计划（{@code planJson} 为空）：这时不比对模式。带了计划就必须写明
     * 同一个模式，对不上或写不清楚都当场报错。</p>
     */
    private void checkScenarioMatches(AcceptanceFixtureRow row, JsonNode context) {
        String planJson = row.planJson();
        if (planJson == null || planJson.isBlank()) {
            return;
        }
        PlanExecutionMode frozen = frozenMode(row);
        PlanExecutionMode requested = parseRequestedMode(context);
        if (requested == PlanExecutionMode.AUTO) {
            return;
        }
        if (frozen == null) {
            throw reject("acceptance_control_scenario_unproven",
                    "请求指定了 " + requested + "，但控制行没带冻结计划、说明不了这次按哪种模式跑: "
                            + row.describe());
        }
        if (frozen != requested) {
            throw reject("acceptance_control_scenario_mismatch",
                    "请求指定 " + requested + "，控制行写明 " + frozen + ": " + row.describe());
        }
    }

    private PlanExecutionMode frozenMode(AcceptanceFixtureRow row) {
        String planJson = row.planJson();
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        JsonNode plan;
        try {
            plan = OBJECT_MAPPER.readTree(planJson);
        } catch (Exception e) {
            throw reject("acceptance_control_invalid",
                    "控制行的冻结计划不是合法 JSON: " + row.describe() + " 原因=" + e.getMessage());
        }
        String raw = textOf(plan, "executionMode");
        if (raw == null) {
            raw = textOf(plan, "execution_mode");
        }
        if (raw == null) {
            throw reject("acceptance_control_invalid",
                    "控制行带了冻结计划，却没写明这次按哪种模式跑: " + row.describe());
        }
        try {
            return AgentRunEventService.parseExecutionMode(raw);
        } catch (IllegalArgumentException e) {
            throw reject("acceptance_control_invalid",
                    "控制行的冻结计划写了认不出的模式 " + raw + ": " + row.describe());
        }
    }

    private PlanExecutionMode parseRequestedMode(JsonNode context) {
        String raw = textOf(context, "execution_mode");
        if (raw == null) {
            raw = textOf(context, "executionMode");
        }
        try {
            return AgentRunEventService.parseExecutionMode(raw);
        } catch (IllegalArgumentException e) {
            throw reject("acceptance_control_invalid", "请求上下文写了认不出的执行模式: " + raw);
        }
    }

    private JsonNode readContext(String contextJson) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(contextJson);
        } catch (Exception e) {
            throw reject("acceptance_control_invalid",
                    "请求上下文不是合法 JSON（" + e.getMessage() + "）：分不清它是不是一次控制请求，"
                            + "按控制这一侧拒绝，不按普通请求跑");
        }
        if (root == null || !root.isObject()) {
            throw reject("acceptance_control_invalid", "请求上下文不是一个 JSON 对象");
        }
        return root;
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isBlank() ? null : text;
    }

    private static LangchainRunRejectedException reject(String code, String detail) {
        return new LangchainRunRejectedException(code + ": " + detail, code);
    }
}
