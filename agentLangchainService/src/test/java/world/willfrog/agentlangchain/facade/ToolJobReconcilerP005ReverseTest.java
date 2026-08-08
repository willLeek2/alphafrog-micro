package world.willfrog.agentlangchain.facade;

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
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.agentlangchain.tooljob.ToolJobConfig;
import world.willfrog.agentlangchain.tooljob.ToolJobEventHook;
import world.willfrog.agentlangchain.tooljob.ToolJobFinalizer;
import world.willfrog.agentlangchain.tooljob.ToolJobReconciler;
import world.willfrog.agentlangchain.tooljob.ToolJobRedisCache;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeService;
import world.willfrog.agentlangchain.tooljob.ToolJobUsageHook;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-05 reverse: cancel with active task -> capacity leak evidence.
 * <p>
 * Production gap: When a run is CANCELED while a tool job is active
 * (WAITING_TOOL_JOB with a PENDING anchor), the reconciler still picks up
 * the run from the due ZSET and calls {@code finalizer.handleTerminal}.
 * However, handleTerminal's ENVELOPE step CAS expects
 * {@link AgentRunStatus#WAITING_TOOL_JOB} status. Since the run is now
 * CANCELED, the CAS fails, the finalizer returns early, capacity is never
 * released, and the reservation stays occupied forever (capacity leak).
 * <p>
 * Path A -- public {@link ToolJobReconciler#reconcileFromDue()}:
 * <ol>
 *   <li>Seed run with status=WAITING_TOOL_JOB, PENDING anchor with
 *       autoResume=true, reservation, and nextPollAt in the past</li>
 *   <li>Write run into Redis due ZSET</li>
 *   <li>Call real {@link LangchainRunControlService#cancelRun(CancelAgentRunRequest)}
 *       which updates DB status to CANCELED via real AgentRunMapper</li>
 *   <li>Stub sandboxService.getTaskStatus() -- returns SUCCEEDED</li>
 *   <li>Stub sandboxService.getTaskResult() -- returns valid result</li>
 *   <li>Call reconciler.reconcileFromDue()</li>
 * </ol>
 * <p>
 * Oracles (Path A):
 * <ol>
 *   <li>Anchor finalizerStep is null in DB (ENVELOPE CAS failed, not persisted)</li>
 *   <li>Capacity NOT released (reservationJson preserved, releaseCallCount==0)</li>
 *   <li>Anchor terminalStatus, terminalAt, sandboxTerminalStatus remain null</li>
 *   <li>finalizerSpy.handleTerminal WAS called (reconciler tried)</li>
 *   <li>Anchor still PENDING (no state advancement)</li>
 *   <li>Ledger identity still active (distinctReservationIds == 1)</li>
 * </ol>
 * <p>
 * Path B -- direct finalizer (narrow evidence):
 * <ol>
 *   <li>Directly call finalizer.handleTerminal with seed anchor + valid result</li>
 *   <li>Verify anchor object in-memory has finalizerStep=ENVELOPE (set before CAS)</li>
 *   <li>Reload from DB: finalizerStep=null (CAS failed), terminalStatus=null,
 *       capacity unchanged</li>
 * </ol>
 */
@Testcontainers
class ToolJobReconcilerP005ReverseTest {

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
    private static final String RUN_ID = "run-p005";
    private static final String USER_ID = "u1";
    private static final String DUE_ZSET_KEY = "agent:tool-job:due";

    // Redis infra -- shared across tests
    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    // MyBatis -- per-test session
    private static SqlSessionFactory sqlSessionFactory;
    private SqlSession currentSession;

    private AgentRunMapper mapper;
    private ToolJobRedisCache redisCache;
    private ToolJobReconciler reconciler;
    private ToolJobFinalizer finalizerSpy;

    // LangchainRunControlService with real mapper + mocked dependencies
    private LangchainRunControlService controlService;
    private StatefulCapacityFake capacityFake;

    // Mocked dependencies for LangchainRunControlService
    private LangchainRunReadService readService;
    private AgentEventService eventService;
    private AgentRunStateStore stateStore;
    private AgentObservabilityService observabilityService;
    private LangchainLinearRunPipeline pipeline;
    private AgentRunCreditSettlementService creditSettlementService;

    // ---- Infrastructure lifecycle ----

    @BeforeAll
    static void setUpInfra() throws Exception {
        // PostgreSQL schema
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

        // Redis via Lettuce
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

        // ---- Mocked dependencies for LangchainRunControlService ----
        readService = mock(LangchainRunReadService.class);
        eventService = mock(AgentEventService.class);
        stateStore = mock(AgentRunStateStore.class);
        observabilityService = mock(AgentObservabilityService.class);
        pipeline = mock(LangchainLinearRunPipeline.class);
        creditSettlementService = mock(AgentRunCreditSettlementService.class);

        // Configure mocks for cancelRun:
        // - requireWritableRun / requireReadableRun: load from real DB
        when(readService.requireWritableRun(anyString(), anyString())).thenAnswer(inv -> {
            AgentRun run = mapper.findById(inv.getArgument(0));
            if (run == null) {
                throw new IllegalArgumentException("run not found");
            }
            return run;
        });
        when(readService.requireReadableRun(anyString(), anyString())).thenAnswer(inv -> {
            AgentRun run = mapper.findById(inv.getArgument(0));
            if (run == null) {
                throw new IllegalArgumentException("run not found");
            }
            return run;
        });
        // - observabilityService.forceFlush: void, mock does nothing by default
        // - observabilityService.attachObservabilityToSnapshot: return simple JSON
        when(observabilityService.attachObservabilityToSnapshot(anyString(), anyString(), any()))
                .thenReturn("{\"status\":\"CANCELED\"}");
        // - eventService.nextInterruptedExpiresAt: return a future timestamp
        when(eventService.nextInterruptedExpiresAt()).thenReturn(OffsetDateTime.now().plusDays(7));
        // - eventService.append: void, mock does nothing by default
        // - stateStore.markRunStatus: void, mock does nothing by default
        // - creditSettlementService.settleAsync: void, mock does nothing by default

        // Real anchor service (used by both controlService and reconciler/finalizer)
        ToolJobConfig config = new ToolJobConfig();
        redisCache = new ToolJobRedisCache(redisTemplate, om, config);
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);

        // Construct LangchainRunControlService with REAL mapper + anchor service + mocked dependencies
        controlService = new LangchainRunControlService(
                readService, mapper, eventService, stateStore,
                observabilityService, pipeline, creditSettlementService, anchorService);

        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, config, om);

        // Stateful capacity ledger (tracks release call count and ledger identity)
        capacityFake = new StatefulCapacityFake();

        // Real ToolJobFinalizer wrapped in a spy so we can verify handleTerminal
        // was called (proving the reconciler tried)
        ToolJobFinalizer realFinalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityFake, resumeService, config, mock(FinanceRecordChannelProcessor.class), mock(FinanceRecordChannelConfigLoader.class), mock(FinanceToolResultFormatter.class), mock(FinanceResultModelAdapter.class));
        // Wire usage/event hooks (mocked — return success)
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(anyString(), any())).thenReturn(true);
        java.lang.reflect.Field usageField = ToolJobFinalizer.class.getDeclaredField("usageHook");
        usageField.setAccessible(true);
        usageField.set(realFinalizer, usageHook);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(eventHook.emitTerminalEvent(anyString(), any())).thenReturn(true);
        java.lang.reflect.Field eventField = ToolJobFinalizer.class.getDeclaredField("eventHook");
        eventField.setAccessible(true);
        eventField.set(realFinalizer, eventHook);
        finalizerSpy = spy(realFinalizer);

        // Create the reconciler with the spy finalizer (5-arg constructor)
        reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizerSpy, resumeService, config);

        // Inject a stub PythonSandboxService via reflection since the field is
        // @DubboReference-injected (not a constructor parameter)
        injectSandboxStub(reconciler);
    }

    @AfterEach
    void tearDown() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
    }

    // ---- Test: Path A -- reconcileFromDue ----

    @Test
    void shouldCancelRunPersistsAnchorDispositionThenReconcilerReleasesCapacity() throws Exception {
        // Step 1: Seed -- insert run with WAITING_TOOL_JOB, a PENDING anchor,
        //         and a real DataAnalysisReservation
        Instant originalNextPollAt = Instant.now().minusSeconds(60); // in the past

        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, "tc-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-p005", Instant.now().minusSeconds(120));
        String reservationJson = om.writeValueAsString(reservation);

        // Pre-seed the stateful capacity ledger with the real reservation
        capacityFake.restoreReservation(reservation);
        assertThat(capacityFake.releaseCallCount).isEqualTo(0);
        assertThat(capacityFake.distinctReservationIds()).isEqualTo(1);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p005");
        anchor.setAutoResume(true);
        anchor.setReservationJson(reservationJson);
        anchor.setEstimateJson("{\"estimatedRows\":1,\"estimatedBytes\":10,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setNextPollAt(originalNextPollAt);
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(RUN_ID, USER_ID, AgentRunStatus.WAITING_TOOL_JOB.name(), anchor.toJson());

        // Step 2: Write into Redis due ZSET with nextPollAt in the past
        redisCache.upsertDue(RUN_ID, anchor);

        // Precondition: run is in the due ZSET
        Double score = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, RUN_ID);
        assertThat(score).isNotNull();
        assertThat(score.longValue()).isLessThanOrEqualTo(System.currentTimeMillis());

        // Precondition: run loaded from DB has WAITING_TOOL_JOB status
        AgentRun run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);

        // Precondition: anchor is PENDING with autoResume=true
        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");
        assertThat(dbAnchor.isAutoResume()).isTrue();
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getFinalizerStep()).isNull();

        // Step 3: Call real LangchainRunControlService.cancelRun()
        CancelAgentRunRequest cancelRequest = CancelAgentRunRequest.newBuilder()
                .setId(RUN_ID)
                .setUserId(USER_ID)
                .build();
        controlService.cancelRun(cancelRequest);

        // Step 4: After cancelRun — anchor has cancel disposition, run status kept for CAS
        run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        // Status still WAITING_TOOL_JOB (not CANCELED) — finalizer CAS needs it
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);

        dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");
        assertThat(dbAnchor.isAutoResume()).isFalse(); // cancel disposition persisted
        assertThat(dbAnchor.getRunDisposition()).isEqualTo("CANCELED");
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(dbAnchor.getFinalizerStep()).isNull();

        // Step 5: Call reconcileFromDue — sees autoResume=false, routes via
        //         checkPausedTerminal → finalizer.handleTerminal(autoResume=false)
        //         → ENVELOPE/RELEASE/USAGE/EVENT succeed (CAS with WAITING_TOOL_JOB)
        //         → CANCELED disposition → transition to CANCELED status
        reconciler.reconcileFromDue();

        // ---- Oracles (flipped: capacity IS released after fix) ----

        // Oracle 1: handleTerminal WAS called (reconciler tried) with autoResume=false
        verify(finalizerSpy).handleTerminal(
                eq(RUN_ID), any(ToolJobAnchor.class), eq("SUCCEEDED"),
                any(TaskResultResponse.class), eq(false));

        // Oracle 2: finalizerStep = CANCELED (finalizer completed, not null)
        run = mapper.findById(RUN_ID);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);

        dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getFinalizerStep()).isEqualTo("CANCELED");

        // Oracle 3: terminalStatus, terminalAt ARE set (ENVELOPE succeeded)
        assertThat(dbAnchor.getTerminalStatus()).isEqualTo("SUCCEEDED");
        assertThat(dbAnchor.getTerminalAt()).isNotNull();

        // Oracle 4: sandboxTerminalStatus is set
        assertThat(dbAnchor.getSandboxTerminalStatus()).isEqualTo("SUCCEEDED");

        // Oracle 5: capacity WAS released (releaseCallCount == 1)
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);

        // Oracle 6: reservation released — transition from PENDING to RELEASED counted
        assertThat(capacityFake.transitionCount).isEqualTo(1);

        // Oracle 7: terminal result fields ARE set
        assertThat(dbAnchor.getTerminalResultPreview()).isNotNull();

        // Oracle 8: repeat reconcile does NOT double-release
        int releaseBefore = capacityFake.releaseCallCount;
        reconciler.reconcileFromDue();
        assertThat(capacityFake.releaseCallCount).isEqualTo(releaseBefore);
    }

    // ---- Test: Path B -- direct finalizer (narrow evidence) ----

    @Test
    void shouldFinalizerProcessCancelDispositionAndTransitionToCanceled() throws Exception {
        String runIdB = RUN_ID + "-b";

        DataAnalysisOperationIdentity identityB =
                new DataAnalysisOperationIdentity(runIdB, "tc-1", 1);
        DataAnalysisReservation reservationB = new DataAnalysisReservation(
                identityB.operationId(), identityB,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-p005b", Instant.now().minusSeconds(120));
        String reservationJsonB = om.writeValueAsString(reservationB);

        // Pre-seed capacity ledger
        capacityFake.restoreReservation(reservationB);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(identityB.operationId());
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-p005b");
        anchor.setAutoResume(false);  // cancel disposition already set
        anchor.setRunDisposition("CANCELED");
        anchor.setReservationJson(reservationJsonB);
        anchor.setEstimateJson("{\"estimatedRows\":1,\"estimatedBytes\":10,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        insertRun(runIdB, USER_ID, AgentRunStatus.WAITING_TOOL_JOB.name(), anchor.toJson());

        // Build a valid terminal result
        TaskResultResponse result = TaskResultResponse.newBuilder()
                .setTaskId("task-p005b")
                .setStatus("SUCCEEDED")
                .setStdout("some output data")
                .setRetryable(false)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setExitReason("OK").build())
                .build();

        // Directly call handleTerminal on the spy with autoResume=false
        finalizerSpy.handleTerminal(runIdB, anchor, "SUCCEEDED", result, false);

        // In-memory evidence: finalizer set STEP_CANCELED
        assertThat(anchor.getFinalizerStep()).isEqualTo("CANCELED");
        assertThat(anchor.getTerminalStatus()).isEqualTo("SUCCEEDED");

        // DB evidence: finalizer completed, status transitioned to CANCELED
        AgentRun run = mapper.findById(runIdB);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);

        ToolJobAnchor dbAnchor = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        assertThat(dbAnchor.getFinalizerStep()).isEqualTo("CANCELED");
        assertThat(dbAnchor.getTerminalStatus()).isEqualTo("SUCCEEDED");
        assertThat(dbAnchor.getTerminalAt()).isNotNull();

        // Capacity released
        assertThat(dbAnchor.getReservationJson()).isNotNull().isNotBlank();
        assertThat(capacityFake.releaseCallCount).isEqualTo(1);

        // Verify handleTerminal was invoked with autoResume=false
        verify(finalizerSpy).handleTerminal(
                eq(runIdB), any(ToolJobAnchor.class), eq("SUCCEEDED"),
                any(TaskResultResponse.class), eq(false));
    }

    // ---- Test: Repeat reconcile after cancel (due/pending cleanup + idempotent) ----

    @Test
    void repeatReconcileAfterCancelDoesNotDoubleReleaseOrLeaveStaleDue() throws Exception {
        // Seed and cancel (same setup as Path A)
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID + "-rpt", "tc-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.operationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PENDING_TRANSFERRED,
                "task-rpt", Instant.now().minusSeconds(120));
        capacityFake.restoreReservation(reservation);

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-rpt");
        anchor.setAutoResume(true);
        anchor.setReservationJson(om.writeValueAsString(reservation));
        anchor.setEstimateJson("{\"estimatedRows\":1,\"estimatedBytes\":10,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setNextPollAt(Instant.now().minusSeconds(60));
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));

        String runIdRpt = RUN_ID + "-rpt";
        insertRun(runIdRpt, USER_ID, AgentRunStatus.WAITING_TOOL_JOB.name(), anchor.toJson());
        redisCache.upsertDue(runIdRpt, anchor);

        // Cancel
        controlService.cancelRun(CancelAgentRunRequest.newBuilder()
                .setId(runIdRpt).setUserId(USER_ID).build());

        // First reconcile — terminal finalize + CANCELED transition
        reconciler.reconcileFromDue();
        assertThat(capacityFake.releaseCallCount).isGreaterThanOrEqualTo(1);
        int releaseAfterFirst = capacityFake.releaseCallCount;
        int transitionAfterFirst = capacityFake.transitionCount;

        // Verify due ZSET is empty (cleaned up by finalizer)
        Double dueScore = redisTemplate.opsForZSet().score(DUE_ZSET_KEY, runIdRpt);
        assertThat(dueScore).isNull();

        // Second reconcile — should be a no-op (no due entry, nothing to process)
        reconciler.reconcileFromDue();

        // Oracle: no additional release or transition
        assertThat(capacityFake.releaseCallCount).isEqualTo(releaseAfterFirst);
        assertThat(capacityFake.transitionCount).isEqualTo(transitionAfterFirst);

        // Oracle: run still CANCELED (not changed by re-entry)
        AgentRun run = mapper.findById(runIdRpt);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELED);
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

    private static void insertRun(String id, String userId, String status, String anchorJson)
            throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, user_id, status, tool_job_anchor_json) "
                             + "VALUES (?, ?, ?, CAST(? AS jsonb))")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, status);
            ps.setString(4, anchorJson);
            ps.executeUpdate();
        }
    }

    /**
     * Direct SQL status update used only by Path B (narrow direct-finalizer test).
     * Path A uses real {@link LangchainRunControlService#cancelRun} instead.
     */
    private static void updateRunStatus(String id, String status) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE alphafrog_agent_run SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * Injects a stub {@link PythonSandboxService} into the reconciler via
     * reflection. The sandboxService field is normally injected by Dubbo's
     * {@code @DubboReference} annotation, but in tests we provide our own stub.
     */
    private static void injectSandboxStub(ToolJobReconciler reconciler) throws Exception {
        Field field = ToolJobReconciler.class.getDeclaredField("sandboxService");
        field.setAccessible(true);
        field.set(reconciler, new StubPythonSandboxService());
    }

    /**
     * Stub sandbox service:
     * <ul>
     *   <li>{@code getTaskStatus} -- returns SUCCEEDED (terminal status)</li>
     *   <li>{@code getTaskResult} -- returns a valid result with stdout
     *       (so fetchResult returns non-null and handleTerminal is called)</li>
     *   <li>All other methods -- return default/empty instances (never called)</li>
     * </ul>
     * <p>
     * Unlike the P0-09 stub (where getTaskResult throws to simulate result
     * unavailability), this stub returns a valid result so the reconciler
     * actually calls handleTerminal, which then fails at the ENVELOPE CAS
     * because the run status is CANCELED instead of WAITING_TOOL_JOB.
     */
    private static class StubPythonSandboxService implements PythonSandboxService {

        @Override
        public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
            return TaskStatusResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus("SUCCEEDED")
                    .build();
        }

        @Override
        public CompletableFuture<TaskStatusResponse> getTaskStatusAsync(
                GetTaskStatusRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
            // Return a valid result so fetchResult passes validation and
            // handleTerminal is called (as opposed to null, which causes
            // the reconciler to skip the finalizer entirely)
            return TaskResultResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus("SUCCEEDED")
                    .setStdout("mock output for " + request.getTaskId())
                    .setRetryable(false)
                    .setResourceUsage(SandboxResourceUsage.newBuilder()
                            .setExitReason("OK").build())
                    .build();
        }

        @Override
        public CompletableFuture<TaskResultResponse> getTaskResultAsync(
                GetTaskResultRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public ExecuteResponse createTask(ExecuteRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public CompletableFuture<ExecuteResponse> createTaskAsync(ExecuteRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public GetTaskByOperationIdResponse getTaskByOperationId(
                GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }

        @Override
        public CompletableFuture<GetTaskByOperationIdResponse> getTaskByOperationIdAsync(
                GetTaskByOperationIdRequest request) {
            throw new UnsupportedOperationException("Not implemented in stub");
        }
    }

    /**
     * Stateful capacity ledger that tracks actual reserved-to-released transitions.
     * <p>
     * Unlike a Mockito mock, this proves the ledger state changes at most once
     * even when {@code releaseReservation} is called multiple times during
     * crash/re-entry. In this test, releaseReservation is never called at all
     * (the finalizer ENVELOPE CAS fails because run is CANCELED), so
     * {@code releaseCallCount} stays 0 and the pre-seeded reservation stays in
     * the ledger with its original state.
     */
    static class StatefulCapacityFake implements DataAnalysisCapacityService {
        private final Map<String, DataAnalysisReservationState> ledger = new LinkedHashMap<>();
        int releaseCallCount;
        int transitionCount;

        int distinctReservationIds() {
            return ledger.size();
        }

        @Override
        public DataAnalysisRestoreOutcome restoreReservation(DataAnalysisReservation reservation) {
            String id = reservation.reservationId();
            DataAnalysisReservationState current = ledger.get(id);
            if (current != null && current == reservation.state()) {
                return DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME;
            }
            ledger.put(id, reservation.state());
            return DataAnalysisRestoreOutcome.ADDED;
        }

        @Override
        public DataAnalysisReleaseOutcome releaseReservation(DataAnalysisReleaseRequest request) {
            releaseCallCount++;
            String id = request.reservation().reservationId();
            DataAnalysisReservationState current = ledger.getOrDefault(id, request.reservation().state());
            if (current == DataAnalysisReservationState.RELEASED) {
                return DataAnalysisReleaseOutcome.ALREADY_RELEASED;
            }
            ledger.put(id, DataAnalysisReservationState.RELEASED);
            transitionCount++;
            return DataAnalysisReleaseOutcome.RELEASED;
        }

        // ---- unused in this fixture ----
        @Override public DataAnalysisReservation reserve(DataAnalysisOperationIdentity identity,
                                                          DataAnalysisEstimate estimate) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisCapacityRecoveryReport recover(
                List<DataAnalysisReservation> durableReservations, int configuredMaxUnits,
                int configuredMaxHeavyActive) {
            throw new UnsupportedOperationException();
        }
        @Override public DataAnalysisAdmissionState admissionState() {
            throw new UnsupportedOperationException();
        }
    }
}
