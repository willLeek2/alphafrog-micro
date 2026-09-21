package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 夹具里冻结的结果放行与指定失败策略。
 *
 * <p>作用点是「外部任务的结果被接回来的那一步」：按脚本里的工具调用身份点名，可以要求某几条
 * 成员的结果压到某个放行点被打开才落终态，可以要求某几条等别的成员先落终态，也可以点名一条
 * 成员按失败收尾。没点名的成员照常立刻收尾——策略只改「什么时候接回来」与「哪一条回来是失败」，
 * 成员终态、组齐备、恢复分段、名额与用量这些照旧。</p>
 *
 * <p>放行点是一条由受限控制面写入的库记录（{@code alphafrog_agent_run_release_point}），
 * 标成已放行就是这个点被打开。按「许可」读而不是「取走令牌」：每一轮重新读它，进程在读写之间
 * 退出也不会把这次放行弄丢；同一条成员终态仍然只写一次，由成员自己的状态机保证。</p>
 *
 * <p>读法与模型脚本同样从严：认不出的字段一律拒绝，拒绝时指出是哪条成员、哪个字段。</p>
 */
public final class AcceptanceReleasePolicy {

    /**
     * 被点名按失败收尾的成员，结果体里写的错误码。
     *
     * <p>等到结果再收尾的那条路（结果接收方）与当场就收尾的那条路（节点分段执行器）共用这一个码，
     * 两处写出来的成员结果才对得上。</p>
     */
    public static final String DESIGNATED_FAILURE_CODE = "acceptance_fixture_designated_failure";

    private static final Set<String> POLICY_FIELDS = Set.of("version", "members", "maxHoldSeconds");
    private static final Set<String> RULE_FIELDS = Set.of("holdUntilPoint", "releaseAfter", "fail");
    private static final int CURRENT_VERSION = 1;

    /** 一条成员的规则：三种动作里恰好一种。 */
    public record MemberRule(String holdReleaseKey, List<String> releaseAfter, String failureDetail) {

        static MemberRule hold(String releaseKey) {
            return new MemberRule(releaseKey, List.of(), null);
        }

        static MemberRule after(List<String> members) {
            return new MemberRule(null, List.copyOf(members), null);
        }

        static MemberRule fail(String detail) {
            return new MemberRule(null, List.of(), detail);
        }
    }

    private final Map<String, MemberRule> rulesByToolCallId;
    private final int maxHoldSeconds;

    private AcceptanceReleasePolicy(Map<String, MemberRule> rulesByToolCallId, int maxHoldSeconds) {
        this.rulesByToolCallId = Map.copyOf(rulesByToolCallId);
        this.maxHoldSeconds = maxHoldSeconds;
    }

