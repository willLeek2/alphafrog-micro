package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
    void markingAndExpirationBindIdAndCloseEverySubquery() throws Exception {
        Configuration configuration = mapper(AgentRunMapper.class, "mapper/AgentRunMapper.xml");
        String runId = System.getProperty("workspace.retention.probe-run-id", "run-retention");
        Map<String, Object> parameters = Map.of("id", runId);
        for (String name : new String[]{"markWorkspaceCleanupStarted", "markWorkspaceExpired"}) {
            var statement = configuration.getMappedStatement(AgentRunMapper.class.getName() + '.' + name);
            BoundSql boundSql = statement.getBoundSql(parameters);
            assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                    .containsExactly("id");
            PreparedStatement jdbc = mock(PreparedStatement.class);
            new DefaultParameterHandler(statement, parameters, boundSql).setParameters(jdbc);
            verify(jdbc).setString(1, runId);
            verifyNoMoreInteractions(jdbc);

            // 从实际绑定 SQL 检查全部子查询闭合，字符串中的括号不参与计算。
            String sql = boundSql.getSql();
            String structuralSql = sql.replaceAll("'(?:''|[^'])*'", "''");
            int depth = 0;
            for (char token : structuralSql.toCharArray()) {
                if (token == '(') depth++;
                if (token == ')') depth--;
                assertThat(depth).as("%s 子查询不得提前闭合", name).isGreaterThanOrEqualTo(0);
            }
            assertThat(depth).as("%s 每个子查询都必须完整闭合", name).isZero();

            // 只导出 SELECT 探针，直接复用绑定后的全部 WHERE，不执行状态写入。
            String probeDirectory = System.getProperty("workspace.retention.probe-dir");
            if (probeDirectory != null && !probeDirectory.isBlank()) {
                Path directory = Path.of(probeDirectory);
                Files.createDirectories(directory);
                String where = sql.substring(sql.indexOf("WHERE r.id"))
                        .replace("?", "'" + runId.replace("'", "''") + "'");
                Files.writeString(directory.resolve(name + ".sql"),
                        "SELECT count(*) AS eligible_count FROM alphafrog_agent_run r " + where + "\n");
            }
        }
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
