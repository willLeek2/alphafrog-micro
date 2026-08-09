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

    @Test
    void onRunFinalized_expiredAlwaysUsesConservativeDump() {
        WorkspaceDumpScheduler scheduler = mock(WorkspaceDumpScheduler.class);
        WorkspaceFinalizedEventListener listener = new WorkspaceFinalizedEventListener(scheduler);

        listener.onRunFinalized(new AgentRunFinalizedEvent("run-expired", 7L, "EXPIRED", false));

        verify(scheduler).enqueueDumpAsync("run-expired", true);
    }

    @Test
    void onRunFinalized_canceledNeverUsesConservativeDump() {
        WorkspaceDumpScheduler scheduler = mock(WorkspaceDumpScheduler.class);
        WorkspaceFinalizedEventListener listener = new WorkspaceFinalizedEventListener(scheduler);

        listener.onRunFinalized(new AgentRunFinalizedEvent("run-canceled", 7L, "CANCELED", true));

        verify(scheduler).enqueueDumpAsync("run-canceled", false);
    }

    @Test
    void onRunFinalized_malformedOrNonTerminalEventIgnored() {
        WorkspaceDumpScheduler scheduler = mock(WorkspaceDumpScheduler.class);
        WorkspaceFinalizedEventListener listener = new WorkspaceFinalizedEventListener(scheduler);

        listener.onRunFinalized(new AgentRunFinalizedEvent(" ", 7L, "EXPIRED", true));
        listener.onRunFinalized(new AgentRunFinalizedEvent("run-active", 7L, "EXECUTING", false));
        listener.onRunFinalized(new AgentRunFinalizedEvent("run-unknown", 7L, null, false));

        verifyNoInteractions(scheduler);
    }
}
