package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命中记录这一张表的写法：一条规则在整条 Run 里只绑一个目标，动作结果要单独写一次，
 * 策略快照写完要读回赢家比对。
 *
 * <p>这里的库是替身，验的是「写什么、读回来什么、不一致时怎么拒」，不是 SQL 在真库上的行为；
 * 真库上验的是迁移 017 的表结构与唯一约束。</p>
 */
class FixtureRuleHitStoreTest {

    private static final String RUN = "run-1";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final FixtureRuleHitStore store =
            new FixtureRuleHitStore(jdbcTemplate, new ObjectMapper());

    private static final AcceptanceReleasePolicy.MemberFacts MEMBER_A =
            new AcceptanceReleasePolicy.MemberFacts(7, "n1", 1, 0, 0, 0, "call-a");
    private static final AcceptanceReleasePolicy.MemberFacts MEMBER_B =
            new AcceptanceReleasePolicy.MemberFacts(7, "n1", 1, 0, 0, 1, "call-b");

    /** 反复记同一件事（派发前记过、结果接收方又记一次）只留一行，不报错也不覆盖。 */
    @Test
    void theSameTargetRecordedTwiceIsNotAConflict() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(0);
        hitRowBacks(MEMBER_A, null);

        store.recordMatch(RUN, rule(0), 7L, MEMBER_A);
        store.recordMatch(RUN, rule(0), 7L, MEMBER_A);
    }

    /**
     * 同一个规则序号落到另一个目标上：当场拒绝。
     *
     * <p>两批调用撞上同一个点名时，压住、等待或判失败的是谁就说不清了；选择器写全之后本不该发生，
     * 所以这里不许悄悄多记一行、也不许把先记下来的那一条改掉。</p>
     */
    @Test
    void aRuleLandingOnAnotherTargetIsRefused() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(0);
        hitRowBacks(MEMBER_A, null);

        assertThatThrownBy(() -> store.recordMatch(RUN, rule(0), 7L, MEMBER_B))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_policy_target_conflict"))
                .hasMessageContaining("只该绑定一个目标")
                .hasMessageContaining(MEMBER_A.describe())
                .hasMessageContaining(MEMBER_B.describe());
    }

    /** 命中那一行没写进去（也读不回来）时同样拒绝：这一次验收说不清动作落到了谁身上。 */
    @Test
    void aMatchThatCannotBeWrittenBackIsRefused() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> store.recordMatch(RUN, rule(0), 7L, MEMBER_A))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_policy_target_conflict"))
                .hasMessageContaining("读不回来");
    }

    /** 目标身份按列读回来，与派发、接收两边凑出来的成员身份用同一个说法。 */
    @Test
    void theTargetComesBackFromTheIdentityColumns() {
        hitRowBacks(MEMBER_A, null);

        FixtureRuleHitStore.RuleHit hit = store.hitOf(RUN, 0).orElseThrow();

        assertThat(hit.target()).isEqualTo(MEMBER_A);
        assertThat(hit.applied()).isFalse();
        assertThat(hit.settled()).isFalse();
    }

    /** 第一次写策略快照：写进去的摘要与规则条数要能原样读回来。 */
    @Test
    void theFirstSnapshotIsWrittenAndReadBack() {
        AcceptanceReleasePolicy policy = policy();
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(policy.digest()));

        store.snapshotPolicy(RUN, "fx-1", "scenario-a", policy);
    }

    /**
     * 读回来的赢家是另一版：拒绝。
     *
     * <p>两个进程同时第一次写时，库里只留其中一个版本的摘要；不读回比对的话，两边会各自按自己
     * 读到的那一版往下跑，而记下来的内容与执行记录对不上。</p>
     */
    @Test
    void aSnapshotThatComesBackWithAnotherDigestIsRefused() {
        AcceptanceReleasePolicy policy = policy();
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of("b".repeat(64)));

        assertThatThrownBy(() -> store.snapshotPolicy(RUN, "fx-1", "scenario-a", policy))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("b".repeat(64))
                .hasMessageContaining(policy.digest());
    }

    /** 策略快照没写进去也读不回来：拒绝，而不是让这一次验收悄悄换一版。 */
    @Test
    void aSnapshotThatCannotBeReadBackIsRefused() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> store.snapshotPolicy(RUN, "fx-1", "scenario-a", policy()))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("读不回来");
    }

    /**
     * 「这条 Run 一开始就没有策略」也冻结成一个明确的值：与真有策略时一样，写完读回比对。
     *
     * <p>不冻结的话漏掉一种改法：夹具一开始没写策略，跑到一半被改成带策略——前半段的成员结果当场
     * 收尾，后半段却按新策略压住或判失败。</p>
     */
    @Test
    void theAbsentPolicyIsFrozenAsItsOwnValue() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(FixtureRuleHitStore.ABSENT_POLICY_DIGEST));

        store.snapshotPolicyAbsent(RUN, "fx-1", "scenario-a");
    }

    /** 这一次读到空、库里已经冻结了一份真策略（并发首次读取输了）：拒绝，不把「读不到」当「没有」。 */
    @Test
    void anAbsentPolicyThatLosesToAFrozenRealPolicyIsRefused() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of("c".repeat(64)));

        assertThatThrownBy(() -> store.snapshotPolicyAbsent(RUN, "fx-1", "scenario-a"))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("没有放行策略")
                .hasMessageContaining("c".repeat(64));
    }

    /** 反过来：库里冻结的是「没有策略」，这一次读到的是真策略（并发首次读取输了）：同样拒绝。 */
    @Test
    void aRealPolicyThatLosesToAFrozenAbsentPolicyIsRefused() {
        AcceptanceReleasePolicy policy = policy();
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(FixtureRuleHitStore.ABSENT_POLICY_DIGEST));

        assertThatThrownBy(() -> store.snapshotPolicy(RUN, "fx-1", "scenario-a", policy))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("没有放行策略")
                .hasMessageContaining(policy.digest());
    }

    /** 动作结果已经写过一次时，第二次上报不算数：三种生效结果各写一次，不许被改掉。 */
    @Test
    void aSecondActionReportIsNotCounted() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(0);
        hitRowBacks(MEMBER_A, FixtureRuleHitStore.APPLIED_HOLD);

        assertThat(store.recordAction(RUN, 0, FixtureRuleHitStore.APPLIED_PEER, "又来一次"))
                .isFalse();
    }

    /** 结果写进去了：算一次。 */
    @Test
    void anActionReportThatLandsIsCounted() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        assertThat(store.recordAction(RUN, 0, FixtureRuleHitStore.APPLIED_HOLD, "压住")).isTrue();
    }

    /** 传进来的是空 Run 或空身份时不写库：这条 Run 不是夹具 Run。 */
    @Test
    void nothingIsWrittenWithoutARunOrATarget() {
        store.recordMatch(null, rule(0), 7L, MEMBER_A);
        store.recordMatch(RUN, rule(0), 7L, null);
        assertThat(store.hitOf(null, 0)).isEmpty();
        assertThat(store.hitsOf("  ")).isEmpty();
        assertThat(store.policyOf(null)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void hitRowBacks(AcceptanceReleasePolicy.MemberFacts target, String outcome) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(hitRow(target, outcome), 0));
                });
    }

    private static ResultSet hitRow(AcceptanceReleasePolicy.MemberFacts target, String outcome)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("rule_index")).thenReturn(0);
        when(rs.getString("action")).thenReturn("holdUntilPoint");
        when(rs.getString("selector_text")).thenReturn(target.describe());
        when(rs.getLong("group_id")).thenReturn(7L);
        when(rs.getInt("plan_generation")).thenReturn(target.planGeneration());
        when(rs.getString("node_id")).thenReturn(target.nodeId());
        when(rs.getInt("node_attempt")).thenReturn(target.nodeAttempt());
        when(rs.getInt("segment_sequence")).thenReturn(target.segmentSequence());
        when(rs.getInt("model_turn")).thenReturn(target.modelTurn());
        when(rs.getInt("member_seq")).thenReturn(target.memberSeq());
        when(rs.getString("tool_call_id")).thenReturn(target.toolCallId());
        when(rs.getObject("matched_at", OffsetDateTime.class)).thenReturn(OffsetDateTime.now());
        when(rs.getObject("action_settled_at", OffsetDateTime.class))
                .thenReturn(outcome == null ? null : OffsetDateTime.now());
        when(rs.getString("action_outcome")).thenReturn(outcome);
        return rs;
    }

    private static AcceptanceReleasePolicy.Rule rule(int index) {
        return policy().rules().get(index);
    }

    private static AcceptanceReleasePolicy policy() {
        return AcceptanceReleasePolicy.parse("fx-1", """
                {"rules":[{"for":{"planGeneration":7,"nodeId":"n1","nodeAttempt":1,"segmentSequence":0,"modelTurn":0,"memberSeq":0},"holdUntilPoint":"point-a"}]}
                """, new ObjectMapper()).orElseThrow();
    }
}
