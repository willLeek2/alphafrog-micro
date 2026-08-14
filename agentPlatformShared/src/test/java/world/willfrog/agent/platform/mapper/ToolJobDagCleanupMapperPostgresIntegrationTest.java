package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies DAG lease takeover and multi-status cleanup CAS against PostgreSQL JSONB predicates.
 */
@Testcontainers(disabledWithoutDocker = true)
class ToolJobDagCleanupMapperPostgresIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpMapper() throws Exception {
        DataSource dataSource = dataSource();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE alphafrog_agent_run (
                        id VARCHAR(64) PRIMARY KEY,
                        user_id VARCHAR(64),
                        status VARCHAR(32),
                        current_step INT DEFAULT 0,
                        max_steps INT DEFAULT 20,
                        plan_json JSONB NOT NULL DEFAULT '{}',
                        snapshot_json JSONB NOT NULL DEFAULT '{}',
                        last_error TEXT,
                        ttl_expires_at TIMESTAMPTZ,
                        started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMPTZ,
                        ext JSONB NOT NULL DEFAULT '{}',
                        execution_checkpoint_json JSONB NOT NULL DEFAULT '{}',
                        restart_attempt INT NOT NULL DEFAULT 0,
                        tool_job_anchor_json JSONB NOT NULL DEFAULT '{}'
                    )
                    """);
        }

        Configuration configuration = new Configuration(
                new Environment("postgres-test", new JdbcTransactionFactory(), dataSource)
        );
        String mapperResource = "mapper/AgentRunMapper.xml";
        try (InputStream mapperXml = Resources.getResourceAsStream(mapperResource)) {
            new XMLMapperBuilder(
                    mapperXml,
                    configuration,
                    mapperResource,
                    configuration.getSqlFragments()
            ).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void expiredLeaseTakeoverRequiresMatchingOperationOwnerAndDatabaseExpiry() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            insertRun(mapper, "future-lease", AgentRunStatus.EXECUTING, null,
                    liveDagAnchor(
                            "future-lease:call-1:1",
                            Instant.parse("2999-01-01T00:00:00Z")));
            insertRun(mapper, "expired-lease", AgentRunStatus.EXECUTING, null,
                    liveDagAnchor(
                            "expired-lease:call-1:1",
                            Instant.parse("2000-01-01T00:00:00Z")));
            ToolJobAnchor promotedAnchor = ToolJobAnchor.fromJson(
                    liveDagAnchor(
                            "expired-lease:call-1:1",
                            Instant.parse("2000-01-01T00:00:00Z")));
            promotedAnchor.setRunDisposition("DAG_BLOCKING_WORKER_LOST");
            String promoted = promotedAnchor.toJson();

            assertThat(mapper.promoteExpiredDagBlockingWorkerLost(
                    "future-lease", promoted, "future-lease:call-1:1", "owner-1")).isZero();
            assertThat(mapper.promoteExpiredDagBlockingWorkerLost(
                    "expired-lease", promoted, "expired-lease:call-1:1", "owner-other")).isZero();
            assertThat(mapper.promoteExpiredDagBlockingWorkerLost(
                    "expired-lease", promoted, "expired-lease:call-1:1", "owner-1")).isEqualTo(1);

            assertThat(mapper.findById("future-lease").getToolJobAnchorJson())
                    .contains("DAG_BLOCKING_NO_RESUME");
            assertThat(mapper.findById("expired-lease").getToolJobAnchorJson())
                    .contains("DAG_BLOCKING_WORKER_LOST");
        }
    }

    @Test
    void preparingCleanupCasCannotOverwriteAttachedOrTerminalWinner() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            insertRun(mapper, "preparing-cas", AgentRunStatus.FAILED, "pipeline_failed",
                    preparingAnchor("preparing-cas"));
            insertRun(mapper, "winner-attached", AgentRunStatus.EXECUTING, null,
                    attachedAnchor("winner-attached", "ATTACHED"));
            insertRun(mapper, "winner-terminal", AgentRunStatus.CANCELED, "user_canceled",
                    attachedAnchor("winner-terminal", "TERMINAL"));

            assertThat(mapper.updateDagCleanupPreparingToolJobAnchor(
                    "preparing-cas",
                    attachedAnchor("preparing-cas", "ATTACHED"),
                    "preparing-cas:call-1:1",
                    "owner-1",
                    "sha256:wrong")).isZero();
            assertThat(mapper.updateDagCleanupPreparingToolJobAnchor(
                    "preparing-cas",
                    attachedAnchor("preparing-cas", "ATTACHED"),
                    "preparing-cas:call-1:1",
                    "owner-1",
                    "sha256:request")).isEqualTo(1);
            assertThat(mapper.findById("preparing-cas").getToolJobAnchorJson())
                    .contains("\"anchorState\": \"ATTACHED\"")
                    .contains("\"taskId\": \"task-1\"");

            assertThat(mapper.updateDagCleanupPreparingToolJobAnchor(
                    "winner-attached",
                    preparingAnchor("winner-attached"),
                    "winner-attached:call-1:1",
                    "owner-1",
                    "sha256:request")).isZero();
            assertThat(mapper.updateDagCleanupPreparingToolJobAnchor(
                    "winner-terminal",
                    preparingAnchor("winner-terminal"),
                    "winner-terminal:call-1:1",
                    "owner-1",
                    "sha256:request")).isZero();
            assertThat(mapper.findById("winner-attached").getToolJobAnchorJson())
                    .contains("\"anchorState\": \"ATTACHED\"");
            assertThat(mapper.findById("winner-terminal").getToolJobAnchorJson())
                    .contains("\"anchorState\": \"TERMINAL\"");
            assertThat(mapper.findById("winner-terminal").getStatus())
                    .isEqualTo(AgentRunStatus.CANCELED);
            assertThat(mapper.findById("winner-terminal").getLastError())
                    .isEqualTo("user_canceled");
        }
    }

    @Test
    void cleanupCompletionRequiresAllDurableProofs() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            insertRun(mapper, "proof-missing", AgentRunStatus.EXECUTING, null, """
                    {
                      "operationId":"proof-missing:call-1:1",
                      "blockingOwnerId":"owner-1",
                      "runDisposition":"DAG_BLOCKING_WORKER_LOST",
                      "autoResume":false,
                      "terminalStatus":"FAILED"
                    }
                    """);
            insertRun(mapper, "proof-complete", AgentRunStatus.EXECUTING, null,
                    proofAnchor("proof-complete"));

            assertThat(mapper.completeDagCleanupAndClearToolJobAnchor(
                    "proof-missing", "proof-missing:call-1:1", "owner-1",
                    "DAG_BLOCKING_WORKER_LOST")).isZero();
            assertThat(mapper.completeDagCleanupAndClearToolJobAnchor(
                    "proof-complete", "proof-complete:call-1:1", "owner-1",
                    "DAG_BLOCKING_WORKER_LOST")).isEqualTo(1);

            AgentRun failed = mapper.findById("proof-complete");
            assertThat(failed.getStatus()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(failed.getToolJobAnchorJson()).isEqualTo("{}");
            assertThat(failed.getLastError()).isEqualTo("DAG_BLOCKING_WORKER_LOST");
            assertThat(failed.getCompletedAt()).isNotNull();
            assertThat(mapper.findById("proof-missing").getToolJobAnchorJson())
                    .contains("proof-missing");
        }
    }

    @Test
    void failedAndCanceledCleanupPreserveOriginalBusinessOutcome() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            insertRun(mapper, "already-failed", AgentRunStatus.FAILED,
                    "pipeline_failed", proofAnchor("already-failed"));
            insertRun(mapper, "already-canceled", AgentRunStatus.CANCELED,
                    "user_canceled", proofAnchor("already-canceled"));

            assertThat(mapper.listActiveToolJobAnchors(20))
                    .extracting(AgentRun::getId)
                    .contains("already-failed", "already-canceled");
            assertThat(mapper.updateDagCleanupToolJobAnchor(
                    "already-failed", proofAnchor("already-failed"),
                    "already-failed:call-1:1", "owner-1")).isEqualTo(1);
            assertThat(mapper.updateDagCleanupToolJobAnchor(
                    "already-canceled", proofAnchor("already-canceled"),
                    "already-canceled:call-1:1", "owner-1")).isEqualTo(1);
            assertThat(mapper.completeDagCleanupAndClearToolJobAnchor(
                    "already-failed", "already-failed:call-1:1", "owner-1",
                    "DAG_BLOCKING_WORKER_LOST")).isEqualTo(1);
            assertThat(mapper.completeDagCleanupAndClearToolJobAnchor(
                    "already-canceled", "already-canceled:call-1:1", "owner-1",
                    "DAG_BLOCKING_WORKER_LOST")).isEqualTo(1);

            AgentRun failed = mapper.findById("already-failed");
            assertThat(failed.getStatus()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(failed.getLastError()).isEqualTo("pipeline_failed");
            assertThat(failed.getToolJobAnchorJson()).isEqualTo("{}");
            AgentRun canceled = mapper.findById("already-canceled");
            assertThat(canceled.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
            assertThat(canceled.getLastError()).isEqualTo("user_canceled");
            assertThat(canceled.getToolJobAnchorJson()).isEqualTo("{}");
        }
    }

    private static String proofAnchor(String runId) {
        return """
                {
                  "operationId":"%s:call-1:1",
                  "blockingOwnerId":"owner-1",
                  "runDisposition":"DAG_BLOCKING_WORKER_LOST",
                  "autoResume":false,
                  "anchorState":"TERMINAL",
                  "terminalStatus":"FAILED",
                  "terminalAt":"2026-07-30T00:00:00Z",
                  "finalizerStep":"EVENT",
                  "usagePersisted":true,
                  "terminalEventEmitted":true,
                  "reservationJson":"{\\"state\\":\\"RELEASED\\"}"
                }
                """.formatted(runId);
    }

    private static String liveDagAnchor(
            String operationId,
            Instant leaseUntil) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setBlockingOwnerId("owner-1");
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setRunDisposition("DAG_BLOCKING_NO_RESUME");
        anchor.setAutoResume(false);
        return anchor.toJson();
    }

    private static String preparingAnchor(String runId) {
        return """
                {
                  "operationId":"%s:call-1:1",
                  "blockingOwnerId":"owner-1",
                  "runDisposition":"DAG_BLOCKING_WORKER_LOST",
                  "autoResume":false,
                  "requestFingerprint":"sha256:request",
                  "anchorState":"PREPARING",
                  "reservationJson":"{\\"state\\":\\"PREPARING\\"}"
                }
                """.formatted(runId);
    }

    private static String attachedAnchor(String runId, String state) {
        return """
                {
                  "operationId":"%s:call-1:1",
                  "blockingOwnerId":"owner-1",
                  "runDisposition":"DAG_BLOCKING_WORKER_LOST",
                  "autoResume":false,
                  "requestFingerprint":"sha256:request",
                  "anchorState":"%s",
                  "taskId":"task-1",
                  "reservationJson":"{\\"state\\":\\"TASK_ATTACHED\\"}"
                }
                """.formatted(runId, state);
    }

    private static void insertRun(
            AgentRunMapper mapper,
            String runId,
            AgentRunStatus status,
            String lastError,
            String anchorJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        run.setStatus(status);
        run.setCurrentStep(1);
        run.setMaxSteps(12);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setLastError(lastError);
        run.setTtlExpiresAt(OffsetDateTime.now().plusMinutes(30));
        run.setExt("{}");
        run.setToolJobAnchorJson(anchorJson);
        assertThat(mapper.insert(run)).isEqualTo(1);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
