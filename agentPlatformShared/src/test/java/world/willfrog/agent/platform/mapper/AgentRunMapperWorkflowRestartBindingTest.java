package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 工作流启动恢复 SQL 的 host-only MyBatis 绑定与原子条件合同。 */
class AgentRunMapperWorkflowRestartBindingTest {

    private static final List<String> STATEMENTS = List.of(
            "updateExecutionCheckpoint",
            "listStartupRecoveryCandidates",
            "claimStartupRestart",
            "completeStartupCancellation",
            "failStartupRecovery");

    private Configuration configuration;
    private Map<String, Set<String>> methodParams;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        configuration.addMapper(AgentRunMapper.class);
        String resource = "mapper/AgentRunMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        methodParams = new HashMap<>();
        for (Method method : AgentRunMapper.class.getDeclaredMethods()) {
            Set<String> names = java.util.Arrays.stream(method.getParameters())
                    .map(Parameter::getAnnotations)
                    .flatMap(java.util.Arrays::stream)
                    .filter(org.apache.ibatis.annotations.Param.class::isInstance)
                    .map(org.apache.ibatis.annotations.Param.class::cast)
                    .map(org.apache.ibatis.annotations.Param::value)
                    .collect(Collectors.toSet());
            methodParams.put(method.getName(), names);
        }
    }

    @Test
    void everyWorkflowRestartMethodHasMatchingXmlParameters() {
        for (String id : STATEMENTS) {
            MappedStatement statement = statement(id);
            Map<String, Object> parameters = dummyParameters(methodParams.get(id));
            BoundSql sql = statement.getSqlSource().getBoundSql(parameters);
            Set<String> xmlParams = sql.getParameterMappings().stream()
                    .map(ParameterMapping::getProperty)
                    .map(name -> name.contains(".") ? name.substring(0, name.indexOf('.')) : name)
                    .collect(Collectors.toSet());
            assertThat(xmlParams).as(id + " XML 参数必须与 Java @Param 一致")
                    .isSubsetOf(methodParams.get(id));
        }
    }

    @Test
    void startupDiscoveryIsBoundedAndExcludesManualWaiting() {
        MappedStatement statement = statement("listStartupRecoveryCandidates");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        String sql = normalizedSql(statement.getBoundSql(Map.of(
                "startedBefore", OffsetDateTime.now(), "limit", 100)));
        assertThat(sql)
                .contains("started_at < ?")
                .contains("LIMIT ?")
                .contains("'WAITING_TOOL_JOB'")
                .contains("'CANCELING'")
                .doesNotContain("'WAITING'");
    }

    @Test
    void restartClaimIsNarrowCasAndClearsOnlyLegacyToolAnchor() {
        String sql = normalizedSql(statement("claimStartupRestart").getBoundSql(Map.of(
                "id", "run-1",
                "expectedStatus", AgentRunStatus.EXECUTING,
                "expectedRestartAttempt", 0,
                "maxRestartAttempts", 1)));
        assertThat(sql)
                .contains("status = 'RECEIVED'")
                .contains("restart_attempt = restart_attempt + 1")
                .contains("tool_job_anchor_json = '{}'::jsonb")
                .contains("status = ?")
                .contains("restart_attempt = ?")
                .contains("restart_attempt < ?")
                .doesNotContain("execution_checkpoint_json = '{}'::jsonb");
    }

    @Test
    void cancellationAndFailureHaveExpectedStatusFences() {
        String cancel = normalizedSql(statement("completeStartupCancellation")
                .getBoundSql(Map.of("id", "run-1")));
        String fail = normalizedSql(statement("failStartupRecovery").getBoundSql(Map.of(
                "id", "run-1",
                "expectedStatus", AgentRunStatus.EXECUTING,
                "lastError", "invalid")));
        assertThat(cancel).contains("status = 'CANCELED'", "status = 'CANCELING'");
        assertThat(fail).contains("status = 'FAILED'", "status = ?", "last_error = ?");
    }

    private MappedStatement statement(String id) {
        String fullId = AgentRunMapper.class.getName() + "." + id;
        assertThat(configuration.hasStatement(fullId)).as(id + " 必须存在").isTrue();
        return configuration.getMappedStatement(fullId);
    }

    private Map<String, Object> dummyParameters(Set<String> names) {
        Map<String, Object> values = new HashMap<>();
        for (String name : names) {
            values.put(name, switch (name) {
                case "startedBefore" -> OffsetDateTime.now();
                case "expectedStatus" -> AgentRunStatus.EXECUTING;
                case "limit", "expectedRestartAttempt", "maxRestartAttempts" -> 1;
                default -> "value";
            });
        }
        return values;
    }

    private String normalizedSql(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
