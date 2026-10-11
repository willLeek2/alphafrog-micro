package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.Optional;
import java.util.function.Supplier;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 按 Run 决定「这条 Run 的模型回复从哪儿来」。
 *
 * <p>执行层每次要建阶段模型时都来这里问一句：不带夹具编号的 Run 拿到空，照原来的方式解析真实
 * 模型；带编号的 Run 拿到一个按夹具脚本作答的模型。脚本 {@code for} 没点名的调用，由阶段模型
 * 解析那一步懒建的真实客户端回答。</p>
 *
 * <p>这个模型是「模板」：它自己不回答问题，使用它的地方要用 {@link #forCall(ChatModel,
 * FixtureCallIdentity)} 把这一次调用的身份绑上去。脚本按调用身份发回合（见
 * {@link FixtureCallStore}），所以同一份模型对象可以同时服务同一条 Run 上并行的几个节点，也可以
 * 在进程重启之后继续用同一个身份接着领——位置是数据库里的事实，不在这个对象里。</p>
 *
 * <p>夹具本身由 {@link AcceptanceFixtureResolver} 查回来并核对（编号、泳道代际、启用与过期）。</p>
 */
@Component
@Slf4j
public class AcceptanceFixtureModelRegistry {

    private final AcceptanceFixtureResolver fixtureResolver;
    private final FixtureCallStore callStore;
    private final ObjectMapper objectMapper;

    public AcceptanceFixtureModelRegistry(AcceptanceFixtureResolver fixtureResolver,
                                          FixtureCallStore callStore,
                                          ObjectMapper objectMapper) {
        this.fixtureResolver = fixtureResolver;
        this.callStore = callStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 这条 Run 该用哪个模型。
     *
     * @param run 正在执行的 Run
     * @return 不带夹具编号的 Run 返回空（照原样解析真实模型）；带编号的返回脚本模型
     * @throws AcceptanceFixtureExecutionException 带了编号但夹具现在不能用、脚本读不出来，
     *                                             或者这条 Run 跑在夹具不支持的调度器版本上
     */
    public Optional<ScriptedStage> stageForRun(AgentRun run) {
        Optional<AcceptanceFixtureRow> row = fixtureResolver.resolve(run);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        requireSegmentPerModelCall(run);
        FrozenModelScript script = FrozenModelScript.parse(
                row.get().fixtureId(), row.get().modelScriptJson(), objectMapper);
        ScriptedChatModel model = new ScriptedChatModel(
                run.getId(), row.get().fixtureId(), row.get().scenarioId(), script, callStore,
                () -> rereadScript(run, row.get().fixtureId(), row.get().scenarioId()));
        log.info("验收夹具接管这条 Run 的模型回复: runId={} fixture={} scenario={} turns={} 脚本摘要={}",
                run.getId(), row.get().fixtureId(), row.get().scenarioId(), script.size(), script.digest());
        return Optional.of(new ScriptedStage(model, row.get().fixtureId(), row.get().scenarioId()));
    }

    /**
     * 每一次模型调用之前重读一遍夹具内容。
     *
     * <p>重读顺带把「这条夹具现在还能不能用」再核一遍（还在、已启用、没过期、泳道代际对得上）：
     * 夹具在跑的中途被停用或过期时，这一步就会停下。读回来的内容交给认领那一步与冻结的摘要比对，
     * 被原位改过时会拒绝，不会按新内容作答。</p>
     */
    private ScriptedChatModel.FixtureScript rereadScript(AgentRun run, String fixtureId, String scenarioId) {
        AcceptanceFixtureRow current = fixtureResolver.resolve(run).orElseThrow(() -> refuse(
                "acceptance_fixture_identity_changed",
                "这条 Run 的请求上下文里已经不带夹具编号了: runId=" + run.getId()));
        return new ScriptedChatModel.FixtureScript(current.fixtureId(), current.scenarioId(),
                FrozenModelScript.parse(current.fixtureId(), current.modelScriptJson(), objectMapper));
    }

    /**
     * 把模型绑到一次具体调用上。
     *
     * <p>脚本模型返回一个只服务这个身份的实例；真实模型原样返回（普通 Run 的调用点不用为夹具分叉，
     * 直接调这个方法即可）。身份用「要用的时候才算」的形式传：普通 Run 上算身份这件事根本不该发生
     * ——拼身份要 Run 号一类的字段，普通路径上它们未必齐，为了一个用不上的值去拼身份只会平白报错。</p>
     */
    public static ChatModel forCall(ChatModel model, Supplier<FixtureCallIdentity> identity) {
        if (!(model instanceof ScriptedChatModel scripted)) {
            return model;
        }
        return scripted.boundTo(identity.get());
    }

    /**
     * 夹具的前提：这条 Run 跑在「一个分段一次模型调用」的调度器版本上。
     *
     * <p>脚本按「一次模型调用 = 一个回合」声明。旧的调度路径不是这样：一个节点内部是一个工具循环，
     * 一次模型调用换一个工具回合，同一个节点能吃掉几十个回合，夹具作者没法按调用声明回合。版本是
     * 泳道热配置、可以在运行期改，所以这里按 Run 上冻结的那个版本核对，不按当前配置猜。</p>
     */
    private void requireSegmentPerModelCall(AgentRun run) {
        SchedulerVersion version;
        try {
            version = SchedulerVersion.fromWire(run.getSchedulerVersion());
        } catch (RuntimeException e) {
            throw refuse("acceptance_fixture_scheduler_version_unusable",
                    "这条 Run 的调度器版本读不出来（" + run.getSchedulerVersion() + "）：夹具要求"
                            + "「一个分段一次模型调用」的那个版本，认不出来就不能让它跑");
        }
        if (!version.usesWaitGroups()) {
            throw refuse("acceptance_fixture_scheduler_version_unusable",
                    "夹具要求 Run 跑在「一个分段一次模型调用」的调度器版本上，这条 Run 是 " + version
                            + "：旧的调度路径一个节点内部是工具循环，一次模型调用换一个工具回合，"
                            + "没法按调用声明回合");
        }
    }

    /**
     * Run 走到终态就核对一次脚本：声明的必答回合是不是都真的发生并被领走过。
     *
     * <p>订阅的是终态事件而不是某一条收尾分支：完成、部分完成、失败、取消、过期都会发这个事件，
     * 挂在这里才不会漏。结论落在库里（见 {@code alphafrog_agent_run_acceptance_fixture_scenario}），
     * 验收执行器按 Run 直接读。</p>
     */
    @EventListener
    public void onRunFinalized(AgentRunFinalizedEvent event) {
        if (event == null || event.runId() == null) {
            return;
        }
        try {
            callStore.recordVerdict(event.runId());
        } catch (RuntimeException e) {
            // 核对本身不能把收尾带下去：结论没落上时留一句明确的日志，Run 自己该怎样还是怎样。
            log.error("夹具脚本的终态核对没做成: runId={} reason={}", event.runId(), e.getMessage(), e);
        }
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
