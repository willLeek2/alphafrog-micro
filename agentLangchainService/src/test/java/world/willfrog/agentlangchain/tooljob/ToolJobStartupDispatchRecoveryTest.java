package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolJobStartupDispatchRecoveryTest {

    @Test
    void preparingAnchorFindsExistingOperationBeforeAdmissionOpens() throws Exception {
        Fixture fixture = fixture();
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true).setTaskId("task-existing")
                        .setRequestFingerprint("sha256:" + "a".repeat(64)).build());

        fixture.recovery.onReady();

        verify(fixture.sandbox).getTaskByOperationId(argThat(request ->
                "run-1:call-1:1".equals(request.getOperationId())));
        ArgumentCaptor<List<DataAnalysisReservation>> reservations = ArgumentCaptor.forClass(List.class);
        verify(fixture.capacity).recover(reservations.capture(), anyInt(), anyInt());
        assertThat(reservations.getValue()).singleElement().satisfies(reservation -> {
            assertThat(reservation.state()).isEqualTo(DataAnalysisReservationState.TASK_ATTACHED);
            assertThat(reservation.taskId()).isEqualTo("task-existing");
        });
        verify(fixture.anchorService).updateActiveAndStatus(
                eq("run-1"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB),
                eq(AgentRunStatus.EXECUTING), eq("run-1:call-1:1"));
        verify(fixture.redisCache).atomicWritePendingAndDue(eq("run-1"), any());
    }

    @Test
    void preparingAnchorRecreatesFromDurableRequestWhenOperationIsAbsent() throws Exception {
        Fixture fixture = fixture();
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder().setFound(false).build());
        when(fixture.sandbox.createTask(any())).thenAnswer(invocation -> {
            ExecuteRequest request = invocation.getArgument(0);
            return ExecuteResponse.newBuilder().setTaskId("task-recreated")
                    .setRequestFingerprint(request.getRequestFingerprint()).build();
        });

        fixture.recovery.onReady();

        verify(fixture.sandbox).createTask(argThat(request ->
                "run-1:call-1:1".equals(request.getOperationId())
                        && request.getCode().equals("print(1)")));
        verify(fixture.anchorService).updateActiveAndStatus(
                eq("run-1"), argThat(anchor -> "task-recreated".equals(anchor.getTaskId())),
                eq(AgentRunStatus.WAITING_TOOL_JOB), eq(AgentRunStatus.EXECUTING),
                eq("run-1:call-1:1"));
    }

    @Test
    void preparingAnchorDoesNotReplayWhenLookupIsUnavailable() throws Exception {
        Fixture fixture = fixture();
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("gateway timeout")
                        .build());

        fixture.recovery.onReady();

        verify(fixture.sandbox, never()).createTask(any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), any(), any(), any());
        verify(fixture.capacity, never()).recover(anyList(), anyInt(), anyInt());
    }

    @Test
    void preparingAnchorDoesNotAttachFoundTaskWithoutFingerprint() throws Exception {
        Fixture fixture = fixture();
        when(fixture.sandbox.getTaskByOperationId(any())).thenReturn(
                GetTaskByOperationIdResponse.newBuilder()
                        .setFound(true)
                        .setTaskId("task-existing")
                        .build());

        fixture.recovery.onReady();

        verify(fixture.sandbox, never()).createTask(any());
        verify(fixture.anchorService, never()).updateActiveAndStatus(
                any(), any(), any(), any(), any());
        verify(fixture.capacity, never()).recover(anyList(), anyInt(), anyInt());
    }

    private Fixture fixture() throws Exception {
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        ToolJobStartupRecovery recovery = new ToolJobStartupRecovery(
                anchorService, redisCache, capacity, properties, finalizer, resumeService, config);
        java.lang.reflect.Field sandboxField = ToolJobStartupRecovery.class.getDeclaredField("sandboxService");
        sandboxField.setAccessible(true);
        sandboxField.set(recovery, sandbox);

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(AgentRunStatus.EXECUTING);
        ToolJobAnchor anchor = preparingAnchor();
        when(anchorService.listActive(200)).thenReturn(List.of(run));
        when(anchorService.listResumeReady(200)).thenReturn(List.of());
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(anchorService.updateAnchor(eq("run-1"), any(), any())).thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-1"), any(), any(), any())).thenReturn(true);
        when(anchorService.updateActive(eq("run-1"), any(), any(), any())).thenReturn(true);
        when(anchorService.updateActiveAndStatus(eq("run-1"), any(), any(), any(), any())).thenReturn(true);
        when(capacity.recover(anyList(), anyInt(), anyInt()))
                .thenReturn(new DataAnalysisCapacityRecoveryReport(
                        1, 1, 0, 1, properties.getMaxUnits(), properties.getMaxHeavyActive(),
                        false, false, List.of(), DataAnalysisAdmissionState.OPEN));
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(sandbox.getTaskStatus(any())).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());
        return new Fixture(recovery, anchorService, redisCache, capacity, sandbox);
    }

    private ToolJobAnchor preparingAnchor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-1", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
                DataAnalysisReservationState.PREPARING, null, Instant.now());
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setDatasetId("ds-1").setCode("print(1)")
                .setOperationId(identity.operationId())
                .setRequestFingerprint("sha256:" + "a".repeat(64)).build();
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId(identity.toolCallId());
        anchor.setAttempt(identity.attempt());
        anchor.setRequestFingerprint(request.getRequestFingerprint());
        anchor.setCreateRequestJson(JsonFormat.printer().print(request));
        anchor.setAnchorState("PREPARING");
        anchor.setReservationJson(mapper.writeValueAsString(reservation));
        anchor.setEstimateJson(mapper.writeValueAsString(new DataAnalysisEstimate(
                2, 100, 1, 1.0, 0, List.of(), DataAnalysisResourceClass.STANDARD, 1)));
        anchor.setDatasetSnapshotJson("{\"datasets\":[],\"manifests\":[]}");
        anchor.setDatasetSnapshotDigest("sha256:" + "b".repeat(64));
        return anchor;
    }

    private record Fixture(
            ToolJobStartupRecovery recovery,
            ToolJobAnchorService anchorService,
            ToolJobRedisCache redisCache,
            DataAnalysisCapacityService capacity,
            PythonSandboxService sandbox) {
    }
}
