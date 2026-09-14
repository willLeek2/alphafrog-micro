package world.willfrog.agent.platform.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunFailureClassTest {

    @Test
    void containsError_shouldSeeBareAndWrappedError() {
        assertTrue(AgentRunFailureClass.containsError(new AssertionError("boom")));
        assertTrue(AgentRunFailureClass.containsError(new RuntimeException("wrap", new AssertionError("boom"))));
        assertFalse(AgentRunFailureClass.containsError(new IllegalStateException("plain")));
        assertFalse(AgentRunFailureClass.containsError(null));
    }
}
