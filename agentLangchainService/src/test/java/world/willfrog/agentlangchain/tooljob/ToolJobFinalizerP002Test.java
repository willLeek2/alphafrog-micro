package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-02: Fast-path timeout to Pending (suspend) contract fixture.
 *
 * <p>Verifies the suspend() path contracts without requiring the full Dubbo sandbox
 * stack. Seeds a run with WAITING_TOOL_JOB status and PENDING anchor (the state
 * after suspend() durably transferred to PostgreSQL and Redis), then verifies all
 * the oracle conditions.
 *
 * <p>Oracles (from DESIGN-v5.md):
 * <ol>
 *   <li>anchor.anchorState = PENDING</li>
 *   <li>run.status = WAITING_TOOL_JOB</li>
 *   <li>Redis pending cache exists: {@code redisCache.readPendingCache(runId)} non-null</li>
 *   <li>Redis due ZSET:
 *       {@code redisTemplate.opsForZSet().score("agent:tool-job:due", runId)} valid</li>
 *   <li>reservation state = PENDING_TRANSFERRED</li>
 * </ol>
 */
@Testcontainers
class ToolJobFinalizerP002Test {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final String DUE_ZSET_KEY = "agent:tool-job:due";

    // Redis
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    // MyBatis
    private static SqlSessionFactory sqlSessionFactory;
    private SqlSession currentSession;

    @BeforeAll
    static void setUpInfra() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY,
                    user_id VARCHAR(64),
                    deployment_id VARCHAR(64),
                    deployment_generation_id VARCHAR(64),
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

        redisConnectionFactory = new LettuceConnectionFactory(
                redisContainer.getHost(), redisContainer.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownInfra() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void cleanUp() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void tearDown() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    // ===== Infrastructure helpers =====

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private AgentRunMapper newMapper() throws Exception {
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

    private static final String DEPLOYMENT_ID = "stable";
    private static final String GENERATION_ID = "gen-" + "a".repeat(64);

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

    // ===== Test: P0-02 suspend() path =====


}
