package world.willfrog.agentlangchain.acceptance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 验收夹具的读取与使用登记。
 *
 * <p>只做两件事：按「泳道 + 部署代际 + 编号」取回一条夹具，以及原子登记一次使用。
 * 写入方是宿主机上的受限控制面，这里没有任何写内容的入口——Agent 进程只能读，
 * 所以业务请求里带一个编号就能影响执行，但带不了自己想要的计划或模型回合。</p>
 *
 * <p>作用域口径沿用阶段二那张故障表（迁移 005/006）：{@code lane_id} 列放的是部署编号
 * {@code deploymentId}，{@code deployment_version} 列放的是部署代际 {@code generationId}。
 * 这样同一套宿主机控制面的写入动作在两张表上含义一致。</p>
 */
@Component
@Slf4j
public class AcceptanceFixtureStore {

    private final JdbcTemplate jdbcTemplate;

    public AcceptanceFixtureStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按泳道、部署代际与编号取一条夹具；没有就是没有，不猜也不回落。 */
    public Optional<AcceptanceFixtureRow> find(String deploymentId,
                                              String deploymentGenerationId,
                                              String fixtureId) {
        List<AcceptanceFixtureRow> rows = jdbcTemplate.query("""
                SELECT fixture_id, scenario_id, enabled, enabled_at, expires_at,
                       plan_json::text AS plan_json,
                       model_script_json::text AS model_script_json,
                       dispatch_policy_json::text AS dispatch_policy_json
                FROM alphafrog_agent_run_acceptance_fixture
                WHERE lane_id = ?
                  AND deployment_version = ?
                  AND fixture_id = ?
                """, (rs, rowNum) -> mapRow(rs),
                deploymentId, deploymentGenerationId, fixtureId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 原子登记一次使用：只有「已启用且未过期」的行才会被加一。
     *
     * <p>返回 false 说明在读取与登记之间这条夹具被停用或过期了。调用方必须把它当成
     * 「现在不能用」处理，不能因为前面读到过就继续。</p>
     */
    public boolean recordUse(String deploymentId, String deploymentGenerationId, String fixtureId) {
        int updated = jdbcTemplate.update("""
                UPDATE alphafrog_agent_run_acceptance_fixture
                SET use_count = use_count + 1,
                    last_used_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE lane_id = ?
                  AND deployment_version = ?
                  AND fixture_id = ?
                  AND enabled = TRUE
                  AND expires_at > CURRENT_TIMESTAMP
                """, deploymentId, deploymentGenerationId, fixtureId);
        return updated == 1;
    }

    private static AcceptanceFixtureRow mapRow(ResultSet rs) throws SQLException {
        return new AcceptanceFixtureRow(
                rs.getString("fixture_id"),
                rs.getString("scenario_id"),
                rs.getBoolean("enabled"),
                rs.getObject("enabled_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("plan_json"),
                rs.getString("model_script_json"),
                rs.getString("dispatch_policy_json"));
    }
}
