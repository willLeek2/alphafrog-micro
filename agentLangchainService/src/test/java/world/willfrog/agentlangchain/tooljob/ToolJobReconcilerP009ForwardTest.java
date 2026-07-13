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
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-09 forward: terminal status with unavailable result → retry budget →
 * RESULT_LOST → finalizer release.  Flips the reverse fixture.
 */
@Testcontainers
class ToolJobReconcilerP009ForwardTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String RUN_ID = "run-p009-fwd";

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static SqlSessionFactory sqlSessionFactory;
    private static DataSource dataSource;

    private SqlSession currentSession;

    @BeforeAll
    static void setUpInfra() throws Exception {
        dataSource = buildDataSource();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
                    id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64),
                    status VARCHAR(32) NOT NULL, current_step INT DEFAULT 0,
                    max_steps INT DEFAULT 20, plan_json JSONB DEFAULT '{}',
                    snapshot_json JSONB DEFAULT '{}', last_error TEXT,
                    ttl_expires_at TIMESTAMPTZ,
                    started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    completed_at TIMESTAMPTZ, ext JSONB DEFAULT '{}',
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
        if (redisConnectionFactory != null) redisConnectionFactory.destroy();
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @AfterEach
    void tearDown() {
        if (currentSession != null) { currentSession.close(); currentSession = null; }
    }

    private void seedAndVerify(String runId, int maxAttempts, long deadlineSeconds,
                                int seedAttempts, int seedConfSecondsAgo,
                                int expectedReleaseCallCount, int expectedTransitionCount,
                                boolean expectExhausted) throws Exception {
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(maxAttempts);
        config.setResultRetentionDeadlineSeconds(deadlineSeconds);
        config.setReconcilerIntervalMs(5000);
        config.setPollIntervalMs(100);

        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(runId, "tc", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-" + runId, Instant.now());
        String reservationJson = om.writeValueAsString(reservation);

        // Mock capacity service — always release successfully
        CapacityCountingFake capacityFake = new CapacityCountingFake();

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc");
        anchor.setAttempt(1);
        anchor.setTaskId("task-" + runId);
        anchor.setAutoResume(true);
        anchor.setReservationJson(reservationJson);
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        if (seedAttempts > 0) {
            anchor.setResultFetchState("PENDING");
            anchor.setTerminalConfirmedAt(Instant.now().minus(seedConfSecondsAgo, ChronoUnit.SECONDS));
            anchor.setResultFetchAttempts(seedAttempts);
        }

        insertRun(runId, "WAITING_TOOL_JOB", anchor.toJson());

        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config);
        injectHook(finalizer, "usageHook", (ToolJobUsageHook) (rid, a) -> true);
        injectHook(finalizer, "eventHook", (ToolJobEventHook) (rid, a) -> true);

        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config);
        injectSandboxStub(reconciler);

        resetDue(anchorService, redisCache, runId);
        reconciler.reconcileFromDue();

        AgentRun run = mapper.findById(runId);
        ToolJobAnchor loaded = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());

        if (expectExhausted) {
            assertThat(loaded.getResultFetchState()).as("resultFetchState").isEqualTo("LOST");
            assertThat(capacityFake.releaseCallCount).as("releaseCallCount").isEqualTo(expectedReleaseCallCount);
            assertThat(capacityFake.transitionCount).as("transitionCount").isEqualTo(expectedTransitionCount);
        } else {
            assertThat(loaded.getResultFetchState()).as("resultFetchState PENDING").isEqualTo("PENDING");
            assertThat(loaded.getResultFetchAttempts()).as("attempts incremented").isEqualTo(seedAttempts + 1);
            assertThat(loaded.getTerminalConfirmedAt()).as("confirmedAt set").isNotNull();
            assertThat(capacityFake.releaseCallCount).as("no release").isEqualTo(0);
        }
    }

    @Test
    void firstNullSetsPendingAndConfirmedAt() throws Exception {
        // Fresh anchor (no prior retry) → first null → PENDING, attempts=1
        seedAndVerify("run-r1", 10, 600, 0, 0, 0, 0, false);
    }

    @Test
    void retryIncrementsAttemptsWithoutRelease() throws Exception {
        // Anchors with 1 prior attempt → null → attempt=2, still no release (max=10)
        seedAndVerify("run-r2", 10, 600, 1, 30, 0, 0, false);
    }

    @Test
    void exhaustionByMaxAttemptsTriggersRelease() throws Exception {
        // Anchors with 2 prior attempts → 3rd null (max=3) → exhaustion → release
        seedAndVerify("run-r3", 3, 600, 2, 30, 1, 1, true);
    }

    @Test
    void exhaustionByDeadlineTriggersRelease() throws Exception {
        // Anchors with 1 attempt + confirmed 5s ago + deadline=1s → exhaustion
        seedAndVerify("run-r4", 100, 1, 1, 5, 1, 1, true);
    }

    @Test
    void lostReentryIsIdempotent() throws Exception {
        // First: exhaust
        seedAndVerify("run-r5", 2, 600, 1, 30, 1, 1, true);

        // Second: reentry with fresh services (simulates restart after crash before USAGE/EVENT)
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(2);
        config.setResultRetentionDeadlineSeconds(600);
        config.setReconcilerIntervalMs(5000);
        config.setPollIntervalMs(100);

        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-r5", "tc", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-run-r5", Instant.now());

        CapacityCountingFake capacityFake2 = new CapacityCountingFake();
        AgentRunMapper mapper2 = newMapper();
        ToolJobAnchorService anchorService2 = new ToolJobAnchorService(mapper2);
        ToolJobRedisCache redisCache2 = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService2 = new ToolJobResumeService(
                anchorService2, redisCache2, config, om);
        ToolJobFinalizer finalizer2 = new ToolJobFinalizer(
                anchorService2, redisCache2, capacityFake2, resumeService2, config);
        injectHook(finalizer2, "usageHook", (ToolJobUsageHook) (rid, a) -> true);
        injectHook(finalizer2, "eventHook", (ToolJobEventHook) (rid, a) -> true);

        ToolJobReconciler reconciler2 = new ToolJobReconciler(
                redisCache2, anchorService2, finalizer2, resumeService2, config);
        injectSandboxStub(reconciler2);

        resetDue(anchorService2, redisCache2, "run-r5");
        reconciler2.reconcileFromDue();

        // handleTerminal was called again, but RELEASE is skipped (isStepDone=true)
        // So releaseReservation is never called on the fresh capacity ledger
        assertThat(capacityFake2.releaseCallCount).as("release skipped after reentry").isEqualTo(0);
        assertThat(capacityFake2.transitionCount).as("no transition").isEqualTo(0);
    }

    // ---- Helpers ----

    private static DataSource buildDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private AgentRunMapper newMapper() throws Exception {
        if (currentSession != null) { currentSession.close(); currentSession = null; }
        if (sqlSessionFactory == null) {
            var c = new org.apache.ibatis.session.Configuration();
            c.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource);
            c.setEnvironment(env);
            c.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (java.io.Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, c, res, c.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(c);
        }
        currentSession = sqlSessionFactory.openSession(true);
        return currentSession.getMapper(AgentRunMapper.class);
    }

    private void insertRun(String id, String status, String anchorJson) throws Exception {
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, status, tool_job_anchor_json) "
                             + "VALUES (?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, status);
            ps.setString(3, anchorJson);
            ps.executeUpdate();
        }
    }

    private void resetDue(ToolJobAnchorService anchorService,
                           ToolJobRedisCache redisCache, String runId) {
        ToolJobAnchor a = anchorService.loadAnchor(runId);
        if (a != null) {
            a.setNextPollAt(Instant.now().minusSeconds(60));
            anchorService.updateAnchor(runId, a, AgentRunStatus.WAITING_TOOL_JOB);
            redisCache.upsertDue(runId, a);
        }
    }

    private static void injectSandboxStub(ToolJobReconciler reconciler) throws Exception {
        Field field = ToolJobReconciler.class.getDeclaredField("sandboxService");
        field.setAccessible(true);
        field.set(reconciler, new ResultUnavailableStub());
    }

    private static void injectHook(Object target, String fieldName, Object hook) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, hook);
    }

    private static class ResultUnavailableStub implements PythonSandboxService {
        @Override
        public TaskStatusResponse getTaskStatus(GetTaskStatusRequest r) {
            return TaskStatusResponse.newBuilder().setTaskId(r.getTaskId()).setStatus("SUCCEEDED").build();
        }
        @Override public CompletableFuture<TaskStatusResponse> getTaskStatusAsync(GetTaskStatusRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public TaskResultResponse getTaskResult(GetTaskResultRequest r) {
            throw new RuntimeException("Result temporarily unavailable");
        }
        @Override public CompletableFuture<TaskResultResponse> getTaskResultAsync(GetTaskResultRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public ExecuteResponse createTask(ExecuteRequest r) { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<ExecuteResponse> createTaskAsync(ExecuteRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public GetTaskByOperationIdResponse getTaskByOperationId(GetTaskByOperationIdRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public CompletableFuture<GetTaskByOperationIdResponse> getTaskByOperationIdAsync(
                GetTaskByOperationIdRequest r) { throw new UnsupportedOperationException(); }
    }

    /**
     * Simple capacity fake: always returns RELEASED on releaseReservation.
     * Avoids buildEnvelope complexity in the forward test.
     */
    static class CapacityCountingFake implements DataAnalysisCapacityService {
        int releaseCallCount;
        int transitionCount;

        @Override
        public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request) {
            releaseCallCount++;
            transitionCount++;
            return DataAnalysisReleaseOutcome.RELEASED;
        }

        @Override public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation r) {
            return DataAnalysisRestoreOutcome.ADDED;
        }
        @Override public DataAnalysisReservation reserve(DataAnalysisOperationIdentity i, DataAnalysisEstimate e) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisCapacityRecoveryReport recover(
                List<DataAnalysisReservation> dr, int cmu, int cmha) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisAdmissionState admissionState() { throw new UnsupportedOperationException(); }
    }
}
