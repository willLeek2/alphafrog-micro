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
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
        DataAnalysisOperationIdentity preparingId = operation("run-a", "call-a");
        DataAnalysisOperationIdentity attachedId = operation("run-b", "call-b");
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
        DataAnalysisOperationIdentity id = operation("run-c", "call-c");
        DataAnalysisReservation attached = reservation(id, DataAnalysisReservationState.TASK_ATTACHED, "task-c");
        DataAnalysisOperationIdentity lateId = operation("run-l", "call-l");
        DataAnalysisReservation late = reservation(lateId, DataAnalysisReservationState.TASK_ATTACHED, "task-l");
        WaitMember canceledMember = member(3, attached, "CANCELED");
        WaitMember lateMember = member(4, late, "LATE");
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200))
                .thenReturn(List.of(canceledMember, lateMember));
        when(capacity.recover(anyList(), anyInt(), anyInt())).thenReturn(emptyReport());

        recovery().onReady();

        verify(capacity).recover(eq(List.of(attached, late)), anyInt(), anyInt());
    }

    @Test
    void restoresExecuteQueryMemberFromDurableProof() throws Exception {
        DataAnalysisOperationIdentity id = operation("run-q", "call-q", "executeQuery");
        DataAnalysisReservation attached = reservation(id, DataAnalysisReservationState.TASK_ATTACHED, "task-q");
        WaitMember member = member(5, attached, "RUNNING", "executeQuery");
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200))
                .thenReturn(List.of(member));
        when(capacity.recover(anyList(), anyInt(), anyInt())).thenReturn(emptyReport());

        recovery().onReady();

        verify(capacity).recover(eq(List.of(attached)), anyInt(), anyInt());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void preparingV2SessionAnchorOnlyYieldsWhenACompleteMemberProofExists(boolean completeProof) throws Exception {
        var id = operation("run-q", "call-q", "executeQuery");
        var preparing = reservation(id, DataAnalysisReservationState.PREPARING, null);
        var attached = new DataAnalysisReservation(preparing.reservationId(), preparing.identity(),
                preparing.resourceClass(), preparing.capacityUnits(), DataAnalysisReservationState.TASK_ATTACHED,
                "accepted-task", preparing.acquiredAt());
        WaitMember member = member(5, attached, "RUNNING", "executeQuery");
        var originalRequest = world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest.newBuilder()
                .setOperationId(id.operationId()).setRequestFingerprint("fingerprint").setCode("print('saved query')").build();
        var oldProof = WaitMemberDispatchProof.fromJson(mapper, member.getDispatchProofJson()).orElseThrow();
        member.setDispatchProofJson(new WaitMemberDispatchProof(WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION,
                oldProof.operationId(), oldProof.taskId(), oldProof.requestFingerprint(), oldProof.canonicalCreateSpecJson(),
                oldProof.estimateJson(), oldProof.reservationJson(), oldProof.submittedAt(),
                com.google.protobuf.util.JsonFormat.printer().print(originalRequest)).toJson(mapper));
        if (!completeProof) {
            member.setState("PENDING");
            member.setDispatchProofJson(null);
        }
        var anchor = new world.willfrog.agent.platform.dataanalysis.ToolJobAnchor();
        anchor.setToolName("executeQuery"); anchor.setAnchorState("PREPARING");
        anchor.setOperationId(id.operationId()); anchor.setRequestFingerprint("fingerprint");
        anchor.setReservationJson(mapper.writeValueAsString(preparing));
        // 当前V2会话锚点保存未enrich的baseRequest；只有成员保存真实完整请求。
        anchor.setCreateRequestJson("{\"code\":\"print('base')\"}");
        var anchors = mock(ToolJobAnchorService.class);
        when(anchors.loadAnchor("run-q")).thenReturn(anchor);
        var redis = mock(ToolJobRedisCache.class);
        var run = new AgentRun(); run.setId("run-q"); run.setStatus(world.willfrog.agent.platform.model.AgentRunStatus.EXECUTING);
        run.setSchedulerVersion("DUAL_POOL_V2");
        when(ownership.listActiveAnchors(200)).thenReturn(List.of(run));
        when(groups.findMemberByOperation("run-q", id.operationId())).thenReturn(Optional.of(member));
        when(groups.hasUnresolvedMember("run-q", id.operationId())).thenReturn(true);
        when(groups.scanUnresolvedPythonMembersForCapacity("deployment", GENERATION, 0, 200)).thenReturn(List.of(member));
        when(capacity.recover(anyList(), anyInt(), anyInt())).thenReturn(emptyReport());
        var recovery = recovery(anchors, redis);
        var sandbox = mock(world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(recovery, "sandboxService", sandbox);
        when(sandbox.getTaskByOperationId(org.mockito.ArgumentMatchers.any())).thenReturn(
                world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse.getDefaultInstance());

        recovery.onReady();

        if (completeProof) {
            verify(capacity).recover(eq(List.of(attached)), anyInt(), anyInt());
            org.mockito.Mockito.verifyNoInteractions(sandbox);
        } else {
            // 未保存完整成员证明的旧窗口仍不猜请求；原预约保留且准入不开。
            verify(capacity, never()).recover(anyList(), anyInt(), anyInt());
            verify(sandbox, never()).createTask(org.mockito.ArgumentMatchers.any());
        }
        verify(anchors, never()).renewExecuteQueryPreparingReplayClaim(anyString(), org.mockito.ArgumentMatchers.any());
        assertEquals("PREPARING", anchor.getAnchorState());
    }

    private ToolJobStartupRecovery recovery() {
        return recovery(mock(ToolJobAnchorService.class), mock(ToolJobRedisCache.class));
    }

    private ToolJobStartupRecovery recovery(ToolJobAnchorService anchors, ToolJobRedisCache redis) {
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("deployment", GENERATION));
        when(ownership.findOwnedRun(anyString())).thenAnswer(invocation -> {
            AgentRun run = new AgentRun();
            run.setId(invocation.getArgument(0));
            return run;
        });
        return new ToolJobStartupRecovery(anchors,
                redis, capacity, properties,
                mock(ToolJobFinalizer.class), mock(ToolJobResumeService.class),
                new ToolJobConfig(), ownership, groups);
    }

    private WaitMember member(long rowId, DataAnalysisReservation reservation, String state) throws Exception {
        return member(rowId, reservation, state, "executePython");
    }

    private WaitMember member(long rowId, DataAnalysisReservation reservation, String state, String toolName)
            throws Exception {
        WaitMember member = new WaitMember();
        member.setId(rowId);
        member.setGroupId(rowId);
        member.setRunId(reservation.identity().runId());
        String durableCallId = reservation.identity().toolCallId();
        member.setToolCallId(durableCallId.substring(0,
                durableCallId.indexOf(DurableToolCallIds.WORK_ITEM_SUFFIX)));
        WaitGroup group = new WaitGroup();
        group.setId(rowId);
        group.setRunId(member.getRunId());
        group.setPlanGeneration(1);
        group.setNodeId("node");
        group.setNodeAttempt(1);
        group.setSegmentSequence(1);
        group.setModelTurn(1);
        when(groups.findGroup(rowId)).thenReturn(Optional.of(group));
        member.setExternalOperationId(reservation.operationId());
        member.setToolName(toolName);
        member.setState(state);
        member.setDispatchProofJson(new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, reservation.operationId(),
                reservation.taskId(), "fingerprint", "{}", "{}",
                mapper.writeValueAsString(reservation), Instant.parse("2026-09-25T00:00:00Z").toString())
                .toJson(mapper));
        return member;
    }

    private static DataAnalysisOperationIdentity operation(String runId, String rawCallId) {
        return operation(runId, rawCallId, "executePython");
    }

    private static DataAnalysisOperationIdentity operation(String runId, String rawCallId, String toolName) {
        return new DataAnalysisOperationIdentity(runId, DurableToolCallIds.forTool(
                toolName, rawCallId, new NodeWorkItemIdentity(runId, 1, "node", 1, 1)), 1);
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
