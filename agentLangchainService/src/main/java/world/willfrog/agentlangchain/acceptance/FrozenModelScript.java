package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 夹具里冻结的模型回合清单。
 *
 * <p>一条 Run 的模型调用按这份清单的顺序一次一次消费：先规划一次，然后每执行一段来一次，
 * 最后写答案再来一次。所以它是一份有序的回合表，每个回合要么给一段文本，要么给一组工具调用；
 * 工具调用的身份、工具名、参数与先后顺序都照夹具写的样子交出去，执行路径该做的解析、校验、
 * 派发一件不少。</p>
 *
 * <p>读法上按「认不出的字段一律拒绝」处理：夹具是拿来验收的，把写错的字段名当成没写，
 * 跑出来的结果没法说清是哪一次验收。宁可当场报错，也要指出是哪个回合、哪个字段。</p>
 *
 * <p>工具参数 {@code argumentsJson} 两种写法都收：写成 JSON 对象，或者写成装着 JSON 对象的
 * 字符串。两种都归一成一段 JSON 对象文本，字段顺序照夹具写的顺序原样保留。</p>
 *
 * <p>本类只负责「读得懂、形状对」，不解释内容：文本怎么解析成计划、工具参数合不合法，
 * 都交给原来的解析与工具层。</p>
 */
public final class FrozenModelScript {

    private static final Set<String> SCRIPT_FIELDS = Set.of("turns");
    private static final Set<String> TURN_FIELDS = Set.of("text", "toolCalls");
    private static final Set<String> CALL_FIELDS = Set.of("id", "name", "argumentsJson");

    /** 一次工具调用：身份、工具名、参数原文。 */
    public record ToolCall(String id, String name, String argumentsJson) { }

    /** 一个模型回合：一段文本、一组工具调用，或者两者都给。 */
    public record Turn(String text, List<ToolCall> toolCalls) { }

    private final String fixtureId;
    private final List<Turn> turns;

    private FrozenModelScript(String fixtureId, List<Turn> turns) {
        this.fixtureId = fixtureId;
        this.turns = turns;
    }

