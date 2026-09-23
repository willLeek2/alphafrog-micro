package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 夹具里冻结的结果放行与指定失败策略。
 *
 * <p>作用点是「外部任务的结果被接回来的那一步」：按选择器点名某一次调用，可以要求它的结果压到某个
 * 放行点被打开才落终态，可以要求它等同一个等待组里的别的成员先落终态，也可以点名让它按失败收尾。
 * 没点名的成员照常立刻收尾——策略只改「什么时候接回来」与「哪一条回来算失败」，成员终态、组齐备、
 * 恢复分段、名额与用量这些照旧。</p>
 *
 * <p>点名用的是选择器（{@code for}），而且要求写全：等待组那一级五项（计划代际、节点、第几次尝试、
 * 第几段、第几次模型回合）加上组内序号，工具调用编号写了就一起比对。原因很直接：工具调用编号是模型
 * 给的，同一个编号在别的节点、别的分段、别的模型回合里可以再出现一次（模型完全可能每次都从
 * {@code call_1} 开始编号）；少写任何一个组级字段，规则都会连带命中同一个 Run 里后面的某个等待组
 * （计划重建后有同名节点，同一个节点的后续分段里成员序号会重新从 0 开始）。写全之后，一条选择器在整条
 * Run 里只指得出一条成员，压住、等待、按失败收尾的对象才与夹具作者点名的那一条是同一个。</p>
 *
 * <p>匹配的结果按「一条成员最多被一条规则点名」核对：一条规则同时命中同一批里的两条以上成员、
 * 或者两条规则点名了同一条成员，都当场拒绝。{@code allExternalTasks} 是例外：这一条规则会打中
 * 本组里每一个对得上的外部作业。夹具写错了要当场说清哪一条规则对不上，不能让一次验收
 * 跑出个说不清压住了谁的结果。</p>
 *
 * <p>放行点是一条由受限控制面写入的库记录（{@code alphafrog_agent_run_release_point}），
 * 标成已放行就是这个点被打开。按「许可」读而不是「取走令牌」：每一轮重新读它，进程在读写之间
 * 退出也不会把这次放行弄丢；同一条成员终态仍然只写一次，由成员自己的状态机保证。</p>
 *
 * <p>读法与模型脚本同样从严：认不出的字段一律拒绝，拒绝时指出是哪条规则、哪个字段。</p>
 */
public final class AcceptanceReleasePolicy {

    /**
     * 被点名按失败收尾的成员，结果体里写的错误码。
     *
     * <p>等到结果再收尾的那条路（结果接收方）与当场就收尾的那条路（节点分段执行器）共用这一个码，
     * 两处写出来的成员结果才对得上。</p>
     */
    public static final String DESIGNATED_FAILURE_CODE = "acceptance_fixture_designated_failure";

    /**
     * 选择器里「哪一个等待组」这一级的字段：五个都要写。
     *
     * <p>写全这一级才唯一：等待组就是「哪一次模型回合的那一批工具调用」，这一段里的每个字段都能
     * 在别的等待组里再出现一次（计划重建后有同名节点、同一个节点的下一段里分段号不同但节点号相同）。
     * 少写一个，规则就可能连带命中同一 Run 里后面的某个等待组，压住、放行、判失败的对象就不是夹具
     * 作者点名的那一条。计划代际也算在里面：计划重建之后节点号会重新从同一个名字开始。</p>
     */
    public static final Set<String> GROUP_KEYS =
            Set.of("planGeneration", "nodeId", "nodeAttempt", "segmentSequence", "modelTurn");

    /**
     * 选择器里「组里哪一条成员」这一级的字段：没写 {@code operationId} 时 {@code memberSeq} 要写，
     * {@code toolCallId} 可以再写。写了非空 {@code operationId} 时，组内序号可以不写。
     *
     * <p>组内序号是夹具作者自己排出来的（这一批里第几个工具调用），与模型给不给编号无关；
     * 工具调用编号是模型给的，写了就一起比对，模型给出的编号与脚本写的不一样时这条规则不命中。
     * 外部作业身份是派发时按工具调用编出来的稳定编号，同一条 Run 里唯一。</p>
     */
    public static final Set<String> MEMBER_KEYS = Set.of("memberSeq", "toolCallId", "operationId");

    /** {@code uniqueExternalTask}：本组里对得上的外部作业必须恰好一条。 */
    public static final String MATCH_UNIQUE_EXTERNAL_TASK = "uniqueExternalTask";
    /** {@code allExternalTasks}：本组里对得上的每一条外部作业都被这一条规则点名。 */
    public static final String MATCH_ALL_EXTERNAL_TASKS = "allExternalTasks";

