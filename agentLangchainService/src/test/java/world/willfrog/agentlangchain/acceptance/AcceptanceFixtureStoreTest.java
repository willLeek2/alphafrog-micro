package world.willfrog.agentlangchain.acceptance;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 夹具读取与使用登记：列名、类型与「只有一行被改到才算用上」这三件事。
 *
 * <p>这里的库是替身，验的是映射关系（列名写错、类型取错会当场露馅），不是 SQL 在真库上的行为；
 * 真库上验的是迁移 014 的表结构与约束。</p>
 */
class AcceptanceFixtureStoreTest {

    private static final String LANE = "lane-beta";
    private static final String GENERATION = "gen-1";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AcceptanceFixtureStore store = new AcceptanceFixtureStore(jdbcTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void findMapsEveryColumnOfTheFixtureRow() throws Exception {
        OffsetDateTime enabledAt = OffsetDateTime.now().minusMinutes(5);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(2);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("fixture_id")).thenReturn("fx-1");
        when(rs.getString("scenario_id")).thenReturn("scenario-a");
        when(rs.getBoolean("enabled")).thenReturn(true);
        when(rs.getObject("enabled_at", OffsetDateTime.class)).thenReturn(enabledAt);
        when(rs.getObject("expires_at", OffsetDateTime.class)).thenReturn(expiresAt);
        when(rs.getString("plan_json")).thenReturn("{\"executionMode\":\"DAG\"}");
        when(rs.getString("model_script_json")).thenReturn("[]");
        when(rs.getString("dispatch_policy_json")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        Optional<AcceptanceFixtureRow> found = store.find(LANE, GENERATION, "fx-1");

        assertThat(found).isPresent();
        AcceptanceFixtureRow row = found.orElseThrow();
        assertThat(row.fixtureId()).isEqualTo("fx-1");
        assertThat(row.scenarioId()).isEqualTo("scenario-a");
        assertThat(row.enabled()).isTrue();
        assertThat(row.enabledAt()).isEqualTo(enabledAt);
        assertThat(row.expiresAt()).isEqualTo(expiresAt);
        assertThat(row.planJson()).isEqualTo("{\"executionMode\":\"DAG\"}");
        assertThat(row.modelScriptJson()).isEqualTo("[]");
        assertThat(row.dispatchPolicyJson()).isNull();
        // 三份内容列在库里是 JSONB，读取时要按 JSON 取文本，否则取回来的不是可解析的内容。
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbcTemplate)
                .query(sql.capture(), any(RowMapper.class), any(), any(), any());
        assertThat(sql.getValue()).contains("alphafrog_agent_run_acceptance_fixture")
                .contains("plan_json::text")
                .contains("model_script_json::text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findReturnsNothingWhenNoRowMatches() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(store.find(LANE, GENERATION, "fx-1")).isEmpty();
    }

    @Test
    void recordUseOnlySucceedsWhenExactlyOneRowWasUpdated() {
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);
        assertThat(store.recordUse(LANE, GENERATION, "fx-1")).isTrue();

        // 一行都没改到：夹具在这中间被停用或过期了，调用方必须当成不可用。
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);
        assertThat(store.recordUse(LANE, GENERATION, "fx-1")).isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .update(sql.capture(), any(), any(), any());
        assertThat(sql.getValue())
                .as("登记使用与「已启用且未过期」必须是一次条件更新")
                .contains("use_count = use_count + 1")
                .contains("enabled = TRUE")
                .contains("expires_at > CURRENT_TIMESTAMP");
    }
}
