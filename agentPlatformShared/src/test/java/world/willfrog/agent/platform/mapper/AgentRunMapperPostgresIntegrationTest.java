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
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.io.InputStream;
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
