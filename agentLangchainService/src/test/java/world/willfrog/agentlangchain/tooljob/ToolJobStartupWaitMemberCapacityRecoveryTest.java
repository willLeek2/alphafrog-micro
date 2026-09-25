package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobStartupWaitMemberCapacityRecoveryTest {
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WaitGroupStore groups = mock(WaitGroupStore.class);
    private final DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
    private final RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
    private final DataAnalysisCapacityProperties properties = new DataAnalysisCapacityProperties();

    @Test
    void restoresBothPreparingAndAttachedMembersFromDurableProofs() throws Exception {
        DataAnalysisOperationIdentity preparingId = new DataAnalysisOperationIdentity("run-a", "call-a", 1);
        DataAnalysisOperationIdentity attachedId = new DataAnalysisOperationIdentity("run-b", "call-b", 1);
        DataAnalysisReservation preparing = reservation(preparingId, DataAnalysisReservationState.PREPARING, null);
        DataAnalysisReservation attached = reservation(attachedId, DataAnalysisReservationState.TASK_ATTACHED, "task-b");
        WaitMember first = member(1, preparing, "PENDING");
        WaitMember second = member(2, attached, "RUNNING");
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200)).thenReturn(List.of(first, second));
        when(capacity.recover(anyList(), anyInt(), anyInt())).thenReturn(emptyReport());

        recovery().onReady();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DataAnalysisReservation>> captured = ArgumentCaptor.forClass(List.class);
        verify(capacity).recover(captured.capture(), eq(properties.getMaxUnits()),
                eq(properties.getMaxHeavyActive()));
        assertEquals(List.of(preparing, attached), captured.getValue());
    }

    @Test
    void canceledAndLateMembersWithUnconfirmedStopsStillOccupyCapacity() throws Exception {
        DataAnalysisOperationIdentity id = new DataAnalysisOperationIdentity("run-c", "call-c", 1);
        DataAnalysisReservation attached = reservation(id, DataAnalysisReservationState.TASK_ATTACHED, "task-c");
        DataAnalysisOperationIdentity lateId = new DataAnalysisOperationIdentity("run-l", "call-l", 1);
        DataAnalysisReservation late = reservation(lateId, DataAnalysisReservationState.TASK_ATTACHED, "task-l");
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200))
                .thenReturn(List.of(member(3, attached, "CANCELED"), member(4, late, "LATE")));
        when(capacity.recover(anyList(), anyInt(), anyInt())).thenReturn(emptyReport());

        recovery().onReady();

        verify(capacity).recover(eq(List.of(attached, late)), anyInt(), anyInt());
    }

    @Test
    void missingProofOnCancelledStopKeepsAdmissionClosed() {
        WaitMember member = new WaitMember();
        member.setId(4L);
        member.setRunId("run-d");
        member.setToolName("executePython");
        member.setState("CANCELED");
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200)).thenReturn(List.of(member));

        recovery().onReady();

        verify(capacity, never()).recover(anyList(), anyInt(), anyInt());
    }

    @Test
    void failedScanKeepsAdmissionClosed() {
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200))
                .thenThrow(new IllegalStateException("database unavailable"));

        recovery().onReady();

        verify(capacity, never()).recover(anyList(), anyInt(), anyInt());
    }

    private ToolJobStartupRecovery recovery() {
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("deployment", GENERATION));
        when(ownership.findOwnedRun(anyString())).thenAnswer(invocation -> {
            AgentRun run = new AgentRun();
            run.setId(invocation.getArgument(0));
            return run;
        });
        return new ToolJobStartupRecovery(mock(ToolJobAnchorService.class),
                mock(ToolJobRedisCache.class), capacity, properties,
                mock(ToolJobFinalizer.class), mock(ToolJobResumeService.class),
                new ToolJobConfig(), ownership, groups);
    }

    private WaitMember member(long rowId, DataAnalysisReservation reservation, String state) throws Exception {
        WaitMember member = new WaitMember();
        member.setId(rowId);
        member.setRunId(reservation.identity().runId());
        member.setToolCallId(reservation.identity().toolCallId());
        member.setExternalOperationId(reservation.operationId());
        member.setToolName("executePython");
        member.setState(state);
        member.setDispatchProofJson(new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, reservation.operationId(),
                reservation.taskId(), "fingerprint", "{}", "{}",
                mapper.writeValueAsString(reservation), Instant.parse("2026-09-25T00:00:00Z").toString())
                .toJson(mapper));
        return member;
    }

    private static DataAnalysisReservation reservation(DataAnalysisOperationIdentity id,
                                                       DataAnalysisReservationState state, String taskId) {
        return new DataAnalysisReservation(id.reservationId(), id, DataAnalysisResourceClass.STANDARD,
                1, state, taskId, Instant.parse("2026-09-25T00:00:00Z"));
    }

    private DataAnalysisCapacityRecoveryReport emptyReport() {
        return new DataAnalysisCapacityRecoveryReport(0, 0, 0, 0,
                properties.getMaxUnits(), properties.getMaxHeavyActive(), false, false,
                List.of(), DataAnalysisAdmissionState.OPEN);
    }
}
