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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 认领表：哪一次模型调用拿走了哪一个脚本回合。
 *
 * <p>这张表是夹具唯一的消费位置。以前位置在进程内存里，进程一重启就没了，已经规划过的 Run
 * 只能按失败处理；现在位置落库，而且是按「调用身份」落的：同一次调用（同一条 Run、同一个节点、
 * 同一段、同一模型回合）重启后重做时报同一个身份，拿回同一个回合；新的调用身份才会去领下一个
 * 声明回合。两件事因此同时成立：重启能接着跑，同一条 Run 上两个节点并行也各拿各的。</p>
 *
 * <p>认领靠数据库约束定胜负，不靠进程内加锁：一张表上两个唯一键，{@code (run_id, call_identity)}
 * 保证一次调用只领一个回合，{@code (run_id, turn_index)} 保证一个回合只被一次调用领走。两个线程
 * 同时抢同一个回合时，只有一条 INSERT 会成功，另一条要么认出自己的身份已经被别人写过（重放，
 * 拿同一个回合），要么换下一个声明回合再试。所以「谁先谁后」不影响结果，只影响重试次数。</p>
 *
 * <p>内容摘要在认领时一起核对：这条 Run 第一次领回合时把当时那份脚本的摘要写进认领行，之后每一次
 * 重放都要求当前脚本的摘要一致。夹具行被原位改过时会当场拒绝，而不是照着新内容跑出一次说不清是
 * 哪一版场景的验收。</p>
 */
