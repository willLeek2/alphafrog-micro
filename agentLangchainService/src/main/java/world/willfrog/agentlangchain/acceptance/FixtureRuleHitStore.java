package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 放行策略的规则记录表：每条规则打中了哪一条成员，以及被点名的动作有没有真的落到它身上。
 *
 * <p>规则只写选择器的时候，写错一处（计划代际记错、节点名打错、分段号写错、成员序号写反）不会报错，
 * 只会静静地谁也不打中：那次验收看起来跑完了，被压住、被放行的对象却根本不存在。所以每条规则命中
 * 一条成员就留一行，跑到终态时按它核对「这次点名要求的那几件事是不是真的都发生了」。</p>
 *
 * <p>只有「打中了谁」这一件事还不够。一条要求压住某条成员、或者让某条成员等兄弟成员的规则，在
 * 那条成员当场就把结果拿回来的时候根本没有可等的东西：命中的那一行已经在了，压住的动作却没发生，
 * 一次验收会因此显示证据完整。所以一行里记两个时刻——{@code matched_at} 是选择器打中目标的时刻，
 * {@code action_settled_at} 与 {@code action_outcome} 是被点名的动作真的生效（或者明确没有生效）时才写。
 * 终态核对按后者算：动作没生效的规则与一条都没打中的规则一样，都要在结论里点名。</p>
 *
 * <p>一条规则在整条 Run 里只能绑定一个目标（表上 {@code (run_id, rule_index)} 唯一）。选择器写全之后
 * 本来就只指得出一条成员，所以这条约束是最后一道：真出现第二条时写入方当场失败关闭（错误码
 * {@code acceptance_fixture_policy_target_conflict}），不会悄悄多记一行，把「动作落到了点名的对象上」
 * 这句话撑成通过。</p>
 *
 * <p>策略原文的摘要与全部规则也在第一次读到它的时候留一份快照：之后夹具行被改过、被停用或被删掉，
 * 核对结论仍然说得清「这次验收要求点名哪些调用」。快照的写入是「插入后读回赢家」，两个进程同时
 * 第一次读到不同内容时，后写的那个会读回先写的摘要并比对，对不上就当场拒绝。</p>
 */
@Component
@Slf4j
public class FixtureRuleHitStore {

    /** 压住等放行点：成员真的被压住了（只推下次查询时间，不落终态）。 */
    public static final String APPLIED_HOLD = "hold_waiting";
    /** 等兄弟成员先落终态：等待关系真的建立了，这条成员在为别人等着。 */
    public static final String APPLIED_PEER = "peer_waiting";
    /** 按失败收尾：指定失败的终态真的写进去了。 */
    public static final String APPLIED_FAILURE = "designated_failure";
    /** 点名了成员，但被点名的动作没有落到它身上（成员当场出结果、或者这条成员被中止收尾）。 */
    public static final String NOT_APPLIED = "not_applied";

    /**
     * 「这条 Run 一开始就没有放行策略」写进摘要列的冻结值。
     *
     * <p>它占的是摘要那一列，所以写成一个不会与 sha-256 撞上的固定串；读回来时按 {@link #describe}
     * 说成「没有放行策略」，不让人以为是一次摘要对不上。</p>
     */
    public static final String ABSENT_POLICY_DIGEST = "absent-no-policy";
    /** 算「动作真的生效」的三种结果。 */
    public static final Set<String> APPLIED_OUTCOMES = Set.of(APPLIED_HOLD, APPLIED_PEER, APPLIED_FAILURE);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FixtureRuleHitStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 把这条 Run 用的策略快照写下来。
     *
     * <p>第一次读到策略时写（那时夹具行一定还在），同一条 Run 只写一次；已经写过时要求摘要一致——
     * 内容被原位改过时当场拒绝，而不是让一次验收悄悄换一版策略。两个进程同时第一次写时读回赢家
     * 比对摘要：不读回的话，两边会各自按自己读到的那一版往下跑，而库里只留下其中一个版本的摘要。</p>
     */
    public void snapshotPolicy(String runId,
                               String fixtureId,
                               String scenarioId,
                               AcceptanceReleasePolicy policy) {
        if (runId == null || runId.isBlank() || policy == null) {
            return;
        }
        freezePolicy(runId, fixtureId, scenarioId, policy.digest(), rulesJson(policy),
                policy.ruleCount(), "策略摘要 " + policy.digest());
    }