    /**
     * 读一份放行策略。
     *
     * @param fixtureId        夹具编号，只用来在报错里指明是哪一条夹具
     * @param policyJson       {@code dispatch_policy_json} 列的原文
     * @param objectMapper     仓库里注入的 JSON 解析器
     * @return 解析好的策略；一份规则都没有时返回空（这条夹具不管放行）
     * @throws AcceptanceFixtureExecutionException 策略读不出来：不是 JSON、有不认得的字段、
     *                                             一条成员写了两种动作或什么都没写、放行点名字是空的
     */
    public static Optional<AcceptanceReleasePolicy> parse(String fixtureId,
                                                          String policyJson,
                                                          ObjectMapper objectMapper) {
        if (policyJson == null || policyJson.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(policyJson);
        } catch (Exception e) {
            throw invalid(fixtureId, "放行策略不是合法 JSON: " + e.getMessage());
        }
        if (root == null || root.isNull()) {
            return Optional.empty();
        }
        if (!root.isObject()) {
            throw invalid(fixtureId, "放行策略的最外层要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, root, POLICY_FIELDS, "放行策略");
        JsonNode version = root.get("version");
        if (version != null && !version.isNull()
                && (!version.isIntegralNumber() || version.asInt() != CURRENT_VERSION)) {
            throw invalid(fixtureId, "放行策略的 version 只认 " + CURRENT_VERSION
                    + "，读到的是 " + version);
        }
        int maxHoldSeconds = 0;
        JsonNode maxHold = root.get("maxHoldSeconds");
        if (maxHold != null && !maxHold.isNull()) {
            if (!maxHold.isIntegralNumber() || maxHold.asInt() < 1) {
                throw invalid(fixtureId, "放行策略的 maxHoldSeconds 要是大于 0 的整数");
            }
            maxHoldSeconds = maxHold.asInt();
        }
        Map<String, MemberRule> rules = new LinkedHashMap<>();
        JsonNode members = root.get("members");
        if (members != null && !members.isNull()) {
            if (!members.isObject()) {
                throw invalid(fixtureId, "放行策略的 members 要是一个对象，键是脚本里的工具调用身份");
            }
            Iterator<String> names = members.fieldNames();
            while (names.hasNext()) {
                String toolCallId = names.next();
                if (toolCallId.isBlank()) {
                    throw invalid(fixtureId, "放行策略里有一条成员的工具调用身份是空的");
                }
                rules.put(toolCallId, readRule(fixtureId, toolCallId, members.get(toolCallId)));
            }
        }
        if (rules.isEmpty()) {
            // 一条成员都没点名的策略管不了任何事，当成「这条夹具不管放行」处理。
            return Optional.empty();
        }
        rejectUnwaitableRules(fixtureId, rules);
        return Optional.of(new AcceptanceReleasePolicy(rules, maxHoldSeconds));
    }

    /** 这条工具调用的结果要不要等放行点；返回放行点的名字。 */
    public Optional<String> releasePointKey(String toolCallId) {
        MemberRule rule = rulesByToolCallId.get(toolCallId);
        return rule == null || rule.holdReleaseKey() == null
                ? Optional.empty() : Optional.of(rule.holdReleaseKey());
    }

    /** 这条工具调用要等哪些成员先落终态。 */
    public List<String> releaseAfter(String toolCallId) {
        MemberRule rule = rulesByToolCallId.get(toolCallId);
        return rule == null ? List.of() : rule.releaseAfter();
    }

    /** 这条工具调用是不是被点名按失败收尾；返回写在策略里的失败说明。 */
    public Optional<String> designatedFailure(String toolCallId) {
        MemberRule rule = rulesByToolCallId.get(toolCallId);
        return rule == null || rule.failureDetail() == null
                ? Optional.empty() : Optional.of(rule.failureDetail());
    }

    /** 兜底：压住多少秒还没人放行就照常收尾；0 表示不设兜底（一直压着）。 */
    public int maxHoldSeconds() {
        return maxHoldSeconds;
    }

    /** 这份策略管几条成员。 */
    public int size() {
        return rulesByToolCallId.size();
    }

    /** 这条工具调用有没有被策略点名（任何一种动作）。 */
    public boolean covers(String toolCallId) {
        return rulesByToolCallId.containsKey(toolCallId);
    }

    private static MemberRule readRule(String fixtureId, String toolCallId, JsonNode node) {
        String where = "放行策略里成员 " + toolCallId;
        if (node == null || !node.isObject()) {
            throw invalid(fixtureId, where + "的规则要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, node, RULE_FIELDS, where);
        List<String> actions = new ArrayList<>();
        for (String field : RULE_FIELDS) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                actions.add(field);
            }
        }
        if (actions.size() != 1) {
            throw invalid(fixtureId, where + "要恰好写一种动作（"
                    + String.join("、", RULE_FIELDS.stream().sorted().toList())
                    + "），现在写了 " + actions.size() + " 种");
        }
        String action = actions.get(0);
        JsonNode value = node.get(action);
        return switch (action) {
            case "holdUntilPoint" -> MemberRule.hold(requiredText(fixtureId, value, where + "的 holdUntilPoint"));
            case "fail" -> MemberRule.fail(requiredText(fixtureId, value, where + "的 fail"));
            case "releaseAfter" -> MemberRule.after(readNames(fixtureId, value, where));
            default -> throw invalid(fixtureId, where + "写了认不出的动作 " + action);
        };
    }

    private static List<String> readNames(String fixtureId, JsonNode value, String where) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw invalid(fixtureId, where + "的 releaseAfter 要是非空数组，写清等哪些成员");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.asText().isBlank()) {
                throw invalid(fixtureId, where + "的 releaseAfter 里每一项都要是非空字符串");
            }
            names.add(element.asText());
        }
        return names;
    }

    /**
     * 点名的等待关系要等得出头：不许自己等自己，也不许绕成一圈。
     *
     * <p>这两类写法只从策略本身就能判出来，所以读策略这一步就拒绝，不等派发成员之后才发现：等不出头的
     * 关系会让那几条成员一直压着，除了兜底时限或人来收拾，没有别的出路；夹具写错了就该当场说清是哪
     * 几条成员绕住了，而不是让一次验收跑出个看不出原因的等待。</p>
     *
     * <p>「点名了不存在的成员」不在这里判：那要看这一批真的派发了哪些工具调用，是派发前那一步的事。</p>
     */
    private static void rejectUnwaitableRules(String fixtureId,
                                             Map<String, MemberRule> rules) {
        for (Map.Entry<String, MemberRule> entry : rules.entrySet()) {
            String toolCallId = entry.getKey();
            List<String> peers = entry.getValue().releaseAfter();
            if (peers.contains(toolCallId)) {
                throw invalid(fixtureId, "放行策略里成员 " + toolCallId + " 要等自己先落终态，这条永远等不到头");
            }
        }
        // 只有「等别的成员先落终态」这种规则会连成等待关系；一条成员只写一种动作，所以等待关系就是
        // 这些边。深度优先找出第一条绕回来的路径，报错里写清是哪一圈。
        Map<String, Integer> marks = new LinkedHashMap<>();
        for (String start : rules.keySet()) {
            List<String> path = new ArrayList<>();
            if (findCycle(start, rules, marks, path)) {
                throw invalid(fixtureId, "放行策略里这几条成员互相等成了一整圈："
                        + String.join(" → ", path));
            }
        }
    }

    /** 从这条成员往下走，看是否绕回自己；走到认过的成员就剪掉，path 里留着这一圈。 */
    private static boolean findCycle(String toolCallId,
                                     Map<String, MemberRule> rules,
                                     Map<String, Integer> marks,
                                     List<String> path) {
        Integer mark = marks.get(toolCallId);
        if (mark != null) {
            // 2 表示这棵子树已经查完、没有圈；1 表示还在当前这条路径上，遇到它就是绕回来了。
            if (mark == 1) {
                // 只有「重见」这一个地方往路径上补名字：往上回退的那几层不再补，
                // 否则同一圈会被每一层各补一次，报错里写出来的路径就不是真正那一圈。
                path.add(toolCallId);
                return true;
            }
            return false;
        }
        MemberRule rule = rules.get(toolCallId);
        if (rule == null || rule.releaseAfter().isEmpty()) {
            marks.put(toolCallId, 2);
            return false;
        }
        marks.put(toolCallId, 1);
        path.add(toolCallId);
        for (String peer : rule.releaseAfter()) {
            if (findCycle(peer, rules, marks, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        marks.put(toolCallId, 2);
        return false;
    }

    private static String requiredText(String fixtureId, JsonNode value, String where) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(fixtureId, where + "要是非空字符串");
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

    private static AcceptanceFixtureExecutionException invalid(String fixtureId, String detail) {
        return refuse("acceptance_fixture_policy_invalid",
                "夹具 " + fixtureId + " 的放行策略读不出来，" + detail);
    }
}