    private static final Set<String> MATCH_VALUES =
            Set.of(MATCH_UNIQUE_EXTERNAL_TASK, MATCH_ALL_EXTERNAL_TASKS);
    private static final Set<String> POLICY_FIELDS = Set.of("version", "rules", "maxHoldSeconds");
    private static final Set<String> RULE_FIELDS =
            Set.of("for", "holdUntilPoint", "releaseAfter", "fail", "match", "toolName", "codeContains");
    private static final Set<String> ACTIONS = Set.of("holdUntilPoint", "releaseAfter", "fail");
    private static final int CURRENT_VERSION = 2;

    /**
     * 一条规则：选择器 + 恰好一种动作。
     *
     * @param index          这条规则在策略里的序号（从 0 起）；命中记录与核对结论都用它点名
     * @param selector       选择器：字段名到值的映射，只写了的那几个字段参与比对；{@code match} 规则可为空
     * @param action         {@code holdUntilPoint} / {@code releaseAfter} / {@code fail}
     * @param holdReleaseKey 压住时等的那个放行点名字
     * @param releaseAfter   等哪几条成员先落终态（每条是一个选择器，在这个等待组里解析）
     * @param failureDetail  按失败收尾时写进成员结果的说明
     * @param match          按外部作业身份点名：{@code uniqueExternalTask} / {@code allExternalTasks}；与 {@code for} 互斥
     * @param toolName       {@code match} 时可选：只认这个工具名；不写就是本组里任意工具
     * @param codeContains   {@code match} 时可选：参数正文要含这一段
     */
    public record Rule(int index,
                       Map<String, String> selector,
                       String action,
                       String holdReleaseKey,
                       List<Map<String, String>> releaseAfter,
                       String failureDetail,
                       String match,
                       String toolName,
                       String codeContains) {

        public boolean holds() {
            return "holdUntilPoint".equals(action);
        }

        public boolean waitsForPeers() {
            return "releaseAfter".equals(action);
        }

        public boolean fails() {
            return "fail".equals(action);
        }

        /** 选择器的规范写法：字段名按字母序。证据与报错都用它，两处写出来是同一串字。 */
        public String selectorText() {
            return AcceptanceReleasePolicy.selectorText(selector);
        }

        public String describe() {
            if (match != null && !match.isBlank()) {
                return "第 " + index + " 条规则（" + action + "，match=" + match + "）";
            }
            return "第 " + index + " 条规则（" + action + "，选择器 " + selectorText() + "）";
        }
    }

    /**
     * 一次调用在策略眼里的样子：等待组一级的身份加上组内成员一级的身份。
     *
     * <p>两边调用点各自凑得出这些值：派发前从分段身份与这一段的模型回合来，结果接收方从等待组与成员
     * 记录来。凑出来的字段完全一样，策略才能在两个地方给同一个答案。</p>
     *
     * <p>这些字段合起来在同一条 Run 里唯一：等待组由「计划代际 + 节点 + 第几次尝试 + 第几段 + 第几次
     * 模型回合」定死，成员由组内序号（可再加工具调用编号）定死。策略的选择器要写全前五项与组内序号，
     * 一条规则因此只会命中一条成员——这是「点名的动作真的落到点名的对象上」的前提。选择器也可以只写
     * {@code operationId}：那是稳定的外部作业身份，同一组里两条成员不会共用。</p>
     *
     * <p>{@code operationId}、{@code toolName}、{@code argumentText} 只参与策略匹配。成员是不是同一条
     * 仍按等待组加组内序号（再加工具调用编号）认定：命中行落库时没有这三列，读回来要比对得上。</p>
     */
    public record MemberFacts(int planGeneration,
                              String nodeId,
                              int nodeAttempt,
                              int segmentSequence,
                              int modelTurn,
                              int memberSeq,
                              String toolCallId,
                              String operationId,
                              String toolName,
                              String argumentText) {

        /** 旧写法：外部作业身份、工具名、参数正文都空着。 */
        public MemberFacts(int planGeneration,
                           String nodeId,
                           int nodeAttempt,
                           int segmentSequence,
                           int modelTurn,
                           int memberSeq,
                           String toolCallId) {
            this(planGeneration, nodeId, nodeAttempt, segmentSequence, modelTurn, memberSeq, toolCallId,
                    null, null, null);
        }

        public Map<String, String> fields() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("planGeneration", String.valueOf(planGeneration));
            fields.put("nodeId", nodeId);
            fields.put("nodeAttempt", String.valueOf(nodeAttempt));
            fields.put("segmentSequence", String.valueOf(segmentSequence));
            fields.put("modelTurn", String.valueOf(modelTurn));
            fields.put("memberSeq", String.valueOf(memberSeq));
            if (toolCallId != null && !toolCallId.isBlank()) {
                fields.put("toolCallId", toolCallId);
            }
            if (operationId != null && !operationId.isBlank()) {
                fields.put("operationId", operationId);
            }
            if (toolName != null && !toolName.isBlank()) {
                fields.put("toolName", toolName);
            }
            return fields;
        }

