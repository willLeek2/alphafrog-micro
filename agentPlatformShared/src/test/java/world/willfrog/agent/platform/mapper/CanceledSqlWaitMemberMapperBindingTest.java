package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 解析实际Mapper资源，核取消闭合的身份和未收尾责任条件；不启动数据库。 */
class CanceledSqlWaitMemberMapperBindingTest {
    @Test
    void closureBindsOriginalProofAndRequiresEveryResourceResponsibilityToFinish() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentRunMapper.class);
        String resource = "mapper/AgentRunMapper.xml";
        try (var in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(AgentRunMapper.class.getName()
                + ".completeCanceledSqlWaitMember");
        var sql = statement.getBoundSql(Map.of("runId", "run", "operationId", "op", "requestFingerprint", "fp",
                "waitMemberId", 41L, "dispatchProofJson", "{}", "reservationJson", "{}", "terminalReservationJson", "{}"));
        var method = Arrays.stream(AgentRunMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals("completeCanceledSqlWaitMember")).findFirst().orElseThrow();
        var parameters = Arrays.stream(method.getParameters()).map(parameter ->
                parameter.getAnnotation(org.apache.ibatis.annotations.Param.class).value()).collect(Collectors.toSet());
        assertThat(sql.getParameterMappings().stream().map(mapping -> mapping.getProperty()).collect(Collectors.toSet()))
                .isEqualTo(parameters);
        String text = sql.getSql().replaceAll("\\s+", " ").trim();
        assertThat(text).contains("UPDATE alphafrog_agent_run r", "tool_job_anchor_json = '{}'::jsonb",
                "THEN r.status ELSE 'CANCELED' END", "r.scheduler_version = 'DUAL_POOL_V2'",
                "'toolName' = 'executeQuery'", "'operationId' = ?", "'requestFingerprint' = ?",
                "'reservationJson' = ?", "'runDisposition' = 'CANCELED'", "'autoResume'",
                "m.state IN ('CANCELED', 'LATE')", "m.dispatch_proof_json = CAST(? AS jsonb)",
                "'schemaVersion' = '2'", "'createRequestJson'", "'createRequestExpired'",
                "s.state = 'CONFIRMED'", "s.task_id = s.terminal_task_id", "s.terminal_status",
                "'{data_analysis_observability,calls}'", "'{reservation,state}' = 'TERMINAL_CONFIRMED'",
                "'terminalAt'", "terminal.call -> 'reservation' = CAST(? AS jsonb)", "other.state != 'CONFIRMED'", "g.state IN ('WAITING', 'READY')",
                "other.state IN ('PENDING', 'RUNNING')", "wi.state NOT IN ('RESULT_COMMITTED', 'EXECUTION_FAILED', 'CANCELED', 'STALE')");
        assertThat(text).doesNotContain("SET snapshot_json", "SET dispatch_proof_json");
        var scan = configuration.getMappedStatement(AgentRunMapper.class.getName()
                + ".listActiveToolJobAnchorsForDeployment").getBoundSql(Map.of("deploymentId", "deployment",
                "deploymentGenerationId", "generation", "limit", 200)).getSql();
        assertThat(scan).contains("'COMPLETED', 'PARTIAL', 'EXPIRED'", "scheduler_version = 'DUAL_POOL_V2'");
    }

    @Test
    void resumeWaitsForTheOriginalCanceledSqlSessionAndUsesTheSameRunRow() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(AgentRunMapper.class);
        String resource = "mapper/AgentRunMapper.xml";
        try (var in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var resume = configuration.getMappedStatement(AgentRunMapper.class.getName() + ".resetForResume")
                .getBoundSql(Map.of("id", "run", "userId", "user", "ttlExpiresAt", "2026-10-10T00:00:00Z"));
        String text = resume.getSql().replaceAll("\\s+", " ").trim();
        assertThat(text).contains("UPDATE alphafrog_agent_run", "status IN ('FAILED', 'CANCELED', 'WAITING')",
                "AND NOT ( scheduler_version = 'DUAL_POOL_V2'", "'toolName', '') = 'executeQuery'",
                "'runDisposition', '') = 'CANCELED'", "'operationId', '') != ''");
        var lock = configuration.getMappedStatement(AgentRunMapper.class.getName() + ".findByIdForUpdate")
                .getBoundSql(Map.of("id", "run")).getSql();
        assertThat(lock).contains("FROM alphafrog_agent_run", "WHERE id = ?", "FOR UPDATE");
    }
}
