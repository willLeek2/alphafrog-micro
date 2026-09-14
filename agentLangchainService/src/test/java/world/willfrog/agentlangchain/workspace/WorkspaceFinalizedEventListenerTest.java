package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkspaceFinalizedEventListenerTest {

    @Test
    void onRunFinalized_enqueuesWorkspaceDump() {
        WorkspaceDumpScheduler scheduler = mock(WorkspaceDumpScheduler.class);
        WorkspaceFinalizedEventListener listener = new WorkspaceFinalizedEventListener(scheduler);

        listener.onRunFinalized(new AgentRunFinalizedEvent("run-1", 7L, "COMPLETED", false));

        verify(scheduler).enqueueDumpAsync("run-1", false);
    }

    @Test
    void onRunFinalized_nullEventIgnored() {
        WorkspaceDumpScheduler scheduler = mock(WorkspaceDumpScheduler.class);
        WorkspaceFinalizedEventListener listener = new WorkspaceFinalizedEventListener(scheduler);

        listener.onRunFinalized(null);

        verifyNoInteractions(scheduler);
    }
}
