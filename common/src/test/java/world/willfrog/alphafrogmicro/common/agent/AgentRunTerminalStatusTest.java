package world.willfrog.alphafrogmicro.common.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunTerminalStatusTest {

    @Test
    void aliasesNormalizeToCanonicalTerminalValues() {
        assertEquals("CANCELED", AgentRunTerminalStatus.normalize(" cancelled "));
        assertEquals("EXPIRED", AgentRunTerminalStatus.normalize("timeout"));
        assertEquals("EXPIRED", AgentRunTerminalStatus.normalize("TIMED_OUT"));
        assertEquals("PARTIAL", AgentRunTerminalStatus.normalize(" partial "));
    }

    @Test
    void transitionalAndEmptyStatusesAreNotTerminal() {
        assertFalse(AgentRunTerminalStatus.isTerminal("CANCELING"));
        assertFalse(AgentRunTerminalStatus.isTerminal("EXECUTING"));
        assertFalse(AgentRunTerminalStatus.isTerminal(null));
        assertNull(AgentRunTerminalStatus.normalize("  "));
        assertTrue(AgentRunTerminalStatus.isTerminal("FAILED"));
    }
}
