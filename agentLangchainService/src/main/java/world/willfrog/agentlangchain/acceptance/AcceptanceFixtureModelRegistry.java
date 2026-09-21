package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Run 记住「这条 Run 的模型回复从哪儿来」。
 *
 * <p>执行层每次要建阶段模型时都来这里问一句：不带夹具编号的 Run 拿到空，照原来的方式解析真实
 * 模型；带编号的 Run 拿到一个按夹具脚本作答的模型，而且同一条 Run 的规划、节点执行、写答案
 * 三段拿到的都是同一个实例——脚本是按顺序消费的，位置必须跟着 Run 走，不能一段一个位置。</p>
 *
 * <p>夹具的编号来自 Run 自己的 {@code ext.context_json}（创建时随请求上下文一起存下来的），
 * 泳道与代际取 Run 上的那两个字段，并且要求它们与本进程的部署身份一致：夹具只在本泳道本代际
 * 下生效，换了代际之后旧夹具不该被另一份构建接着用。</p>
 *
 * <p>每一次取用都重新核对夹具「还在、已启用、没过期」：夹具在跑的中途被停用或过期时，这条 Run
 * 的后续执行按失败处理并写明原因，不会悄悄改走真实模型。</p>
 *
 * <p>位置是进程内的。进程重启后如果这条 Run 已经规划过，位置就没法重建，这时按失败处理
 * （错误码 {@code acceptance_fixture_script_position_lost}），而不是从头再喂一遍脚本——
 * 从头喂会让后面的回合与真实的调用顺序错开，跑出来的结果说不清是哪一次验收。</p>
 */
@Component
@Slf4j
public class AcceptanceFixtureModelRegistry {

    /** 进程里最多同时记住这么多条 Run 的脚本位置。 */
    static final int MAX_TRACKED_RUNS = 128;

    private final AcceptanceFixtureStore store;
    private final DeploymentIdentityProvider identityProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, ScriptedStage> stageByRun = new ConcurrentHashMap<>();

