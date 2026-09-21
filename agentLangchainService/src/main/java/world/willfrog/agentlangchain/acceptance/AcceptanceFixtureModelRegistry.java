package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 按 Run 记住「这条 Run 的模型回复从哪儿来」。
 *
 * <p>执行层每次要建阶段模型时都来这里问一句：不带夹具编号的 Run 拿到空，照原来的方式解析真实
 * 模型；带编号的 Run 拿到一个按夹具脚本作答的模型，而且同一条 Run 的规划、节点执行、写答案
 * 三段拿到的都是同一个实例——脚本是按顺序消费的，位置必须跟着 Run 走，不能一段一个位置。</p>
 *
 * <p>夹具本身由 {@link AcceptanceFixtureResolver} 查回来并核对（编号、泳道代际、启用与过期），
 * 这里只管与模型有关的两件事：同一个实例复用，以及进程内的消费位置什么时候该放掉。</p>
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

    private final AcceptanceFixtureResolver fixtureResolver;
    private final ObjectMapper objectMapper;
    private final Map<String, ScriptedStage> stageByRun = new ConcurrentHashMap<>();

    public AcceptanceFixtureModelRegistry(AcceptanceFixtureResolver fixtureResolver,
                                          ObjectMapper objectMapper) {
        this.fixtureResolver = fixtureResolver;
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
        Optional<AcceptanceFixtureRow> row = fixtureResolver.resolve(run);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String runId = run.getId();
        ScriptedStage cached = stageByRun.get(runId);
        if (cached != null) {
            if (!cached.fixtureId().equals(row.get().fixtureId())) {
                throw refuse("acceptance_fixture_identity_changed",
                        "这条 Run 一开始用的是夹具 " + cached.fixtureId() + "，现在请求上下文里写着 "
                                + row.get().fixtureId() + "：同一条 Run 的夹具身份不许中途换");
            }
            return Optional.of(cached);
        }
        return Optional.of(remember(run, row.get()));
    }

    /** 放掉一条 Run 的脚本位置；没有这条 Run 就是空动作。 */
    public void evict(String runId) {
        if (runId == null || runId.isBlank()) {
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

    private ScriptedStage remember(AgentRun run, AcceptanceFixtureRow row) {
        if (run.getPlanJson() != null && !run.getPlanJson().isBlank()) {
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
        FrozenModelScript script = FrozenModelScript.parse(
                row.fixtureId(), row.modelScriptJson(), objectMapper);
        ScriptedChatModel model = new ScriptedChatModel(row.fixtureId(), row.scenarioId(), script);
        ScriptedStage built = new ScriptedStage(model, row.fixtureId(), row.scenarioId());
        // 同一刻可能有别的线程也在为这条 Run 建模型；先放进去的那一份才算数，两边拿到同一个位置。
        ScriptedStage winner = stageByRun.putIfAbsent(run.getId(), built);
        log.info("验收夹具接管这条 Run 的模型回复: runId={} fixture={} scenario={} turns={}",
                run.getId(), row.fixtureId(), row.scenarioId(), script.size());
        return winner == null ? built : winner;
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