        public String describe() {
            return "计划第 " + planGeneration + " 代 " + nodeId + " 第 " + nodeAttempt + " 次尝试 第 "
                    + segmentSequence + " 段 第 " + modelTurn + " 次模型回合 第 " + memberSeq + " 条成员"
                    + (toolCallId == null || toolCallId.isBlank() ? "（模型没给编号）" : "（" + toolCallId + "）")
                    + (operationId == null || operationId.isBlank() ? "" : " operationId=" + operationId);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MemberFacts facts)) {
                return false;
            }
            return planGeneration == facts.planGeneration
                    && nodeAttempt == facts.nodeAttempt
                    && segmentSequence == facts.segmentSequence
                    && modelTurn == facts.modelTurn
                    && memberSeq == facts.memberSeq
                    && java.util.Objects.equals(nodeId, facts.nodeId)
                    && java.util.Objects.equals(toolCallId, facts.toolCallId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(planGeneration, nodeId, nodeAttempt, segmentSequence,
                    modelTurn, memberSeq, toolCallId);
        }
    }

    /**
     * 一个等待组里每条规则命中了哪几条成员。
     *
     * <p>没命中的规则不在这里：那条规则可能点名的是别的等待组。所以这个结果只说明「这一批里各条规则
     * 打到了谁」，而「有没有哪条规则一次都没打中」要看整条 Run 的命中记录，是跑到终态时核对的事。</p>
     *
     * <p>{@code allExternalTasks} 允许一条规则打中同一批里的多条成员；{@code uniqueExternalTask}
     * 与 {@code for} 选择器仍是一条规则一条成员。{@link #targetOf} 取第一条，给旧的核对写法用。</p>
     */
    public record RuleMatches(Map<Integer, List<MemberFacts>> targetsByRuleIndex) {

        public RuleMatches(Map<Integer, List<MemberFacts>> targetsByRuleIndex) {
            Map<Integer, List<MemberFacts>> copy = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<MemberFacts>> entry : targetsByRuleIndex.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            this.targetsByRuleIndex = Map.copyOf(copy);
        }

        /** 这条规则打中的第一条成员；一条都没打中就是空。 */
        public Optional<MemberFacts> targetOf(int ruleIndex) {
            List<MemberFacts> targets = targetsByRuleIndex.get(ruleIndex);
            if (targets == null || targets.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(targets.get(0));
        }

        /** 这条规则打中的全部成员；{@code allExternalTasks} 时可能多于一条。 */
        public List<MemberFacts> targetsOf(int ruleIndex) {
            return targetsByRuleIndex.getOrDefault(ruleIndex, List.of());
        }

        public boolean isEmpty() {
            return targetsByRuleIndex.isEmpty();
        }

        /** 这一批里被打中的规则序号，按规则顺序。 */
        public Set<Integer> ruleIndexes() {
            return new LinkedHashSet<>(targetsByRuleIndex.keySet());
        }
    }

    private final String fixtureId;
    private final String digest;
    private final List<Rule> rules;
    private final int maxHoldSeconds;

    private AcceptanceReleasePolicy(String fixtureId, String digest, List<Rule> rules, int maxHoldSeconds) {
        this.fixtureId = fixtureId;
        this.digest = digest;
        this.rules = List.copyOf(rules);
        this.maxHoldSeconds = maxHoldSeconds;
    }

    /**
     * 读一份放行策略。
     *
     * @param fixtureId    夹具编号，报错与证据里都用它点名
     * @param policyJson   {@code dispatch_policy_json} 列的原文
     * @param objectMapper 仓库里注入的 JSON 解析器
     * @return 解析好的策略；一条规则都没有时返回空（这条夹具不管放行）
     * @throws AcceptanceFixtureExecutionException 策略读不出来：不是 JSON、有不认得的字段、
     *                                             选择器写不清点名的是哪一条成员、一条规则写了两种动作
     *                                             或什么都没写、放行点名字是空的、等兄弟成员的关系等不出头
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
        if (root.has("members")) {
            throw invalid(fixtureId, "放行策略还在用老写法 members（只写工具调用编号）：同一个编号在别的"
                    + "节点、别的分段可以再出现一次，点不准是哪一条成员。改成 rules，每条规则用 for 选择器"
                    + "写清是哪个等待组的哪一条成员");
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
        List<Rule> rules = new ArrayList<>();
        JsonNode ruleNodes = root.get("rules");
        if (ruleNodes != null && !ruleNodes.isNull()) {
            if (!ruleNodes.isArray()) {
                throw invalid(fixtureId, "放行策略的 rules 要是一个数组，每一项是一条规则");
            }
            int index = 0;
            for (JsonNode ruleNode : ruleNodes) {
                rules.add(readRule(fixtureId, index, ruleNode));
                index++;
            }
        }
        if (rules.isEmpty()) {
            // 一条规则都没有的策略管不了任何事，当成「这条夹具不管放行」处理。
            return Optional.empty();
        }
        rejectUnwaitableRules(fixtureId, rules);
        return Optional.of(new AcceptanceReleasePolicy(fixtureId, digestOf(policyJson), rules,
                maxHoldSeconds));
    }

    public String fixtureId() {
        return fixtureId;
    }

    /** 这份策略原文的 sha-256：同一条 Run 跑的过程中夹具行被原位改过时，重读会对不上。 */
    public String digest() {
        return digest;
    }

    public List<Rule> rules() {
        return rules;
    }

    public int ruleCount() {
        return rules.size();
    }

    /** 兜底：压住多少秒还没人放行就照常收尾；0 表示不设兜底（一直压着）。 */
    public int maxHoldSeconds() {
        return maxHoldSeconds;
    }

    /**
     * 这一批成员里，每条规则打到了谁。
     *
     * @param group 同一个等待组里的全部成员（派发前是这一批草稿，结果接收方是组里已落库的成员）
     * @throws AcceptanceFixtureExecutionException 一条规则同时命中两条以上成员（{@code allExternalTasks}
     *                                             除外），或者两条规则点名了同一条成员：点名对象不确定，
     *                                             压住/放行/判失败的是谁就说不清，当场拒绝。
     *                                             {@code uniqueExternalTask} 对上两条以上时用
     *                                             {@code acceptance_control_bind_ambiguous}
     */
    public RuleMatches match(List<MemberFacts> group) {
        Map<Integer, List<MemberFacts>> targets = new LinkedHashMap<>();
        Map<String, Rule> claimedBy = new LinkedHashMap<>();
        for (Rule rule : rules) {
            List<MemberFacts> hits = hitsOf(rule, group);
            boolean allowsMany = MATCH_ALL_EXTERNAL_TASKS.equals(rule.match());
            if (!allowsMany && hits.size() > 1) {
                throw refuse("acceptance_fixture_policy_ambiguous",
                        "夹具 " + fixtureId + " 的放行策略里，" + rule.describe() + " 同时命中同一批里的 "
                                + hits.size() + " 条成员（"
                                + hits.stream().map(MemberFacts::describe).toList()
                                + "）：一条规则只该点名一条成员，写得更具体一点");
            }
            if (hits.isEmpty()) {
                continue;
            }
            for (MemberFacts hit : hits) {
                Rule other = claimedBy.putIfAbsent(hit.describe(), rule);
                if (other != null) {
                    throw refuse("acceptance_fixture_policy_ambiguous",
                            "夹具 " + fixtureId + " 的放行策略里，" + other.describe() + " 与 " + rule.describe()
                                    + " 点名了同一条成员（" + hit.describe() + "）：同一条成员只该被一条规则点名，"
                                    + "两条规则会互相盖掉对方");
                }
            }
            targets.put(rule.index(), List.copyOf(hits));
        }
        return new RuleMatches(targets);
    }

    /** 这一批里，点名了这条成员的规则（按前面的核对，{@code allExternalTasks} 时一条规则可点名多条）。 */
    public Optional<Rule> ruleAt(RuleMatches matches, MemberFacts member) {
        for (Rule rule : rules) {
            for (MemberFacts target : matches.targetsOf(rule.index())) {
                if (member.equals(target)) {
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 一条「等兄弟成员先落终态」的规则，在这个等待组里等的是哪几条成员。
     *
     * @param rule   这条规则
     * @param target 这条规则点名的成员（要等的成员不能是它自己）
     * @param group  这个等待组里的全部成员
     * @throws AcceptanceFixtureExecutionException 点名了组里没有的成员、一个选择器命中两条以上成员
     *                                             （说明夹具写不清等的是哪一条），或者这条规则要等自己
     *                                             ——等自己永远等不到头，写法一对上就当场说清
     */
    public List<MemberFacts> peersOf(Rule rule, MemberFacts target, List<MemberFacts> group) {
        List<MemberFacts> peers = new ArrayList<>();
        for (Map<String, String> peerSelector : rule.releaseAfter()) {
            List<MemberFacts> hits = new ArrayList<>();
            for (MemberFacts member : group) {
                if (matches(peerSelector, member.fields())) {
                    hits.add(member);
                }
            }
            if (hits.isEmpty()) {
                throw refuse("acceptance_fixture_policy_peer_unknown",
                        "夹具 " + fixtureId + " 的放行策略里，" + rule.describe() + " 要等的成员 "
                                + selectorText(peerSelector) + " 不在这个等待组里（这一组是："
                                + group.stream().map(MemberFacts::describe).toList() + "）");
            }
            if (hits.size() > 1) {
                throw refuse("acceptance_fixture_policy_ambiguous",
                        "夹具 " + fixtureId + " 的放行策略里，" + rule.describe() + " 要等的成员 "
                                + selectorText(peerSelector) + " 在这个等待组里对上了 "
                                + hits.size() + " 条成员（"
                                + hits.stream().map(MemberFacts::describe).toList()
                                + "）：说清等的是哪一条");
            }
            MemberFacts peer = hits.get(0);
            if (target != null && peer.equals(target)) {
                throw refuse("acceptance_fixture_policy_peer_unknown",
                        "夹具 " + fixtureId + " 的放行策略里，" + rule.describe() + " 要等自己（"
                                + peer.describe() + "）先落终态，这条永远等不到头");
            }
            peers.add(peer);
        }
        return List.copyOf(peers);
    }

    /**
     * 这个等待组里点名的等待关系有没有绕成圈。
     *
     * <p>读策略时按选择器写法比对只能查出「写法一样的那类圈」；同一个组里两条成员互相等、写法却不同
     * （一条写 {@code {"nodeId":"n1","memberSeq":0}}，另一条写 {@code {"memberSeq":1}}）时，只有拿到
     * 这个组里到底有哪几条成员才判得出来。这件事必须在派发之前判：绕成圈的那几条成员会一直压着，
     * 除了兜底时限没有别的出路，而一个外部作业都还没建出来时停住不用还任何账。</p>
     *
     * @param group   这个等待组里的全部成员
     * @param matches 这一批里每条规则打中了谁
     * @throws AcceptanceFixtureExecutionException 点名的等待关系在这个组里绕成了圈
     */
    public void rejectCyclesInGroup(List<MemberFacts> group, RuleMatches matches) {
        // 先把这个组里每条规则「等谁」解析出来，顺便把等自己、等不存在的成员这两类拒掉。
        Map<MemberFacts, List<MemberFacts>> waitingOn = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (!rule.waitsForPeers()) {
                continue;
            }
            for (MemberFacts target : matches.targetsOf(rule.index())) {
                waitingOn.put(target, peersOf(rule, target, group));
            }
        }
        for (MemberFacts start : waitingOn.keySet()) {
            List<MemberFacts> path = new ArrayList<>();
            Set<MemberFacts> onPath = new LinkedHashSet<>();
            if (findCycleInGroup(start, waitingOn, onPath, path)) {
                throw refuse("acceptance_fixture_policy_ambiguous",
                        "夹具 " + fixtureId + " 的放行策略里，这个等待组内的等待关系绕成了一整圈："
                                + String.join(" → ", path.stream().map(MemberFacts::describe).toList())
                                + "：这几条成员互相等，谁都等不到头");
            }
        }
    }

    /** 从这个成员往下走，看是否绕回路径上已经走过的成员。 */
    private static boolean findCycleInGroup(MemberFacts current,
                                            Map<MemberFacts, List<MemberFacts>> waitingOn,
                                            Set<MemberFacts> onPath,
                                            List<MemberFacts> path) {
        if (onPath.contains(current)) {
            path.add(current);
            return true;
        }
        onPath.add(current);
        path.add(current);
        for (MemberFacts peer : waitingOn.getOrDefault(current, List.of())) {
            if (waitingOn.containsKey(peer) && findCycleInGroup(peer, waitingOn, onPath, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        onPath.remove(current);
        return false;
    }

    /** 选择器里写的字段是不是都能实际比对：写在里面的字段名要在认得的那几个里。 */
    private static boolean matches(Map<String, String> selector, Map<String, String> actual) {
        for (Map.Entry<String, String> entry : selector.entrySet()) {
            String value = actual.get(entry.getKey());
            if (value == null || !value.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这一条规则在这个等待组里打中了谁。
     *
     * <p>{@code for} 选择器按字段比对；{@code match} 按外部作业身份筛候选。{@code uniqueExternalTask}
     * 对上两条以上当场拒绝，一条都对不上就当作这条规则在这一批里没命中。</p>
     */
    private List<MemberFacts> hitsOf(Rule rule, List<MemberFacts> group) {
        if (rule.match() != null) {
            List<MemberFacts> candidates = candidatesOf(rule, group);
            if (MATCH_UNIQUE_EXTERNAL_TASK.equals(rule.match()) && candidates.size() > 1) {
                throw refuse("acceptance_control_bind_ambiguous",
                        "夹具 " + fixtureId + " 的放行策略里，" + rule.describe()
                                + " 的 uniqueExternalTask 对上了 " + candidates.size()
                                + " 条成员（operationId="
                                + candidates.stream().map(MemberFacts::operationId).toList()
                                + "）：这一组里只能有一条对得上，把 toolName 或 codeContains 写得更具体一点");
            }
            return candidates;
        }
        List<MemberFacts> hits = new ArrayList<>();
        for (MemberFacts member : group) {
            if (matches(rule.selector(), member.fields())) {
                hits.add(member);
            }
        }
        return hits;
    }

    /**
     * {@code match} 规则的候选：有外部作业身份，工具名对得上（写了的话），参数正文含指定片段（写了的话）。
     */
    private static List<MemberFacts> candidatesOf(Rule rule, List<MemberFacts> group) {
        List<MemberFacts> candidates = new ArrayList<>();
        for (MemberFacts member : group) {
            if (member.operationId() == null || member.operationId().isBlank()) {
                continue;
            }
            if (rule.toolName() != null && !rule.toolName().equals(member.toolName())) {
                continue;
            }
            if (rule.codeContains() != null) {
                String args = member.argumentText();
                if (args == null || !args.contains(rule.codeContains())) {
                    continue;
                }
            }
            candidates.add(member);
        }
        return candidates;
    }

    private static String optionalText(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.isIntegralNumber() ? String.valueOf(value.asInt()) : value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    /** 选择器的规范写法：字段名按字母序拼成 {@code key=value;key=value}。 */
    public static String selectorText(Map<String, String> selector) {
        List<String> keys = new ArrayList<>(selector.keySet());
        keys.sort(Comparator.naturalOrder());
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            parts.add(key + "=" + selector.get(key));
        }
        return String.join(";", parts);
    }

    private static Rule readRule(String fixtureId, int index, JsonNode node) {
        String where = "放行策略里第 " + index + " 条规则";
        if (node == null || !node.isObject()) {
            throw invalid(fixtureId, where + "要是一个 JSON 对象");
        }
        rejectUnknownFields(fixtureId, node, RULE_FIELDS, where);
        String match = optionalText(node.get("match"));
        String filterToolName = optionalText(node.get("toolName"));
        String codeContains = optionalText(node.get("codeContains"));
        boolean hasFor = node.get("for") != null && !node.get("for").isNull();
        if (match != null && hasFor) {
            throw invalid(fixtureId, where + "不能同时写 for 和 match：要么用选择器点名一条成员，"
                    + "要么按外部作业身份匹配");
        }
        if (match == null && !hasFor) {
            throw invalid(fixtureId, where + "没有写 for 选择器，也没有写 match：说清这条规则点名的是哪一次调用");
        }
        if (match != null && !MATCH_VALUES.contains(match)) {
            throw invalid(fixtureId, where + "的 match 只认 " + MATCH_UNIQUE_EXTERNAL_TASK + " 或 "
                    + MATCH_ALL_EXTERNAL_TASKS + "，读到的是 " + match);
        }
        if (match == null && (filterToolName != null || codeContains != null)) {
            throw invalid(fixtureId, where + "写了 toolName 或 codeContains，却没有写 match："
                    + "这两个字段只跟 uniqueExternalTask / allExternalTasks 一起用");
        }
        Map<String, String> selector = hasFor
                ? readSelector(fixtureId, node.get("for"), where)
                : Map.of();
        List<String> actions = new ArrayList<>();
        for (String field : ACTIONS) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                actions.add(field);
            }
        }
        if (actions.size() != 1) {
            throw invalid(fixtureId, where + "要恰好写一种动作（"
                    + String.join("、", ACTIONS.stream().sorted().toList())
                    + "），现在写了 " + actions.size() + " 种");
        }
        String action = actions.get(0);
        JsonNode value = node.get(action);
        return switch (action) {
            case "holdUntilPoint" -> new Rule(index, selector, action,
                    requiredText(fixtureId, value, where + "的 holdUntilPoint"), List.of(), null,
                    match, filterToolName, codeContains);
            case "fail" -> new Rule(index, selector, action, null, List.of(),
                    requiredText(fixtureId, value, where + "的 fail"),
                    match, filterToolName, codeContains);
            case "releaseAfter" -> new Rule(index, selector, action, null,
                    readPeerSelectors(fixtureId, value, where), null,
                    match, filterToolName, codeContains);
            default -> throw invalid(fixtureId, where + "写了认不出的动作 " + action);
        };
    }

    /**
     * 读一条规则的选择器。
     *
     * <p>写了非空 {@code operationId} 时，等待组那一级和组内序号都可以不写：外部作业身份在同一条
     * Run 里唯一，单独就能点名一条成员。</p>
     *
     * <p>没写 {@code operationId} 时，等待组那一级的五个字段要写全，成员这一级要写组内序号（工具调用
     * 编号写了就一起比对）。这样一条选择器在整条 Run 里只指得出一条成员：等待组由这五项定死，组内由
     * 序号定死。</p>
     *
     * <p>只写这一级的一部分也是不够的。少写 {@code planGeneration}，计划重建之后的同名节点会被打中；
     * 少写 {@code segmentSequence} 或 {@code modelTurn}，同一个节点的后续分段、同一段里的后续模型回合
     * 里同名的成员会被打中；少写组内序号，这个组里的每一条成员都会被这条规则打中（压住、等谁、
     * 按失败收尾的对象就说不清）。工具调用编号是模型给的，模型完全可能每次都从 {@code call_1}
     * 开始编号，所以它不能单独充当成员那一级。这几种写法只从策略本身就能判出来，读策略这一步就拒绝。</p>
     */
    private static Map<String, String> readSelector(String fixtureId, JsonNode node, String where) {
        if (node == null || node.isNull()) {
            throw invalid(fixtureId, where + "没有写 for 选择器：说清这条规则点名的是哪一次调用");
        }
        if (!node.isObject() || node.isEmpty()) {
            throw invalid(fixtureId, where + "的 for 要是非空对象，写清点名的是哪一次调用");
        }
        Map<String, String> selector = new LinkedHashMap<>();
        var names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!GROUP_KEYS.contains(field) && !MEMBER_KEYS.contains(field)) {
                throw invalid(fixtureId, where + "的 for 里有认不出的字段 " + field + "（认得的字段："
                        + String.join("、", allowedSelectorKeys()) + "）");
            }
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || !value.isValueNode()) {
                throw invalid(fixtureId, where + "的 for 里 " + field + " 的值读不出来，"
                        + "要写文字或整数");
            }
            String text = value.isIntegralNumber() ? String.valueOf(value.asInt()) : value.asText();
            if (text == null || text.isBlank()) {
                throw invalid(fixtureId, where + "的 for 里 " + field + " 是空的");
            }
            selector.put(field, text.trim());
        }
        if (selector.containsKey("operationId")) {
            // 外部作业身份单独就能点名一条成员：等待组字段和组内序号都可以不写。
            return selector;
        }
        List<String> missingGroup = new ArrayList<>(GROUP_KEYS);
        missingGroup.removeAll(selector.keySet());
        missingGroup.sort(Comparator.naturalOrder());
        if (!missingGroup.isEmpty()) {
            throw invalid(fixtureId, where + "的 for 里没写全等待组这一级的字段（"
                    + selectorText(selector) + "），少了 " + String.join("、", missingGroup)
                    + "：" + missingGroupHint(missingGroup));
        }
        if (!selector.containsKey("memberSeq")) {
            throw invalid(fixtureId, where + "的 for 里没写组内序号 memberSeq（"
                    + selectorText(selector) + "）：这个组里的每一条成员都会被这条规则打中，"
                    + "说不清点名的是哪一条。工具调用编号是模型给的，同一个编号在别的组里会再出现，"
                    + "不能单独充当点名成员那一级");
        }
        return selector;
    }

    /** 少写某一个组级字段时会发生什么：报错里直接写成「会打中谁」，不用人去推。 */
    private static String missingGroupHint(List<String> missingGroup) {
        List<String> hints = new ArrayList<>();
        for (String field : missingGroup) {
            switch (field) {
                case "planGeneration" -> hints.add("计划代际：计划重建之后同一个节点名会再来一次，会被这条规则打中");
                case "nodeId" -> hints.add("节点：别的节点里同名的成员会被这条规则打中");
                case "nodeAttempt" -> hints.add("第几次尝试：同一个节点重试那一次会被这条规则打中");
                case "segmentSequence" -> hints.add("第几段：同一个节点后续分段里同名的成员会被这条规则打中");
                case "modelTurn" -> hints.add("第几次模型回合：同一段里后续模型回合的成员会被这条规则打中");
                default -> hints.add(field);
            }
        }
        return String.join("；", hints);
    }

    private static List<Map<String, String>> readPeerSelectors(String fixtureId,
                                                              JsonNode value,
                                                              String where) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw invalid(fixtureId, where + "的 releaseAfter 要是非空数组，写清等哪些成员");
        }
        List<Map<String, String>> peers = new ArrayList<>();
        int index = 0;
        for (JsonNode element : value) {
            String peerWhere = where + "的 releaseAfter 第 " + index + " 项";
            if (!element.isObject() || element.isEmpty()) {
                throw invalid(fixtureId, peerWhere + "要是非空对象：在同一个等待组里点名一条成员，"
                        + "写 memberSeq 或 toolCallId（可以再加节点那几项）");
            }
            Map<String, String> selector = new LinkedHashMap<>();
            var names = element.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                if (!GROUP_KEYS.contains(field) && !MEMBER_KEYS.contains(field)) {
                    throw invalid(fixtureId, peerWhere + "里有认不出的字段 " + field + "（认得的字段："
                            + String.join("、", allowedSelectorKeys()) + "）");
                }
                JsonNode fieldValue = element.get(field);
                if (fieldValue == null || fieldValue.isNull() || !fieldValue.isValueNode()) {
                    throw invalid(fixtureId, peerWhere + "里 " + field + " 的值读不出来");
                }
                String text = fieldValue.isIntegralNumber()
                        ? String.valueOf(fieldValue.asInt()) : fieldValue.asText();
                if (text == null || text.isBlank()) {
                    throw invalid(fixtureId, peerWhere + "里 " + field + " 是空的");
                }
                selector.put(field, text.trim());
            }
            if (selector.keySet().stream().noneMatch(MEMBER_KEYS::contains)) {
                throw invalid(fixtureId, peerWhere + "没写是组里哪一条成员（"
                        + selectorText(selector) + "）：补上 memberSeq 或 toolCallId");
            }
            peers.add(selector);
            index++;
        }
        return List.copyOf(peers);
    }

    private static List<String> allowedSelectorKeys() {
        Set<String> allowed = new LinkedHashSet<>(GROUP_KEYS);
        allowed.addAll(MEMBER_KEYS);
        List<String> keys = new ArrayList<>(allowed);
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    /**
     * 点名的等待关系要等得出头：不许自己等自己，也不许绕成一圈。
     *
     * <p>这两类写法只从策略本身就能判出来，所以读策略这一步就拒绝，不等派发成员之后才发现：等不出头的
     * 关系会让那几条成员一直压着，除了兜底时限或人来收拾，没有别的出路；夹具写错了就该当场说清是哪
     * 几条成员绕住了，而不是让一次验收跑出个看不出原因的等待。</p>
     *
     * <p>判据用的是选择器的规范写法：两条规则的选择器写法一样时才算同一个点。也就是「写法一样的那类
     * 圈」在这里就拒掉，写法不同、但落到同一个等待组里确实互相等的那类，要等那批成员摆出来才判得出，
     * 放在 {@link #rejectCyclesInGroup}。「点名了不存在的成员」同样要看真的派发了哪些调用，也在那里判。</p>
     */
    private static void rejectUnwaitableRules(String fixtureId, List<Rule> rules) {
        Map<String, Rule> bySelectorText = new LinkedHashMap<>();
        for (Rule rule : rules) {
            bySelectorText.putIfAbsent(rule.selectorText(), rule);
        }
        for (Rule rule : rules) {
            if (!rule.waitsForPeers()) {
                continue;
            }
            for (Map<String, String> peer : rule.releaseAfter()) {
                if (peer.equals(rule.selector())) {
                    throw invalid(fixtureId, "放行策略里 " + rule.describe() + " 要等自己先落终态，"
                            + "这条永远等不到头");
                }
            }
        }
        // 只有「等别的成员先落终态」这种规则会连成等待关系（一条规则只写一种动作）。按选择器的规范
        // 写法连边：两条规则指的是同一条成员（选择器写法一样）就算同一个点。深度优先找出第一条绕回来
        // 的路径，报错里写清是哪一圈。
        Map<String, Integer> marks = new LinkedHashMap<>();
        for (Rule start : rules) {
            if (!start.waitsForPeers()) {
                continue;
            }
            List<String> path = new ArrayList<>();
            if (findCycle(start, bySelectorText, marks, path)) {
                throw invalid(fixtureId, "放行策略里这几条规则互相等成了一整圈："
                        + String.join(" → ", path));
            }
        }
    }

    /** 从这条规则往下走，看是否绕回自己；走到认过的点就剪掉，path 里留着这一圈。 */
    private static boolean findCycle(Rule rule,
                                     Map<String, Rule> rulesBySelector,
                                     Map<String, Integer> marks,
                                     List<String> path) {
        String key = rule.selectorText();
        Integer mark = marks.get(key);
        if (mark != null) {
            // 2 表示这棵子树已经查完、没有圈；1 表示还在当前这条路径上，遇到它就是绕回来了。
            if (mark == 1) {
                // 只有「重见」这一个地方往路径上补名字：往上回退的那几层不再补，
                // 否则同一圈会被每一层各补一次，报错里写出来的路径就不是真正那一圈。
                path.add(key);
                return true;
            }
            return false;
        }
        marks.put(key, 1);
        path.add(key);
        for (Map<String, String> peer : rule.releaseAfter()) {
            Rule next = rulesBySelector.get(selectorText(peer));
            if (next != null && next.waitsForPeers() && findCycle(next, rulesBySelector, marks, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        marks.put(key, 2);
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

    private static String digestOf(String policyJson) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(policyJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte one : hash) {
                hex.append(Character.forDigit((one >> 4) & 0xF, 16));
                hex.append(Character.forDigit(one & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("算不出放行策略的摘要：" + e.getMessage(), e);
        }
    }

    private static AcceptanceFixtureExecutionException invalid(String fixtureId, String detail) {
        return refuse("acceptance_fixture_policy_invalid",
                "夹具 " + fixtureId + " 的放行策略读不出来，" + detail);
    }
}
