package world.willfrog.agentlangchain.acceptance;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 按夹具脚本作答的模型。
 *
 * <p>它不按「第几次调用」发回合，而是按调用身份发：每种调用各自带上自己的身份（
 * {@link FixtureCallIdentity}），{@link FixtureCallStore} 找出声明回答这一次调用的回合、原子认领。
 * 所以同一个模型实例可以同时服务同一条 Run 上并行的几个节点，谁都不会拿错——拿哪一份只由身份决定，
 * 与调用先后无关。</p>
 *
 * <p>身份要显式绑上来：这个类本身是「模板」，{@link #boundTo(FixtureCallIdentity)} 返回的才是一次
 * 具体调用用的模型。没绑身份就被调用时当场拒绝（错误码 {@code acceptance_fixture_call_identity_missing}）：
 * 认不出这是哪一次调用，就没法知道该给哪一份回复，随便给一份等于让一次本该失败的验收看起来跑过去了。</p>
 *
 * <p>落点选在 {@code doChat}：仓库里调用模型的方式不止一种（规划回合走 {@code chat(List)}、
 * 节点回合走 {@code chat(ChatRequest)}、DAG 判定直接调 {@code doChat}、LC4j 的 AiServices
 * 内部也落在 {@code doChat}）。langchain4j 的这几个入口最终都会走到 {@code doChat}，
 * 写在这一层就全覆盖了；写在上层会漏掉直接调 {@code doChat} 的那条路。</p>
 *
 * <p>没有可领的回合时抛错，不回落到任何真实供应商：那一步一旦发生，一次本该失败的验收就会看起来
 * 像是跑过去了。</p>
 */
public class ScriptedChatModel implements ChatModel {

    private final String runId;
    private final String fixtureId;
    private final String scenarioId;
    private final FrozenModelScript script;
    private final FixtureCallStore callStore;

    public ScriptedChatModel(String runId,
                             String fixtureId,
                             String scenarioId,
                             FrozenModelScript script,
                             FixtureCallStore callStore) {
        this.runId = runId;
        this.fixtureId = fixtureId;
        this.scenarioId = scenarioId;
        this.script = script;
        this.callStore = callStore;
    }

    /** 模板自己不能被调用：调用方拿到模型后必须先用 {@link #boundTo(FixtureCallIdentity)} 绑上身份。 */
    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        throw refuse("acceptance_fixture_call_identity_missing",
                "夹具 " + fixtureId + "（场景 " + scenarioId + "）的模型被调用时没有带调用身份："
                        + "脚本按调用身份发回复，认不出这是哪一次调用就不能作答");
    }

    /**
     * 绑定一次调用：返回的模型只服务这一个身份。
     *
     * <p>同一个身份重复调用（重试、挂起后重做、进程重启后重领）拿到同一个回合；不同身份各自领
     * 自己声明的那一份。</p>
     */
    public ChatModel boundTo(FixtureCallIdentity identity) {
        if (identity == null) {
            throw refuse("acceptance_fixture_call_identity_missing",
                    "夹具 " + fixtureId + "（场景 " + scenarioId + "）的调用身份是空的");
        }
        if (!runId.equals(identity.runId())) {
            throw refuse("acceptance_fixture_call_identity_missing",
                    "夹具 " + fixtureId + " 绑的是 Run " + runId + "，调用身份写的是 " + identity.runId()
                            + "：模型只能服务自己那条 Run");
        }
        return new Bound(this, identity);
    }

    /** 脚本总回合数。 */
    public int scriptSize() {
        return script.size();
    }

    /** 这条 Run 用的是哪一条夹具的哪一个场景：事件与日志里直接看得出来。 */
    public String describe() {
        return "acceptance-fixture:" + fixtureId + "/" + scenarioId;
    }

    public String fixtureId() {
        return fixtureId;
    }

    public String scenarioId() {
        return scenarioId;
    }

    /** 这份脚本的摘要：认领行里记的、以及验收证据里的都是它。 */
    public String scriptDigest() {
        return script.digest();
    }

    /** 正在跑的那条 Run。 */
    public String runId() {
        return runId;
    }

    private AiMessage answerFor(FixtureCallIdentity identity) {
        FixtureCallStore.Claim claim = callStore.claim(runId, fixtureId, scenarioId, script, identity);
        return messageOf(script.turns().get(claim.turnIndex()));
    }

    private static AiMessage messageOf(FrozenModelScript.Turn turn) {
        if (turn.toolCalls().isEmpty()) {
            return AiMessage.from(turn.text());
        }
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (FrozenModelScript.ToolCall call : turn.toolCalls()) {
            requests.add(ToolExecutionRequest.builder()
                    .id(call.id())
                    .name(call.name())
                    .arguments(call.argumentsJson())
                    .build());
        }
        return AiMessage.builder()
                .text(turn.text())
                .toolExecutionRequests(requests)
                .build();
    }

    /** 一次具体调用用的模型：身份固定，其余都交给模板。 */
    private record Bound(ScriptedChatModel template, FixtureCallIdentity identity) implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return ChatResponse.builder().aiMessage(template.answerFor(identity)).build();
        }
    }
}
