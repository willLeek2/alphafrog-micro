package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CAS_STATUS→RESUME_READY recovery gap fix tests.
 *
 * <p>Verifies: half-state discovery, atomic promotion with narrow CAS,
 * winner/loser side effects, normal finalizer convergence, and backfill
 * integration. Pure mock tests — no Docker, Testcontainers, PostgreSQL, or Redis.
 * Real two-instance DB concurrency remains gated on authorized non-macOS environment.
 */
@ExtendWith(MockitoExtension.class)
class ToolJobCasStatusRecoveryTest {

    private static final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Mock
    private AgentRunMapper agentRunMapper;

    private ToolJobAnchorService anchorService;
    private ToolJobFinalizer finalizer;
    private ToolJobResumeService resumeService;
    private ToolJobRedisCache redisCache;
    private ToolJobReconciler reconciler;
    private ToolJobUsageHook usageHook;
    private ToolJobEventHook eventHook;
    private DataAnalysisCapacityService capacityService;

    @BeforeEach
    void setUp() {
        anchorService = spy(new ToolJobAnchorService(agentRunMapper));
        resumeService = mock(ToolJobResumeService.class);
        redisCache = mock(ToolJobRedisCache.class);
        usageHook = mock(ToolJobUsageHook.class);
        eventHook = mock(ToolJobEventHook.class);
        capacityService = mock(DataAnalysisCapacityService.class);

        ToolJobConfig config = mock(ToolJobConfig.class);
        lenient().when(config.getResultRetentionDeadlineSeconds()).thenReturn(300L);
        lenient().when(config.getResultFetchMaxAttempts()).thenReturn(5);
        lenient().when(config.getReconcilerIntervalMs()).thenReturn(30000L);
        lenient().when(config.getPollIntervalMs()).thenReturn(5000L);
        lenient().when(config.getLaunchingStaleSeconds()).thenReturn(60L);

        finalizer = new ToolJobFinalizer(
                anchorService, redisCache, capacityService, resumeService,
                config,
                mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class),
                mock(FinanceToolResultFormatter.class),
                mock(FinanceResultModelAdapter.class));
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config, capacityService);
    }

    // ===== completeResumeReady: guard conditions =====

    @Test
    void completeResumeReady_skipsWhenNotCasStatus() {
        ToolJobAnchor anchor = buildAnchor("run-1", "tc-1", 1, "task-1");
        anchor.setFinalizerStep("ENVELOPE"); // wrong step

        finalizer.completeResumeReady("run-1", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
        verify(redisCache, never()).writePendingCache(anyString(), any());
        verify(resumeService, never()).tryResume(anyString());
    }

    @Test
    void completeResumeReady_skipsWhenResumeStateAlreadySet() {
        ToolJobAnchor anchor = buildAnchor("run-2", "tc-2", 1, "task-2");
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setResumeState("READY"); // already set by another instance

        finalizer.completeResumeReady("run-2", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_failClosedWhenOperationIdMissing() {
        ToolJobAnchor anchor = buildAnchor("run-3", "tc-3", 1, "task-3");
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setOperationId(null); // missing

        finalizer.completeResumeReady("run-3", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_failClosedWhenToolCallIdBlank() {
        ToolJobAnchor anchor = buildAnchor("run-4", "", 1, "task-4");
        anchor.setFinalizerStep("CAS_STATUS");

        finalizer.completeResumeReady("run-4", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_failClosedWhenTaskIdBlank() {
        ToolJobAnchor anchor = buildAnchor("run-5", "tc-5", 1, "");
        anchor.setFinalizerStep("CAS_STATUS");

        finalizer.completeResumeReady("run-5", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_failClosedWhenAttemptNegative() {
        ToolJobAnchor anchor = buildAnchor("run-6", "tc-6", -1, "task-6");
        anchor.setFinalizerStep("CAS_STATUS");

        finalizer.completeResumeReady("run-6", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_failClosedWhenAttemptIsZero() {
        ToolJobAnchor anchor = buildAnchor("run-7", "tc-7", 0, "task-7");
        anchor.setFinalizerStep("CAS_STATUS");

        finalizer.completeResumeReady("run-7", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_failClosedWhenResumeLeaseVersionNegative() {
        ToolJobAnchor anchor = buildAnchor("run-8", "tc-8", 1, "task-8");
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setResumeLeaseVersion(-1);

        finalizer.completeResumeReady("run-8", anchor);

        verify(anchorService, never()).promoteCasStatusToResumeReady(
                anyString(), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    void completeResumeReady_proceedsWhenResumeStateIsWhitespace() {
        ToolJobAnchor anchor = buildAnchor("run-9", "tc-9", 1, "task-9");
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setResumeState(" "); // whitespace-only treated as empty via isBlank()

        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-9"), eq("run-9:tc-9:1"), eq("tc-9"), eq(1), eq("task-9"),
                eq(0L), anyString()))
                .thenReturn(1);

        ToolJobAnchor persisted = buildAnchor("run-9", "tc-9", 1, "task-9");
        persisted.setFinalizerStep("RESUME_READY");
        persisted.setResumeState("READY");
        persisted.setResumeToken("tok-whitespace");
        persisted.setResumeLeaseVersion(1);
        persisted.setResumeClaimedAt(Instant.now());
        AgentRun persistedRun = new AgentRun();
        persistedRun.setId("run-9");
        persistedRun.setStatus(AgentRunStatus.RECEIVED);
        persistedRun.setToolJobAnchorJson(persisted.toJson());
        when(agentRunMapper.findById("run-9")).thenReturn(persistedRun);

        finalizer.completeResumeReady("run-9", anchor);

        // Whitespace resumeState must NOT block recovery
        verify(anchorService).promoteCasStatusToResumeReady(
                eq("run-9"), eq("run-9:tc-9:1"), eq("tc-9"), eq(1), eq("task-9"),
                eq(0L), anyString());
        verify(resumeService).tryResume("run-9");
    }

    // ===== completeResumeReady: winner/loser side effects =====

    @Test
    void completeResumeReady_loserHasNoSideEffects() {
        ToolJobAnchor anchor = buildAnchor("run-loser", "tc-l", 1, "task-l");
        anchor.setFinalizerStep("CAS_STATUS");

        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-loser"), eq("run-loser:tc-l:1"), eq("tc-l"), eq(1), eq("task-l"),
                eq(0L), anyString()))
                .thenReturn(0); // lost the race

        finalizer.completeResumeReady("run-loser", anchor);

        // Loser must NOT write Redis or start worker
        verify(redisCache, never()).writePendingCache(anyString(), any());
        verify(resumeService, never()).tryResume(anyString());
        verify(anchorService, never()).loadAnchor(anyString()); // not even re-read
    }

    @Test
    void completeResumeReady_winnerWritesRedisAndStartsWorker() {
        ToolJobAnchor anchor = buildAnchor("run-winner", "tc-w", 1, "task-w");
        anchor.setFinalizerStep("CAS_STATUS");

        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-winner"), eq("run-winner:tc-w:1"), eq("tc-w"), eq(1), eq("task-w"),
                eq(0L), anyString()))
                .thenReturn(1); // won the race

        // Simulate re-read of persisted anchor
        ToolJobAnchor persisted = buildAnchor("run-winner", "tc-w", 1, "task-w");
        persisted.setFinalizerStep("RESUME_READY");
        persisted.setResumeState("READY");
        persisted.setResumeToken("token-from-db");
        persisted.setResumeLeaseVersion(1);
        persisted.setResumeClaimedAt(Instant.now());
        AgentRun run = new AgentRun();
        run.setId("run-winner");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setToolJobAnchorJson(persisted.toJson());
        when(agentRunMapper.findById("run-winner")).thenReturn(run);

        finalizer.completeResumeReady("run-winner", anchor);

        // Winner must re-read, write Redis, and tryResume with persisted anchor
        verify(anchorService).loadAnchor("run-winner");
        verify(redisCache).writePendingCache(eq("run-winner"), any(ToolJobAnchor.class));
        verify(resumeService).tryResume("run-winner");
    }

    @Test
    void completeResumeReady_winnerReReadFailsLeavesReadyForNextCycle() {
        ToolJobAnchor anchor = buildAnchor("run-rf", "tc-rf", 1, "task-rf");
        anchor.setFinalizerStep("CAS_STATUS");

        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-rf"), eq("run-rf:tc-rf:1"), eq("tc-rf"), eq(1), eq("task-rf"),
                eq(0L), anyString()))
                .thenReturn(1); // won

        when(agentRunMapper.findById("run-rf")).thenReturn(null); // re-read fails

        finalizer.completeResumeReady("run-rf", anchor);

        // Must NOT write Redis or tryResume when re-read fails
        verify(redisCache, never()).writePendingCache(anyString(), any());
        verify(resumeService, never()).tryResume(anyString());
        // READY is already in DB; next READY scan will pick it up
    }

    // ===== promoteCasStatusToResumeReady: narrow CAS conditions =====

    @Test
    void promoteCasStatus_bindsAllIdentityFields() {
        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-7"), eq("run-7:tc-7:1"), eq("tc-7"), eq(1), eq("task-7"),
                eq(0L), eq("token-1")))
                .thenReturn(1);

        int rows = anchorService.promoteCasStatusToResumeReady(
                "run-7", "run-7:tc-7:1", "tc-7", 1, "task-7", 0L, "token-1");

        assertThat(rows).isEqualTo(1);
        verify(agentRunMapper).promoteCasStatusToResumeReady(
                "run-7", "run-7:tc-7:1", "tc-7", 1, "task-7", 0L, "token-1");
    }

    @Test
    void promoteCasStatus_bindsLeaseVersion() {
        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-8"), anyString(), anyString(), anyInt(), anyString(),
                eq(3L), anyString()))
                .thenReturn(0); // version mismatch → lost race

        int rows = anchorService.promoteCasStatusToResumeReady(
                "run-8", "run-8:tc-8:1", "tc-8", 1, "task-8", 3L, "token-2");

        assertThat(rows).isEqualTo(0);
    }

    // ===== Reconciler backfill integration =====

    @Test
    void rebuildFromAnchors_callsCompleteResumeReadyForStuckRuns() {
        ToolJobAnchor stuck = buildAnchor("run-stuck", "tc-s", 1, "task-s");
        stuck.setFinalizerStep("CAS_STATUS");
        AgentRun stuckRun = new AgentRun();
        stuckRun.setId("run-stuck");
        stuckRun.setStatus(AgentRunStatus.RECEIVED);
        stuckRun.setToolJobAnchorJson(stuck.toJson());

        when(anchorService.listStuckAtCasStatus(20)).thenReturn(List.of(stuckRun));
        // Do NOT stub loadAnchor — let real impl call agentRunMapper.findById
        // so the thenReturn chain on findById is exercised.
        // AnchorService.listActive/listResumeReady return empty for the other sections
        when(agentRunMapper.listActiveToolJobAnchors(100)).thenReturn(List.of());
        when(agentRunMapper.listResumeReadyAnchors(50)).thenReturn(List.of());
        // promoteCasStatusToResumeReady will be called; make it win
        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-stuck"), eq("run-stuck:tc-s:1"), eq("tc-s"), eq(1), eq("task-s"),
                eq(0L), anyString()))
                .thenReturn(1);
        // Re-read
        ToolJobAnchor persisted = buildAnchor("run-stuck", "tc-s", 1, "task-s");
        persisted.setFinalizerStep("RESUME_READY");
        persisted.setResumeState("READY");
        persisted.setResumeToken("tok-backfill");
        persisted.setResumeLeaseVersion(1);
        persisted.setResumeClaimedAt(Instant.now());
        AgentRun persistedRun = new AgentRun();
        persistedRun.setId("run-stuck");
        persistedRun.setStatus(AgentRunStatus.RECEIVED);
        persistedRun.setToolJobAnchorJson(persisted.toJson());
        when(agentRunMapper.findById("run-stuck"))
                .thenReturn(stuckRun)   // first loadAnchor
                .thenReturn(persistedRun); // re-read after win

        reconciler.rebuildFromAnchors();

        verify(resumeService).tryResume("run-stuck");
    }

    @Test
    void rebuildFromAnchors_alreadyPromotedAnchorIsSkipped() {
        ToolJobAnchor alreadyReady = buildAnchor("run-ready", "tc-r", 1, "task-r");
        alreadyReady.setFinalizerStep("CAS_STATUS");
        alreadyReady.setResumeState("READY"); // another instance already promoted
        AgentRun readyRun = new AgentRun();
        readyRun.setId("run-ready");
        readyRun.setStatus(AgentRunStatus.RECEIVED);
        readyRun.setToolJobAnchorJson(alreadyReady.toJson());

        when(anchorService.listStuckAtCasStatus(20)).thenReturn(List.of(readyRun));
        when(agentRunMapper.findById("run-ready")).thenReturn(readyRun);
        when(agentRunMapper.listActiveToolJobAnchors(100)).thenReturn(List.of());
        when(agentRunMapper.listResumeReadyAnchors(50)).thenReturn(List.of());

        reconciler.rebuildFromAnchors();

        // completeResumeReady should skip due to resumeState guard
        verify(agentRunMapper, never()).promoteCasStatusToResumeReady(
                eq("run-ready"), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyString());
    }

    // ===== listResumeReady: existing contracts preserved =====

    @Test
    void listResumeReadyAnchors_includesReadyState() {
        ToolJobAnchor ready = buildAnchor("run-rd", "tc-rd", 1, "task-rd");
        ready.setFinalizerStep("RESUME_READY");
        ready.setResumeState("READY");
        ready.setResumeToken("tok");
        ready.setResumeLeaseVersion(1);
        AgentRun run = new AgentRun();
        run.setId("run-rd");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setToolJobAnchorJson(ready.toJson());

        when(agentRunMapper.listResumeReadyAnchors(50)).thenReturn(List.of(run));
        List<AgentRun> result = anchorService.listResumeReady(50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("run-rd");
    }

    @Test
    void listStuckAtCasStatusAnchors_includesOnlyCasStatusWithNullResumeState() {
        ToolJobAnchor stuck = buildAnchor("run-cas", "tc-cas", 1, "task-cas");
        stuck.setFinalizerStep("CAS_STATUS");
        AgentRun run = new AgentRun();
        run.setId("run-cas");
        run.setStatus(AgentRunStatus.RECEIVED);
        run.setToolJobAnchorJson(stuck.toJson());

        when(agentRunMapper.listStuckAtCasStatusAnchors(20)).thenReturn(List.of(run));
        List<AgentRun> result = anchorService.listStuckAtCasStatus(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("run-cas");
    }

    // ===== handleTerminal step 6 convergence =====

    @Test
    void handleTerminal_step6UsesCompleteResumeReady() {
        String reservationJson = buildReservationJson(
                "run-h6", "tc-h6", 1, "task-h6",
                DataAnalysisReservationState.TERMINAL_CONFIRMED);
        ToolJobAnchor anchor = buildTerminalAnchor(
                "run-h6", "tc-h6", 1, "task-h6", reservationJson);
        // Simulate: steps 1-5 already done, only RESUME_READY remains.
        // anchor already has anchorState=TERMINAL, terminalRetryable=false,
        // usagePersisted=true, terminalEventEmitted=true, terminalStatus=SUCCEEDED,
        // and finalizerStep=CAS_STATUS. So isStepDone returns true for ENVELOPE..CAS_STATUS.
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalRetryable(false);
        anchor.setUsagePersisted(true);
        anchor.setTerminalEventEmitted(true);

        // No updateAnchorAndStatus stub needed — CAS_STATUS is already done
        // and isStepDone(STEP_CAS_STATUS) returns true.

        // promoteCasStatusToResumeReady wins
        when(agentRunMapper.promoteCasStatusToResumeReady(
                eq("run-h6"), eq("run-h6:tc-h6:1"), eq("tc-h6"), eq(1), eq("task-h6"),
                eq(0L), anyString()))
                .thenReturn(1);

        // Re-read
        ToolJobAnchor persisted = buildAnchor("run-h6", "tc-h6", 1, "task-h6");
        persisted.setFinalizerStep("RESUME_READY");
        persisted.setResumeState("READY");
        persisted.setResumeToken("tok-h6");
        persisted.setResumeLeaseVersion(1);
        persisted.setResumeClaimedAt(Instant.now());
        AgentRun persistedRun = new AgentRun();
        persistedRun.setId("run-h6");
        persistedRun.setStatus(AgentRunStatus.RECEIVED);
        persistedRun.setToolJobAnchorJson(persisted.toJson());
        when(agentRunMapper.findById("run-h6")).thenReturn(persistedRun);

        // Call handleTerminal (autoResume=true)
        finalizer.handleTerminal("run-h6", anchor, "SUCCEEDED", null, true);

        // Verify completeResumeReady was called (via promoteCasStatusToResumeReady)
        verify(agentRunMapper).promoteCasStatusToResumeReady(
                eq("run-h6"), eq("run-h6:tc-h6:1"), eq("tc-h6"), eq(1), eq("task-h6"),
                eq(0L), anyString());
        // Winner writes Redis and tries resume
        verify(redisCache).writePendingCache(eq("run-h6"), any(ToolJobAnchor.class));
        verify(resumeService).tryResume("run-h6");
    }

    // ===== helpers =====

    private static ToolJobAnchor buildAnchor(String runId, String toolCallId,
                                              int attempt, String taskId) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        anchor.setTaskId(taskId);
        anchor.setResumeLeaseVersion(0);
        anchor.setResumeState(null);
        anchor.setFinalizerStep("CAS_STATUS");
        anchor.setAnchorState("TERMINAL");
        anchor.setTerminalRetryable(false);
        anchor.setUsagePersisted(true);
        anchor.setTerminalEventEmitted(true);
        anchor.setAutoResume(true);
        return anchor;
    }

    private static ToolJobAnchor buildTerminalAnchor(String runId, String toolCallId,
                                                      int attempt, String taskId,
                                                      String reservationJson) {
        ToolJobAnchor a = buildAnchor(runId, toolCallId, attempt, taskId);
        a.setTerminalStatus("SUCCEEDED");
        a.setReservationJson(reservationJson);
        a.setEstimateJson("{\"resourceClass\":\"STANDARD\",\"capacityUnits\":1,"
                + "\"estimatedRows\":0,\"estimatedBytes\":0,\"fileCount\":0,"
                + "\"selectedColumnRatio\":1.0,\"manifestMemberCount\":0}");
        a.setTerminalUsageJson("{\"maxMemoryBytes\":\"1048576\",\"wallTimeMs\":\"500\"}");
        a.setTerminalResultPreview("ok");
        return a;
    }

    private static String buildReservationJson(String runId, String toolCallId,
                                                int attempt, String taskId,
                                                DataAnalysisReservationState state) {
        return "{\"reservationId\":\"res-" + runId + "\","
                + "\"identity\":{\"runId\":\"" + runId + "\","
                + "\"toolCallId\":\"" + toolCallId + "\",\"attempt\":" + attempt + "},"
                + "\"resourceClass\":\"STANDARD\",\"capacityUnits\":1,"
                + "\"state\":\"" + state + "\","
                + "\"taskId\":\"" + taskId + "\","
                + "\"acquiredAt\":\"2026-08-10T10:00:00Z\"}";
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