    /**
     * 把「这条 Run 一开始就没有放行策略」也冻结成一个明确的值。
     *
     * <p>不冻结的话会漏掉一种改法：夹具一开始没写策略，跑到一半被改成带策略。这条 Run 在前半段
     * 的成员结果是当场收尾的（没有策略可依），后半段却会按新加的策略压住或判失败，一次验收说不清
     * 它到底按哪一版跑完。两个进程第一次同时读时一个读到空、一个读到策略，同样由这里的读回比对
     * 定胜负：赢家是策略时这一次空读当场拒绝，赢家是空时读到策略的那一次会拒绝。</p>
     */
    public void snapshotPolicyAbsent(String runId, String fixtureId, String scenarioId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        freezePolicy(runId, fixtureId, scenarioId, ABSENT_POLICY_DIGEST, "[]", 0, "没有放行策略");
    }

    private void freezePolicy(String runId,
                              String fixtureId,
                              String scenarioId,
                              String digest,
                              String rulesJson,
                              int ruleCount,
                              String describe) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO alphafrog_agent_run_acceptance_fixture_policy
                    (run_id, fixture_id, scenario_id, policy_digest, rules_json, rule_count)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT DO NOTHING
                """, runId, fixtureId, scenarioId, digest, rulesJson, ruleCount);
        String stored = policydigestOf(runId);
        if (stored == null) {
            throw refuse("acceptance_fixture_content_changed",
                    "这条 Run 的策略快照没写进去，也读不回来：这一次验收说不清用的是哪一版策略");
        }
        if (!stored.equals(digest)) {
            throw refuse("acceptance_fixture_content_changed",
                    "这条 Run 一开始冻结的是 " + describe(stored) + "，现在读回来的是 " + describe(digest)
                            + "：同一条 Run 跑的过程中夹具的放行策略被改过，这一次验收说不清用的是哪一版");
        }
        if (inserted == 0) {
            log.info("这条 Run 的策略快照已经写过了，读回赢家核对一致: runId={} 冻结值={}",
                    runId, describe(stored));
        }
    }

    /** 排查与报错里把冻结值说成人话：空策略那个值不是摘要，别让它看起来像摘要对不上。 */
    private static String describe(String digest) {
        return ABSENT_POLICY_DIGEST.equals(digest) ? "「没有放行策略」" : "策略摘要 " + digest;
    }

    private String policydigestOf(String runId) {
        List<String> recorded = jdbcTemplate.query("""
                SELECT policy_digest
                FROM alphafrog_agent_run_acceptance_fixture_policy
                WHERE run_id = ?
                """, (rs, rowNum) -> rs.getString("policy_digest"), runId);
        return recorded.isEmpty() ? null : recorded.get(0);
    }

    /**
     * 记一笔「这条规则的选择器打中这条成员」，并把目标身份一起写下来。
     *
     * <p>重复记同一件事（派发前记过、结果接收方又记一次、同一条成员被压住时一轮一轮走到这里）
     * 只留一行，不报错也不覆盖。同一个规则序号落到**另一个**目标上时当场拒绝：选择器写全之后
     * 不该发生，真发生了说明这条 Run 上有两批调用撞上了同一个点名，动作落到谁身上已经说不清。</p>
     */
    public void recordMatch(String runId,
                            AcceptanceReleasePolicy.Rule rule,
                            long groupId,
                            AcceptanceReleasePolicy.MemberFacts target) {
        if (runId == null || runId.isBlank() || rule == null || target == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO alphafrog_agent_run_acceptance_fixture_rule_hit
                    (run_id, rule_index, selector_text, action, group_id,
                     plan_generation, node_id, node_attempt, segment_sequence, model_turn,
                     member_seq, tool_call_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, runId, rule.index(), rule.selectorText(), rule.action(), groupId,
                target.planGeneration(), target.nodeId(), target.nodeAttempt(),
                target.segmentSequence(), target.modelTurn(), target.memberSeq(), target.toolCallId());
        RuleHit stored = hitOf(runId, rule.index()).orElse(null);
        if (stored == null) {
            throw refuse("acceptance_fixture_policy_target_conflict",
                    "这条 Run 上第 " + rule.index() + " 条规则的目标没写进去，也读不回来："
                            + "这一次验收说不清点名的动作落到了谁身上");
        }
        if (!stored.target().equals(target)) {
            throw refuse("acceptance_fixture_policy_target_conflict",
                    "这条 Run 上第 " + rule.index() + " 条规则已经点名过 " + stored.target().describe()
                            + "，现在又要点名 " + target.describe()
                            + "：一条规则在整条 Run 里只该绑定一个目标，两批调用撞上同一个点名时"
                            + "压住、等待或判失败的是谁就说不清了");
        }
    }

    /**
     * 记一笔「被点名的动作落到这条成员身上了」。
     *
     * @param outcome 三种生效结果之一（{@link #APPLIED_HOLD}、{@link #APPLIED_PEER}、
     *                {@link #APPLIED_FAILURE}）或者 {@link #NOT_APPLIED}
     * @param detail  排查用的一句话；没生效时写清为什么
     * @return true 表示这次结果写进去了（重复上报同一结果不算，返回 false）
     */
    public boolean recordAction(String runId, int ruleIndex, String outcome, String detail) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        int updated = jdbcTemplate.update("""
                UPDATE alphafrog_agent_run_acceptance_fixture_rule_hit
                SET action_settled_at = CURRENT_TIMESTAMP,
                    action_outcome = ?,
                    action_detail = ?
                WHERE run_id = ?
                  AND rule_index = ?
                  AND action_outcome IS NULL
                """, outcome, detail, runId, ruleIndex);
        if (updated == 0) {
            RuleHit stored = hitOf(runId, ruleIndex).orElse(null);
            if (stored == null) {
                // 命中那一行没写进去（或者已经被清理）：结论会按「这条规则一次都没打中」算，
                // 这里留一句日志说明真实原因，别让排查的人以为选择器没对上。
                log.warn("被点名的动作没有对应的命中行，动作结果没记上: runId={} 规则={} 结果={}",
                        runId, ruleIndex, outcome);
            }
            return false;
        }
        return true;
    }

    /** 这条 Run 上还占着名额、动作结果还没写的一条规则：调用方在动作真的生效后写它。 */
    public Optional<RuleHit> hitOf(String runId, int ruleIndex) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        List<RuleHit> rows = jdbcTemplate.query("""
                SELECT rule_index, selector_text, action, group_id,
                       plan_generation, node_id, node_attempt, segment_sequence, model_turn,
                       member_seq, tool_call_id, matched_at, action_settled_at, action_outcome,
                       action_detail
                FROM alphafrog_agent_run_acceptance_fixture_rule_hit
                WHERE run_id = ?
                  AND rule_index = ?
                """, (rs, rowNum) -> mapHit(rs), runId, ruleIndex);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 这条 Run 上每一笔规则记录：终态核对按它算「打中了谁、动作生效了没有」。 */
    public List<RuleHit> hitsOf(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT rule_index, selector_text, action, group_id,
                       plan_generation, node_id, node_attempt, segment_sequence, model_turn,
                       member_seq, tool_call_id, matched_at, action_settled_at, action_outcome,
                       action_detail
                FROM alphafrog_agent_run_acceptance_fixture_rule_hit
                WHERE run_id = ?
                ORDER BY rule_index
                """, (rs, rowNum) -> mapHit(rs), runId);
    }

    /** 这条 Run 用的策略快照；没写过（不是夹具 Run，或夹具没写策略）时为空。 */
    public Optional<PolicySnapshot> policyOf(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        List<PolicySnapshot> rows = jdbcTemplate.query("""
                SELECT policy_digest, rules_json::text AS rules_json
                FROM alphafrog_agent_run_acceptance_fixture_policy
                WHERE run_id = ?
                """, (rs, rowNum) -> new PolicySnapshot(rs.getString("policy_digest"),
                readRuleFacts(rs.getString("rules_json"))), runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static RuleHit mapHit(ResultSet rs) throws SQLException {
        return new RuleHit(
                rs.getInt("rule_index"),
                rs.getString("action"),
                rs.getString("selector_text"),
                rs.getLong("group_id"),
                // 目标身份按列重建：命中行与派发/接收两边凑出来的成员身份用同一个说法，
                // 比对与排查才对得上。
                new AcceptanceReleasePolicy.MemberFacts(
                        rs.getInt("plan_generation"),
                        rs.getString("node_id"),
                        rs.getInt("node_attempt"),
                        rs.getInt("segment_sequence"),
                        rs.getInt("model_turn"),
                        rs.getInt("member_seq"),
                        rs.getString("tool_call_id")),
                rs.getObject("matched_at", OffsetDateTime.class),
                rs.getObject("action_settled_at", OffsetDateTime.class),
                rs.getString("action_outcome"),
                rs.getString("action_detail"));
    }

    private String rulesJson(AcceptanceReleasePolicy policy) {
        try {
            ArrayNode rules = objectMapper.createArrayNode();
            for (AcceptanceReleasePolicy.Rule rule : policy.rules()) {
                ObjectNode one = rules.addObject();
                one.put("index", rule.index());
                one.put("action", rule.action());
                one.put("selector", rule.selectorText());
            }
            return objectMapper.writeValueAsString(rules);
        } catch (Exception broken) {
            throw new IllegalStateException("放行策略的规则写不成 JSON：" + broken.getMessage(), broken);
        }
    }

    private List<RuleFact> readRuleFacts(String rulesJson) throws SQLException {
        try {
            JsonNode rules = objectMapper.readTree(rulesJson);
            List<RuleFact> facts = new ArrayList<>();
            for (JsonNode rule : rules) {
                facts.add(new RuleFact(rule.path("index").asInt(), rule.path("action").asText(""),
                        rule.path("selector").asText("")));
            }
            return List.copyOf(facts);
        } catch (Exception broken) {
            throw new SQLException("放行策略快照读不回来：" + broken.getMessage(), broken);
        }
    }

    /** 策略快照：摘要加上每条规则的点名写法。 */
    public record PolicySnapshot(String digest, List<RuleFact> rules) {
    }

    /**
     * 一条规则的写法：核对结论里点名「这条规则一次都没打中」时写的就是它。
     *
     * @param index        规则在策略里的序号
     * @param action       这条规则的动作
     * @param selectorText 选择器的规范写法
     */
    public record RuleFact(int index, String action, String selectorText) {

        public String describe() {
            return "第 " + index + " 条规则（" + action + "，选择器 " + selectorText + "）";
        }
    }

    /**
     * 一条规则在库里的样子：打中了哪一条成员，被点名的动作有没有落到它身上。
     *
     * @param ruleIndex       规则序号
     * @param action          动作
     * @param selectorText    选择器的规范写法
     * @param groupId         命中所在的等待组
     * @param target          命中的那一条成员
     * @param matchedAt       打中目标的时刻
     * @param actionSettledAt 动作结果写下来的时刻；还没落地时为空
     * @param actionOutcome   动作结果；还没落地时为空
     * @param actionDetail    动作结果的补充说明
     */
    public record RuleHit(int ruleIndex,
                          String action,
                          String selectorText,
                          long groupId,
                          AcceptanceReleasePolicy.MemberFacts target,
                          OffsetDateTime matchedAt,
                          OffsetDateTime actionSettledAt,
                          String actionOutcome,
                          String actionDetail) {

        /** 动作真的落到点名的成员身上了没有。 */
        public boolean applied() {
            return actionOutcome != null && APPLIED_OUTCOMES.contains(actionOutcome);
        }

        /** 打中了成员、动作的结果也定下来了（生效或明确没有生效）。 */
        public boolean settled() {
            return actionOutcome != null;
        }

        public String describe() {
            return target.describe() + "（动作结果 "
                    + (actionOutcome == null ? "还没落地" : actionOutcome) + "）";
        }
    }

    /** 规则序号到记录：终态核对按它算。 */
    public Map<Integer, RuleHit> hitsByRule(String runId) {
        Map<Integer, RuleHit> byRule = new LinkedHashMap<>();
        for (RuleHit hit : hitsOf(runId)) {
            byRule.put(hit.ruleIndex(), hit);
        }
        return byRule;
    }
}
