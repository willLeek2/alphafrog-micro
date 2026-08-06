package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobFinalizerDagCleanupTest {

    @Test
    void workerLostDagFinalizesOnExecutingThenFailsAndClearsWithoutResume() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(anchorService.updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(anchorService.completeDagCleanupAndClear(
                "run-dag",
                "run-dag:call-1:1",
                "owner-old",
                ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST)).thenReturn(true);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(usageHook.upsertUsage(eq("run-dag"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-dag"), any())).thenReturn(true);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService,
                redisCache,
                mock(DataAnalysisCapacityService.class),
                resumeService,
                new ToolJobConfig());
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);
        ToolJobAnchor anchor = cleanupAnchor();

        finalizer.handleTerminal("run-dag", anchor, "FAILED", null, false);

        assertThat(anchor.getAnchorState()).isEqualTo("TERMINAL");
        assertThat(anchor.getFinalizerStep()).isEqualTo(ToolJobFinalizer.STEP_EVENT);
        assertThat(anchor.getFinalizerError())
                .isEqualTo(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        verify(anchorService).completeDagCleanupAndClear(
                "run-dag",
                "run-dag:call-1:1",
                "owner-old",
                ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        verify(redisCache).removeDue("run-dag");
        verify(redisCache).deletePendingCache("run-dag");
        verify(anchorService, never()).updateAnchorAndStatus(
                any(), any(), eq(AgentRunStatus.RECEIVED), any());
        verify(resumeService, never()).tryResume(any());
    }

    @Test
    void reentryBackfillsTerminalProofBeforeClearingLegacyCleanupAnchor() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(anchorService.updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(anchorService.completeDagCleanupAndClear(
                "run-dag", "run-dag:call-1:1", "owner-old",
                ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST)).thenReturn(true);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService,
                redisCache,
                mock(DataAnalysisCapacityService.class),
                resumeService,
                new ToolJobConfig());
        ToolJobAnchor anchor = cleanupAnchor();
        anchor.setAnchorState("ATTACHED");
        anchor.setTerminalStatus("FAILED");
        anchor.setTerminalAt(Instant.now());
        anchor.setUsagePersisted(true);
        anchor.setTerminalEventEmitted(true);
        anchor.setFinalizerStep(ToolJobFinalizer.STEP_EVENT);

        finalizer.handleTerminal("run-dag", anchor, "FAILED", null, false);

        assertThat(anchor.getAnchorState()).isEqualTo("TERMINAL");
        verify(anchorService).updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old"));
        verify(anchorService).completeDagCleanupAndClear(
                "run-dag", "run-dag:call-1:1", "owner-old",
                ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        verify(resumeService, never()).tryResume(any());
    }

    @Test
    void failAndClearCasLossKeepsRedisEvidenceForNextOwner() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(anchorService.updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(usageHook.upsertUsage(eq("run-dag"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-dag"), any())).thenReturn(true);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService,
                redisCache,
                mock(DataAnalysisCapacityService.class),
                resumeService,
                new ToolJobConfig());
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);

        finalizer.handleTerminal("run-dag", cleanupAnchor(), "FAILED", null, false);

        verify(redisCache, never()).removeDue(any());
        verify(redisCache, never()).deletePendingCache(any());
        verify(resumeService, never()).tryResume(any());
    }

    @Test
    void lostTerminalBodyStillReleasesAndFailsCleanupOnlyRunWithoutResume() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        when(anchorService.updateDagCleanup(
                eq("run-dag"), any(ToolJobAnchor.class),
                eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(anchorService.completeDagCleanupAndClear(
                "run-dag",
                "run-dag:call-1:1",
                "owner-old",
                ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST)).thenReturn(true);
        ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
        ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
        when(usageHook.upsertUsage(eq("run-dag"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-dag"), any())).thenReturn(true);
        ToolJobConfig config = new ToolJobConfig();
        config.setResultFetchMaxAttempts(1);
        ToolJobFinalizer finalizer = new ToolJobFinalizer(
                anchorService,
                redisCache,
                mock(DataAnalysisCapacityService.class),
                resumeService,
                config);
        inject(finalizer, "usageHook", usageHook);
        inject(finalizer, "eventHook", eventHook);
        ToolJobAnchor anchor = cleanupAnchor();
        anchor.setTerminalConfirmedAt(Instant.now());

        finalizer.handleNotFound("run-dag", anchor);

        assertThat(anchor.getTerminalStatus()).isEqualTo("RESULT_LOST");
        assertThat(anchor.getTerminalRetryable()).isFalse();
        verify(anchorService).completeDagCleanupAndClear(
                "run-dag",
                "run-dag:call-1:1",
                "owner-old",
                ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        verify(redisCache).removeDue("run-dag");
        verify(redisCache).deletePendingCache("run-dag");
        verify(resumeService, never()).tryResume(any());
    }

    private ToolJobAnchor cleanupAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-dag:call-1:1");
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-dag");
        anchor.setBlockingOwnerId("owner-old");
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setAutoResume(false);
        anchor.setTerminalRetryable(false);
        return anchor;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
