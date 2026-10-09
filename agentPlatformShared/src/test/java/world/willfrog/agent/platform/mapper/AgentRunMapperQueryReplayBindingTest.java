package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/** 解析真实 MyBatis 映射，核对续占写入只刷新时间且绑定原成员、控制版本及请求。 */
class AgentRunMapperQueryReplayBindingTest {
    @Test
    void replayRenewalBindsEveryIdentityAndLeavesProofAndAnchorUntouched() throws Exception {
        var configuration = new Configuration();
        configuration.addMapper(AgentRunMapper.class);
        String resource = "mapper/AgentRunMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(AgentRunMapper.class.getName() + ".renewExecuteQueryReplayClaim");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.UPDATE);
        var bound = statement.getBoundSql(Map.of("runId", "run", "groupId", 7L, "memberIdentity", "member",
                "operationId", "op", "requestFingerprint", "fp", "createRequestJson", "request",
                "planGeneration", 2L, "runControlVersion", 5L));
        var names = bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).collect(Collectors.toSet());
        var method = Arrays.stream(AgentRunMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals("renewExecuteQueryReplayClaim")).findFirst().orElseThrow();
        Set<String> javaNames = Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(org.apache.ibatis.annotations.Param.class).value())
                .collect(Collectors.toSet());
        assertThat(names).isEqualTo(javaNames);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).startsWith("UPDATE alphafrog_agent_run r SET updated_at = clock_timestamp() WHERE")
                .contains("r.status = 'EXECUTING'", "r.run_control_version = ?", "r.plan_generation = ?",
                        "'operationId' = ?", "'requestFingerprint' = ?", "'createRequestJson' = ?",
                        "g.state = 'WAITING'", "m.state IN ('PENDING', 'RUNNING')", "m.run_id = r.id",
                        "'taskId' IS NULL", "'workspaceRefusalCode' IS NULL", "'createRequestExpired'")
                .doesNotContain("SET tool_job_anchor_json", "SET dispatch_proof_json");
        var count = configuration.getMappedStatement(AgentRunMapper.class.getName() + ".countInFlightExecuteQueryByUser")
                .getBoundSql(Map.of("userId", "user", "excludeRunId", "run", "toolName", "executeQuery", "staleSeconds", 600));
        assertThat(count.getSql()).contains("updated_at >= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')");
        assertThat(count.getParameterMappings().get(count.getParameterMappings().size() - 1).getProperty())
                .isEqualTo("staleSeconds");
    }
}
