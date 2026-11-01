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
