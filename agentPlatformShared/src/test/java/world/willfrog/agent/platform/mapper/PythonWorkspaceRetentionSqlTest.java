package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 校验到期标记与删盘确认两处 SQL 都有异步责任栅栏。 */
class PythonWorkspaceRetentionSqlTest {
    @Test
    void markingAndExpirationBothRequireEveryAsyncResponsibilityToSettle() throws Exception {
        Configuration configuration = mapper(AgentRunMapper.class, "mapper/AgentRunMapper.xml");
        for (String statement : new String[]{"markWorkspaceCleanupStarted", "markWorkspaceExpired"}) {
            String sql = bound(configuration, AgentRunMapper.class, statement);
            assertThat(sql).contains("r.status IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED', 'EXPIRED')")
                    .contains("g.state IN ('WAITING', 'READY')")
                    .contains("m.tool_name = 'executePython'")
                    .contains("m.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'LATE')")
                    .contains("m.state = 'CANCELED' AND m.dispatch_proof_json IS NOT NULL")
                    .contains("confirmed.state = 'CONFIRMED'")
                    .contains("m.state = 'LATE' AND EXISTS")
                    .contains("pending_stop.state <> 'CONFIRMED'")
                    .contains("s.state <> 'CONFIRMED'")
                    .contains("i.capacity_released_at IS NULL")
                    .contains("c.active_child_count > 0")
                    .contains("b.active_nodes > 0 OR b.external_waits > 0")
                    .contains("n.state = 'WAITING'")
                    .contains("r.tool_job_anchor_json = '{}'::jsonb")
                    .contains("'finalizerStep' = 'EVENT'")
                    .contains("'reservationJson')::jsonb ->> 'state' = 'RELEASED'");
        }
        assertThat(bound(configuration, AgentRunMapper.class, "markWorkspaceExpired"))
                .contains("tool_job_anchor_json = r.tool_job_anchor_json - 'createRequestJson'");
    }

    @Test
    void memberBodyCleanupRequiresPermanentExpirationAndSettledProof() throws Exception {
        Configuration configuration = mapper(WaitGroupMapper.class, "mapper/WaitGroupMapper.xml");
        String sql = bound(configuration, WaitGroupMapper.class, "compactExpiredWaitMemberProofs");
        assertThat(sql).contains("r.workspace_expired_at IS NOT NULL")
                .contains("m.state IN ('SUCCEEDED', 'FAILED', 'LATE')")
                .contains("m.state = 'CANCELED' AND EXISTS")
                .contains("confirmed.state = 'CONFIRMED'")
                .contains("s.state <> 'CONFIRMED'")
                .contains("m.dispatch_proof_json - 'createRequestJson'")
                .contains("jsonb_build_object('createRequestExpired', true)");
        assertThat(sql).doesNotContain("dispatch_proof_json = '{}'::jsonb");
    }

    @Test
    void lateResultWithoutStopRowIsSettledButAnyExistingStopMustBeConfirmed() throws Exception {
        String marking = bound(mapper(AgentRunMapper.class, "mapper/AgentRunMapper.xml"),
                AgentRunMapper.class, "markWorkspaceCleanupStarted");
        String compacting = bound(mapper(WaitGroupMapper.class, "mapper/WaitGroupMapper.xml"),
                WaitGroupMapper.class, "compactExpiredWaitMemberProofs");

        assertThat(marking).contains("m.state = 'LATE' AND EXISTS (")
                .contains("pending_stop.wait_member_id = m.id")
                .contains("pending_stop.state <> 'CONFIRMED'")
                .contains("m.state = 'CANCELED' AND m.dispatch_proof_json IS NOT NULL")
                .contains("NOT EXISTS (")
                .contains("confirmed.state = 'CONFIRMED'");
        assertThat(compacting).contains("m.state IN ('SUCCEEDED', 'FAILED', 'LATE')")
                .contains("NOT EXISTS (")
                .contains("s.wait_member_id = m.id AND s.state <> 'CONFIRMED'")
                .contains("m.state = 'CANCELED' AND EXISTS (")
                .contains("confirmed.state = 'CONFIRMED'");
    }

    private static Configuration mapper(Class<?> type, String path) throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(type);
        try (InputStream input = Resources.getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String bound(Configuration configuration, Class<?> type, String id) {
        return configuration.getMappedStatement(type.getName() + '.' + id)
                .getBoundSql(Map.of("id", "run-1", "runId", "run-1")).getSql();
    }
}
