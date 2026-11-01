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
