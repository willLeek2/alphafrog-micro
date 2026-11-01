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
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId("gen-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
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
