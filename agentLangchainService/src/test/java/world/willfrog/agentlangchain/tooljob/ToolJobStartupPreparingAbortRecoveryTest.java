package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ToolJobStartupPreparingAbortRecoveryTest {

    @Test
    void failedPreparingAbortRecoversCapacityWithoutSandboxWaitingOrResume()
            throws Exception {
        ToolJobAnchorService anchorService =
                mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DataAnalysisCapacityService capacityService =
                mock(DataAnalysisCapacityService.class);
        DataAnalysisCapacityProperties capacityProperties =
                new DataAnalysisCapacityProperties();
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        PythonSandboxService sandbox = mock(PythonSandboxService.class);

        AgentRun failed = new AgentRun();
        failed.setId("run-abort");
        failed.setStatus(AgentRunStatus.FAILED);
        ToolJobAnchor aborting = abortingAnchor();
        when(anchorService.listActive(200))
                .thenReturn(List.of(failed), List.of());
        when(anchorService.listResumeReady(200)).thenReturn(List.of());
        when(anchorService.loadAnchor("run-abort")).thenReturn(aborting);
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.NOT_FOUND);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-abort"),
                any(ToolJobAnchor.class),
                eq("run-abort:call-1:1"),
                eq("worker-old"),
                eq(aborting.getBlockingLeaseUntil()))).thenReturn(true);
        when(redisCache.claimPreparingAbortCleanupIndexes(
                eq("run-abort"), eq(aborting), any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(redisCache.removePendingAndDueIfMatches(
                eq("run-abort"),
                eq("run-abort:call-1:1"),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.REMOVED);
        when(anchorService.completeLiveDagBlockingPreparingAbort(
                eq("run-abort"),
                eq(AgentRunStatus.EXECUTING),
                eq("run-abort:call-1:1"),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(true);
        when(capacityService.recover(any(), anyInt(), anyInt()))
                .thenReturn(new DataAnalysisCapacityRecoveryReport(
                        0,
                        0,
                        0,
                        0,
                        capacityProperties.getMaxUnits(),
                        capacityProperties.getMaxHeavyActive(),
                        false,
                        false,
                        List.of(),
                        DataAnalysisAdmissionState.OPEN));

        ToolJobStartupRecovery recovery = new ToolJobStartupRecovery(
                anchorService,
                redisCache,
                capacityService,
                capacityProperties,
                finalizer,
                resumeService,
                config);
        inject(recovery, "sandboxService", sandbox);

        recovery.onReady();

        verify(capacityService).releaseReservation(any());
        verify(anchorService).completeLiveDagBlockingPreparingAbort(
                eq("run-abort"),
                eq(AgentRunStatus.EXECUTING),
                eq("run-abort:call-1:1"),
                contains("/abort-cleanup/"),
                any(Instant.class));
        verify(capacityService).recover(eq(List.of()), anyInt(), anyInt());
        verifyNoInteractions(sandbox);
        verify(anchorService, never()).updateActiveAndStatus(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB), any(), any());
        verify(anchorService, never()).updateAnchor(
                any(), any(), eq(AgentRunStatus.WAITING_TOOL_JOB));
        verify(resumeService, never()).tryResume(any());
        verify(finalizer, never()).handleTerminal(
                any(), any(), any(), any(), any(Boolean.class));
    }

    @Test
    void startupClearLoserDoesNotDeleteNewOperationIndexes()
            throws Exception {
        ToolJobAnchorService anchorService =
                mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DataAnalysisCapacityService capacityService =
                mock(DataAnalysisCapacityService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        PythonSandboxService sandbox = mock(PythonSandboxService.class);

        AgentRun failed = new AgentRun();
        failed.setId("run-abort");
        failed.setStatus(AgentRunStatus.FAILED);
        ToolJobAnchor staleAbort = abortingAnchor();
        ToolJobAnchor winner = new ToolJobAnchor();
        winner.setOperationId("run-abort:call-2:1");
        winner.setAnchorState("ATTACHED");
        when(anchorService.listActive(200))
                .thenReturn(List.of(failed), List.of());
        when(anchorService.listResumeReady(200)).thenReturn(List.of());
        when(anchorService.loadAnchor("run-abort"))
                .thenReturn(staleAbort, winner);
        when(capacityService.releaseReservation(any()))
                .thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(anchorService.claimLiveDagBlockingPreparingAbortCleanup(
                eq("run-abort"),
                any(ToolJobAnchor.class),
                eq("run-abort:call-1:1"),
                eq("worker-old"),
                eq(staleAbort.getBlockingLeaseUntil())))
                .thenReturn(false);

        ToolJobStartupRecovery recovery = new ToolJobStartupRecovery(
                anchorService,
                redisCache,
                capacityService,
                new DataAnalysisCapacityProperties(),
                finalizer,
                resumeService,
                new ToolJobConfig());
        inject(recovery, "sandboxService", sandbox);

        recovery.onReady();

        verify(redisCache, never()).removeDue("run-abort");
        verify(redisCache, never()).deletePendingCache("run-abort");
        verify(redisCache, never()).upsertDue(
                eq("run-abort"), any(ToolJobAnchor.class));
        verify(redisCache, never()).atomicWritePendingAndDue(
                eq("run-abort"), any(ToolJobAnchor.class));
        verify(capacityService, never()).recover(any(), anyInt(), anyInt());
        verifyNoInteractions(sandbox);
        verify(resumeService, never()).tryResume(any());
    }

    private static ToolJobAnchor abortingAnchor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-abort", "call-1", 1);
        DataAnalysisReservation released = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.RELEASED,
                null,
                Instant.parse("2026-07-30T07:00:00Z"));
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(
                ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-old");
        anchor.setBlockingLeaseUntil(
                Instant.parse("2026-07-30T07:30:00Z"));
        anchor.setReservationJson(mapper.writeValueAsString(released));
        return anchor;
    }

    private static void inject(
            Object target,
            String fieldName,
            Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
