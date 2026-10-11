package world.willfrog.beta.tooljob;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import world.willfrog.beta.config.BetaControllerProperties;
import world.willfrog.beta.core.BetaDeploymentService.ToolJobTestTarget;
import world.willfrog.beta.core.ControllerException;

@Repository
@ConditionalOnProperty(prefix = "alphafrog.beta-controller.tool-job-test-control",
        name = "enabled", havingValue = "true")
public class JdbcToolJobTestStore implements ToolJobTestStore {
    private static final Set<String> CHECKPOINTS = Set.of("BEFORE_SANDBOX_SUBMIT", "AFTER_SANDBOX_ACCEPTED",
            "AFTER_RESUME_COMMITTED", "AFTER_MODEL_COMPLETED");
    private static final Set<String> ACTIONS = Set.of("THREAD_INTERRUPT", "PROCESS_HALT");
    private final BetaControllerProperties.ToolJobTestControl properties;

    public JdbcToolJobTestStore(BetaControllerProperties controllerProperties) {
        this.properties = controllerProperties.getToolJobTestControl();
    }

    @PostConstruct
    void validateConfiguration() {
        if (properties.getJdbcUrl() == null || !properties.getJdbcUrl().startsWith("jdbc:postgresql://")
                || properties.getUsername() == null || properties.getUsername().isBlank()) {
            throw new IllegalStateException("Tool-job test database connection is not configured");
        }
        readPassword();
    }