    /**
     * 读一份脚本。
     *
     * @param fixtureId    夹具编号，只用来在报错里指明是哪一条夹具
     * @param scriptJson   {@code model_script_json} 列的原文
     * @param objectMapper 仓库里注入的 JSON 解析器
     * @return 解析好的有序回合清单
     * @throws AcceptanceFixtureExecutionException 脚本读不出来：空、最外层不是对象、有不认得的字段、
     *                                             一个回合里两句都没给、工具调用身份重复、参数不是 JSON 对象，
     *                                             任何一种都拒绝执行
     */
    public static FrozenModelScript parse(String fixtureId, String scriptJson, ObjectMapper objectMapper) {
        if (scriptJson == null || scriptJson.isBlank()) {
            throw invalid(fixtureId, "夹具的模型脚本是空的");
        }
        JsonNode root = readJson(fixtureId, scriptJson, objectMapper, "模型脚本");
        if (!root.isObject()) {
            throw invalid(fixtureId, "模型脚本的最外层要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, root, SCRIPT_FIELDS, "模型脚本");
        JsonNode turnsNode = root.get("turns");
        if (turnsNode == null || !turnsNode.isArray() || turnsNode.isEmpty()) {
            throw invalid(fixtureId, "模型脚本里的 turns 要是非空数组：一条 Run 至少要有一个模型回合");
        }
        List<Turn> turns = new ArrayList<>();
        for (int turnIndex = 0; turnIndex < turnsNode.size(); turnIndex++) {
            turns.add(readTurn(fixtureId, turnsNode.get(turnIndex), turnIndex, objectMapper));
        }
        return new FrozenModelScript(fixtureId, List.copyOf(turns));
    }

    /** 按顺序排好的回合清单。 */
    public List<Turn> turns() {
        return turns;
    }

    /** 有几个回合。 */
    public int size() {
        return turns.size();
    }

    private static Turn readTurn(String fixtureId, JsonNode node, int turnIndex, ObjectMapper objectMapper) {
        String where = "第 " + (turnIndex + 1) + " 个回合";
        if (node == null || !node.isObject()) {
            throw invalid(fixtureId, where + "要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, node, TURN_FIELDS, where);
        String text = null;
        JsonNode textNode = node.get("text");
        if (textNode != null && !textNode.isNull()) {
            if (!textNode.isTextual() || textNode.asText().isBlank()) {
                throw invalid(fixtureId, where + "的 text 要是非空字符串");
            }
            text = textNode.asText();
        }
        List<ToolCall> calls = new ArrayList<>();
        JsonNode callsNode = node.get("toolCalls");
        if (callsNode != null && !callsNode.isNull()) {
            if (!callsNode.isArray() || callsNode.isEmpty()) {
                throw invalid(fixtureId, where + "的 toolCalls 要是非空数组");
            }
            Set<String> seenIds = new HashSet<>();
            for (int callIndex = 0; callIndex < callsNode.size(); callIndex++) {
                ToolCall call = readCall(fixtureId, callsNode.get(callIndex), where, callIndex, objectMapper);
                if (!seenIds.add(call.id())) {
                    throw invalid(fixtureId, where + "里有两个工具调用的 id 都是 " + call.id()
                            + "：同一个回合里的调用身份要各不一样，工具结果才配得回去");
                }
                calls.add(call);
            }
        }
        if (text == null && calls.isEmpty()) {
            throw invalid(fixtureId, where + "既没有文本也没有工具调用，读不出模型要说什么");
        }
        return new Turn(text, List.copyOf(calls));
    }

    private static ToolCall readCall(String fixtureId,
                                     JsonNode node,
                                     String turnWhere,
                                     int index,
                                     ObjectMapper objectMapper) {
        String where = turnWhere + "的第 " + (index + 1) + " 个工具调用";
        if (node == null || !node.isObject()) {
            throw invalid(fixtureId, where + "要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, node, CALL_FIELDS, where);
        String id = requiredText(fixtureId, node, "id", where);
        String name = requiredText(fixtureId, node, "name", where);
        String argumentsJson = readArguments(fixtureId, node, where, objectMapper);
        return new ToolCall(id, name, argumentsJson);
    }

    /**
     * 读工具参数。
     *
     * <p>两种写法都收：直接写成 JSON 对象（读起来自然，也正是工具收到的形状），或者写成装着
     * JSON 对象的字符串（从别处整段搬过来的参数）。最终都归一成一段 JSON 对象文本，
     * 字段顺序照夹具写的顺序原样保留。</p>
     */
    private static String readArguments(String fixtureId,
                                        JsonNode call,
                                        String where,
                                        ObjectMapper objectMapper) {
        JsonNode value = call.get("argumentsJson");
        if (value == null || value.isNull()) {
            throw invalid(fixtureId, where + "没写 argumentsJson：工具收到的参数就是这一份，不能缺");
        }
        if (value.isObject()) {
            return value.toString();
        }
        if (value.isTextual()) {
            String raw = value.asText();
            if (raw.isBlank()) {
                throw invalid(fixtureId, where + "的 argumentsJson 是空的");
            }
            JsonNode parsed = readJson(fixtureId, raw, objectMapper, where + "的 argumentsJson");
            if (!parsed.isObject()) {
                throw invalid(fixtureId, where + "的 argumentsJson 要是一个 JSON 对象");
            }
            return raw;
        }
        throw invalid(fixtureId, where + "的 argumentsJson 要么写成 JSON 对象，"
                + "要么写成一段装着 JSON 对象的字符串");
    }

    private static String requiredText(String fixtureId, JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(fixtureId, where + "的 " + field + " 要是非空字符串");
        }
        return value.asText();
    }

    private static void rejectUnknownFields(String fixtureId,
                                            JsonNode node,
                                            Set<String> allowed,
                                            String where) {
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw invalid(fixtureId, where + "里有认不出的字段 " + String.join("、", unknown)
                    + "（认得的字段：" + String.join("、", allowed.stream().sorted().toList()) + "）");
        }
    }

    private static JsonNode readJson(String fixtureId,
                                     String json,
                                     ObjectMapper objectMapper,
                                     String where) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull()) {
                throw invalid(fixtureId, where + "是空的");
            }
            return node;
        } catch (AcceptanceFixtureExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(fixtureId, where + "不是合法 JSON：" + e.getMessage());
        }
    }

    private static AcceptanceFixtureExecutionException invalid(String fixtureId, String detail) {
        return new AcceptanceFixtureExecutionException("acceptance_fixture_script_invalid",
                "夹具 " + fixtureId + " 的模型脚本读不出来，" + detail);
    }
}
