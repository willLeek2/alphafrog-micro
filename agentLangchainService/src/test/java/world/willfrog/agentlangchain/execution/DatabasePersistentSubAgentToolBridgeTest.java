package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.childrun.ChildRunReservation;
import world.willfrog.agent.platform.childrun.ChildRunReserveRequest;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentPromptService;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberState;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabasePersistentSubAgentToolBridgeTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final ChildRunIntentStore children = mock(ChildRunIntentStore.class);
    private final WaitGroupStore waits = mock(WaitGroupStore.class);
    private final AgentPromptService prompts = mock(AgentPromptService.class);
    private final ObjectMapper json = new ObjectMapper();
    private DatabasePersistentSubAgentToolBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new DatabasePersistentSubAgentToolBridge(runs, children, waits, prompts, json,
                30_000L, 5_000L);
        when(prompts.subAgentEnabled()).thenReturn(true);
        when(prompts.maxSubAgentCount()).thenReturn(3);
        when(prompts.maxSubAgentSteps()).thenReturn(5);
        AgentRun parent = new AgentRun();
        parent.setId("run-parent");
        parent.setSchedulerVersion("DUAL_POOL_V2");
        parent.setExt("{\"model_name\":\"parent-model\",\"endpoint_name\":\"parent-endpoint\"}");
        when(runs.findById("run-parent")).thenReturn(parent);
        when(children.rootRunIdOf("run-parent")).thenReturn(Optional.of("run-parent"));
    }




    private void pendingMember(PersistentSubAgentToolBridge.ReservationRequest request, String tool) {
        WaitMember member = new WaitMember();
        member.setRunId(request.parentRunId());
        member.setGroupId(request.groupId());
        member.setMemberIdentity(request.memberIdentity());
        member.setToolCallId(request.toolCallId());
        member.setToolName(tool);
        member.setExternalOperationId(request.operationId());
        member.setState(WaitMemberState.PENDING.name());
        when(waits.findMemberByIdentity(request.groupId(), request.memberIdentity()))
                .thenReturn(Optional.of(member));
    }

    private static PersistentSubAgentToolBridge.ReservationRequest request(String tool, String arguments) {
        return new PersistentSubAgentToolBridge.ReservationRequest(
                "run-parent", new NodeWorkItemIdentity("run-parent", 3, "todo-1", 1, 0),
                new NodeWorkItemVersions(0L, 1L, 1), 42L, 0,
                "call-1", "call-1", "member-operation-1", arguments);
    }

    private static ChildRunIntentView child(String id) {
        return new ChildRunIntentView(1L, "run-parent", "run-parent", id, "child-op", 41L,
                "spawn-member", "spawn-call", 3, 1, 1L, "ACCEPTED", "RECEIVED",
                "RUNNING", OffsetDateTime.now(), null, null, null);
    }
}
