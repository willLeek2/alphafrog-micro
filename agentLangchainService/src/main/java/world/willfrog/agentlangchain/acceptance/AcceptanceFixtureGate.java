package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 创建 Run 之前的那道验收夹具门。
 *
 * <p>请求上下文里带 {@code acceptanceFixtureId} 时，这里要么确认这条夹具现在能用，
 * 要么直接报错；**不存在「夹具不可用就照普通请求跑一遍」这条路**。理由很直接：
 * 静默回落会让一次本该失败的验收看起来像跑过了，而这正是验收最不能有的结果。</p>
 *
 * <p>不带这个字段的请求完全不受影响：连夹具表都不会查。</p>
 */
@Component
@Slf4j
public class AcceptanceFixtureGate {

    /** 请求上下文里的字段名；只带编号，带不了计划或模型内容。 */
    public static final String CONTEXT_FIELD = "acceptanceFixtureId";

    /** 控制器写入的显式开关。未设置时，有泳道范围的进程打开，主 Beta 保持关闭。 */
    public static final String ENV_FLAG = "AF_AGENT_ACCEPTANCE_FIXTURE_ENABLED";

    /** 泳道容器由控制器注入；主 Beta 不写。未设显式开关时用它判断是不是隔离泳道。 */
    public static final String LANE_SCOPE_PROPERTY = "AF_LANE_TRAFFIC_SCOPE_ID";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AcceptanceFixtureStore store;
    private final Environment environment;

    public AcceptanceFixtureGate(AcceptanceFixtureStore store, Environment environment) {
        this.store = store;
        this.environment = environment;
    }

    /**
     * 确认这次请求可以按夹具执行。
     *
     * @param contextJson          请求上下文原文（未解析）
     * @param deploymentId         本进程所在泳道
     * @param deploymentGenerationId 本进程的部署代际
     * @return 上下文没带夹具编号时为空；带了则返回这条夹具当前的样子
     * @throws LangchainRunRejectedException 带了编号但夹具不可用：调用方必须就此停下，
     *                                       不创建 Run，也不改走普通规划
     */
    public Optional<AcceptanceFixtureRow> admitRequestContext(String contextJson,
                                                              String deploymentId,
                                                              String deploymentGenerationId) {
        if (contextJson == null || contextJson.isBlank()) {
            return Optional.empty();
        }
        // 先解析，再看有没有夹具编号：不做「正文里出现过这个词」的字面猜测。上下文损坏到字段名都不完整时，
        // 字面猜法会把一次本该跑夹具的请求当成普通请求放过去，那一步之后就接上真实模型了。
        JsonNode context = readContext(contextJson);
        if (context.get(CONTEXT_FIELD) == null || context.get(CONTEXT_FIELD).isNull()) {
            return Optional.empty();
        }
        if (!fixtureControlEnabled()) {
            throw reject("acceptance_fixture_disabled",
                    "执行控制面没有打开（" + ENV_FLAG + " 或泳道范围 " + LANE_SCOPE_PROPERTY
                            + "），带夹具编号的请求一律不创建任务");
        }
        String fixtureId = textOf(context, CONTEXT_FIELD);
        if (fixtureId == null) {
            throw reject("acceptance_fixture_invalid", "上下文里的 " + CONTEXT_FIELD + " 是空的");
        }
        AcceptanceFixtureRow row = store.find(deploymentId, deploymentGenerationId, fixtureId)
                .orElseThrow(() -> reject("acceptance_fixture_not_found",
                        "本泳道本代际没有这条夹具: fixture=" + fixtureId
                                + " lane=" + deploymentId + " generation=" + deploymentGenerationId));
        if (!row.enabled()) {
            throw reject("acceptance_fixture_not_enabled", "夹具还没启用: " + row.describe());
        }
        if (row.expiredAt(OffsetDateTime.now())) {
            throw reject("acceptance_fixture_expired", "夹具授权已过期: " + row.describe());
        }
        checkScenarioMatches(row, context);
        if (!store.recordUse(deploymentId, deploymentGenerationId, fixtureId)) {
            // 读回来到登记之间这一小段时间里被停用或过期了：这是并发窗口，不是「先到先用」，
            // 按不可用处理，免得两个执行器都以为自己是唯一那次。
            throw reject("acceptance_fixture_expired",
                    "夹具在读取与登记之间失效（被停用或过期）: " + row.describe());
        }
        log.info("验收夹具已受理一次请求: {} lane={} generation={}",
                row.describe(), deploymentId, deploymentGenerationId);
        return Optional.of(row);
    }

