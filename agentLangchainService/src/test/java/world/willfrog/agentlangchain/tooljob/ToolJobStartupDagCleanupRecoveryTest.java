package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobStartupDagCleanupRecoveryTest {

    private final world.willfrog.agentlangchain.gateway.RunOwnershipGateway ownershipGateway =
            org.mockito.Mockito.mock(world.willfrog.agentlangchain.gateway.RunOwnershipGateway.class);















    private Fixture fixture(String sandboxStatus) throws Exception {
        return fixture(
                sandboxStatus,
                ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME,
                Instant.now().minusSeconds(5));
    }

    private Fixture fixture(String sandboxStatus, String runDisposition) throws Exception {
        return fixture(sandboxStatus, runDisposition, Instant.now().minusSeconds(5));
    }

    private Fixture fixture(
            String sandboxStatus,
            String runDisposition,
            Instant leaseUntil) throws Exception {
        ToolJobAnchor anchor = dagAnchor(runDisposition);
        anchor.setBlockingLeaseUntil(leaseUntil);
        return fixture(sandboxStatus, anchor);
    }

    private Fixture fixture(
            String sandboxStatus,
            ToolJobAnchor anchor) throws Exception {
        return fixture(sandboxStatus, anchor, AgentRunStatus.EXECUTING);
    }

    private Fixture fixture(
            String sandboxStatus,
            ToolJobAnchor anchor,
            AgentRunStatus runStatus) throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        ToolJobStartupRecovery recovery = new ToolJobStartupRecovery(
                anchorService, redisCache, capacity, properties, finalizer, resumeService, config,
                ownershipGateway);
        inject(recovery, "sandboxService", sandbox);

        AgentRun run = new AgentRun();
        run.setId("run-dag");
        run.setStatus(runStatus);
        when(ownershipGateway.listActiveAnchors(200)).thenReturn(List.of(run));
        when(ownershipGateway.listResumeReadyAnchors(200)).thenReturn(List.of());
        when(anchorService.loadAnchor("run-dag")).thenReturn(anchor);
        when(anchorService.promoteExpiredDagBlockingWorkerLost(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(anchorService.updateDagCleanup(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old")))
                .thenReturn(true);
        when(anchorService.updateDagCleanupPreparing(
                eq("run-dag"), any(), eq("run-dag:call-1:1"), eq("owner-old"),
                eq("sha256:" + "a".repeat(64))))
                .thenReturn(true);
        when(capacity.recover(anyList(), anyInt(), anyInt()))
                .thenReturn(new DataAnalysisCapacityRecoveryReport(
                        1, 1, 0, 1, properties.getMaxUnits(), properties.getMaxHeavyActive(),
                        false, false, List.of(), DataAnalysisAdmissionState.OPEN));
        when(sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus(sandboxStatus).build());
        when(redisCache.fetchDue(20)).thenReturn(Set.of("run-dag"));
        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config);
        inject(reconciler, "sandboxService", sandbox);
        return new Fixture(
                recovery, reconciler, anchorService, redisCache, capacity, finalizer,
                resumeService, sandbox);
    }

    private ToolJobAnchor dagAnchor(String runDisposition) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-dag", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.TASK_ATTACHED,
                "task-dag",
                Instant.now());
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId(identity.toolCallId());
        anchor.setAttempt(identity.attempt());
        anchor.setTaskId("task-dag");
        anchor.setBlockingOwnerId("owner-old");
        anchor.setAnchorState("ATTACHED");
        anchor.setRunDisposition(runDisposition);
        anchor.setAutoResume(false);
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        anchor.setTimeoutAt(Instant.now().plusSeconds(60));
        return anchor;
    }

    private ToolJobAnchor dagPreparingAnchor(
            String runDisposition,
            Instant leaseUntil) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-dag", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.PREPARING,
                null,
                Instant.now());
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setDatasetId("ds-dag")
                .setCode("print(1)")
                .setOperationId(identity.operationId())
                .setRequestFingerprint("sha256:" + "a".repeat(64))
                .build();
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId(identity.toolCallId());
        anchor.setAttempt(identity.attempt());
        anchor.setBlockingOwnerId("owner-old");
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setRequestFingerprint(request.getRequestFingerprint());
        anchor.setCreateRequestJson(com.google.protobuf.util.JsonFormat.printer().print(request));
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition(runDisposition);
        anchor.setAutoResume(false);
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        anchor.setTimeoutAt(Instant.now().plusSeconds(60));
        return anchor;
    }

    private static void cancelByOperationReturns(PythonSandboxService sandbox, String taskId) {
        when(sandbox.cancelTask(any())).thenReturn(CancelTaskResponse.newBuilder()
                .setOutcome(CancelOutcome.CANCELED)
                .setTaskId(taskId).setStatus("CANCELED").build());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ToolJobStartupRecovery recovery,
            ToolJobReconciler reconciler,
            ToolJobAnchorService anchorService,
            ToolJobRedisCache redisCache,
            DataAnalysisCapacityService capacity,
            ToolJobFinalizer finalizer,
            ToolJobResumeService resumeService,
            PythonSandboxService sandbox) {
    }
}
