package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolJobReconcilerDagCleanupTest {















    private Fixture fixture(ToolJobAnchor anchor) throws Exception {
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        DataAnalysisCapacityService capacityService =
                mock(DataAnalysisCapacityService.class);
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(redisCache.fetchDue(20)).thenReturn(Set.of("run-dag"));
        when(anchorService.loadAnchor("run-dag")).thenReturn(anchor);

        world.willfrog.agentlangchain.gateway.RunOwnershipGateway ownershipGateway =
                mock(world.willfrog.agentlangchain.gateway.RunOwnershipGateway.class);
        when(ownershipGateway.owns(anyString())).thenReturn(true);
        when(ownershipGateway.findOwnedRun(anyString())).thenAnswer(invocation -> {
            world.willfrog.agent.platform.entity.AgentRun run =
                    new world.willfrog.agent.platform.entity.AgentRun();
            run.setId(invocation.getArgument(0));
            return run;
        });
        when(ownershipGateway.listActiveAnchors(100)).thenReturn(List.of());
        when(ownershipGateway.listResumeReadyAnchors(50)).thenReturn(List.of());
        when(ownershipGateway.listStuckAtCasStatusAnchors(20)).thenReturn(List.of());
        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache,
                anchorService,
                finalizer,
                resumeService,
                config,
                capacityService,
                ownershipGateway);
        inject(reconciler, "sandboxService", sandbox);
        return new Fixture(
                reconciler,
                redisCache,
                anchorService,
                finalizer,
                resumeService,
                capacityService,
                sandbox,
                ownershipGateway);
    }

    private ToolJobAnchor liveAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME);
        anchor.setBlockingLeaseUntil(Instant.now().plusSeconds(60));
        return anchor;
    }

    private ToolJobAnchor expiredLiveAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME);
        anchor.setBlockingLeaseUntil(Instant.now().minusSeconds(1));
        return anchor;
    }

    private ToolJobAnchor workerLostAnchor() {
        ToolJobAnchor anchor = baseAnchor();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        return anchor;
    }

    private ToolJobAnchor workerLostPreparingAnchor() throws Exception {
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
        ToolJobAnchor anchor = baseAnchor();
        anchor.setTaskId(null);
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setRequestFingerprint(request.getRequestFingerprint());
        anchor.setCreateRequestJson(JsonFormat.printer().print(request));
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        return anchor;
    }

    private ToolJobAnchor preparingAbortAnchor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-dag", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.RELEASED,
                null,
                Instant.now());
        ToolJobAnchor anchor = baseAnchor();
        anchor.setTaskId(null);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(
                ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setBlockingLeaseUntil(Instant.now().minusSeconds(1));
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setNextPollAt(Instant.now().minusSeconds(1));
        return anchor;
    }

    private ToolJobAnchor baseAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-dag:call-1:1");
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-dag");
        anchor.setBlockingOwnerId("owner-old");
        anchor.setAutoResume(false);
        return anchor;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ToolJobReconciler reconciler,
            ToolJobRedisCache redisCache,
            ToolJobAnchorService anchorService,
            ToolJobFinalizer finalizer,
            ToolJobResumeService resumeService,
            DataAnalysisCapacityService capacityService,
            PythonSandboxService sandbox,
            world.willfrog.agentlangchain.gateway.RunOwnershipGateway ownershipGateway) {
    }
}
