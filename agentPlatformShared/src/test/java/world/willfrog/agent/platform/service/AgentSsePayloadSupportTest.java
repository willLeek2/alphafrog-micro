package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentSsePayloadSupportTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void putLlmCallIds_shouldDualWriteBusinessIdAndAlias() {
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putLlmCallIds(payload, "call-abc");

        assertEquals("call-abc", payload.get("llm_call_id"));
        assertEquals("call-abc", payload.get("trace_id"));
    }

    @Test
    void putExecutionAttribution_shouldIncludeTodoAndWorkflowWhenSet() {
        AgentContext.setPhase("linear_execution");
        AgentContext.setStage("todo_execution");
        AgentContext.setTodoContext("todo-1", 2);
        AgentContext.setWorkflow("linear");

        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putExecutionAttribution(payload);

        assertEquals("linear_execution", payload.get("phase"));
        assertEquals("todo_execution", payload.get("stage"));
        assertEquals("todo-1", payload.get("todo_id"));
        assertEquals(2, payload.get("todo_sequence"));
        assertEquals("linear", payload.get("workflow"));
    }

    @Test
    void putExecutionAttribution_shouldOmitTodoFieldsDuringPlanning() {
        AgentContext.setPhase("planning");
        AgentContext.setWorkflow("dag");

        Map<String, Object> payload = new LinkedHashMap<>();
        AgentSsePayloadSupport.putExecutionAttribution(payload);

        assertEquals("planning", payload.get("phase"));
        assertEquals("dag", payload.get("workflow"));
        assertFalse(payload.containsKey("todo_id"));
        assertFalse(payload.containsKey("todo_sequence"));
    }
}
