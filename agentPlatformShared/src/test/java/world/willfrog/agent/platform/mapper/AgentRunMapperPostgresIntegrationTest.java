package world.willfrog.agent.platform.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production mapper contract against PostgreSQL's JSONB and NOT NULL semantics.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunMapperPostgresIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static SqlSessionFactory sqlSessionFactory;
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

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
                        tool_job_anchor_json JSONB NOT NULL DEFAULT '{}'
                    )
                    """);
            statement.execute("""
                    CREATE TABLE alphafrog_agent_run_event (
                        id BIGSERIAL PRIMARY KEY,
                        run_id VARCHAR(64) NOT NULL,
                        seq BIGINT NOT NULL DEFAULT 1,
                        event_type VARCHAR(64) NOT NULL,
                        payload_json JSONB NOT NULL DEFAULT '{}',
                        dedupe_key VARCHAR(255),
                        created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
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
    void insertNullToolJobAnchorPersistsEmptyObjectInsteadOfSqlNull() {
        AgentRun run = new AgentRun();
        run.setId("null-anchor-run");
        run.setUserId("user-1");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setCurrentStep(0);
        run.setMaxSteps(12);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setTtlExpiresAt(OffsetDateTime.now().plusMinutes(30));
        run.setExt("{}");
        run.setToolJobAnchorJson(null);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.insert(run)).isEqualTo(1);
            assertThat(mapper.findById(run.getId()).getToolJobAnchorJson()).isEqualTo("{}");
        }
    }

    @Test
    void synchronousClearRequiresTerminalReleasedUsageProofAndOwnership() throws Exception {
        insertRun("sync-clear-valid", AgentRunStatus.EXECUTING,
                synchronousAnchor("sync-clear-valid:call-1:1", "TERMINAL", "RELEASED", true));
        insertRun("sync-clear-attached", AgentRunStatus.EXECUTING,
                synchronousAnchor("sync-clear-attached:call-1:1", "ATTACHED", "RELEASED", true));
        insertRun("sync-clear-not-released", AgentRunStatus.EXECUTING,
                synchronousAnchor("sync-clear-not-released:call-1:1", "TERMINAL", "TERMINAL_CONFIRMED", true));
        insertRun("sync-clear-no-usage", AgentRunStatus.EXECUTING,
                synchronousAnchor("sync-clear-no-usage:call-1:1", "TERMINAL", "RELEASED", false));

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.clearSynchronouslyCompletedToolJobAnchor(
                    "sync-clear-valid", AgentRunStatus.WAITING_TOOL_JOB,
                    "sync-clear-valid:call-1:1")).isZero();
            assertThat(mapper.clearSynchronouslyCompletedToolJobAnchor(
                    "sync-clear-valid", AgentRunStatus.EXECUTING,
                    "sync-clear-valid:stale:1")).isZero();
            assertThat(mapper.clearSynchronouslyCompletedToolJobAnchor(
                    "sync-clear-attached", AgentRunStatus.EXECUTING,
                    "sync-clear-attached:call-1:1")).isZero();
            assertThat(mapper.clearSynchronouslyCompletedToolJobAnchor(
                    "sync-clear-not-released", AgentRunStatus.EXECUTING,
                    "sync-clear-not-released:call-1:1")).isZero();
            assertThat(mapper.clearSynchronouslyCompletedToolJobAnchor(
                    "sync-clear-no-usage", AgentRunStatus.EXECUTING,
                    "sync-clear-no-usage:call-1:1")).isZero();
            assertThat(mapper.clearSynchronouslyCompletedToolJobAnchor(
                    "sync-clear-valid", AgentRunStatus.EXECUTING,
                    "sync-clear-valid:call-1:1")).isEqualTo(1);
            assertThat(mapper.findById("sync-clear-valid").getToolJobAnchorJson())
                    .isEqualTo("{}");
        }
    }

    @Test
    void liveDagUpdateRequiresStatusOperationOwnerExactLeaseAndUnexpiredLease() throws Exception {
        Instant liveLease = Instant.now().plusSeconds(300);
        Instant renewedLease = liveLease.plusSeconds(30);
        insertRun("dag-live-fence", AgentRunStatus.EXECUTING,
                liveDagAnchor("dag-live-fence:call-1:1", "worker-a", liveLease));
        insertRun("dag-preparing-abort", AgentRunStatus.EXECUTING,
                liveDagAnchor(
                        "dag-preparing-abort:call-1:1", "worker-a", liveLease, "PREPARING"));
        insertRun("dag-direct-promote", AgentRunStatus.EXECUTING,
                liveDagAnchor(
                        "dag-direct-promote:call-1:1", "worker-a", liveLease));
        Instant expiredLease = Instant.now().minusSeconds(5);
        insertRun("dag-expired-fence", AgentRunStatus.EXECUTING,
                liveDagAnchor("dag-expired-fence:call-1:1", "worker-a", expiredLease));

        String renewedAnchor = liveDagAnchor(
                "dag-live-fence:call-1:1", "worker-a", renewedLease);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-live-fence", renewedAnchor, AgentRunStatus.WAITING_TOOL_JOB,
                    "dag-live-fence:call-1:1", "worker-a", liveLease.toString()))
                    .isZero();
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-live-fence", renewedAnchor, AgentRunStatus.EXECUTING,
                    "dag-live-fence:stale:1", "worker-a", liveLease.toString()))
                    .isZero();
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-live-fence", renewedAnchor, AgentRunStatus.EXECUTING,
                    "dag-live-fence:call-1:1", "worker-b", liveLease.toString()))
                    .isZero();
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-live-fence", renewedAnchor, AgentRunStatus.EXECUTING,
                    "dag-live-fence:call-1:1", "worker-a", renewedLease.toString()))
                    .isZero();
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-expired-fence",
                    liveDagAnchor("dag-expired-fence:call-1:1", "worker-a",
                            expiredLease.plusSeconds(30)),
                    AgentRunStatus.EXECUTING,
                    "dag-expired-fence:call-1:1", "worker-a", expiredLease.toString()))
                    .isZero();
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-live-fence", renewedAnchor, AgentRunStatus.EXECUTING,
                    "dag-live-fence:call-1:1", "worker-a", liveLease.toString()))
                    .isEqualTo(1);

            ToolJobAnchor directPromote = ToolJobAnchor.fromJson(
                    liveDagAnchor(
                            "dag-direct-promote:call-1:1", "worker-a", liveLease));
            directPromote.setRunDisposition("DAG_BLOCKING_WORKER_LOST");
            assertThat(mapper.updateLiveDagBlockingToolJobAnchor(
                    "dag-direct-promote",
                    directPromote.toJson(),
                    AgentRunStatus.EXECUTING,
                    "dag-direct-promote:call-1:1",
                    "worker-a",
                    liveLease.toString())).isEqualTo(1);

            ToolJobAnchor persisted = ToolJobAnchor.fromJson(
                    mapper.findById("dag-live-fence").getToolJobAnchorJson());
            assertThat(persisted.getBlockingOwnerId()).isEqualTo("worker-a");
            assertThat(persisted.getBlockingLeaseUntil()).isEqualTo(renewedLease);

            String abortingAnchor = abortingDagAnchor(
                    "dag-preparing-abort:call-1:1", "worker-a", liveLease);
            assertThat(mapper.beginLiveDagBlockingPreparingAbort(
                    "dag-preparing-abort", abortingAnchor, AgentRunStatus.EXECUTING,
                    "dag-preparing-abort:call-1:1", "worker-b", liveLease.toString()))
                    .isZero();
            assertThat(mapper.beginLiveDagBlockingPreparingAbort(
                    "dag-preparing-abort", abortingAnchor, AgentRunStatus.EXECUTING,
                    "dag-preparing-abort:call-1:1", "worker-a", renewedLease.toString()))
                    .isZero();
            assertThat(mapper.beginLiveDagBlockingPreparingAbort(
                    "dag-live-fence", abortingAnchor, AgentRunStatus.EXECUTING,
                    "dag-live-fence:call-1:1", "worker-a", renewedLease.toString()))
                    .isZero();
            assertThat(mapper.beginLiveDagBlockingPreparingAbort(
                    "dag-preparing-abort", abortingAnchor, AgentRunStatus.EXECUTING,
                    "dag-preparing-abort:call-1:1", "worker-a", liveLease.toString()))
                    .isEqualTo(1);
            ToolJobAnchor aborting = ToolJobAnchor.fromJson(
                    mapper.findById("dag-preparing-abort").getToolJobAnchorJson());
            assertThat(aborting.getAnchorState()).isEqualTo("ABORTING");
            assertThat(aborting.getRunDisposition()).isEqualTo("DAG_BLOCKING_PREPARING_ABORT");
            assertThat(aborting.getReservationJson()).contains("\"state\":\"RELEASED\"");
        }

        // 模拟 begin 已提交后进程崩溃：新 session 可在 lease 到期无关的情况下重入清理。
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.casUpdateStatus(
                    "dag-preparing-abort", AgentRunStatus.FAILED, AgentRunStatus.EXECUTING))
                    .isEqualTo(1);
            assertThat(mapper.listActiveToolJobAnchors(20))
                    .extracting(AgentRun::getId)
                    .contains("dag-preparing-abort");
            assertThat(mapper.completeLiveDagBlockingPreparingAbort(
                    "dag-preparing-abort", AgentRunStatus.EXECUTING,
                    "dag-preparing-abort:call-1:1", "worker-b", liveLease.toString()))
                    .isZero();
            assertThat(mapper.completeLiveDagBlockingPreparingAbort(
                    "dag-preparing-abort", AgentRunStatus.EXECUTING,
                    "dag-preparing-abort:call-1:1", "worker-a", renewedLease.toString()))
                    .isZero();
            assertThat(mapper.completeLiveDagBlockingPreparingAbort(
                    "dag-preparing-abort", AgentRunStatus.EXECUTING,
                    "dag-preparing-abort:call-1:1", "worker-a", liveLease.toString()))
                    .isEqualTo(1);
            assertThat(mapper.findById("dag-preparing-abort").getToolJobAnchorJson())
                    .isEqualTo("{}");
            assertThat(mapper.findById("dag-preparing-abort").getStatus())
                    .isEqualTo(AgentRunStatus.FAILED);
        }
    }

    @Test
    void liveDagSynchronousClearRequiresExactLiveLeaseAndDurableEvent() throws Exception {
        Instant liveLease = Instant.now().plusSeconds(300);
        Instant expiredLease = Instant.now().minusSeconds(5);
        insertRun("dag-sync-valid", AgentRunStatus.EXECUTING,
                terminalLiveDagAnchor(
                        "dag-sync-valid:call-1:1", "call-1", "worker-a", liveLease));
        insertRun("dag-sync-no-event", AgentRunStatus.EXECUTING,
                terminalLiveDagAnchor(
                        "dag-sync-no-event:call-1:1", "call-1", "worker-a", liveLease));
        insertRun("dag-sync-expired", AgentRunStatus.EXECUTING,
                terminalLiveDagAnchor(
                        "dag-sync-expired:call-1:1", "call-1", "worker-a", expiredLease));
        insertEvent("dag-sync-valid", "call-1");
        insertEvent("dag-sync-expired", "call-1");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
            assertThat(mapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                    "dag-sync-valid",
                    "dag-sync-valid:call-1:1",
                    "worker-b",
                    liveLease.toString())).isZero();
            assertThat(mapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                    "dag-sync-valid",
                    "dag-sync-valid:call-1:1",
                    "worker-a",
                    liveLease.plusSeconds(30).toString())).isZero();
            assertThat(mapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                    "dag-sync-no-event",
                    "dag-sync-no-event:call-1:1",
                    "worker-a",
                    liveLease.toString())).isZero();
            assertThat(mapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                    "dag-sync-expired",
                    "dag-sync-expired:call-1:1",
                    "worker-a",
                    expiredLease.toString())).isZero();
            assertThat(mapper.clearLiveDagBlockingSynchronouslyCompletedToolJobAnchor(
                    "dag-sync-valid",
                    "dag-sync-valid:call-1:1",
                    "worker-a",
                    liveLease.toString())).isEqualTo(1);
            assertThat(mapper.findById("dag-sync-valid").getToolJobAnchorJson())
                    .isEqualTo("{}");
        }
    }

    private static String synchronousAnchor(
            String operationId,
            String anchorState,
            String reservationState,
            boolean usagePersisted) throws Exception {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("operationId", operationId);
        anchor.put("anchorState", anchorState);
        anchor.put("usagePersisted", usagePersisted);
        anchor.put("reservationJson",
                OBJECT_MAPPER.writeValueAsString(Map.of("state", reservationState)));
        return OBJECT_MAPPER.writeValueAsString(anchor);
    }

    private static String liveDagAnchor(
            String operationId,
            String ownerId,
            Instant leaseUntil) throws Exception {
        return liveDagAnchor(operationId, ownerId, leaseUntil, "ATTACHED");
    }

    private static String liveDagAnchor(
            String operationId,
            String ownerId,
            Instant leaseUntil,
            String anchorState) throws Exception {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setAnchorState(anchorState);
        anchor.setRunDisposition("DAG_BLOCKING_NO_RESUME");
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId(ownerId);
        anchor.setBlockingLeaseUntil(leaseUntil);
        return anchor.toJson();
    }

    private static String abortingDagAnchor(
            String operationId,
            String ownerId,
            Instant leaseUntil) throws Exception {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition("DAG_BLOCKING_PREPARING_ABORT");
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId(ownerId);
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setReservationJson(
                OBJECT_MAPPER.writeValueAsString(Map.of("state", "RELEASED")));
        return anchor.toJson();
    }

    private static String terminalLiveDagAnchor(
            String operationId,
            String toolCallId,
            String ownerId,
            Instant leaseUntil) throws Exception {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setToolCallId(toolCallId);
        anchor.setAnchorState("TERMINAL");
        anchor.setRunDisposition("DAG_BLOCKING_NO_RESUME");
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId(ownerId);
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalAt(Instant.now());
        anchor.setFinalizerStep("USAGE");
        anchor.setUsagePersisted(true);
        anchor.setReservationJson(
                OBJECT_MAPPER.writeValueAsString(Map.of("state", "RELEASED")));
        return anchor.toJson();
    }

    private static void insertEvent(String runId, String toolCallId) throws Exception {
        try (var connection = dataSource().getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO alphafrog_agent_run_event
                         (run_id, event_type, dedupe_key)
                     VALUES (?, 'TOOL_CALL_FINISHED', ?)
                     """)) {
            statement.setString(1, runId);
            statement.setString(
                    2, runId + ":" + toolCallId + ":logical_terminal");
            statement.executeUpdate();
        }
    }

    private static void insertRun(
            String runId,
            AgentRunStatus status,
            String anchorJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("user-1");
        run.setStatus(status);
        run.setCurrentStep(0);
        run.setMaxSteps(12);
        run.setPlanJson("{}");
        run.setSnapshotJson("{}");
        run.setTtlExpiresAt(OffsetDateTime.now().plusMinutes(30));
        run.setExt("{}");
        run.setToolJobAnchorJson(anchorJson);
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(session.getMapper(AgentRunMapper.class).insert(run)).isEqualTo(1);
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
