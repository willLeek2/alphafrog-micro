package world.willfrog.agentlangchain.tooljob;

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
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P0-04: Redis eviction to DB anchor rebuild.
 * <p>
 * Verifies that when the Redis pending cache and due ZSET entries for a
 * WAITING_TOOL_JOB run are deleted (simulating Redis eviction/restart),
 * {@link ToolJobReconciler#rebuildFromAnchors()} reconstructs them from
 * the durable {@code tool_job_anchor_json} column in PostgreSQL.
 * <p>
 * Fixture:
 * <ol>
 *   <li>Seed a run with status=WAITING_TOOL_JOB and a PENDING anchor with a
 *       valid reservation, nextPollAt in the future</li>
 *   <li>Write to Redis via {@code atomicWritePendingAndDue}</li>
 *   <li>Delete only this case's Redis keys</li>
 *   <li>Call {@code rebuildFromAnchors()} directly</li>
 * </ol>
 */
@Testcontainers
class ToolJobReconcilerP004Test {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String RUN_ID = "run-p004";
    private static final String DUE_ZSET_KEY = "agent:tool-job:due";
    private static final String PENDING_KEY = "agent:run:" + RUN_ID + ":pending_tool_job";

    // Redis infra — shared across tests
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    // MyBatis — per-test session
    private static SqlSessionFactory sqlSessionFactory;
    private SqlSession currentSession;

    private AgentRunMapper mapper;
    private ToolJobRedisCache redisCache;
    private ToolJobReconciler reconciler;

    // ---- Infrastructure lifecycle ----

    @BeforeAll
    static void setUpInfra() throws Exception {
        // PostgreSQL schema (same DDL as ToolJobAnchorMapperIntegrationTest)
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
                    execution_checkpoint_json JSONB NOT NULL DEFAULT '{}',
                    restart_attempt INT NOT NULL DEFAULT 0,
                    tool_job_anchor_json JSONB DEFAULT '{}'
                )""");
        }

        // Redis connection via Lettuce (Spring Boot starter default)
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

    // ---- Per-test lifecycle ----

    @BeforeEach
    void setUp() throws Exception {
        // Clean PostgreSQL
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }

        // Clean Redis
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // MyBatis mapper
        mapper = newMapper();

        // Real services
        ToolJobConfig config = new ToolJobConfig();
        redisCache = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);

        // ToolJobResumeService: real instance; its tryResume is only called for
        // listResumeReadyAnchors (status=RECEIVED), which does not match our
        // WAITING_TOOL_JOB run, so it is never invoked in this test.
        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);

        // ToolJobFinalizer: stub — rebuildFromAnchors() never calls the finalizer.
        // DataAnalysisCapacityService methods are never invoked in this test.
        DataAnalysisCapacityService capacityService = new DataAnalysisCapacityService() {
            @Override
            public DataAnalysisReservation reserve(DataAnalysisOperationIdentity i, DataAnalysisEstimate e) {
                return null;
            }
            @Override
            public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation r) {
                return DataAnalysisRestoreOutcome.CONFLICT;
            }
            @Override
            public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest r) {
                return DataAnalysisReleaseOutcome.CONFLICT;
            }
            @Override
            public DataAnalysisCapacityRecoveryReport recover(
                    List<DataAnalysisReservation> dr, int cmu, int cmha) {
                return null;
            }
            @Override
            public DataAnalysisAdmissionState admissionState() {
                return DataAnalysisAdmissionState.OPEN;
            }
        };
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityService, resumeService, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));

        reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config);
    }

    @AfterEach
    void tearDown() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    // ---- Test ----

    @Test
    void shouldRebuildRedisFromDbAnchorAfterEviction() throws Exception {
        // Step 1: Seed — insert run with WAITING_TOOL_JOB status and a PENDING anchor
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(RUN_ID + ":tc-1:1");
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p004");
        anchor.setReservationJson(
                "{\"reservationId\":\"res-p004\",\"identity\":{\"runId\":\"" + RUN_ID + "\"}}");
        // nextPollAt in the future prevents processItem() from being called,
        // which would NPE on the uninitialized Dubbo sandboxService reference.
        anchor.setNextPollAt(Instant.now().plusSeconds(3600));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(RUN_ID, "WAITING_TOOL_JOB", anchor.toJson());

        // Step 2: Write to Redis (simulating the normal dispatch→pending flow)
        boolean written = redisCache.atomicWritePendingAndDue(RUN_ID, anchor);
        assertThat(written).isTrue();

        // Precondition: Redis has the data
        assertThat(redisCache.readPendingCache(RUN_ID)).isNotNull();
        assertThat(redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID)).isNotNull();

        // Step 3: Evict only this case's keys
        redisCache.deletePendingCache(RUN_ID);
        redisCache.removeDue(RUN_ID);

        // Confirm eviction
        assertThat(redisCache.readPendingCache(RUN_ID)).isNull();
        assertThat(redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID)).isNull();

        // Step 4: Rebuild from DB anchors
        reconciler.rebuildFromAnchors();

        // ---- Oracles ----

        // Oracle 1: anchor.anchorState still PENDING in DB after eviction
        AgentRun run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");

        // Oracle 2: Redis pending cache rebuilt
        ToolJobAnchor cached = redisCache.readPendingCache(RUN_ID);
        assertThat(cached).isNotNull();
        assertThat(cached.getAnchorState()).isEqualTo("PENDING");
        assertThat(cached.getTaskId()).isEqualTo("task-p004");

        // Oracle 3: Redis key is literal "agent:run:" + runId + ":pending_tool_job"
        assertThat(redisTemplate.hasKey(PENDING_KEY)).isTrue();

        // Oracle 4: Redis TTL set on pending key (> 0)
        Long ttl = redisTemplate.getExpire(PENDING_KEY);
        assertThat(ttl).isNotNull().isGreaterThan(0L);

        // Oracle 5: due ZSET key is "agent:tool-job:due", score valid
        assertThat(redisTemplate.hasKey(DUE_ZSET_KEY)).isTrue();
        Double score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID);
        assertThat(score).isNotNull().isGreaterThan(0.0);

        // Oracle 6: No duplicate due entry (ZSET member is unique by design;
        //           verify the runId is present in the ZSET)
        Long rank = redisTemplate.opsForZSet().rank(DUE_ZSET_KEY, RUN_ID);
        assertThat(rank).isNotNull(); // present exactly once
    }

    // ---- Helpers ----

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
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, res, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                    .build(config);
        }
        currentSession = sqlSessionFactory.openSession(true);
        return currentSession.getMapper(AgentRunMapper.class);
    }

    private static void insertRun(String id, String status, String anchorJson)
            throws Exception {
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
}
