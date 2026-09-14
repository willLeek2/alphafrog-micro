package world.willfrog.agent.platform.event;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentRunFinalizationServiceTest {

    @Test
    void publishesCanceledAsFullDumpAndExpiredAsConservativeDump() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AgentRunFinalizationService service = new AgentRunFinalizationService(publisher);
        ArgumentCaptor<AgentRunFinalizedEvent> event = ArgumentCaptor.forClass(AgentRunFinalizedEvent.class);

        service.publishFinalizedEvent("run-c", "7", "CANCELED");
        service.publishFinalizedEvent("run-e", "8", "EXPIRED");

        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(event.capture());
        assertEquals("CANCELED", event.getAllValues().get(0).terminalStatus());
        assertFalse(event.getAllValues().get(0).conservative());
        assertEquals("EXPIRED", event.getAllValues().get(1).terminalStatus());
        assertTrue(event.getAllValues().get(1).conservative());
    }

    @Test
    void rejectsMalformedOrNonTerminalInputs() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AgentRunFinalizationService service = new AgentRunFinalizationService(publisher);

        service.publishFinalizedEvent(" ", "7", "CANCELED");
        service.publishFinalizedEvent("run-active", "7", "EXECUTING");
        service.publishFinalizedEvent("run-user", "not-a-number", "EXPIRED");

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }
}
