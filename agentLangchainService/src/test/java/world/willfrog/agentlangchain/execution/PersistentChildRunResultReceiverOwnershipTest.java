package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistentChildRunResultReceiverOwnershipTest {
    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Test
    void foreignParentIsNeverCompletedByEitherBackgroundScan() {
        WaitGroupStore waitGroups = mock(WaitGroupStore.class);
        ChildRunIntentStore intents = mock(ChildRunIntentStore.class);
        RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("candidate", GENERATION));
        WaitMember foreign = new WaitMember();
        foreign.setGroupId(7L);
        foreign.setRunId("main-parent");
        foreign.setMemberIdentity("member");
        when(waitGroups.scanDueSubAgentMembers(eq("candidate"), eq(GENERATION), any(), eq(64)))
                .thenReturn(List.of(foreign));
        ChildRunIntentView foreignIntent = new ChildRunIntentView(1L, "main-parent", "main-parent",
                "main-child", "operation", 7L, "member", "call", 0, 0, 0L,
                "ACCEPTED", "EXECUTING", "RUNNING", OffsetDateTime.now(), null, null, null);
        when(intents.listAcceptedSpawnMembersPending("candidate", GENERATION, 0L, 64))
                .thenReturn(List.of(foreignIntent));

        PersistentChildRunResultReceiver receiver = new PersistentChildRunResultReceiver(
                waitGroups, intents, mock(AgentRunMapper.class), ownership, new ObjectMapper(), 64, 4096);
        receiver.collectDueResults();
        receiver.repairAcceptedSpawnResults();

        verify(ownership, org.mockito.Mockito.times(2)).findOwnedRun("main-parent");
        verify(waitGroups, never()).completeMember(any());
        verify(waitGroups, never()).rescheduleMember(anyLong(), any(), any(), anyInt());
        verify(waitGroups, never()).findMemberByIdentity(anyLong(), any());
    }
}
