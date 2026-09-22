package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 夹具里冻结的模型回合清单。
 *
 * <p>每个回合都要用 {@code for} 声明它回答哪一次调用——{@code for} 是一个对象，里面写
 * {@code stage}（{@code planning} / {@code node} / {@code answer} / {@code judge}）以及这一阶段
 * 的域字段，例如 {@code {"stage": "node", "nodeId": "n1", "modelTurn": 0}}。执行层每次调用模型
 * 时报上自己这次调用的身份（见 {@link FixtureCallIdentity}），由 {@link FixtureCallStore} 找出
 * 声明相符、还没被领走的回合，原子地认领。</p>
 *
 * <p>这样一份脚本不再依赖「调用发生的先后」：同一条 Run 上两个节点并行要回复，各领各的声明回合，
 * 与线程调度先后无关；一段因为挂起或进程重启被重做时，报上来的身份不变，拿回的还是同一个回合。
 * 声明里没写的字段就是通配，例如只写 {@code nodeId} 不写 {@code modelTurn}，表示这个节点的每一段
 * 都算；只写 {@code stage: "node"}，表示任何节点都可以领。</p>
 *
 * <p>可以不发生的回合（例如「进程重启后重做这一段」这种情况夹具作者不打算造）写
 * {@code "optional": true}。必答回合没有被领走时，Run 走到终态那一刻会被点名报出来——
 * 一次验收里脚本写了、实际没发生的回合，不能算通过。</p>
 *
 * <p>一个回合里写什么有明确的口径：{@code text} 与 {@code toolCalls} 至少写一个，两个都写也可以
 * （真实模型的回复本来就可能既有话要说、又同时要调工具，夹具要能造出这种形态）；两个都不写就拒绝，
 * 因为读不出模型要说什么。工具调用的身份、工具名、参数与先后顺序都照夹具写的样子交出去，执行路径
 * 该做的解析、校验、派发一件不少。</p>
 *
 * <p>要写几个回合、每个回合声明回答哪一次调用，取决于这条 Run 会问几次模型。夹具 Run 跑在
 * 「一个分段一次模型调用」的调度器版本上（见 {@code AcceptanceFixtureModelRegistry} 的前置核对），
 * 这条路上会发生的调用是：</p>
 * <ul>
 *   <li>规划两次：先策略（{@code planPhase: "strategy"}）、再待办清单（{@code planPhase: "todos"}），
 *       两次都带 {@code planAttempt}（从 1 起）。整段规划读不过校验时两次一起重来，默认最多两次尝试，
 *       所以最坏情况要声明四个回合。</li>
 *   <li>每个节点每一段一次：这一段里模型说的话就是这一段要干的工具调用；这一段因为长工具挂起、
 *       或者进程退出后重领，会带着自己的 {@code segmentSequence} / {@code modelTurn} 再要一次回复，
 *       夹具作者要把这种情况也声明出来。空回复不算一次补救，直接按节点失败收场。</li>
 *   <li>写答案一次：声明 {@code {"stage": "answer"}}，拿不到答案就是失败，不会重问。</li>
 *   <li>用了 {@code spawnSubAgent} 的话，子任务自己还有一轮工具循环，用的还是同一份脚本。</li>
 * </ul>
 *
 * <p>夹具管不到、也不会替换的模型调用：工具内部自己解析模型的那些路（搜索证据判定、金融方法解析、
 * 长输出压缩摘要）以及追问历史的摘要压缩。写场景时避开这些工具与开关，否则那几次调用会落到真实
 * 供应商上，这一次验收就不再是纯夹具跑出来的。</p>
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
    private static final Set<String> TURN_FIELDS = Set.of("for", "optional", "text", "toolCalls");
    private static final Set<String> CALL_FIELDS = Set.of("id", "name", "argumentsJson");
    /** 声明里的阶段字段名；除它以外的键都要在 {@link FixtureCallIdentity#SCOPE_KEYS} 里。 */
    private static final String STAGE_KEY = "stage";

    /** 一次工具调用：身份、工具名、参数原文。 */
    public record ToolCall(String id, String name, String argumentsJson) { }

    /** 一个模型回合：一段文本、一组工具调用，或者两者都给。 */
    public record Turn(String text, List<ToolCall> toolCalls) { }

    /**
     * 一个回合的声明：它回答哪一次调用，以及它是不是「可以不发生」。
     *
     * @param turnIndex 这个回合在脚本里的位置，从 0 起
     * @param stage     阶段名（{@code planning} / {@code node} / {@code answer} / {@code judge}）
     * @param scope     这一阶段的域字段；声明里没写的字段是通配
     * @param optional  可以不发生：Run 走到终态时没被领走不算问题
     */
    public record TurnDeclaration(int turnIndex, String stage, Map<String, String> scope, boolean optional) {

        /** 这次调用是不是落在这条声明里。 */
        public boolean matches(FixtureCallIdentity identity) {
            return identity != null && identity.matches(stage, scope);
        }

        /** 报错、日志与证据里的写法。 */
        public String describe() {
            StringBuilder text = new StringBuilder("回合 " + (turnIndex + 1) + " 声明 stage=").append(stage);
            scope.keySet().stream().sorted()
                    .forEach(key -> text.append(';').append(key).append('=').append(scope.get(key)));
            return optional ? text + "（可以不发生）" : text.toString();
        }
    }

    private final String fixtureId;
    private final String digest;
    private final List<Turn> turns;
    private final List<TurnDeclaration> declarations;

    private FrozenModelScript(String fixtureId, String digest, List<Turn> turns, List<TurnDeclaration> declarations) {
        this.fixtureId = fixtureId;
        this.digest = digest;
        this.turns = turns;
        this.declarations = declarations;
    }

    /**
     * 读一份脚本。
     *
     * @param fixtureId    夹具编号，只用来在报错里指明是哪一条夹具
     * @param scriptJson   {@code model_script_json} 列的原文
     * @param objectMapper 仓库里注入的 JSON 解析器
     * @return 解析好的有序回合清单与每个回合的声明，外加这份脚本的摘要
     * @throws AcceptanceFixtureExecutionException 脚本读不出来：空、最外层不是对象、有不认得的字段、
     *                                             回合没写 {@code for}、声明里的字段名或阶段认不出来、
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
        List<TurnDeclaration> declarations = new ArrayList<>();
        for (int turnIndex = 0; turnIndex < turnsNode.size(); turnIndex++) {
            JsonNode turnNode = turnsNode.get(turnIndex);
            declarations.add(readDeclaration(fixtureId, turnNode, turnIndex));
            turns.add(readTurn(fixtureId, turnNode, turnIndex, objectMapper));
        }
        return new FrozenModelScript(fixtureId, digestOf(scriptJson), List.copyOf(turns), List.copyOf(declarations));
    }

    /** 按顺序排好的回合清单。 */
    public List<Turn> turns() {
        return turns;
    }

    /** 有几个回合。 */
    public int size() {
        return turns.size();
    }

    /** 这份脚本的摘要（sha-256 十六进制）：夹具行被原位改过时，认领行里的摘要会对不上。 */
    public String digest() {
        return digest;
    }

    /** 第几个回合的声明。 */
    public TurnDeclaration declarationAt(int turnIndex) {
        return declarations.get(turnIndex);
    }

    /**
     * 声明回答这一次调用的回合，按回合序号从小到大。
     *
     * <p>认领时按这个顺序一个一个试：前一个被别人抢走了就试下一个，试到插进去为止。</p>
     */
    public List<TurnDeclaration> candidatesFor(FixtureCallIdentity identity) {
        List<TurnDeclaration> candidates = new ArrayList<>();
        for (TurnDeclaration declaration : declarations) {
            if (declaration.matches(identity)) {
                candidates.add(declaration);
            }
        }
        return List.copyOf(candidates);
    }

    /** 全部声明，按回合序号。 */
    public List<TurnDeclaration> declarations() {
        return declarations;
    }

    /** 全部声明的紧凑写法：报「没有可领的回合」时用它说明脚本里到底声明了什么。 */
    public List<String> declarationSummary() {
        return declarations.stream().map(TurnDeclaration::describe).toList();
    }

    /**
     * 读一个回合的声明。
     *
     * <p>{@code for} 必写：不写就是「按顺序第几个」那种隐式规则，少跑一次没人看得出来。
     * 字段名写错也当场拒绝——写成 {@code nodeid} 的声明永远不命中，等于把一次必答调用变成静默跳过。</p>
     */
    private static TurnDeclaration readDeclaration(String fixtureId, JsonNode node, int turnIndex) {
        String where = "第 " + (turnIndex + 1) + " 个回合";
        if (node == null || !node.isObject()) {
            throw invalid(fixtureId, where + "要是一个 JSON 对象");
        }
        JsonNode declaration = node.get("for");
        if (declaration == null || declaration.isNull()) {
            throw invalid(fixtureId, where + "没写 for：每个回合都要声明它回答哪一次调用，"
                    + "例如 {\"stage\": \"node\", \"nodeId\": \"n1\"}");
        }
        if (!declaration.isObject()) {
            throw invalid(fixtureId, where + "的 for 要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, declaration, declarationKeys(), where + "的 for");
        JsonNode stageNode = declaration.get(STAGE_KEY);
        if (stageNode == null || !stageNode.isTextual() || stageNode.asText().isBlank()) {
            throw invalid(fixtureId, where + "的 for 必须写 stage："
                    + String.join("、", stageNames()));
        }
        String stage = stageNode.asText().trim().toLowerCase();
        if (!stageNames().contains(stage)) {
            throw invalid(fixtureId, where + "的 for 里 stage 认不出来：" + stageNode.asText()
                    + "（认得的阶段：" + String.join("、", stageNames()) + "）");
        }
        Map<String, String> scope = new LinkedHashMap<>();
        declaration.fieldNames().forEachRemaining(key -> {
            if (STAGE_KEY.equals(key)) {
                return;
            }
            JsonNode value = declaration.get(key);
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                throw invalid(fixtureId, where + "的 for 里 " + key + " 没有值");
            }
            if (!value.isTextual() && !value.isNumber()) {
                throw invalid(fixtureId, where + "的 for 里 " + key + " 要写成字符串或数字");
            }
            scope.put(key, value.asText().trim());
        });
        boolean optional = false;
        JsonNode optionalNode = node.get("optional");
        if (optionalNode != null && !optionalNode.isNull()) {
            if (!optionalNode.isBoolean()) {
                throw invalid(fixtureId, where + "的 optional 要写成 true 或 false");
            }
            optional = optionalNode.asBoolean();
        }
        return new TurnDeclaration(turnIndex, stage, Map.copyOf(scope), optional);
    }

    private static Set<String> declarationKeys() {
        Set<String> keys = new HashSet<>(FixtureCallIdentity.SCOPE_KEYS);
        keys.add(STAGE_KEY);
        return keys;
    }

    private static Set<String> stageNames() {
        Set<String> names = new HashSet<>();
        for (FixtureCallIdentity.Stage stage : FixtureCallIdentity.Stage.values()) {
            names.add(stage.name().toLowerCase());
        }
        return names;
    }

    private static String digestOf(String scriptJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(scriptJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("这台机器上没有 SHA-256：夹具脚本摘要算不出来", missing);
        }
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