@Component
@Slf4j
public class FixtureCallStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FixtureRuleHitStore ruleHitStore;

    public FixtureCallStore(JdbcTemplate jdbcTemplate,
                            ObjectMapper objectMapper,
                            FixtureRuleHitStore ruleHitStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ruleHitStore = ruleHitStore;
    }

    /**
     * 认领一次调用的回合。
     *
     * @param runId     正在跑的那条 Run
     * @param fixtureId 这条 Run 用的夹具编号（进证据）
     * @param scenarioId 场景编号（进证据）
     * @param script    读回来的脚本
     * @param identity  这次调用的身份
     * @return 这次调用该用哪个回合，以及它是新领的还是重放回来的
     * @throws AcceptanceFixtureExecutionException 身份认不出来、脚本内容变过，或者没有可领的声明回合
     */
    public Claim claim(String runId,
                       String fixtureId,
                       String scenarioId,
                       FrozenModelScript script,
                       FixtureCallIdentity identity) {
        if (runId == null || runId.isBlank() || fixtureId == null || script == null || identity == null) {
            throw refuse("acceptance_fixture_call_identity_missing",
                    "认领模型回合时缺了 Run、夹具或调用身份，认不出这是哪一次调用");
        }
        Optional<Claim> existing = findClaim(runId, identity.describe());
        if (existing.isPresent()) {
            return replayed(runId, script, existing.get());
        }
        // 第一次为这条 Run 认领时把场景声明写下来：夹具行之后过期或被删，验收结论也还能核对。
        recordScenario(runId, fixtureId, scenarioId, script);
        List<FrozenModelScript.TurnDeclaration> candidates = script.candidatesFor(identity);
        if (candidates.isEmpty()) {
            throw refuse("acceptance_fixture_no_declared_turn",
                    "夹具 " + fixtureId + "（场景 " + scenarioId + "）里没有任何回合声明回答这一次调用："
                            + "调用身份 " + identity.describe() + "，脚本声明的回合是 "
                            + script.declarationSummary());
        }
        for (FrozenModelScript.TurnDeclaration candidate : candidates) {
            int inserted = insertClaim(runId, fixtureId, scenarioId, script, identity, candidate);
            if (inserted == 1) {
                log.info("验收夹具按调用身份发出一个回合: runId={} fixture={} 身份={} 回合={} 声明={}",
                        runId, fixtureId, identity.describe(), candidate.turnIndex(), candidate.describe());
                return new Claim(candidate.turnIndex(), identity.describe(), candidate.describe(),
                        candidate.optional(), script.digest(), OffsetDateTime.now());
            }
            // 没插进去有两种可能：这次调用的身份已经被写过了（并发重放），或者这个回合被别的调用抢走了。
            Optional<Claim> concurrent = findClaim(runId, identity.describe());
            if (concurrent.isPresent()) {
                return replayed(runId, script, concurrent.get());
            }
        }
        throw refuse("acceptance_fixture_declared_turns_used_up",
                "夹具 " + fixtureId + "（场景 " + scenarioId + "）里声明回答这一次调用的回合"
                        + "都已经被别的调用领走了：调用身份 " + identity.describe() + "，可以领的回合是 "
                        + candidates.stream().map(FrozenModelScript.TurnDeclaration::describe).toList());
    }

    /**
     * 把这条 Run 用的场景声明写下来。
     *
     * <p>第一次认领时写，写在夹具行还在的时候：之后夹具过期、被停用或被删掉，都不影响事后核对
     * 「这次验收要求哪些回复发生」。同一条 Run 只写一次；已经写过时要求摘要一致——内容被改过
     * 时这里也会拒绝，而不是让一次验收悄悄换一版场景。</p>
     *
     * <p>两个进程同时第一次认领、各自读到不同内容时，插入只有一个会成；没插进去的那个要把赢家
     * 读回来比对摘要，对不上就当场拒绝。不读回的话，两边会各自按自己读到的那一版往下跑，
     * 而库里只留下其中一份声明，事后谁也说不清这次执行对应哪一版。</p>
     */
    public void recordScenario(String runId, String fixtureId, String scenarioId, FrozenModelScript script) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO alphafrog_agent_run_acceptance_fixture_scenario
                    (run_id, fixture_id, scenario_id, script_digest, script_size, declarations_json,
                     required_turn_count, optional_turn_count)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT DO NOTHING
                """, runId, fixtureId, scenarioId, script.digest(), script.size(),
                declarationsJson(script), requiredTurns(script), optionalTurns(script));
        String recorded = recordedScriptDigest(runId);
        if (recorded == null) {
            throw refuse("acceptance_fixture_content_changed",
                    "这条 Run 的场景声明没写进去，也读不回来：这一次验收说不清用的是哪一版脚本");
        }
        if (!script.digest().equals(recorded)) {
            throw refuse("acceptance_fixture_content_changed",
                    "这条 Run 一开始用的是脚本摘要 " + recorded + "，现在读回来的是 " + script.digest()
                            + "：同一条 Run 跑的过程中夹具内容被改过，这一次验收说不清用的是哪一版");
        }
        if (inserted == 0) {
            log.info("这条 Run 的场景声明已经写过了，读回赢家核对摘要一致: runId={} 摘要={}", runId, recorded);
        }
    }

    /** 这条 Run 的场景快照里冻结的脚本摘要；还没写过时为空。 */
    public Optional<String> frozenScriptDigest(String runId) {
        return Optional.ofNullable(recordedScriptDigest(runId));
    }

    private String recordedScriptDigest(String runId) {
        List<String> recorded = jdbcTemplate.query("""
                SELECT script_digest
                FROM alphafrog_agent_run_acceptance_fixture_scenario
                WHERE run_id = ?
                """, (rs, rowNum) -> rs.getString("script_digest"), runId);
        return recorded.isEmpty() ? null : recorded.get(0);
    }

    private static int requiredTurns(FrozenModelScript script) {
        int required = 0;
        for (int turnIndex = 0; turnIndex < script.size(); turnIndex++) {
            if (!script.declarationAt(turnIndex).optional()) {
                required++;
            }
        }
        return required;
    }

    private static int optionalTurns(FrozenModelScript script) {
        return script.size() - requiredTurns(script);
    }

    /**
     * Run 走到终态那一刻核对两件事：必答回合是不是都真的被领走过，点名的规则是不是都真的打中过成员。
     *
     * <p>少了回合时，前面那几条回复照样能让 Run 走到终态，脚本尾部没用上的部分会被静默丢掉；
     * 规则点错了字段名或序号时一声不响，一条成员都打不中。两种情况下那次验收看起来都像跑完了。
     * 这个结论落在库里，验收执行器与排查都能按 Run 直接读，不必自己拼脚本、策略与执行记录。</p>
     *
     * @return 核对结论；这条 Run 不是夹具 Run（没有场景快照）时返回空
     */
    public Optional<Map<String, Object>> recordVerdict(String runId) {
        List<Map<String, Object>> scenarios = jdbcTemplate.query("""
                SELECT fixture_id, scenario_id, script_digest, script_size, declarations_json::text AS declarations_json
                FROM alphafrog_agent_run_acceptance_fixture_scenario
                WHERE run_id = ?
                """, (rs, rowNum) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("fixtureId", rs.getString("fixture_id"));
            one.put("scenarioId", rs.getString("scenario_id"));
            one.put("scriptDigest", rs.getString("script_digest"));
            one.put("scriptSize", rs.getInt("script_size"));
            one.put("declarationsJson", rs.getString("declarations_json"));
            return one;
        }, runId);
        if (scenarios.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> scenario = scenarios.get(0);
        List<Claim> claims = callsOf(runId);
        Set<Integer> claimedTurns = new LinkedHashSet<>();
        for (Claim claim : claims) {
            claimedTurns.add(claim.turnIndex());
        }
        Optional<FixtureRuleHitStore.PolicySnapshot> policy = ruleHitStore.policyOf(runId);
        List<FixtureRuleHitStore.RuleFact> ruleFacts = policy.map(FixtureRuleHitStore.PolicySnapshot::rules)
                .orElse(List.of());
        List<FixtureRuleHitStore.RuleHit> ruleHits = ruleHitStore.hitsOf(runId);
        FixtureScenarioVerdict verdict;
        try {
            // 快照里存的是「这次要求发生哪些调用、点名哪些调用」；核对是对着它算，不再去读夹具行
            //（可能已经回收）。
            verdict = FixtureScenarioVerdict.evaluate(declarationsOf(scenario), ruleFacts, claimedTurns, ruleHits);
        } catch (Exception broken) {
            log.error("夹具场景声明的快照读不出来，这条 Run 的必答回合没法核对: runId={} reason={}",
                    runId, broken.getMessage());
            return Optional.empty();
        }
        long appliedRules = ruleHits.stream().filter(FixtureRuleHitStore.RuleHit::applied).count();
        String ruleDetail = joinRuleGaps(verdict);
        String detail = verdict.describeMissing();
        jdbcTemplate.update("""
                UPDATE alphafrog_agent_run_acceptance_fixture_scenario
                SET verdict = ?,
                    claimed_turn_count = ?,
                    required_missing_count = ?,
                    detail = ?,
                    policy_rule_count = ?,
                    policy_hit_rule_count = ?,
                    policy_missing_count = ?,
                    policy_unapplied_count = ?,
                    policy_detail = ?,
                    recorded_at = CURRENT_TIMESTAMP
                WHERE run_id = ?
                """, verdict.verdict(), claims.size(), verdict.missing().size(), detail,
                policy.map(FixtureRuleHitStore.PolicySnapshot::rules).map(List::size).orElse(null),
                appliedRules,
                policy.isEmpty() ? null : verdict.missingRules().size(),
                policy.isEmpty() ? null : verdict.unappliedRules().size(),
                ruleDetail,
                runId);
        if (!verdict.missing().isEmpty()) {
            log.error("验收夹具脚本有必答回合没有发生，这次验收不能算通过: runId={} fixture={} 没发生的回合={}",
                    runId, scenario.get("fixtureId"), verdict.missing());
        }
        if (!verdict.missingRules().isEmpty()) {
            log.error("验收夹具的放行策略有规则一次都没打中成员，这次验收不能算通过: runId={} fixture={} 没打中的规则={}",
                    runId, scenario.get("fixtureId"), verdict.missingRules());
        }
        if (!verdict.unappliedRules().isEmpty()) {
            log.error("验收夹具的放行策略有规则打中了成员但动作没有生效，这次验收不能算通过: "
                            + "runId={} fixture={} 动作没生效的规则={}",
                    runId, scenario.get("fixtureId"), verdict.unappliedRules());
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", runId);
        snapshot.put("fixtureId", scenario.get("fixtureId"));
        snapshot.put("scenarioId", scenario.get("scenarioId"));
        snapshot.put("scriptDigest", scenario.get("scriptDigest"));
        snapshot.put("scriptSize", scenario.get("scriptSize"));
        snapshot.put("verdict", verdict.verdict());
        snapshot.put("claimedTurnCount", claims.size());
        snapshot.put("requiredMissingCount", verdict.missing().size());
        snapshot.put("missingRequiredDeclarations", verdict.missing());
        snapshot.put("consumedDeclarations", verdict.consumed());
        snapshot.put("policyDigest", policy.map(FixtureRuleHitStore.PolicySnapshot::digest).orElse(null));
        snapshot.put("policyRuleCount", policy.map(FixtureRuleHitStore.PolicySnapshot::rules)
                .map(List::size).orElse(null));
        snapshot.put("policyHitRuleCount", appliedRules);
        snapshot.put("policyMissingRules", verdict.missingRules());
        snapshot.put("policyUnappliedRules", verdict.unappliedRules());
        snapshot.put("ruleHits", ruleHits.stream().map(FixtureRuleHitStore.RuleHit::describe).toList());
        return Optional.of(snapshot);
    }

    /** 两路缺口写成一行：没打中的、动作没生效的分开写，排查的人一眼看得出是哪一类。 */
    private static String joinRuleGaps(FixtureScenarioVerdict verdict) {
        List<String> parts = new ArrayList<>();
        if (!verdict.missingRules().isEmpty()) {
            parts.add("一次都没打中任何成员：" + String.join("；", verdict.missingRules()));
        }
        if (!verdict.unappliedRules().isEmpty()) {
            parts.add("打中了成员但动作没有生效：" + String.join("；", verdict.unappliedRules()));
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    private String declarationsJson(FrozenModelScript script) {
        try {
            ArrayNode declarations = objectMapper.createArrayNode();
            for (FrozenModelScript.TurnDeclaration declaration : script.declarations()) {
                ObjectNode one = declarations.addObject();
                one.put("turn", declaration.turnIndex());
                one.put("stage", declaration.stage());
                one.put("optional", declaration.optional());
                ObjectNode scope = one.putObject("scope");
                declaration.scope().keySet().stream().sorted()
                        .forEach(key -> scope.put(key, declaration.scope().get(key)));
            }
            return objectMapper.writeValueAsString(declarations);
        } catch (Exception broken) {
            throw new IllegalStateException("夹具场景声明写不成 JSON：" + broken.getMessage(), broken);
        }
    }

    /** 把落库的声明快照读回成声明清单：核对用的是它，不是现在那份夹具内容。 */
    private List<FrozenModelScript.TurnDeclaration> declarationsOf(Map<String, Object> scenario) throws Exception {
        JsonNode declarations = objectMapper.readTree(String.valueOf(scenario.get("declarationsJson")));
        List<FrozenModelScript.TurnDeclaration> rebuilt = new ArrayList<>();
        for (JsonNode declaration : declarations) {
            Map<String, String> scope = new LinkedHashMap<>();
            declaration.path("scope").fields().forEachRemaining(entry -> scope.put(entry.getKey(), entry.getValue().asText()));
            rebuilt.add(new FrozenModelScript.TurnDeclaration(
                    declaration.path("turn").asInt(),
                    declaration.path("stage").asText(),
                    Map.copyOf(scope),
                    declaration.path("optional").asBoolean(false)));
        }
        return List.copyOf(rebuilt);
    }

    /** 这条 Run 已经领走的回合，按回合序号排：验收证据里「哪些回复真的被用掉了」直接读它。 */
    public List<Claim> callsOf(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT turn_index, call_identity, declared_for, optional_turn, claimed_at
                FROM alphafrog_agent_run_acceptance_fixture_call
                WHERE run_id = ?
                ORDER BY turn_index
                """, (rs, rowNum) -> mapClaim(rs, false), runId);
    }

    /** 这条 Run 领走的回合里，脚本声明的必答回合有哪些已经用掉：终态核对与验收结论用。 */
    public Map<String, Object> consumptionOf(String runId, FrozenModelScript script) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Claim> claims = callsOf(runId);
        Map<Integer, String> claimedByTurn = new LinkedHashMap<>();
        for (Claim claim : claims) {
            claimedByTurn.put(claim.turnIndex(), claim.callIdentity());
        }
        FixtureScenarioVerdict verdict = FixtureScenarioVerdict.evaluate(script, claimedByTurn.keySet());
        snapshot.put("scriptSize", script.size());
        snapshot.put("scriptDigest", script.digest());
        snapshot.put("claimedTurnCount", claims.size());
        snapshot.put("consumedDeclarations", verdict.consumed());
        snapshot.put("verdict", verdict.verdict());
        snapshot.put("missingRequiredDeclarations", verdict.missing());
        snapshot.put("claims", claims.stream()
                .map(claim -> claim.turnIndex() + "←" + claim.callIdentity())
                .toList());
        return snapshot;
    }

    private Claim replayed(String runId, FrozenModelScript script, Claim stored) {
        if (!script.digest().equals(stored.scriptDigest())) {
            throw refuse("acceptance_fixture_content_changed",
                    "这条 Run 一开始用的是脚本摘要 " + stored.scriptDigest() + "，现在读回来的夹具脚本摘要是 "
                            + script.digest() + "：同一条 Run 跑的过程中夹具内容被改过，这一次验收说不清用的是哪一版，"
                            + "只能按失败处理");
        }
        log.info("验收夹具按调用身份重放同一个回合: runId={} 身份={} 回合={}", runId, stored.callIdentity(),
                stored.turnIndex());
        return new Claim(stored.turnIndex(), stored.callIdentity(), stored.declaredFor(),
                stored.optional(), stored.scriptDigest(), stored.claimedAt());
    }

    private int insertClaim(String runId,
                            String fixtureId,
                            String scenarioId,
                            FrozenModelScript script,
                            FixtureCallIdentity identity,
                            FrozenModelScript.TurnDeclaration declaration) {
        return jdbcTemplate.update("""
                INSERT INTO alphafrog_agent_run_acceptance_fixture_call
                    (run_id, fixture_id, scenario_id, call_identity, call_stage, turn_index,
                     script_digest, declared_for, optional_turn)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, runId, fixtureId, scenarioId, identity.describe(), identity.stage().name(),
                declaration.turnIndex(), script.digest(), declaration.describe(), declaration.optional());
    }

    private Optional<Claim> findClaim(String runId, String callIdentity) {
        List<Claim> rows = jdbcTemplate.query("""
                SELECT turn_index, call_identity, declared_for, optional_turn, script_digest, claimed_at
                FROM alphafrog_agent_run_acceptance_fixture_call
                WHERE run_id = ?
                  AND call_identity = ?
                """, (rs, rowNum) -> mapClaim(rs, true), runId, callIdentity);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Claim mapClaim(ResultSet rs, boolean withDigest) throws SQLException {
        return new Claim(
                rs.getInt("turn_index"),
                rs.getString("call_identity"),
                rs.getString("declared_for"),
                rs.getBoolean("optional_turn"),
                withDigest ? rs.getString("script_digest") : null,
                rs.getObject("claimed_at", OffsetDateTime.class));
    }

    /**
     * 一次认领的结果。
     *
     * @param turnIndex    用脚本里的第几个回合（从 0 起）
     * @param callIdentity 认领它的调用身份
     * @param declaredFor  命中的那条声明的原文
     * @param optional     这条声明是不是「可以不发生」
     * @param scriptDigest 认领时那份脚本的摘要（按 Run 列全部认领行时为 null，那一列不是每次都要读）
     * @param claimedAt    什么时候领走的
     */
    public record Claim(int turnIndex,
                        String callIdentity,
                        String declaredFor,
                        boolean optional,
                        String scriptDigest,
                        OffsetDateTime claimedAt) { }
}
