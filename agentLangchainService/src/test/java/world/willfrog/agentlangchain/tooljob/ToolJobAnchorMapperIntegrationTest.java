package world.willfrog.agentlangchain.tooljob;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL integration tests using real MyBatis AgentRunMapper mapped statements.
 * Every test calls production AgentRunMapper methods — not hand-copied SQL.
 * Verifies JSONB predicates and ToolJobAnchor.fromJson roundtrip after each write.
 */
@Testcontainers
class ToolJobAnchorMapperIntegrationTest {

    private static final String DEPLOYMENT_ID = "stable";
    private static final String GENERATION_ID = "gen-" + "a".repeat(64);
    private static final DeploymentIdentity LOCAL_IDENTITY =
            new DeploymentIdentity(DEPLOYMENT_ID, GENERATION_ID);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String VALID_SNAPSHOT_JSON;
    private static final String VALID_SNAPSHOT_DIGEST;
    private static final String VALID_ESTIMATE_JSON;

    static {
        try {
            var snapshot = AgentRunDatasetSnapshot.empty();
            VALID_SNAPSHOT_JSON = om.writeValueAsString(snapshot);
            VALID_SNAPSHOT_DIGEST = snapshot.immutableDigest();

            var estimate = new DataAnalysisEstimate(0, 0, 0, 0.0, 0,
                    List.of(), DataAnalysisResourceClass.STANDARD, 1);
            VALID_ESTIMATE_JSON = om.writeValueAsString(estimate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeAll
    static void createTable() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64),
                    deployment_id VARCHAR(64),
                    deployment_generation_id VARCHAR(68),
                    lane_tag VARCHAR(128),
                    status VARCHAR(32) NOT NULL,
                    current_step INT DEFAULT 0,
                    max_steps INT DEFAULT 20,
                    plan_json JSONB DEFAULT '{}',
                    snapshot_json JSONB DEFAULT '{}',
                    last_error TEXT,
                    ttl_expires_at TIMESTAMPTZ,
                    started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMPTZ,
                    ext JSONB DEFAULT '{}',
                    execution_checkpoint_json JSONB NOT NULL DEFAULT '{}',
                    restart_attempt INT NOT NULL DEFAULT 0,
                    tool_job_anchor_json JSONB DEFAULT '{}',
                    scheduler_version VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
                    plan_generation INT NOT NULL DEFAULT -1,
                    run_control_version BIGINT NOT NULL DEFAULT 0
                )""");
        }
    }

    @AfterAll
    static void closeContainer() { /* @Container handles cleanup */ }

    private SqlSession currentSession;

    @AfterEach
    void closeSession() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    @BeforeEach
    void cleanTable() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private static SqlSessionFactory sqlSessionFactory;

    private AgentRunMapper newMapper() throws Exception {
        // Close previous session before opening a new one
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
        if (sqlSessionFactory == null) {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, res, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        }
        currentSession = sqlSessionFactory.openSession(true);
        return currentSession.getMapper(AgentRunMapper.class);
    }

    private static void insertRun(String id, String status, String anchorJson) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run "
                             + "(id, status, tool_job_anchor_json, deployment_id, "
                             + "deployment_generation_id) "
                             + "VALUES (?, ?, CAST(? AS jsonb), ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.setString(4, DEPLOYMENT_ID);
            ps.setString(5, GENERATION_ID);
            ps.executeUpdate();
        }
    }

    private static void updateLastError(String id, String lastError) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET last_error = ? WHERE id = ?")) {
            ps.setString(1, lastError);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static void updateUserId(String id, String userId) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET user_id = ? WHERE id = ?")) {
            ps.setString(1, userId);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static void ageUpdatedAt(String id, String interval) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET updated_at = CURRENT_TIMESTAMP - CAST(? AS interval) WHERE id = ?")) {
            ps.setString(1, interval);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    // ========== updateToolJobCheckpoint: atomic merge CAS ==========
















    // ========== casUpdateAnchorResumeState: claim CAS ==========





    // ========== clearToolJobAnchorWithToken ==========








    // ========== 260818: batch 20260818-182948 root causes ==========


















    // ========== Production service chain: version race ==========

    private ToolJobCheckpointService newServiceChain() throws Exception {
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        return new ToolJobCheckpointService(mapper, anchorService);
    }







}
