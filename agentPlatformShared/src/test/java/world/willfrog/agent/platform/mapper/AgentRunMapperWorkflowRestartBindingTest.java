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
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

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
            "updateStatus",
            "updateStatusWithTtl",
            "updatePlanJson",
            "updateExecutionCheckpoint",
            "updateTerminalSnapshot",
            "updateSnapshotIfStatus",
            "persistCancelDisposition",
            "pauseSnapshotWithTtl",
            "cancelTerminalSnapshotWithTtl",
            "updateResumedTerminal",
            "resetForResume",
            "completeStartupCancellation",
            "failStartupRecovery",
            "findByIdForDeployment",
            "findByIdAndUserForDeployment",
            "listStartupRecoveryCandidatesForDeployment",
            "claimStartupRestartForDeployment",
            "listNonTerminalDeploymentGenerations",
            "failNonTerminalRunsForDeploymentGeneration",
            "countNonTerminalRunsForDeploymentGeneration",
            "failOrphanedNonTerminalRunsForDeploymentGeneration",
            "admitFollowUpForDeployment",
            "claimResumeLauncher",
            "takeoverExpiredResumeLauncher",
            "listActiveToolJobAnchorsForDeployment",
            "listResumeReadyAnchorsForDeployment",
            "listStuckAtCasStatusAnchorsForDeployment");

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
                case "ttlExpiresAt" -> OffsetDateTime.now();
                case "deploymentId" -> "stable";
                case "deploymentGenerationId" -> "gen-" + "a".repeat(64);
                case "status" -> AgentRunStatus.EXECUTING;
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
