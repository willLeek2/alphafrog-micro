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
import java.util.List;
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
                        deployment_id VARCHAR(64) NOT NULL,
                        deployment_generation_id VARCHAR(68) NOT NULL,
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
                        tool_job_anchor_json JSONB NOT NULL DEFAULT '{}',
                        scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                        plan_generation INT NOT NULL DEFAULT -1,
                        run_control_version BIGINT NOT NULL DEFAULT 0
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

    private static String clearingDagAnchor(
            String operationId,
            String ownerId,
            Instant leaseUntil) throws Exception {
        ToolJobAnchor anchor = ToolJobAnchor.fromJson(
                abortingDagAnchor(operationId, ownerId, leaseUntil));
        anchor.setCleanupSourceOwnerId(ownerId);
        anchor.setCleanupSourceLeaseUntil(leaseUntil);
        anchor.setAnchorState("CLEARING");
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
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId("gen-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
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
