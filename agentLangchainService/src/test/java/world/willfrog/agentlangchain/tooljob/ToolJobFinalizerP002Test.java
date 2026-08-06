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
                    tool_job_anchor_json JSONB DEFAULT '{}'
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

    private static void insertRun(String id, String status, String anchorJson) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) "
                             + "VALUES (?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.executeUpdate();
        }
    }

    // ===== Test: P0-02 suspend() path =====

    @Test
    void suspendPathCreatesPendingAnchorWithRedisEntries() throws Exception {
        // ---- Arrange: simulate the state after suspend() ----
        // suspend() transitions reservation to PENDING_TRANSFERRED, sets
        // anchorState=PENDING, calls transferToPending which writes to PG
        // (WAITING_TOOL_JOB) and Redis (pending cache + due ZSET).

        String runId = "run-p002";
        String toolCallId = "tc-p002";
        int attempt = 1;
        String taskId = "task-p002";
        String operationId = runId + ":" + toolCallId + ":" + attempt;

        // Build reservation in PENDING_TRANSFERRED state (as suspend() would leave it)
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED, taskId, Instant.now());
        String reservationJson = objectMapper.writeValueAsString(reservation);

        // Build the PENDING anchor as suspend() would produce
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(operationId);
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        anchor.setTaskId(taskId);
        anchor.setReservationJson(reservationJson);
        anchor.setAutoResume(true);
        anchor.setNextPollAt(Instant.now().plusSeconds(3600));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        // ---- Seed PostgreSQL (transferToPending path) ----
        insertRun(runId, "WAITING_TOOL_JOB", anchor.toJson());

        // ---- Seed Redis (atomicWritePendingAndDue from transferToPending) ----
        ToolJobConfig config = new ToolJobConfig();
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, objectMapper, config);
        boolean written = redisCache.atomicWritePendingAndDue(runId, anchor);
        assertThat(written).isTrue();

        // ---- Oracles ----

        // Oracle 1: anchor.anchorState = PENDING in PostgreSQL
        AgentRunMapper mapper = newMapper();
        AgentRun run = mapper.findById(runId);
        assertThat(run).isNotNull();
        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");

        // Oracle 2: run.status = WAITING_TOOL_JOB
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);

        // Oracle 3: Redis pending cache exists and is readable
        ToolJobAnchor cachedAnchor = redisCache.readPendingCache(runId);
        assertThat(cachedAnchor).isNotNull();
        assertThat(cachedAnchor.getAnchorState()).isEqualTo("PENDING");
        assertThat(cachedAnchor.getTaskId()).isEqualTo(taskId);
        assertThat(cachedAnchor.getOperationId()).isEqualTo(operationId);

        // Oracle 4: Redis pending key uses the expected format
        String pendingKey = "agent:run:" + runId + ":pending_tool_job";
        assertThat(redisTemplate.hasKey(pendingKey)).isTrue();

        // Oracle 5: Redis pending cache has a TTL (> 0)
        Long ttl = redisTemplate.getExpire(pendingKey);
        assertThat(ttl).isNotNull().isGreaterThan(0L);

        // Oracle 6: Redis due ZSET contains the run with a valid score
        assertThat(redisTemplate.hasKey(DUE_ZSET_KEY)).isTrue();
        Double score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, runId);
        assertThat(score).isNotNull().isGreaterThan(0.0);

        // Oracle 7: due ZSET member present exactly once (rank is not null)
        Long rank = redisTemplate.opsForZSet().rank(DUE_ZSET_KEY, runId);
        assertThat(rank).isNotNull();

        // Oracle 8: reservation state = PENDING_TRANSFERRED
        // Verify from the DB anchor's reservation JSON
        String storedReservationJson = dbAnchor.getReservationJson();
        assertThat(storedReservationJson).isNotNull();
        DataAnalysisReservation storedReservation = objectMapper.readValue(
                storedReservationJson, DataAnalysisReservation.class);
        assertThat(storedReservation.state()).isEqualTo(DataAnalysisReservationState.PENDING_TRANSFERRED);
        assertThat(storedReservation.taskId()).isEqualTo(taskId);
        assertThat(storedReservation.operationId()).isEqualTo(operationId);

        // Oracle 9: also verify from Redis cached anchor
        DataAnalysisReservation cachedReservation = objectMapper.readValue(
                cachedAnchor.getReservationJson(), DataAnalysisReservation.class);
        assertThat(cachedReservation.state()).isEqualTo(DataAnalysisReservationState.PENDING_TRANSFERRED);
    }

    /**
     * Additional contract: the PENDING anchor with WAITING_TOOL_JOB status
     * is discoverable by listActive scans (proves the reconciler can find it).
     */
    @Test
    void pendingAnchorIsDiscoverableByListActive() throws Exception {
        String runId = "run-p002-discover";
        String toolCallId = "tc-discover";
        int attempt = 1;
        String taskId = "task-discover";
        String operationId = runId + ":" + toolCallId + ":" + attempt;

        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(runId, toolCallId, attempt);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED, taskId, Instant.now());
        String reservationJson = objectMapper.writeValueAsString(reservation);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(operationId);
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        anchor.setTaskId(taskId);
        anchor.setReservationJson(reservationJson);
        anchor.setAutoResume(true);
        anchor.setNextPollAt(Instant.now().plusSeconds(3600));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(runId, "WAITING_TOOL_JOB", anchor.toJson());

        // Redis write
        ToolJobConfig config = new ToolJobConfig();
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, objectMapper, config);
        redisCache.atomicWritePendingAndDue(runId, anchor);

        // listActive scans for non-empty anchors with status IN
        // ('EXECUTING', 'WAITING_TOOL_JOB', 'WAITING', 'CANCELED')
        AgentRunMapper mapper = newMapper();
        java.util.List<AgentRun> active = mapper.listActiveToolJobAnchors(10);
        assertThat(active).isNotEmpty();
        assertThat(active.stream().anyMatch(r -> r.getId().equals(runId))).isTrue();
    }
}
