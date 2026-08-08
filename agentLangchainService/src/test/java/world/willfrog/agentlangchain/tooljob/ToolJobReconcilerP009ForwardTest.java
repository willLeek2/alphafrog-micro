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
                anchorService, redisCache, capacityFake, resumeService, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
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
                anchorService2, redisCache2, capacityFake2, resumeService2, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
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

    @Test
    void lostReentryAfterUsageFailureProvesCrashContinuation() throws Exception {
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(2);
        config.setResultRetentionDeadlineSeconds(600);
        config.setReconcilerIntervalMs(5000);
        config.setPollIntervalMs(100);

        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-crash", "tc", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-crash", Instant.now());

        // Phase 1: exhaust with failing USAGE hook → anchor stuck at RELEASE
        CapacityCountingFake capacity1 = new CapacityCountingFake();
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc");
        anchor.setAttempt(1);
        anchor.setTaskId("task-crash");
        anchor.setAutoResume(true);
        anchor.setReservationJson(om.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setResultFetchState("PENDING");
        anchor.setTerminalConfirmedAt(Instant.now().minusSeconds(30));
        anchor.setResultFetchAttempts(1);

        insertRun("run-crash", "WAITING_TOOL_JOB", anchor.toJson());

        AgentRunMapper mapper1 = newMapper();
        ToolJobAnchorService anchorService1 = new ToolJobAnchorService(mapper1);
        ToolJobRedisCache redisCache1 = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService1 = new ToolJobResumeService(
                anchorService1, redisCache1, config, om);

        ToolJobFinalizer finalizer1 = new ToolJobFinalizer(
                anchorService1, redisCache1, capacity1, resumeService1, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        // USAGE hook FAILS — simulates crash after RELEASE, before USAGE durable write
        injectHook(finalizer1, "usageHook", (ToolJobUsageHook) (rid, a) -> false);
        injectHook(finalizer1, "eventHook", (ToolJobEventHook) (rid, a) -> true);

        ToolJobReconciler reconciler1 = new ToolJobReconciler(
                redisCache1, anchorService1, finalizer1, resumeService1, config);
        injectSandboxStub(reconciler1);

        resetDue(anchorService1, redisCache1, "run-crash");
        reconciler1.reconcileFromDue();

        // Phase 1 assertions: RELEASE succeeded, USAGE blocked
        AgentRun run1 = mapper1.findById("run-crash");
        ToolJobAnchor a1 = ToolJobAnchor.fromJson(run1.getToolJobAnchorJson());
        assertThat(a1.getResultFetchState()).as("phase 1: LOST").isEqualTo("LOST");
        assertThat(a1.getFinalizerStep()).as("phase 1: stuck at RELEASE").isEqualTo("RELEASE");
        assertThat(a1.isUsagePersisted()).as("phase 1: usage not persisted").isFalse();
        assertThat(run1.getStatus()).as("phase 1: still WAITING_TOOL_JOB").isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(capacity1.releaseCallCount).as("phase 1: release called").isEqualTo(1);
        assertThat(capacity1.transitionCount).as("phase 1: one transition").isEqualTo(1);

        // Phase 2: fresh services + successful hooks → reentry continues from USAGE
        CapacityCountingFake capacity2 = new CapacityCountingFake();
        AgentRunMapper mapper2 = newMapper();
        ToolJobAnchorService anchorService2 = new ToolJobAnchorService(mapper2);
        ToolJobRedisCache redisCache2 = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService2 = new ToolJobResumeService(
                anchorService2, redisCache2, config, om);

        AtomicInteger usageCount2 = new AtomicInteger(0);
        AtomicInteger eventCount2 = new AtomicInteger(0);
        ToolJobFinalizer finalizer2 = new ToolJobFinalizer(
                anchorService2, redisCache2, capacity2, resumeService2, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        injectHook(finalizer2, "usageHook", (ToolJobUsageHook) (rid, a) -> {
            usageCount2.incrementAndGet();
            return true;
        });
        injectHook(finalizer2, "eventHook", (ToolJobEventHook) (rid, a) -> {
            eventCount2.incrementAndGet();
            return true;
        });

        ToolJobReconciler reconciler2 = new ToolJobReconciler(
                redisCache2, anchorService2, finalizer2, resumeService2, config);
        injectSandboxStub(reconciler2);

        resetDue(anchorService2, redisCache2, "run-crash");
        reconciler2.reconcileFromDue();

        // Phase 2 assertions: USAGE+EVENT now complete, finalizer advances
        AgentRun run2 = mapper2.findById("run-crash");
        ToolJobAnchor a2 = ToolJobAnchor.fromJson(run2.getToolJobAnchorJson());
        assertThat(usageCount2.get()).as("phase 2: usage called once").isEqualTo(1);
        assertThat(eventCount2.get()).as("phase 2: event called once").isEqualTo(1);
        assertThat(a2.getFinalizerStep()).as("phase 2: advanced to RESUME_READY").isEqualTo("RESUME_READY");
        assertThat(a2.isUsagePersisted()).as("phase 2: usage persisted").isTrue();
        assertThat(capacity2.releaseCallCount).as("phase 2: RELEASE skipped by isStepDone").isEqualTo(0);

        // Phase 3: third round — all steps done, fully idempotent
        AgentRunMapper mapper3 = newMapper();
        ToolJobAnchorService anchorService3 = new ToolJobAnchorService(mapper3);
        ToolJobRedisCache redisCache3 = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService3 = new ToolJobResumeService(
                anchorService3, redisCache3, config, om);
        CapacityCountingFake capacity3 = new CapacityCountingFake();

        AtomicInteger usageCount3 = new AtomicInteger(0);
        AtomicInteger eventCount3 = new AtomicInteger(0);
        ToolJobFinalizer finalizer3 = new ToolJobFinalizer(
                anchorService3, redisCache3, capacity3, resumeService3, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        injectHook(finalizer3, "usageHook", (ToolJobUsageHook) (rid, a) -> {
            usageCount3.incrementAndGet();
            return true;
        });
        injectHook(finalizer3, "eventHook", (ToolJobEventHook) (rid, a) -> {
            eventCount3.incrementAndGet();
            return true;
        });

        ToolJobReconciler reconciler3 = new ToolJobReconciler(
                redisCache3, anchorService3, finalizer3, resumeService3, config);
        injectSandboxStub(reconciler3);

        resetDue(anchorService3, redisCache3, "run-crash");
        reconciler3.reconcileFromDue();

        // Phase 3: no new side effects
        assertThat(usageCount3.get()).as("phase 3: usage not called (isStepDone)").isEqualTo(0);
        assertThat(eventCount3.get()).as("phase 3: event not called (isStepDone)").isEqualTo(0);
        assertThat(capacity3.releaseCallCount).as("phase 3: release not called").isEqualTo(0);
        assertThat(capacity3.transitionCount).as("phase 3: no transition").isEqualTo(0);
    }

    @Test
    void casFailureDoesNotWriteRedisOrPgRetryState() throws Exception {
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(10);
        config.setResultRetentionDeadlineSeconds(600);
        config.setReconcilerIntervalMs(5000);
        config.setPollIntervalMs(100);

        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-cas", "tc", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-cas", Instant.now());

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc");
        anchor.setAttempt(1);
        anchor.setTaskId("task-cas");
        anchor.setAutoResume(true);
        anchor.setReservationJson(om.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));
        anchor.setEstimateJson(
                "{\"estimatedRows\":1000,\"estimatedBytes\":10000,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");

        insertRun("run-cas", "WAITING_TOOL_JOB", anchor.toJson());

        // AnchorService that fails the first updateAnchor for this run
        AgentRunMapper mapper1 = newMapper();
        ToolJobRedisCache redisCache1 = new ToolJobRedisCache(redisTemplate, om, config);

        AtomicInteger updateCallCount = new AtomicInteger(0);
        ToolJobAnchorService failOnceService = new ToolJobAnchorService(mapper1) {
            @Override
            public boolean updateAnchor(String rid, ToolJobAnchor a, AgentRunStatus es) {
                int call = updateCallCount.incrementAndGet();
                if ("run-cas".equals(rid) && call == 1) {
                    return false; // CAS failure — simulates concurrent owner
                }
                return super.updateAnchor(rid, a, es);
            }
        };

        ToolJobResumeService resumeService1 = new ToolJobResumeService(
                failOnceService, redisCache1, config, om);
        CapacityCountingFake capacity1 = new CapacityCountingFake();
        ToolJobFinalizer finalizer1 = new ToolJobFinalizer(
                failOnceService, redisCache1, capacity1, resumeService1, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        injectHook(finalizer1, "usageHook", (ToolJobUsageHook) (rid, a) -> true);
        injectHook(finalizer1, "eventHook", (ToolJobEventHook) (rid, a) -> true);

        ToolJobReconciler reconciler1 = new ToolJobReconciler(
                redisCache1, failOnceService, finalizer1, resumeService1, config);
        injectSandboxStub(reconciler1);

        // Verify run is in due ZSET before reconciler runs
        redisCache1.upsertDue("run-cas", failOnceService.loadAnchor("run-cas"));
        Double scoreBefore = redisTemplate.opsForZSet().score("agent:tool-job:due", "run-cas");
        assertThat(scoreBefore).as("due before").isNotNull();

        reconciler1.reconcileFromDue();

        // CAS failed → due entry removed, Redis pending not written
        Double scoreAfter = redisTemplate.opsForZSet().score("agent:tool-job:due", "run-cas");
        assertThat(scoreAfter).as("due removed after CAS failure").isNull();

        // PG anchor: attempts NOT grown (CAS failed, no durable write)
        AgentRunMapper pgCheckMapper = newMapper();
        AgentRun runPg = pgCheckMapper.findById("run-cas");
        ToolJobAnchor anchorPg = ToolJobAnchor.fromJson(runPg.getToolJobAnchorJson());
        assertThat(anchorPg.getResultFetchAttempts()).as("PG attempts not grown").isEqualTo(0);
        assertThat(anchorPg.getResultFetchState()).as("PG state still null").isNull();

        // Phase 2: normal services → retry succeeds, attempt=1
        AgentRunMapper mapper2 = newMapper();
        ToolJobAnchorService anchorService2 = new ToolJobAnchorService(mapper2);
        ToolJobRedisCache redisCache2 = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobResumeService resumeService2 = new ToolJobResumeService(
                anchorService2, redisCache2, config, om);
        CapacityCountingFake capacity2 = new CapacityCountingFake();
        ToolJobFinalizer finalizer2 = new ToolJobFinalizer(
                anchorService2, redisCache2, capacity2, resumeService2, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        injectHook(finalizer2, "usageHook", (ToolJobUsageHook) (rid, a) -> true);
        injectHook(finalizer2, "eventHook", (ToolJobEventHook) (rid, a) -> true);

        ToolJobReconciler reconciler2 = new ToolJobReconciler(
                redisCache2, anchorService2, finalizer2, resumeService2, config);
        injectSandboxStub(reconciler2);

        resetDue(anchorService2, redisCache2, "run-cas");
        reconciler2.reconcileFromDue();

        // Normal: attempt=1, PENDING state set
        AgentRun runPg2 = mapper2.findById("run-cas");
        ToolJobAnchor anchorPg2 = ToolJobAnchor.fromJson(runPg2.getToolJobAnchorJson());
        assertThat(anchorPg2.getResultFetchAttempts()).as("PG attempts=1 after successful retry").isEqualTo(1);
        assertThat(anchorPg2.getResultFetchState()).as("PG state=PENDING").isEqualTo("PENDING");
        assertThat(anchorPg2.getTerminalConfirmedAt()).as("PG confirmedAt set").isNotNull();
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