    /**
     * 夹具门是否打开。
     *
     * <p>显式 {@code AF_AGENT_ACCEPTANCE_FIXTURE_ENABLED} 优先：主 Beta 由控制器写成 false，
     * 隔离泳道写成 true。宿主控制器还没带上这个变量时，有 {@code AF_LANE_TRAFFIC_SCOPE_ID}
     * 的进程视为隔离泳道并打开；主 Beta 没有这个变量，保持关闭。</p>
     */
    boolean fixtureControlEnabled() {
        Boolean explicit = environment.getProperty(ENV_FLAG, Boolean.class);
        if (explicit != null) {
            return explicit;
        }
        String laneScope = environment.getProperty(LANE_SCOPE_PROPERTY);
        return laneScope != null && !laneScope.isBlank();
    }

    /**
     * 请求里指定的执行模式与夹具写明的模式要对得上。
     *
     * <p>普通路径下「请求要线性、计划是 DAG」会被裁决成线性跑，也就是计划被改写；验收时这种
     * 改写绝不能被容忍——那等于拿另一张图的结果当这次的结果。所以请求一旦写明模式，夹具就必须
     * 写明同一个模式，对不上或压根没写明都当场报错。</p>
     */
    private void checkScenarioMatches(AcceptanceFixtureRow row, JsonNode context) {
        PlanExecutionMode frozen = frozenMode(row);
        PlanExecutionMode requested = parseRequestedMode(context);
        if (requested == PlanExecutionMode.AUTO) {
            // 请求没指定模式：这次跑成什么样由夹具说了算，创建时不比对。
            return;
        }
        if (frozen == null) {
            throw reject("acceptance_fixture_scenario_unproven",
                    "请求指定了 " + requested + "，但夹具没带冻结计划、说明不了这次按哪种模式跑: "
                            + row.describe());
        }
        if (frozen != requested) {
            throw reject("acceptance_fixture_scenario_mismatch",
                    "请求指定 " + requested + "，夹具写明 " + frozen + ": " + row.describe());
        }
    }

    /**
     * 夹具自己写明的模式。
     *
     * <p>夹具没带冻结计划时返回空（这次夹具不管计划）；带了计划就必须写明模式，写不清楚
     * 或者写了个认不出的值都当场按夹具内容有问题拒绝——一份读不懂的夹具跑出来的结果，
     * 没法说它是这次要验收的那件事。</p>
     */
    private PlanExecutionMode frozenMode(AcceptanceFixtureRow row) {
        String planJson = row.planJson();
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        JsonNode plan;
        try {
            plan = OBJECT_MAPPER.readTree(planJson);
        } catch (Exception e) {
            throw reject("acceptance_fixture_invalid",
                    "夹具的冻结计划不是合法 JSON: " + row.describe() + " 原因=" + e.getMessage());
        }
        String raw = textOf(plan, "executionMode");
        if (raw == null) {
            raw = textOf(plan, "execution_mode");
        }
        if (raw == null) {
            throw reject("acceptance_fixture_invalid",
                    "夹具带了冻结计划，却没写明这次按哪种模式跑: " + row.describe());
        }
        try {
            return AgentRunEventService.parseExecutionMode(raw);
        } catch (IllegalArgumentException e) {
            throw reject("acceptance_fixture_invalid",
                    "夹具的冻结计划写了认不出的模式 " + raw + ": " + row.describe());
        }
    }

    /** 请求上下文里写的执行模式，字段口径与创建路径一致（execution_mode 优先）。 */
    private PlanExecutionMode parseRequestedMode(JsonNode context) {
        String raw = textOf(context, "execution_mode");
        if (raw == null) {
            raw = textOf(context, "executionMode");
        }
        try {
            return AgentRunEventService.parseExecutionMode(raw);
        } catch (IllegalArgumentException e) {
            throw reject("acceptance_fixture_invalid", "请求上下文写了认不出的执行模式: " + raw);
        }
    }

    /**
     * 读请求上下文。
     *
     * <p>读不出来就当场拒绝，不按普通请求放过去：只有「读得出来、里面确实没有夹具编号」才算普通请求。
     * 内容损坏时，光看文本有没有出现字段名是猜的——截断、转义变化都可能让字段名不完整，一次本该跑
     * 夹具的验收会因此连上真实模型。</p>
     */
    private JsonNode readContext(String contextJson) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(contextJson);
        } catch (Exception e) {
            throw reject("acceptance_fixture_invalid",
                    "请求上下文不是合法 JSON（" + e.getMessage() + "）：分不清它是不是一次夹具请求，"
                            + "按夹具这一侧拒绝，不按普通请求跑");
        }
        if (root == null || !root.isObject()) {
            throw reject("acceptance_fixture_invalid", "请求上下文不是一个 JSON 对象");
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
