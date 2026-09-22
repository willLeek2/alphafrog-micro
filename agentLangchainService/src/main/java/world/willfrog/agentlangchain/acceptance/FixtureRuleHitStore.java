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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 放行策略的规则命中表：哪一条规则真的打中了哪一条成员。
 *
 * <p>规则只写「点名某一类调用」的时候，写错一处（节点名打错、分段号写错、成员序号写反）不会报错，
 * 只会静静地谁也不打中：那次验收看起来跑完了，被压住、被放行的对象却根本不存在。所以每条规则命中
 * 一条成员就在这张表上留一行，跑到终态时按它核对「这次点名要求的那几件事是不是真的都发生了」，
 * 没打中的规则在结论里点名，不靠人去比对策略与执行记录。</p>
 *
 * <p>同一份策略里每条规则的序号（从 0 起）就是它的身份：命中行按「Run + 规则序号 + 等待组 + 组内
 * 序号」唯一。派发前与结果接收方两处都会记，重复记同一件事只留一行。</p>
 *
 * <p>策略原文的摘要与全部规则也在第一次读到它的时候留一份快照：之后夹具行被改过、被停用或被删掉，
 * 核对结论仍然说得清「这次验收要求点名哪些调用」。</p>
 */
@Component
@Slf4j
public class FixtureRuleHitStore {

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
     * 内容被原位改过时当场拒绝，而不是让一次验收悄悄换一版策略。</p>
     */
    public void snapshotPolicy(String runId,
                               String fixtureId,
                               String scenarioId,
                               AcceptanceReleasePolicy policy) {
        if (runId == null || runId.isBlank() || policy == null) {
            return;
        }
        List<String> recorded = jdbcTemplate.query("""
                SELECT policy_digest
                FROM alphafrog_agent_run_acceptance_fixture_policy
                WHERE run_id = ?
                """, (rs, rowNum) -> rs.getString("policy_digest"), runId);
        if (!recorded.isEmpty()) {
            String stored = recorded.get(0);
            if (stored != null && !stored.equals(policy.digest())) {
                throw refuse("acceptance_fixture_content_changed",
                        "这条 Run 一开始用的是策略摘要 " + stored + "，现在读回来的是 " + policy.digest()
                                + "：同一条 Run 跑的过程中夹具的放行策略被改过，这一次验收说不清用的是哪一版");
            }
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO alphafrog_agent_run_acceptance_fixture_policy
                    (run_id, fixture_id, scenario_id, policy_digest, rules_json, rule_count)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT DO NOTHING
                """, runId, fixtureId, scenarioId, policy.digest(), rulesJson(policy), policy.ruleCount());
    }

    /**
     * 记一笔命中：第几条规则打中了哪个等待组里的哪一条成员。
     *
     * <p>重复记同一件事（派发前记过、结果接收方又记一次）只留一行，不报错也不覆盖。</p>
     */
    public void record(String runId,
                       AcceptanceReleasePolicy.Rule rule,
                       long groupId,
                       int memberSeq) {
        if (runId == null || runId.isBlank() || rule == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO alphafrog_agent_run_acceptance_fixture_rule_hit
                    (run_id, rule_index, selector_text, action, group_id, member_seq)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, runId, rule.index(), rule.selectorText(), rule.action(), groupId, memberSeq);
    }

    /** 这条 Run 上真的打中过成员的规则序号。 */
    public Set<Integer> hitRuleIndexes(String runId) {
        if (runId == null || runId.isBlank()) {
            return Set.of();
        }
        List<Integer> hits = jdbcTemplate.query("""
                SELECT DISTINCT rule_index
                FROM alphafrog_agent_run_acceptance_fixture_rule_hit
                WHERE run_id = ?
                ORDER BY rule_index
                """, (rs, rowNum) -> rs.getInt("rule_index"), runId);
        return new LinkedHashSet<>(hits);
    }

    /** 这条 Run 上每一次命中的明细：验收证据里「哪条规则打中了谁」直接读它。 */
    public List<Map<String, Object>> hitsOf(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT rule_index, selector_text, action, group_id, member_seq, matched_at
                FROM alphafrog_agent_run_acceptance_fixture_rule_hit
                WHERE run_id = ?
                ORDER BY rule_index, group_id, member_seq
                """, (rs, rowNum) -> {
            Map<String, Object> one = new java.util.LinkedHashMap<>();
            one.put("ruleIndex", rs.getInt("rule_index"));
            one.put("selector", rs.getString("selector_text"));
            one.put("action", rs.getString("action"));
            one.put("groupId", rs.getLong("group_id"));
            one.put("memberSeq", rs.getInt("member_seq"));
            one.put("matchedAt", rs.getObject("matched_at", OffsetDateTime.class));
            return one;
        }, runId);
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
}
