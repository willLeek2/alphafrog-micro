package world.willfrog.agentlangchain.acceptance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 放行点：夹具策略把某几条成员的结果压在这里，等受限控制面把点标成已放行。
 *
 * <p>只读。写入方是宿主机上的受限控制面（与夹具同一套形状：只有它写，Agent 只读），业务请求里
 * 带不出这张表的任何写入能力。</p>
 *
 * <p>读法是「许可」：标成已放行之后每一轮读到的都是已放行，重复读不会有副作用，进程在两次读之间
 * 退出也不会把这次放行丢掉。没有这一行、或者行还没标，都按「没放行」处理。</p>
 */
@Component
public class AcceptanceReleasePointStore {

    private final JdbcTemplate jdbcTemplate;

    public AcceptanceReleasePointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 这个 Run 的这个放行点被标成已放行了吗。 */
    public boolean isOpened(String runId, String releaseKey) {
        if (runId == null || runId.isBlank() || releaseKey == null || releaseKey.isBlank()) {
            return false;
        }
        Integer opened = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM alphafrog_agent_run_release_point
                WHERE run_id = ?
                  AND release_key = ?
                  AND opened_at IS NOT NULL
                """, Integer.class, runId, releaseKey);
        return opened != null && opened > 0;
    }
}