    @Override
    public FaultRecord armFault(ToolJobTestTarget target, String runId, String checkpoint,
                                String action, int ttlSeconds) {
        requireIdentifier(runId, "runId", 64);
        if (checkpoint == null || !CHECKPOINTS.contains(checkpoint)) {
            throw invalid("Unsupported fault checkpoint");
        }
        if (action == null || !ACTIONS.contains(action)) {
            throw invalid("Unsupported fault action");
        }
        if (ttlSeconds < 10 || ttlSeconds > properties.getMaximumFaultTtlSeconds()) {
            throw invalid("Fault TTL is outside the configured range");
        }
        if ("PROCESS_HALT".equals(action)
                && (!target.manifestAllowsProcessHalt() || !target.runtime().processHaltEnabled()
                || !target.runtime().restartUnlessStopped())) {
            throw new ControllerException("TOOL_JOB_TEST_PROCESS_HALT_DISABLED",
                    "Process-halt testing is not enabled for the active Agent container");
        }
        String scenarioId = "tj-" + UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            // PostgreSQL 的 READ COMMITTED 会为每条语句取得新快照。第二个 arm 在等待
            // 部署代际锁后执行冲突查询时，必须看见第一个事务刚提交的故障记录。
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            configureTransaction(connection);
            // 故障预置会影响同一个 Agent 容器。按部署代际统一串行化，避免两个不同
            // Run 同时通过 PROCESS_HALT 冲突检查后，各自写入一条进程终止记录。
            advisoryLock(connection, faultArmLockIdentity(target));
            requireMatchingRun(connection, target, runId);
            requireNoConflictingFault(connection, target, runId, action);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO alphafrog_agent_tool_job_fault_injection
                      (lane_id, traffic_scope_id, run_id, scenario_id, checkpoint, action,
                       deployment_version, enabled, expires_at, enabled_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, clock_timestamp())
                    RETURNING scenario_id, enabled_at, expires_at, consumed_at, triggered_at,
                              restart_observed_at, trigger_instance
                    """)) {
                statement.setString(1, target.deploymentId());
                statement.setString(2, target.trafficScopeId());
                statement.setString(3, runId);
                statement.setString(4, scenarioId);
                statement.setString(5, checkpoint);
                statement.setString(6, action);
                statement.setString(7, target.generationId());
                statement.setTimestamp(8, Timestamp.from(expiresAt));
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    FaultRecord record = faultRecord(target, runId, checkpoint, action, result);
                    connection.commit();
                    return record;
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public FaultRecord readFault(ToolJobTestTarget target, String runId, String scenarioId) {
        requireIdentifier(runId, "runId", 64);
        requireIdentifier(scenarioId, "scenarioId", 128);
        String sql = """
                SELECT scenario_id, checkpoint, action, enabled_at, expires_at, consumed_at,
                       triggered_at, restart_observed_at, trigger_instance
                  FROM alphafrog_agent_tool_job_fault_injection
                 WHERE lane_id = ? AND traffic_scope_id = ? AND deployment_version = ?
                   AND run_id = ? AND scenario_id = ?
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.deploymentId());
            statement.setString(2, target.trafficScopeId());
            statement.setString(3, target.generationId());
            statement.setString(4, runId);
            statement.setString(5, scenarioId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ControllerException("TOOL_JOB_TEST_FAULT_NOT_FOUND",
                        "The fault scenario does not exist for this lane and Run");
                return faultRecord(target, runId, result.getString("checkpoint"), result.getString("action"), result);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public PlanInvalidation invalidatePlan(ToolJobTestTarget target, String runId,
                                           int expectedPlanGeneration, long expectedRunControlVersion,
                                           String expectedOperationId) {
        requireIdentifier(runId, "runId", 64);
        requireIdentifier(expectedOperationId, "operationId", 128);
        if (expectedPlanGeneration < 0 || expectedRunControlVersion < 0) {
            throw invalid("Expected versions cannot be negative");
        }
        String sql = """
                WITH candidate AS (
                    SELECT r.id, r.plan_generation, r.run_control_version,
                           r.tool_job_anchor_json #>> '{operationId}' AS operation_id
                      FROM alphafrog_agent_run r
                     WHERE r.id = ?
                       AND r.deployment_id = ?
                       AND r.deployment_generation_id = ?
                       AND r.lane_tag = ?
                       AND r.scheduler_version = 'DUAL_POOL_V1'
                       AND r.status = 'WAITING_TOOL_JOB'
                       AND r.plan_generation = ?
                       AND r.run_control_version = ?
                       AND r.tool_job_anchor_json #>> '{operationId}' = ?
                       AND COALESCE(r.tool_job_anchor_json #>> '{taskId}', '') <> ''
                       AND r.tool_job_anchor_json #>> '{terminalStatus}' IS NULL
                       AND r.tool_job_anchor_json #>> '{resumeState}' IS NULL
                       AND r.tool_job_anchor_json #>> '{workItemPlanGeneration}' = ?::text
                       AND r.tool_job_anchor_json #>> '{workItemRunControlVersion}' = ?::text
                       AND EXISTS (
                           SELECT 1 FROM alphafrog_agent_run_work_item wi
                            WHERE wi.run_id = r.id
                              AND wi.plan_generation = (r.tool_job_anchor_json #>> '{workItemPlanGeneration}')::int
                              AND wi.node_id = r.tool_job_anchor_json #>> '{workItemNodeId}'
                              AND wi.node_attempt = (r.tool_job_anchor_json #>> '{workItemNodeAttempt}')::int
                              AND wi.segment_sequence = (r.tool_job_anchor_json #>> '{workItemSegmentSequence}')::int
                              AND wi.context_version = (r.tool_job_anchor_json #>> '{workItemContextVersion}')::bigint
                              AND wi.run_control_version = (r.tool_job_anchor_json #>> '{workItemRunControlVersion}')::bigint
                              AND wi.claim_epoch = (r.tool_job_anchor_json #>> '{workItemClaimEpoch}')::int
                              AND wi.state = 'WAITING')
                     FOR UPDATE
                ), updated AS (
                    UPDATE alphafrog_agent_run r
                       SET plan_generation = c.plan_generation + 1,
                           updated_at = clock_timestamp()
                      FROM candidate c
                     WHERE r.id = c.id
                    RETURNING c.plan_generation AS previous_plan_generation,
                              r.plan_generation AS current_plan_generation,
                              r.run_control_version, c.operation_id)
                SELECT * FROM updated
                """;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            configureTransaction(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, runId);
                statement.setString(2, target.deploymentId());
                statement.setString(3, target.generationId());
                statement.setString(4, target.trafficScopeId());
                statement.setInt(5, expectedPlanGeneration);
                statement.setLong(6, expectedRunControlVersion);
                statement.setString(7, expectedOperationId);
                statement.setInt(8, expectedPlanGeneration);
                statement.setLong(9, expectedRunControlVersion);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new ControllerException("TOOL_JOB_TEST_PLAN_INVALIDATION_REJECTED",
                            "The Run no longer matches the waiting tool-job identity and version conditions");
                    PlanInvalidation invalidation = new PlanInvalidation(target.deploymentId(),
                            target.trafficScopeId(), target.generationId(), runId,
                            result.getInt("previous_plan_generation"), result.getInt("current_plan_generation"),
                            result.getLong("run_control_version"), result.getString("operation_id"));
                    connection.commit();
                    return invalidation;
                }
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private Connection connection() throws SQLException {
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", properties.getUsername());
        connectionProperties.setProperty("password", readPassword());
        connectionProperties.setProperty("ApplicationName", "alphafrog-beta-tool-job-test-control");
        connectionProperties.setProperty("connectTimeout", "5");
        connectionProperties.setProperty("socketTimeout", "15");
        return DriverManager.getConnection(properties.getJdbcUrl(), connectionProperties);
    }

    private void advisoryLock(Connection connection, String identity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, identity);
            statement.execute();
        }
    }

    private void configureTransaction(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '5s'");
            statement.execute("SET LOCAL statement_timeout = '15s'");
        }
    }

    private void requireMatchingRun(Connection connection, ToolJobTestTarget target, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, deployment_id, deployment_generation_id, lane_tag
                  FROM alphafrog_agent_run WHERE id = ? FOR UPDATE
                """)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new ControllerException("TOOL_JOB_TEST_RUN_NOT_FOUND",
                        "The Run does not exist");
                String status = result.getString("status");
                if (Set.of("COMPLETED", "FAILED", "CANCELED").contains(status)
                        || !target.deploymentId().equals(result.getString("deployment_id"))
                        || !target.generationId().equals(result.getString("deployment_generation_id"))
                        || !target.trafficScopeId().equals(result.getString("lane_tag"))) {
                    throw new ControllerException("TOOL_JOB_TEST_RUN_MISMATCH",
                            "The Run is terminal or does not belong to the active lane generation");
                }
            }
        }
    }

    private void requireNoConflictingFault(Connection connection, ToolJobTestTarget target, String runId,
                                           String action) throws SQLException {
        String sql = """
                SELECT 1 FROM alphafrog_agent_tool_job_fault_injection
                 WHERE lane_id = ? AND traffic_scope_id = ? AND deployment_version = ?
                   AND enabled = TRUE
                   AND (run_id = ?
                     OR (? = 'PROCESS_HALT' AND action = 'PROCESS_HALT'
                         AND ((consumed_at IS NULL AND expires_at > clock_timestamp())
                           OR (consumed_at IS NOT NULL AND restart_observed_at IS NULL))))
                 LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.deploymentId());
            statement.setString(2, target.trafficScopeId());
            statement.setString(3, target.generationId());
            statement.setString(4, runId);
            statement.setString(5, action);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new ControllerException("TOOL_JOB_TEST_FAULT_ALREADY_ARMED",
                        "This Run already has a fault scenario or a process-halt scenario is still active");
            }
        }
    }

    static String faultArmLockIdentity(ToolJobTestTarget target) {
        return "tool-job-fault:" + target.deploymentId() + ':' + target.generationId();
    }

    private FaultRecord faultRecord(ToolJobTestTarget target, String runId, String checkpoint,
                                    String action, ResultSet result) throws SQLException {
        Timestamp enabled = result.getTimestamp("enabled_at");
        Timestamp expires = result.getTimestamp("expires_at");
        Timestamp consumed = result.getTimestamp("consumed_at");
        Timestamp triggered = result.getTimestamp("triggered_at");
        Timestamp restarted = result.getTimestamp("restart_observed_at");
        String status = restarted != null ? "RESTART_OBSERVED"
                : triggered != null && "PROCESS_HALT".equals(action) ? "TRIGGERED_WAITING_RESTART"
                : triggered != null ? "TRIGGERED"
                : expires != null && expires.toInstant().isBefore(Instant.now()) ? "EXPIRED" : "ARMED";
        return new FaultRecord(result.getString("scenario_id"), target.deploymentId(), target.trafficScopeId(),
                target.generationId(), runId, checkpoint, action, status, timestamp(enabled), timestamp(expires),
                timestamp(consumed), timestamp(triggered), timestamp(restarted), result.getString("trigger_instance"));
    }

    private String readPassword() {
        Path file = properties.getPasswordFile();
        try {
            if (file == null || !file.isAbsolute() || !Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new IllegalStateException("Tool-job test database password file is missing or unsafe");
            }
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
                if (permissions.stream().anyMatch(permission -> permission != PosixFilePermission.OWNER_READ
                        && permission != PosixFilePermission.OWNER_WRITE)) {
                    throw new IllegalStateException("Tool-job test database password file permissions are unsafe");
                }
            } catch (UnsupportedOperationException ignored) { }
            String password = Files.readString(file);
            if (password.isEmpty() || !password.equals(password.strip())
                    || password.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException("Tool-job test database password is invalid");
            }
            return password;
        } catch (IOException exception) {
            throw new IllegalStateException("Tool-job test database password cannot be read", exception);
        }
    }

    private ControllerException databaseFailure(SQLException exception) {
        if ("42P01".equals(exception.getSQLState()) || "42703".equals(exception.getSQLState())) {
            return new ControllerException("TOOL_JOB_TEST_MIGRATION_REQUIRED",
                    "The tool-job test database migration has not been applied", exception);
        }
        if ("40001".equals(exception.getSQLState()) || "55P03".equals(exception.getSQLState())) {
            return new ControllerException("TOOL_JOB_TEST_CONCURRENT_CHANGE",
                    "The lane or Run changed while the test action was being applied", exception);
        }
        return new ControllerException("TOOL_JOB_TEST_DATABASE_FAILED",
                "The tool-job test database operation failed", exception);
    }

    private static void requireIdentifier(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw invalid(name + " is invalid");
        }
    }

    private static ControllerException invalid(String message) {
        return new ControllerException("TOOL_JOB_TEST_REQUEST_INVALID", message);
    }

    private static String timestamp(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }
}
