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
            "updateExecutionCheckpoint",
            "updateExecutionCheckpointForDeployment",
            "findByIdForDeployment",
            "findByIdAndUserForDeployment",
            "updateStatusForDeployment",
            "updateStatusWithTtlForDeployment",
            "updatePlanJsonForDeployment",
            "updateSnapshotForDeploymentIfStatus",
            "pauseSnapshotWithTtlForDeployment",
            "updateTerminalSnapshotForDeployment",
            "cancelTerminalSnapshotWithTtlForDeployment",
            "updateResumedTerminalForDeployment",
            "listStartupRecoveryCandidatesForDeployment",
            "claimStartupRestartForDeployment",
            "completeStartupCancellationForDeployment",
            "failStartupRecoveryForDeployment",
            "listNonTerminalDeploymentGenerations",
            "failNonTerminalRunsForDeploymentGeneration",
            "countNonTerminalRunsForDeploymentGeneration",
            "failOrphanedNonTerminalRunsForDeploymentGeneration",
            "admitFollowUpForDeployment",
            "resetForResumeForDeployment",
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
        MappedStatement statement = statement("listStartupRecoveryCandidatesForDeployment");
        assertThat(statement.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        String sql = normalizedSql(statement.getBoundSql(Map.of(
                "startedBefore", OffsetDateTime.now(),
                "deploymentId", "stable",
                "deploymentGenerationId", "gen-" + "a".repeat(64),
                "limit", 100)));
        assertThat(sql)
                .contains("started_at < ?")
                .contains("LIMIT ?")
                .contains("deployment_id = ?")
                .contains("deployment_generation_id = ?")
                .contains("'WAITING_TOOL_JOB'")
                .contains("'CANCELING'")
                .doesNotContain("'WAITING'");
    }

    @Test
    void runInsertAndRecoveryReadsPersistTheImmutableLaneTag() {
        String insert = normalizedSql(statement("insert").getBoundSql(dummyParameters(methodParams.get("insert"))));
        String recovery = normalizedSql(statement("listStartupRecoveryCandidatesForDeployment").getBoundSql(Map.of(
                "startedBefore", OffsetDateTime.now(),
                "deploymentId", "stable",
                "deploymentGenerationId", "gen-" + "a".repeat(64),
                "limit", 100)));
        assertThat(insert).contains("lane_tag");
        assertThat(recovery).contains("lane_tag");
    }

    @Test
    void everyAgentRunReadRestoresThePersistedLaneTag() {
        for (String id : List.of(
                "findById",
                "findByIdForDeployment",
                "findByIdAndUser",
                "findByIdAndUserForDeployment",
                "listByUser",
                "listByStatusAndUpdatedAfter",
                "listByStatusAndUpdatedAfterComposite",
                "listStartupRecoveryCandidatesForDeployment",
                "listActiveToolJobAnchorsForDeployment",
                "listResumeReadyAnchorsForDeployment",
                "listStuckAtCasStatusAnchorsForDeployment")) {
            Map<String, Object> parameters = dummyParameters(methodParams.get(id));
            if (parameters.containsKey("statuses")) {
                parameters.put("statuses", List.of(AgentRunStatus.COMPLETED));
            }
            String sql = normalizedSql(statement(id).getBoundSql(parameters));
            assertThat(sql).as(id + " 必须恢复受理时的泳道标签")
                    .contains("lane_tag");
        }
    }

    @Test
    void restartClaimIsNarrowCasAndClearsOnlyLegacyToolAnchor() {
        String sql = normalizedSql(statement("claimStartupRestartForDeployment").getBoundSql(Map.of(
                "id", "run-1",
                "deploymentId", "stable",
                "deploymentGenerationId", "gen-" + "a".repeat(64),
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
                .contains("deployment_id = ?")
                .contains("deployment_generation_id = ?")
                .doesNotContain("execution_checkpoint_json = '{}'::jsonb");
    }

    @Test
    void cancellationAndFailureHaveExpectedStatusFences() {
        String cancel = normalizedSql(statement("completeStartupCancellationForDeployment")
                .getBoundSql(Map.of(
                        "id", "run-1",
                        "deploymentId", "stable",
                        "deploymentGenerationId", "gen-" + "a".repeat(64))));
        String fail = normalizedSql(statement("failStartupRecoveryForDeployment").getBoundSql(Map.of(
                "id", "run-1",
                "deploymentId", "stable",
                "deploymentGenerationId", "gen-" + "a".repeat(64),
                "expectedStatus", AgentRunStatus.EXECUTING,
                "lastError", "invalid")));
        assertThat(cancel).contains("status = 'CANCELED'", "status = 'CANCELING'",
                "deployment_id = ?", "deployment_generation_id = ?");
        assertThat(fail).contains("status = 'FAILED'", "status = ?", "last_error = ?",
                "deployment_id = ?", "deployment_generation_id = ?");
    }

    @Test
    void shutdownAndOrphanCleanupUseGenerationFencesAndTheOrphanWriteIsBounded() {
        Map<String, Object> parameters = Map.of(
                "deploymentId", "beta-a",
                "deploymentGenerationId", "gen-" + "a".repeat(64),
                "lastError", "deadline_exceeded",
                "limit", 32);
        String shutdown = normalizedSql(statement("failNonTerminalRunsForDeploymentGeneration")
                .getBoundSql(parameters));
        String orphan = normalizedSql(statement("failOrphanedNonTerminalRunsForDeploymentGeneration")
                .getBoundSql(parameters));
        String candidates = normalizedSql(statement("listNonTerminalDeploymentGenerations")
                .getBoundSql(Map.of(
                        "excludedDeploymentId", "beta-a",
                        "excludedDeploymentGenerationId", "gen-" + "b".repeat(64),
                        "limit", 32)));

        assertThat(shutdown)
                .contains("deployment_id = ?", "deployment_generation_id = ?", "status = 'FAILED'")
                .contains("status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED', 'EXPIRED')");
        assertThat(orphan)
                .contains("WITH candidates AS", "LIMIT ?", "FOR UPDATE SKIP LOCKED")
                .contains("deployment_id <> 'stable'", "deployment_id = ?",
                        "deployment_generation_id = ?", "status = 'FAILED'");
        assertThat(candidates)
                .contains("SELECT DISTINCT deployment_id", "LIMIT ?")
                .contains("deployment_id <> 'stable'")
                .contains("NOT (deployment_id = ? AND deployment_generation_id = ?)");

        String pagedCandidates = normalizedSql(statement("listNonTerminalDeploymentGenerations")
                .getBoundSql(Map.of(
                        "excludedDeploymentId", "beta-a",
                        "excludedDeploymentGenerationId", "gen-" + "b".repeat(64),
                        "afterDeploymentId", "beta-b",
                        "afterDeploymentGenerationId", "gen-" + "c".repeat(64),
                        "limit", 32)));
        assertThat(pagedCandidates)
                .contains("(deployment_id, deployment_generation_id) > (?, ?)");
    }

    @Test
    void userAdmissionAndRecoveryScansAlwaysCompareDeploymentIdentity() {
        for (String id : List.of(
                "findByIdForDeployment",
                "findByIdAndUserForDeployment",
                "updateStatusForDeployment",
                "updateStatusWithTtlForDeployment",
                "updatePlanJsonForDeployment",
                "updateSnapshotForDeploymentIfStatus",
                "pauseSnapshotWithTtlForDeployment",
                "updateExecutionCheckpointForDeployment",
                "updateTerminalSnapshotForDeployment",
                "cancelTerminalSnapshotWithTtlForDeployment",
                "updateResumedTerminalForDeployment",
                "admitFollowUpForDeployment",
                "resetForResumeForDeployment",
                "listActiveToolJobAnchorsForDeployment",
                "listResumeReadyAnchorsForDeployment",
                "listStuckAtCasStatusAnchorsForDeployment")) {
            Map<String, Object> parameters = dummyParameters(methodParams.get(id));
            String sql = normalizedSql(statement(id).getBoundSql(parameters));
            assertThat(sql).as(id)
                    .contains("deployment_id = ?")
                    .contains("deployment_generation_id = ?");
        }
    }

    @Test
    void ordinaryPipelineWritesCompareDeploymentAndExactSourceStatus() {
        for (String id : List.of(
                "updateStatusForDeployment",
                "updateStatusWithTtlForDeployment",
                "updatePlanJsonForDeployment",
                "updateExecutionCheckpointForDeployment",
                "updateTerminalSnapshotForDeployment")) {
            String sql = normalizedSql(statement(id)
                    .getBoundSql(dummyParameters(methodParams.get(id))));
            assertThat(sql).as(id)
                    .contains("deployment_id = ?")
                    .contains("deployment_generation_id = ?")
                    .contains("status = ?")
                    .doesNotContain("status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED', 'EXPIRED')");
        }

        String resumed = normalizedSql(statement("updateResumedTerminalForDeployment")
                .getBoundSql(dummyParameters(methodParams.get("updateResumedTerminalForDeployment"))));
        assertThat(resumed)
                .contains("deployment_id = ?")
                .contains("deployment_generation_id = ?")
                .contains("status = 'EXECUTING'")
                .contains("resumeToken")
                .contains("resumeLeaseVersion")
                .contains("resumeLauncherOwnerId");
    }

    @Test
    void cancelAndPauseControlWritesAreAtomicAndGenerationFenced() {
        String snapshot = normalizedSql(statement("updateSnapshotForDeploymentIfStatus")
                .getBoundSql(dummyParameters(methodParams.get("updateSnapshotForDeploymentIfStatus"))));
        String pause = normalizedSql(statement("pauseSnapshotWithTtlForDeployment")
                .getBoundSql(dummyParameters(methodParams.get("pauseSnapshotWithTtlForDeployment"))));
        String cancel = normalizedSql(statement("cancelTerminalSnapshotWithTtlForDeployment")
                .getBoundSql(dummyParameters(methodParams.get("cancelTerminalSnapshotWithTtlForDeployment"))));

        assertThat(snapshot)
                .contains("snapshot_json = CAST(? AS jsonb)")
                .contains("deployment_id = ?", "deployment_generation_id = ?", "status = ?");
        assertThat(pause)
                .contains("status = 'WAITING'")
                .contains("snapshot_json = CAST(? AS jsonb)")
                .contains("ttl_expires_at = ?")
                .contains("deployment_id = ?", "deployment_generation_id = ?", "status = ?")
                .contains("status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED', 'EXPIRED')");
        assertThat(cancel)
                .contains("status = 'CANCELED'")
                .contains("snapshot_json = CAST(? AS jsonb)")
                .contains("ttl_expires_at = ?")
                .contains("deployment_id = ?", "deployment_generation_id = ?")
                .contains("status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED', 'CANCELED', 'EXPIRED')");
    }

    @Test
    void everyToolJobCompareAndSetCanBindTheLocalDeploymentFence() {
        List<String> statements = List.of(
                "updateToolJobAnchor",
                "updateToolJobAnchorAndStatus",
                "claimPreparingToolJobAnchor",
                "persistCancelDisposition",
                "persistPauseDisposition",
                "clearPausedToolJobAnchor",
                "persistRepairAttempt",
                "claimPreparingToolJobAnchorFromResume",
                "updateActiveToolJobAnchor",
                "updateLiveDagBlockingToolJobAnchor",
                "beginLiveDagBlockingPreparingAbort",
                "claimLiveDagBlockingPreparingAbortCleanup",
                "completeLiveDagBlockingPreparingAbort",
                "updateToolJobAnchorAndStatusByOperation",
                "cancelToolJobAnchorFromStatuses",
                "closeResidualCanceledAnchorOnTerminalRun",
                "clearActiveToolJobAnchor",
                "promoteExpiredDagBlockingWorkerLost",
                "updateDagCleanupToolJobAnchor",
                "clearSynchronouslyCompletedToolJobAnchor",
                "clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor",
                "updateDagCleanupPreparingToolJobAnchor",
                "completeDagCleanupAndClearToolJobAnchor",
                "markToolJobCheckpointFailed",
                "markToolJobCheckpointFailurePending",
                "clearToolJobCheckpointFailurePending",
                "casUpdateStatus",
                "promoteCasStatusToResumeReady",
                "casUpdateAnchorResumeState",
                "casUpdateAnchorResumeStateAndStatus",
                "claimResumeLauncher",
                "takeoverExpiredResumeLauncher",
                "heartbeatResumeLauncher",
                "acceptResumeHandoff",
                "clearAcceptedResumeHandoff",
                "updateToolJobCheckpoint",
                "clearToolJobAnchorWithToken");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("deploymentIdentity",
                new DeploymentIdentity("beta-a", "gen-" + "a".repeat(64)));

        for (String id : statements) {
            String sql = normalizedSql(statement(id).getBoundSql(parameters));
            assertThat(sql).as(id)
                    .contains("deployment_id = ?")
                    .contains("deployment_generation_id = ?");
        }
    }

    @Test
    void legacyToolJobTestOverloadDoesNotAddAnUnknownDeploymentParameter() {
        String sql = normalizedSql(statement("updateToolJobAnchor").getBoundSql(Map.of()));
        assertThat(sql)
                .doesNotContain("deployment_id = ?")
                .doesNotContain("deployment_generation_id = ?");
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