    public AcceptanceFixtureModelRegistry(AcceptanceFixtureStore store,
                                          DeploymentIdentityProvider identityProvider,
                                          ObjectMapper objectMapper) {
        this.store = store;
        this.identityProvider = identityProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 这条 Run 该用哪个模型。
     *
     * @param run 正在执行的 Run
     * @return 不带夹具编号的 Run 返回空（照原样解析真实模型）；带编号的返回脚本模型
     * @throws AcceptanceFixtureExecutionException 带了编号但夹具现在不能用、位置重建不了、
     *                                             或者本进程认不出这条 Run 的泳道代际
     */
    public Optional<ScriptedStage> stageForRun(AgentRun run) {
        if (run == null || isBlank(run.getId()) || !mentionsFixture(run.getExt())) {
            return Optional.empty();
        }
        String contextJson = contextJsonOf(run);
        if (contextJson == null || !contextJson.contains(AcceptanceFixtureGate.CONTEXT_FIELD)) {
            // ext 里出现这个词，只是因为别处提到了它（例如用户消息正文里写了这个词）。
            // 请求上下文里没有这个字段，这条 Run 就是普通 Run，照原样解析真实模型。
            return Optional.empty();
        }
        String runId = run.getId();
        DeploymentIdentity lane = laneOf(run);
        String fixtureId = readFixtureId(contextJson);
        AcceptanceFixtureRow row = requireUsableRow(lane, fixtureId, runId);
        ScriptedStage cached = stageByRun.get(runId);
        if (cached != null) {
            if (!cached.fixtureId().equals(fixtureId)) {
                throw refuse("acceptance_fixture_identity_changed",
                        "这条 Run 一开始用的是夹具 " + cached.fixtureId() + "，现在请求上下文里写着 "
                                + fixtureId + "：同一条 Run 的夹具身份不许中途换");
            }
            return Optional.of(cached);
        }
        return Optional.of(remember(run, fixtureId, row));
    }

    /** 放掉一条 Run 的脚本位置；没有这条 Run 就是空动作。 */
    public void evict(String runId) {
        if (isBlank(runId)) {
            return;
        }
        if (stageByRun.remove(runId) != null) {
            log.info("验收夹具的脚本位置随 Run 终态放掉了: runId={}", runId);
        }
    }

    /**
     * Run 走到终态就把它的脚本位置放掉。
     *
     * <p>订阅的是终态事件而不是某一条收尾分支：完成、部分完成、失败、取消、过期都会发这个事件，
     * 挂在这里才不会漏。</p>
     */
    @EventListener
    public void onRunFinalized(AgentRunFinalizedEvent event) {
        if (event != null) {
            evict(event.runId());
        }
    }

    private ScriptedStage remember(AgentRun run, String fixtureId, AcceptanceFixtureRow row) {
        if (!isBlank(run.getPlanJson())) {
            throw refuse("acceptance_fixture_script_position_lost",
                    "这条 Run 已经规划过，进程里却没有它的脚本位置（进程重启或者换了实例）："
                            + "夹具脚本按顺序消费，从头再喂一遍会让后面的回合与实际发生的调用错开，"
                            + "这条 Run 按失败处理，请重跑这个场景");
        }
        if (stageByRun.size() >= MAX_TRACKED_RUNS) {
            throw refuse("acceptance_fixture_tracked_runs_full",
                    "本进程记住的夹具 Run 已经到 " + MAX_TRACKED_RUNS + " 条：这个数只会在终态事件漏掉时涨起来，"
                            + "先查这些 Run 为什么没走到终态");
        }
        FrozenModelScript script = FrozenModelScript.parse(fixtureId, row.modelScriptJson(), objectMapper);
        ScriptedChatModel model = new ScriptedChatModel(fixtureId, row.scenarioId(), script);
        ScriptedStage built = new ScriptedStage(model, fixtureId, row.scenarioId());
        // 同一刻可能有别的线程也在为这条 Run 建模型；先放进去的那一份才算数，两边拿到同一个位置。
        ScriptedStage winner = stageByRun.putIfAbsent(run.getId(), built);
        log.info("验收夹具接管这条 Run 的模型回复: runId={} fixture={} scenario={} turns={}",
                run.getId(), fixtureId, row.scenarioId(), script.size());
        return winner == null ? built : winner;
    }

    private AcceptanceFixtureRow requireUsableRow(DeploymentIdentity lane, String fixtureId, String runId) {
        AcceptanceFixtureRow row = store.find(lane.deploymentId(), lane.generationId(), fixtureId)
                .orElseThrow(() -> refuse("acceptance_fixture_not_found",
                        "本泳道本代际没有这条夹具: fixture=" + fixtureId
                                + " lane=" + lane.deploymentId() + " generation=" + lane.generationId()
                                + " runId=" + runId));
        if (!row.enabled()) {
            throw refuse("acceptance_fixture_not_enabled", "夹具还没启用: " + row.describe());
        }
        if (row.expiredAt(OffsetDateTime.now())) {
            throw refuse("acceptance_fixture_expired", "夹具授权已过期: " + row.describe());
        }
        return row;
    }

    /**
     * 本进程与这条 Run 的泳道代际，两边一致才认。
     *
     * <p>不一致说明这条 Run 不属于当前构建：夹具按泳道与代际发布，拿另一份构建的身份去查，
     * 查到的内容与这次执行没有关系。</p>
     */
    private DeploymentIdentity laneOf(AgentRun run) {
        DeploymentIdentity stored;
        try {
            stored = new DeploymentIdentity(run.getDeploymentId(), run.getDeploymentGenerationId());
        } catch (RuntimeException e) {
            throw refuse("acceptance_fixture_lane_unknown",
                    "这条 Run 的部署身份读不出来（" + e.getMessage() + "），认不出它在哪个泳道哪个代际下跑");
        }
        DeploymentIdentity local;
        try {
            local = identityProvider.current();
        } catch (RuntimeException e) {
            throw refuse("acceptance_fixture_lane_unknown",
                    "本进程的部署身份读不出来（" + e.getMessage() + "），带夹具编号的 Run 不能这样执行");
        }
        if (!local.equals(stored)) {
            throw refuse("acceptance_fixture_lane_mismatch",
                    "这条 Run 属于 lane=" + stored.deploymentId() + " generation=" + stored.generationId()
                            + "，本进程是 lane=" + local.deploymentId() + " generation=" + local.generationId()
                            + "：夹具只在本泳道本代际下生效");
        }
        return stored;
    }

    /** 取 Run 的 ext 里原样存着的请求上下文；拿不到就是空（当成普通 Run 处理）。 */
    private String contextJsonOf(AgentRun run) {
        try {
            JsonNode root = objectMapper.readTree(run.getExt());
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode context = root.get("context_json");
            return context != null && context.isTextual() && !context.asText().isBlank()
                    ? context.asText() : null;
        } catch (Exception e) {
            log.warn("Run 的 ext 读不回来，按普通 Run 处理: runId={} reason={}", run.getId(), e.getMessage());
            return null;
        }
    }

    /** 上下文里写了夹具编号就要读出来；读不出来按不可用处理，不悄悄当普通 Run 跑。 */
    private String readFixtureId(String contextJson) {
        JsonNode contextRoot;
        try {
            contextRoot = objectMapper.readTree(contextJson);
        } catch (Exception e) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里有 " + AcceptanceFixtureGate.CONTEXT_FIELD + "，但整体不是合法 JSON: "
                            + e.getMessage());
        }
        if (contextRoot == null || !contextRoot.isObject()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里有 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 这个词，但整体不是 JSON 对象");
        }
        JsonNode value = contextRoot.get(AcceptanceFixtureGate.CONTEXT_FIELD);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里有 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 这个词，但值读不出来");
        }
        String fixtureId = value.asText("").trim();
        if (fixtureId.isBlank()) {
            throw refuse("acceptance_fixture_invalid",
                    "请求上下文里的 " + AcceptanceFixtureGate.CONTEXT_FIELD + " 是空的");
        }
        return fixtureId;
    }

    /**
     * ext 里有没有可能带夹具。
     *
     * <p>先做一次廉价判断，让普通 Run 一次解析都不做；判断过了还要再看请求上下文里到底有没有这个
     * 字段（见 {@link #stageForRun}），因为 ext 里还存着用户消息正文，正文提到这个词不代表这是夹具 Run。</p>
     */
    private boolean mentionsFixture(String ext) {
        return ext != null && ext.contains(AcceptanceFixtureGate.CONTEXT_FIELD);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AcceptanceFixtureExecutionException refuse(String code, String detail) {
        return new AcceptanceFixtureExecutionException(code, detail);
    }

    /** 一条 Run 的脚本模型，以及它背后的夹具身份。 */
    public record ScriptedStage(ScriptedChatModel model, String fixtureId, String scenarioId) {

        /** 事件与读数里的端点名：写成一个认得出的标记，一眼看得出这次没碰真实供应商。 */
        public static final String ENDPOINT_NAME = "acceptance-fixture";

        public String modelName() {
            return ENDPOINT_NAME + ":" + fixtureId;
        }

        public String describe() {
            return model.describe();
        }
    }
}
