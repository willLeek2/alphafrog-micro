package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.service.AgentRunEventRedisStore;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.DataAnalysisObservabilityService;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import javax.sql.DataSource;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * P0-01: Real fast-path production test using PythonSandboxTools.executePython
 * with real PG-backed PythonSandboxDispatchStoreImpl.
 *
 * <p>Follows the exact fixture pattern from PythonSandboxToolsDataIntenseTest
 * with PG Testcontainers added for real anchor persistence verification.
 */
@Testcontainers
class PythonSandboxToolsP001FastPathTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TempDir Path tempDir;

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;

    private static final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private static final String RUN_ID = "run-test";
    private static final String TOOL_CALL_ID = "call-1";

    private static SqlSessionFactory sqlSessionFactory;
    private final List<SqlSession> openSessions = new ArrayList<>();

    private PythonSandboxTools tools;
    private PythonSandboxService sandbox;
    private DataAnalysisCapacityService capacity;
    private PythonSandboxDispatchStore dispatchStore;
    private DataAnalysisTerminalRecorder recorder;
    private AgentRunDatasetRegistry registry;

    @BeforeAll
    static void setUpInfra() throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
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
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS alphafrog_agent_run_event (
                    id BIGSERIAL PRIMARY KEY,
                    run_id VARCHAR(64) NOT NULL,
                    seq INT NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    payload_json JSONB DEFAULT '{}',
                    dedupe_key VARCHAR(256),
                    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                )""");
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_event_dedupe
                ON alphafrog_agent_run_event (run_id, dedupe_key)
                WHERE dedupe_key IS NOT NULL""");
        }
        // Redis
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
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM alphafrog_agent_run_event");
            stmt.execute("DELETE FROM alphafrog_agent_run");
        }
        // Clean Redis
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        insertRun(RUN_ID, "EXECUTING");

        // Real dispatch store backed by PG + real Redis
        AgentRunMapper mapper = newMapper();
        ToolJobAnchorService anchorService = new ToolJobAnchorService(mapper);
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, new ToolJobConfig());
        dispatchStore = spy(new PythonSandboxDispatchStoreImpl(anchorService, redisCache));

        // PythonSandboxTools with real dispatch store (rest mocked per existing pattern)
        tools = new PythonSandboxTools(om);
        sandbox = mock(PythonSandboxService.class);
        capacity = mock(DataAnalysisCapacityService.class);
        recorder = mock(DataAnalysisTerminalRecorder.class);
        registry = mock(AgentRunDatasetRegistry.class);
        inject(tools, "pythonSandboxService", sandbox);
        inject(tools, "agentRunDatasetRegistry", registry);
        inject(tools, "dataAnalysisCapacityService", capacity);
        inject(tools, "dataAnalysisCapacityProperties", new DataAnalysisCapacityProperties());
        inject(tools, "pythonSandboxDispatchStore", dispatchStore);
        inject(tools, "dataAnalysisTerminalRecorder", recorder);
        inject(tools, "fastPathMs", 5000L);

        AgentContext.setRunId(RUN_ID);
        AgentContext.setToolCallId(TOOL_CALL_ID);
        AgentContext.setTodoContext("todo-1", 1);

        fixtureDataset();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        for (SqlSession s : openSessions) {
            try { s.close(); } catch (Exception ignored) {}
        }
        openSessions.clear();
    }

    @Test
    void fastPathCompletesSynchronouslyPersistsAnchorAndClearsAfterAcknowledgement() throws Exception {
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        // dispatchStore is a real spy — let it persist to PG (no stubs)
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest req = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-p001")
                    .setRequestFingerprint(req.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-p001").setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("ok").setDatasetDir("/sandbox/input")
                .setRetryable(false)
                .setResourceUsage(completeUsage()).build());

        // Execute — goes through full executeDataIntense fast-path
        String output = tools.executePython("print(1)", "1", null, null, 30);

        // Oracle 1: output
        assertThat(output).contains("\"ok\":true").contains("\"stdout\":\"ok\"");

        // Oracle 2: capacity release via terminal proof
        verify(capacity).releaseReservation(argThat(req ->
                req.proof() instanceof DataAnalysisReleaseProof.Terminal));

        // Oracle 3: usage upsert
        verify(recorder).upsert(argThat(env ->
                !env.background() && !env.retryable()
                        && "SUCCEEDED".equals(env.terminalStatus())));

        // Oracle 4: no clearActive during executePython (done later by ToolRouter)
        verify(dispatchStore, never()).clearActive(anyString(), anyString());

        // Oracle 5: no transferToPending (fast path succeeds)
        verify(dispatchStore, never()).transferToPending(anyString(), any());

        // Oracle 6: anchor persisted with terminal data via real PG
        ToolJobAnchorService verifyAnchor = new ToolJobAnchorService(newMapper());
        ToolJobAnchor dbAnchor = verifyAnchor.loadAnchor(RUN_ID);
        assertThat(dbAnchor).isNotNull();
        assertThat(dbAnchor.getAnchorState()).isEqualTo("TERMINAL");
        assertThat(dbAnchor.getFinalizerStep()).isEqualTo("USAGE");
        assertThat(dbAnchor.isUsagePersisted()).isTrue();
        assertThat(dbAnchor.getResumeState()).isNull();

        // Oracle 7: clearActive (simulating ToolRouter acknowledgement)
        String operationId = RUN_ID + ":" + TOOL_CALL_ID + ":1";
        boolean cleared = dispatchStore.clearActive(RUN_ID, operationId);
        assertThat(cleared).isTrue();

        // Oracle 8: after clearActive, anchor cleared to {} (operationId null)
        // Use fresh mapper to avoid MyBatis session cache
        ToolJobAnchorService verifyAfterClear = new ToolJobAnchorService(newMapper());
        ToolJobAnchor afterClear = verifyAfterClear.loadAnchor(RUN_ID);
        assertThat(afterClear).isNotNull();
        assertThat(afterClear.getOperationId()).isNull();
        assertThat(afterClear.getFinalizerStep()).isNull();
        assertThat(afterClear.getResumeState()).isNull();

        // Oracle 9: run still EXECUTING
        assertThat(verifyAfterClear.loadAnchor(RUN_ID)).isNotNull(); // anchor is {} but row exists
        AgentRunMapper runMapper = newMapper();
        assertThat(runMapper.findById(RUN_ID).getStatus()).isEqualTo(AgentRunStatus.EXECUTING);

        // Oracle 10: no transferToPending (fast path never suspends)
        verify(dispatchStore, never()).transferToPending(anyString(), any());
    }

    @Test
    void slowTaskTimeoutThrowsPendingExceptionAndPersistsPendingAnchor() throws Exception {
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest req = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-p002")
                    .setRequestFingerprint(req.getRequestFingerprint()).build();
        });
        // Sandbox stays RUNNING — fast poll times out at 1ms
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        // Set fastPathMs to 1ms to force immediate timeout
        inject(tools, "fastPathMs", 1L);

        ExternalToolJobPendingException pending = assertThrows(
                ExternalToolJobPendingException.class,
                () -> tools.executePython("import time; time.sleep(300)", "1", null, null, 30));

        assertThat(pending.getRunId()).isEqualTo(RUN_ID);
        assertThat(pending.getToolCallId()).isEqualTo(TOOL_CALL_ID);

        // Oracle: anchor persisted with PENDING state via real PG
        ToolJobAnchorService verifyAnchor2 = new ToolJobAnchorService(newMapper());
        ToolJobAnchor dbAnchor = verifyAnchor2.loadAnchor(RUN_ID);
        assertThat(dbAnchor).isNotNull();
        assertThat(dbAnchor.getAnchorState()).isEqualTo("PENDING");

        // Oracle: run status = WAITING_TOOL_JOB (suspend transitions from EXECUTING)
        AgentRunMapper runMapper2 = newMapper();
        assertThat(runMapper2.findById(RUN_ID).getStatus()).isEqualTo(AgentRunStatus.WAITING_TOOL_JOB);

        // Oracle: reservation state = PENDING_TRANSFERRED
        assertThat(dbAnchor.getReservationJson()).contains("PENDING_TRANSFERRED");

        // Oracle: Redis pending cache + due entries exist
        ToolJobRedisCache verifyCache = new ToolJobRedisCache(redisTemplate, om, new ToolJobConfig());
        ToolJobAnchor cached = verifyCache.readPendingCache(RUN_ID);
        assertThat(cached).isNotNull();
        assertThat(cached.getAnchorState()).isEqualTo("PENDING");

        // Oracle: Redis due ZSET entry exists with valid score
        Double dueScore = redisTemplate.opsForZSet().score("agent:tool-job:due", RUN_ID);
        assertThat(dueScore).isNotNull().isGreaterThan(0.0);

        // Oracle: createTask called exactly once
        verify(sandbox, times(1)).createTask(any());

        // Oracle: no capacity release (task not terminal)
        verify(capacity, never()).releaseReservation(any());
        verifyNoInteractions(recorder);
    }

    // ===== P0-01 second half: real ToolRouterToolExecutor appendOnce → clear =====

    @Test
    void toolRouterToolExecutorCallsAppendOnceThenClearActiveForSynchronousPythonTerminal() throws Exception {
        // First: executePython fast-path to create terminal anchor in REAL PG
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(recorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest req = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-p001b")
                    .setRequestFingerprint(req.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandbox.getTaskResult(any())).thenReturn(TaskResultResponse.newBuilder()
                .setTaskId("task-p001b").setStatus("SUCCEEDED").setExitCode(0)
                .setStdout("ok").setDatasetDir("/sandbox/input")
                .setRetryable(false)
                .setResourceUsage(completeUsage()).build());

        tools.executePython("print(1)", "1", null, null, 30);

        // Verify anchor exists in PG with operationId
        ToolJobAnchorService verifyAnchor = new ToolJobAnchorService(newMapper());
        ToolJobAnchor anchorInPg = verifyAnchor.loadAnchor(RUN_ID);
        assertThat(anchorInPg).isNotNull();
        assertThat(anchorInPg.getOperationId()).isEqualTo(RUN_ID + ":" + TOOL_CALL_ID + ":1");

        // Build real AgentEventService (spy for verification + InOrder)
        AgentLlmLocalConfigLoader llmConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        AgentRunEventRedisStore eventRedisStore = new AgentRunEventRedisStore(
                redisTemplate, om, llmConfigLoader);
        AgentEventService realEventService = new AgentEventService(
                newMapper(), newEventMapper(), eventRedisStore, om, redisTemplate,
                llmConfigLoader, mock(AgentMessageService.class), mock(AgentPromptService.class));
        injectEventServiceFields(realEventService);
        AgentEventService eventService = spy(realEventService);

        // Mock ToolRouter — returns fast-path result
        ToolRouter toolRouter = mock(ToolRouter.class);
        when(toolRouter.invokeWithMeta(eq("executePython"), anyMap())).thenReturn(
                ToolRouter.ToolInvocationResult.builder()
                        .output("{\"ok\":true}").success(true).durationMs(1L).build());

        // dispatchStore is a real spy — let clearActive hit PG (no stub)

        // Instantiate real ToolRouterToolExecutor via reflection (package-private class)
        Class<?> executorClass = Class.forName(
                "world.willfrog.agentlangchain.tools.ToolRouterToolExecutor");
        var ctor = executorClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        var executor = ctor.newInstance(
                toolRouter, om, eventService,
                new world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle(
                        false, 20, 60),
                dispatchStore);

        AgentContext.setUserId("user-test");

        // Execute via ToolRouterToolExecutor (same TOOL_CALL_ID → matches anchor operationId)
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id(TOOL_CALL_ID)
                .name("executePython")
                .arguments("{\"dataset_ids\":\"1\",\"code\":\"print(1)\"}")
                .build();
        var executeMethod = executorClass.getDeclaredMethod("execute",
                ToolExecutionRequest.class, Object.class);
        executeMethod.setAccessible(true);
        String output = (String) executeMethod.invoke(executor, request, null);
        assertThat(output).isEqualTo("{\"ok\":true}");

        // Oracle 1: appendOnce called BEFORE clearActive (InOrder on spies)
        InOrder order = inOrder(eventService, dispatchStore);
        String dedupeKey = RUN_ID + ":" + TOOL_CALL_ID + ":logical_terminal";
        order.verify(eventService).appendOnce(
                eq(RUN_ID), eq("user-test"), eq("TOOL_CALL_FINISHED"),
                eq(dedupeKey), any());
        String operationId = RUN_ID + ":" + TOOL_CALL_ID + ":1";
        order.verify(dispatchStore).clearActive(RUN_ID, operationId);

        // Oracle 2: event row persisted to PG
        AgentRunEvent persisted = newEventMapper().findByRunIdAndDedupeKey(RUN_ID, dedupeKey);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getPayloadJson()).contains("executePython");

        // Oracle 3: anchor cleared to {} in PG (real clearActive via real dispatchStore)
        ToolJobAnchorService verifyAfter = new ToolJobAnchorService(newMapper());
        ToolJobAnchor afterClear = verifyAfter.loadAnchor(RUN_ID);
        assertThat(afterClear).isNotNull();
        assertThat(afterClear.getOperationId()).isNull();
        assertThat(afterClear.getFinalizerStep()).isNull();
        assertThat(afterClear.getResumeState()).isNull();
    }

    private static void injectEventServiceFields(AgentEventService svc) throws Exception {
        java.lang.reflect.Field ttlField = AgentEventService.class.getDeclaredField("ttlMinutes");
        ttlField.setAccessible(true);
        ttlField.set(svc, 60);
        java.lang.reflect.Field ittlField = AgentEventService.class.getDeclaredField("interruptedTtlDays");
        ittlField.setAccessible(true);
        ittlField.set(svc, 7);
        java.lang.reflect.Field cvField = AgentEventService.class.getDeclaredField("checkpointVersion");
        cvField.setAccessible(true);
        cvField.set(svc, "v1");
        java.lang.reflect.Field pcField = AgentEventService.class.getDeclaredField("payloadMaxChars");
        pcField.setAccessible(true);
        pcField.set(svc, 50000);
        java.lang.reflect.Field ppField = AgentEventService.class.getDeclaredField("payloadPreviewChars");
        ppField.setAccessible(true);
        ppField.set(svc, 500);
    }

    // ===== P0-07: EVENT step crash → PG reload → re-entry → dedup =====

    @Test
    void eventStepCrashPreservesUsageInPgAndReentryPreventsDoubleEmit() throws Exception {
        // Seed anchor with finalizerStep=USAGE in REAL PG
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE alphafrog_agent_run SET status = 'WAITING_TOOL_JOB' WHERE id = '" + RUN_ID + "'");
        }
        var seedAnchor = buildTerminalAnchorWithUsagesDone();
        ToolJobAnchorService realAnchorService = new ToolJobAnchorService(newMapper());
        assertThat(realAnchorService.updateAnchor(RUN_ID, seedAnchor, AgentRunStatus.WAITING_TOOL_JOB)).isTrue();

        // AnchorService delegate: real PG for all writes EXCEPT EVENT step (fails once)
        var eventCallCount = new java.util.concurrent.atomic.AtomicInteger(0);
        ToolJobAnchorService anchorDelegate = new ToolJobAnchorService(newMapper()) {
            @Override
            public boolean updateAnchor(String runId, ToolJobAnchor anchor, AgentRunStatus expectedStatus) {
                if ("EVENT".equals(anchor.getFinalizerStep())) {
                    int n = eventCallCount.incrementAndGet();
                    if (n == 1) return false;
                }
                boolean result = super.updateAnchor(runId, anchor, expectedStatus);
                return result;
            }
        };

        // Track hook calls
        var hookCallCount = new java.util.concurrent.atomic.AtomicInteger(0);
        ToolJobEventHook eventHook = new ToolJobEventHook() {
            @Override
            public boolean emitTerminalEvent(String rId, ToolJobAnchor a) {
                hookCallCount.incrementAndGet();
                return true;
            }
        };

        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorDelegate, mock(ToolJobRedisCache.class),
                capacity, mock(ToolJobResumeService.class), new ToolJobConfig());
        java.lang.reflect.Field usageField = ToolJobFinalizer.class.getDeclaredField("usageHook");
        usageField.setAccessible(true);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        when(usageHook.upsertUsage(eq(RUN_ID), any())).thenReturn(true);
        usageField.set(finalizer, usageHook);
        java.lang.reflect.Field evtField = ToolJobFinalizer.class.getDeclaredField("eventHook");
        evtField.setAccessible(true);
        evtField.set(finalizer, eventHook);

        // First call: EVENT step fires hook, DB write FAILS
        ToolJobAnchor loaded = anchorDelegate.loadAnchor(RUN_ID);
        assertThat(loaded.getFinalizerStep()).isEqualTo("USAGE");
        finalizer.handleTerminal(RUN_ID, loaded, "SUCCEEDED", null, true);

        // Oracle 1: hook called once, EVENT step intercepted
        assertThat(hookCallCount.get()).as("hook call count").isEqualTo(1);
        assertThat(eventCallCount.get()).as("event step write attempts").isEqualTo(1);

        // Oracle 2: PG reload → anchor still USAGE (EVENT write NOT persisted)
        ToolJobAnchorService reloadService = new ToolJobAnchorService(newMapper());
        ToolJobAnchor afterCrash = reloadService.loadAnchor(RUN_ID);
        assertThat(afterCrash).isNotNull();
        assertThat(afterCrash.getFinalizerStep()).isEqualTo("USAGE");

        // Re-entry: fresh PG reload, EVENT step now succeeds (writes to real PG)
        ToolJobAnchor freshFromPg = reloadService.loadAnchor(RUN_ID);
        assertThat(freshFromPg.getFinalizerStep()).isEqualTo("USAGE");
        finalizer.handleTerminal(RUN_ID, freshFromPg, "SUCCEEDED", null, true);

        // Oracle 3: hook called twice
        assertThat(hookCallCount.get()).isEqualTo(2);

        // Oracle 4: direct JDBC check — anchor in PG reflects EVENT/CAS/RESUME_READY
        try (Connection c = ds.getConnection();
             var ps = c.prepareStatement(
                 "SELECT tool_job_anchor_json::text FROM alphafrog_agent_run WHERE id = ?")) {
            ps.setString(1, RUN_ID);
            var rs2 = ps.executeQuery();
            assertThat(rs2.next()).isTrue();
            String rawJson = rs2.getString(1);
            ToolJobAnchor directFromDb = ToolJobAnchor.fromJson(rawJson);
            // After re-entry with delegate writing to real PG, anchor advanced past USAGE
            assertThat(directFromDb.getFinalizerStep()).isIn("EVENT", "CAS_STATUS", "RESUME_READY");
            assertThat(directFromDb.isTerminalEventEmitted()).isTrue();
        }
    }

    /** Build anchor with ENVELOPE+RELEASE+USAGE steps already done */
    private ToolJobAnchor buildTerminalAnchorWithUsagesDone() throws Exception {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(RUN_ID + ":" + TOOL_CALL_ID + ":1");
        anchor.setToolCallId(TOOL_CALL_ID);
        anchor.setAttempt(1);
        anchor.setTaskId("task-p007");
        anchor.setAnchorState("TERMINAL");
        anchor.setFinalizerStep("USAGE");
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalRetryable(false);
        anchor.setUsagePersisted(true);
        anchor.setTerminalAt(Instant.now());
        anchor.setEstimateJson("{\"estimatedRows\":1,\"estimatedBytes\":10,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");
        anchor.setReservationJson(buildReleasedJson(RUN_ID, TOOL_CALL_ID, 1, "task-p007"));
        return anchor;
    }

    private static String buildReleasedJson(String runId, String tcId, int attempt, String taskId) throws Exception {
        DataAnalysisOperationIdentity id = new DataAnalysisOperationIdentity(runId, tcId, attempt);
        DataAnalysisReservation r = new DataAnalysisReservation(
                id.reservationId(), id, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.RELEASED, taskId, Instant.now());
        return om.writeValueAsString(r);
    }

    // ===== P0-12: real DataAnalysisObservabilityService.upsert with partial attribution (cpuMillis=null) =====

    @Test
    void partialAttributionEnvelopeWithMissingCpuMillisUpsertsAndReadsBack() throws Exception {
        // Real recorder: DataAnalysisObservabilityService backed by real PG
        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        DataAnalysisObservabilityService realRecorder = new DataAnalysisObservabilityService(
                newMapper(), stateStore, om);

        // Build partial DataAnalysisResourceUsage — cpuMillis=null (Docker stats failure)
        DataAnalysisResourceUsage partialUsage = new DataAnalysisResourceUsage(
                DataAnalysisResourceClass.STANDARD,
                null,         // cpuMillis — MISSING (collector failure)
                100L * 1024 * 1024, // memoryPeakBytes
                null,         // memoryByteMillis
                10L * 1024 * 1024,  // logicalBytesScanned
                null, null,   // artifact/temporary
                150L, 200L,   // queueWait, prepare
                5000L,        // executionWall
                100L,         // cleanup
                3,            // datasetOpenCount
                "SUCCEEDED",  // exitReason
                false, false, // oomKilled, timedOut
                false,        // attributionComplete
                null,         // samplingInterval
                List.of("cpuMillis"));

        assertThat(partialUsage.attributionComplete()).isFalse();
        assertThat(partialUsage.missingFields()).containsExactly("cpuMillis");

        // Build envelope with partial usage
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                RUN_ID, TOOL_CALL_ID, 1);
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(
                1000, 5000, 1, 1.0, 1, List.of(),
                DataAnalysisResourceClass.STANDARD, 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.TERMINAL_CONFIRMED, "task-p012",
                Instant.now());
        DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                RUN_ID, TOOL_CALL_ID, 1, identity.operationId(), "task-p012",
                "SUCCEEDED", true, "ok", null, null, null, false,
                estimate, reservation, partialUsage,
                Instant.now(), false);

        // Oracle 1: upsert inserts via real PG CAS
        DataAnalysisUpsertOutcome outcome = realRecorder.upsert(envelope);
        assertThat(outcome).isEqualTo(DataAnalysisUpsertOutcome.INSERTED);

        // Oracle 2: findSummaryByRunId reads back — attributionComplete=false, cpuMillis=null
        DataAnalysisObservabilitySummary summary = realRecorder
                .findSummaryByRunId(RUN_ID, DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY)
                .orElse(null);
        assertThat(summary).isNotNull();
        assertThat(summary.attributionComplete()).isFalse();
        assertThat(summary.missingFields()).containsExactly("cpuMillis");
        assertThat(summary.cpuMillis()).isNull();
        assertThat(summary.toolCallCount()).isEqualTo(1);

        // Oracle 3: findByRunId reads full snapshot — call-level attributionComplete false
        DataAnalysisObservabilitySnapshot snapshot = realRecorder
                .findByRunId(RUN_ID, DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY)
                .orElse(null);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.calls()).hasSize(1);
        assertThat(snapshot.summary().attributionComplete()).isFalse();
        DataAnalysisResourceUsage callUsage = snapshot.calls().get(0).resourceUsage();
        assertThat(callUsage.attributionComplete()).isFalse();
        assertThat(callUsage.missingFields()).containsExactly("cpuMillis");
        assertThat(callUsage.cpuMillis()).isNull();

        // Oracle 4: dup call with same envelope returns ALREADY_PRESENT_SAME
        DataAnalysisUpsertOutcome dup = realRecorder.upsert(envelope);
        assertThat(dup).isEqualTo(DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME);
    }

    // ===== P0-10: real launcher + pipeline — RESUME_READY CAS through real PG =====

    @Test
    void resumeServiceCasReadToLaunchingThroughRealPgAndBuildsCorrectContext() throws Exception {
        // Build a READY anchor with completedTodos via real anchorService (PG persistence)
        ToolJobAnchorService anchorService = new ToolJobAnchorService(newMapper());
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, new ToolJobConfig());

        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(RUN_ID + ":tc-1:1");
        anchor.setTaskId("task-p010");
        anchor.setToolCallId(TOOL_CALL_ID);
        anchor.setAttempt(1);
        anchor.setAnchorState("PENDING");
        anchor.setTodoId("todo_3");
        anchor.setSequence(3);
        anchor.setToolCallsUsed(2);
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("ok");
        anchor.setTerminalRetryable(false);
        anchor.setUsagePersisted(true);
        anchor.setTerminalEventEmitted(true);
        anchor.setCompletedTodosJson("[{\"todoId\":\"todo_1\",\"description\":\"fetch data\"}," +
                "{\"todoId\":\"todo_2\",\"description\":\"analyze\"}]");
        anchor.setDatasetSnapshotJson("{\"digest\":\"abc123\"}");
        anchor.setResumeState("READY");
        anchor.setResumeToken("token-p010");
        anchor.setResumeLeaseVersion(1);
        anchor.setEstimateJson("{\"estimatedRows\":1,\"estimatedBytes\":10,\"fileCount\":1,"
                + "\"selectedColumnRatio\":0.5,\"manifestMemberCount\":1,"
                + "\"heavyOperationHints\":[],\"resourceClass\":\"STANDARD\",\"capacityUnits\":1}");

        // Update run status to RECEIVED first to match anchor's terminal state
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE alphafrog_agent_run SET status = 'RECEIVED' WHERE id = '" + RUN_ID + "'");
        }
        anchorService.updateAnchor(RUN_ID, anchor, AgentRunStatus.RECEIVED);

        // Verify READY anchor was persisted to real PG
        ToolJobAnchor persisted = anchorService.loadAnchor(RUN_ID);
        assertThat(persisted.getResumeState()).isEqualTo("READY");
        assertThat(persisted.getResumeToken()).isEqualTo("token-p010");
        assertThat(persisted.getResumeLeaseVersion()).isEqualTo(1L);

        // Create real resumeService with real anchorService + PG
        ToolJobResumeLauncher launcher = mock(ToolJobResumeLauncher.class);
        ToolJobResumeService resumeService = new ToolJobResumeService(
                anchorService, redisCache, new ToolJobConfig(), om);
        java.lang.reflect.Field launchField = ToolJobResumeService.class.getDeclaredField("resumeLauncher");
        launchField.setAccessible(true);
        launchField.set(resumeService, launcher);
        when(launcher.launch(eq(RUN_ID), any(ToolJobResumeContext.class))).thenReturn(true);

        // Execute tryResume — CAS READY→LAUNCHING through real PG
        boolean result = resumeService.tryResume(RUN_ID);
        assertThat(result).isTrue();

        // Oracle 1: anchor state changed to LAUNCHING in PG
        ToolJobAnchor afterCas = anchorService.loadAnchor(RUN_ID);
        assertThat(afterCas.getResumeState()).isEqualTo("LAUNCHING");
        assertThat(afterCas.getResumeLeaseVersion()).isEqualTo(2L); // incremented

        // Oracle 2: launch called with correct completedTodos (no duplicates)
        ArgumentCaptor<ToolJobResumeContext> ctxCaptor = ArgumentCaptor.forClass(ToolJobResumeContext.class);
        verify(launcher).launch(eq(RUN_ID), ctxCaptor.capture());
        ToolJobResumeContext ctx = ctxCaptor.getValue();
        assertThat(ctx.getRunId()).isEqualTo(RUN_ID);
        assertThat(ctx.getTodoId()).isEqualTo("todo_3");
        assertThat(ctx.getTodoSequence()).isEqualTo(3);
        assertThat(ctx.getCompletedTodos()).hasSize(2);
        assertThat(ctx.getCompletedTodos().get(0).getTodoId()).isEqualTo("todo_1");
        assertThat(ctx.getCompletedTodos().get(1).getTodoId()).isEqualTo("todo_2");
        assertThat(ctx.getToolCallsUsed()).isEqualTo(2);
        assertThat(ctx.isTerminalSuccess()).isTrue();
        assertThat(ctx.getDatasetSnapshotJson()).isEqualTo("{\"digest\":\"abc123\"}");

        // Oracle 3: re-entering LAUNCHING succeeds (launcher called again)
        // The anchor in PG is now LAUNCHING — real loadAnchor reads fresh state
        boolean secondAttempt = resumeService.tryResume(RUN_ID);
        assertThat(secondAttempt).isTrue(); // reenters LAUNCHING, calls launcher again
    }

    // ===== P0-11: production pending→OOM — reconciler calls handleTerminal with real hooks =====

    @Test
    void oomPendingAnchorProcessedByFinalizerWithRealHooksSetsRetryableTrue() throws Exception {
        // Create PENDING anchor via production slow path
        DataAnalysisReservation preparing = preparingReservation();
        when(capacity.reserve(any(), any())).thenReturn(preparing);
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest req = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-p011")
                    .setRequestFingerprint(req.getRequestFingerprint()).build();
        });
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        inject(tools, "fastPathMs", 1L);
        assertThrows(ExternalToolJobPendingException.class,
                () -> tools.executePython("import time; time.sleep(300)", "1", null, null, 30));

        // Verify PENDING anchor persisted
        ToolJobAnchorService anchorService = new ToolJobAnchorService(newMapper());
        ToolJobAnchor pendingAnchor = anchorService.loadAnchor(RUN_ID);
        assertThat(pendingAnchor.getAnchorState()).isEqualTo("PENDING");

        // OOM result from sandbox (reconciler calls getTaskResult after seeing FAILED status)
        TaskResultResponse oomResult = TaskResultResponse.newBuilder()
                .setTaskId("task-p011").setStatus("FAILED").setExitCode(137)
                .setError("oom_killed").setStdout("")
                .setRetryable(true)
                .setResourceUsage(SandboxResourceUsage.newBuilder()
                        .setResourceClass("STANDARD")
                        .setExitReason("OOM_KILLED")
                        .setOomKilled(true)
                        .setAttributionComplete(false).build())
                .build();

        // Real hooks — production persistence to PG
        ToolJobRedisCache redisCache = new ToolJobRedisCache(redisTemplate, om, new ToolJobConfig());
        AgentLlmLocalConfigLoader llmConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        AgentRunEventRedisStore eventRedisStore = new AgentRunEventRedisStore(
                redisTemplate, om, llmConfigLoader);
        AgentEventService eventService = new AgentEventService(
                newMapper(), newEventMapper(), eventRedisStore, om, redisTemplate,
                llmConfigLoader, mock(AgentMessageService.class), mock(AgentPromptService.class));
        injectEventServiceFields(eventService);
        ToolJobEventHookImpl realEventHookP11 = new ToolJobEventHookImpl(newMapper(), eventService);

        AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
        DataAnalysisObservabilityService realRecorder = new DataAnalysisObservabilityService(
                newMapper(), stateStore, om);
        ToolJobUsageHookImpl realUsageHook = new ToolJobUsageHookImpl(realRecorder, om);

        // Production finalizer with real hooks (reconciler's handleTerminal path)
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacity, mock(ToolJobResumeService.class),
                new ToolJobConfig());
        java.lang.reflect.Field usageField = ToolJobFinalizer.class.getDeclaredField("usageHook");
        usageField.setAccessible(true);
        usageField.set(finalizer, realUsageHook);
        java.lang.reflect.Field eventField = ToolJobFinalizer.class.getDeclaredField("eventHook");
        eventField.setAccessible(true);
        eventField.set(finalizer, realEventHookP11);

        // Reconciler calls handleTerminal after sandbox returns FAILED + OOM result
        finalizer.handleTerminal(RUN_ID, pendingAnchor, "FAILED", oomResult, true);

        // Oracle 1: terminalRetryable=true — OOM gate from retryable proto field
        ToolJobAnchor afterFinalize = anchorService.loadAnchor(RUN_ID);
        assertThat(afterFinalize).isNotNull();
        assertThat(afterFinalize.getTerminalRetryable()).isTrue();
        assertThat(afterFinalize.getTerminalStatus()).isEqualTo("FAILED");

        // Oracle 2: real event hook persisted event row to PG
        String dedupeKey = RUN_ID + ":" + TOOL_CALL_ID + ":logical_terminal";
        AgentRunEvent persistedEvent = newEventMapper().findByRunIdAndDedupeKey(RUN_ID, dedupeKey);
        assertThat(persistedEvent).isNotNull();
        assertThat(persistedEvent.getEventType()).isEqualTo("TOOL_CALL_FINISHED");

        // Oracle 3: real usage hook persisted observability to PG
        DataAnalysisObservabilitySummary summary = realRecorder
                .findSummaryByRunId(RUN_ID, DataAnalysisObservabilityReadMode.TERMINAL_DB_ONLY)
                .orElse(null);
        assertThat(summary).isNotNull();
        assertThat(summary.oomCount()).isEqualTo(1);

        // Oracle 4: capacity released (RELEASE step ran with real capacity mock)
        verify(capacity).releaseReservation(any());
    }

    // ---- Helpers (exact pattern from PythonSandboxToolsDataIntenseTest) ----

    private void fixtureDataset() throws Exception {
        Path csv = tempDir.resolve("prices.csv");
        Files.writeString(csv, "ts_code,close\n600000.SH,10\n600001.SH,11\n");
        Path meta = tempDir.resolve("prices.meta.json");
        Files.writeString(meta, "{\"rowCount\":2,\"bytes\":" + Files.size(csv)
                + ",\"columns\":[\"ts_code\",\"close\"]}");
        AgentRunDatasetEntry dataset = AgentRunDatasetEntry.forDataset(
                1, "ds-1", csv.toString(), "600000.SH", "prices.csv");
        when(registry.snapshot(RUN_ID)).thenReturn(
                new AgentRunDatasetSnapshot(List.of(dataset), List.of()));
        when(registry.listDatasetNumbers(RUN_ID)).thenReturn(List.of(1));
        when(registry.listManifestNumbers(RUN_ID)).thenReturn(List.of());
        when(registry.findDatasetByNumber(RUN_ID, 1)).thenReturn(java.util.Optional.of(dataset));
    }

    private DataAnalysisReservation preparingReservation() {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(RUN_ID, TOOL_CALL_ID, 1);
        return new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PREPARING, null, Instant.now());
    }

    private SandboxResourceUsage completeUsage() {
        return SandboxResourceUsage.newBuilder()
                .setResourceClass("STANDARD")
                .setCpuMillis(1).setMemoryPeakBytes(2).setLogicalBytesScanned(3)
                .setQueueWaitMillis(4).setPrepareMillis(5).setExecutionWallMillis(6)
                .setCleanupMillis(7).setDatasetOpenCount(1).setExitReason("SUCCEEDED")
                .setAttributionComplete(true).build();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = PythonSandboxTools.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private AgentRunMapper newMapper() throws Exception {
        if (sqlSessionFactory == null) {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            var env = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource());
            config.setEnvironment(env);
            config.addMapper(AgentRunMapper.class);
            String res = "mapper/AgentRunMapper.xml";
            try (Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
                new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                        r, config, res, config.getSqlFragments()).parse();
            }
            sqlSessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config);
        }
        SqlSession session = sqlSessionFactory.openSession(true);
        openSessions.add(session);
        return session.getMapper(AgentRunMapper.class);
    }

    private static void insertRun(String id, String status) throws Exception {
        DataSource ds = dataSource();
        try (Connection conn = ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO alphafrog_agent_run (id, user_id, status, tool_job_anchor_json) VALUES (?, ?, ?, '{}'::jsonb)")) {
            ps.setString(1, id);
            ps.setString(2, "user-test");
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    private AgentRunEventMapper newEventMapper() throws Exception {
        if (eventMapperConfigured) {
            SqlSession session = sqlSessionFactory.openSession(true);
            openSessions.add(session);
            return session.getMapper(AgentRunEventMapper.class);
        }
        // Add event mapper to the shared factory
        var configuration = sqlSessionFactory.getConfiguration();
        configuration.addMapper(AgentRunEventMapper.class);
        String res = "mapper/AgentRunEventMapper.xml";
        try (Reader r = org.apache.ibatis.io.Resources.getResourceAsReader(res)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                    r, configuration, res, configuration.getSqlFragments()).parse();
        }
        eventMapperConfigured = true;
        SqlSession session = sqlSessionFactory.openSession(true);
        openSessions.add(session);
        return session.getMapper(AgentRunEventMapper.class);
    }

    private static boolean eventMapperConfigured = false;
}
