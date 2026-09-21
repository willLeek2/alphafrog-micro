package world.willfrog.agentlangchain.acceptance;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按夹具脚本作答的模型。
 *
 * <p>一条 Run 里三个阶段的模型调用都用同一个实例，所以脚本的消费位置跟着这条 Run 走：
 * 规划回合拿第一个回合，之后每执行一段往下拿一个，最后写答案拿最后一个。位置是进程内的，
 * 与 Run 自己的持久进度各管一半——脚本只回答「模型说了什么」，Run 的状态机照常跑。</p>
 *
 * <p>这份顺序要对得上有两个前提，两个都在发脚本之前核对、不满足就当场拒绝（见
 * {@code AcceptanceFixtureModelRegistry}）：夹具泳道把「同一条 Run 同时未完成的工作项」设成 1，
 * 而且这条 Run 跑在「一个分段一次模型调用」的调度器版本上。第一条让一条 Run 的节点按计划顺序一段
 * 一段来，脚本的先后就是模型被调用的先后——同一条 Run 上真的并行跑两段时，谁先取回合由线程调度
 * 决定，脚本就说不清哪一回合是哪个节点要的。第二条排除旧调度路径：那里一个节点内部是工具循环，
 * 一次模型调用换一个工具回合，脚本的先后会与夹具写的先后错开。同一段被重做（进程退出后重领）会
 * 再取一个回合，夹具作者要把这种情况也写进脚本；没写到就按脚本用完报错，不静默补一个回复。</p>
 *
 * <p>夹具是不是还能用（还在、已启用、没过期）按分段核对：每一段开始前问一次夹具表，这一段里的模型
 * 调用直接用这份脚本，不再一次一次去查。这条路上一个分段只有一次模型调用，所以差别不大；例外是
 * 子任务（{@code spawnSubAgent}）自己那一轮工具循环，它在一段之内连着消费脚本，中途失效要到这一段
 * 结束才会被发现。</p>
 *
 * <p>落点选在 {@code doChat}：仓库里调用模型的方式不止一种（规划回合走 {@code chat(List)}、
 * 节点回合走 {@code chat(ChatRequest)}、DAG 判定直接调 {@code doChat}、LC4j 的 AiServices
 * 内部也落在 {@code doChat}）。langchain4j 的这几个入口最终都会走到 {@code doChat}，
 * 写在这一层就全覆盖了；写在上层会漏掉直接调 {@code doChat} 的那条路。</p>
 *
 * <p>脚本用完时抛错，不回落到任何真实供应商：那一步一旦发生，一次本该失败的验收就会看起来
 * 像是跑过去了。</p>
 */
public class ScriptedChatModel implements ChatModel {

    private final String fixtureId;
    private final String scenarioId;
    private final FrozenModelScript script;
    private final AtomicInteger consumed = new AtomicInteger();

    public ScriptedChatModel(String fixtureId, String scenarioId, FrozenModelScript script) {
        this.fixtureId = fixtureId;
        this.scenarioId = scenarioId;
        this.script = script;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        int index = consumed.getAndIncrement();
        if (index >= script.size()) {
            throw new AcceptanceFixtureExecutionException("acceptance_fixture_script_exhausted",
                    "夹具 " + fixtureId + "（场景 " + scenarioId + "）的模型脚本只有 " + script.size()
                            + " 个回合，这次执行要第 " + (index + 1) + " 个：实际发生的模型调用比脚本多，"
                            + "这条 Run 按失败处理，不换真实模型接着跑");
        }
        return ChatResponse.builder().aiMessage(messageOf(script.turns().get(index))).build();
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

    /** 已经取走几个回合：读数与验收证据用。用完脚本时停在实际的回合数上，不跟着失败的次数涨。 */
    public int consumedTurns() {
        return Math.min(consumed.get(), script.size());
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
}
