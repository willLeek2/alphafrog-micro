package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.context.AgentContext;

import java.util.Map;

/**
 * SSE event payload helpers for Tool/LLM live events (backend contract 2026-05-29).
 */
public final class AgentSsePayloadSupport {

    private AgentSsePayloadSupport() {
    }

    /**
     * Business id for one LLM HTTP call; {@code trace_id} kept as alias for older clients/scripts.
     */
    public static void putLlmCallIds(Map<String, Object> payload, String llmCallId) {
        if (payload == null || llmCallId == null || llmCallId.isBlank()) {
            return;
        }
        payload.put("llm_call_id", llmCallId);
        payload.put("trace_id", llmCallId);
    }

    /**
     * Execution attribution from {@link AgentContext}. Omitted when not in a todo execution window.
     */
    public static void putExecutionAttribution(Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String phase = AgentContext.getPhase();
        if (phase != null && !phase.isBlank()) {
            payload.put("phase", phase);
        }
        String stage = AgentContext.getStage();
        if (stage != null && !stage.isBlank()) {
            payload.put("stage", stage);
        }
        String todoId = AgentContext.getTodoId();
        if (todoId != null && !todoId.isBlank()) {
            payload.put("todo_id", todoId);
            Integer todoSequence = AgentContext.getTodoSequence();
            if (todoSequence != null) {
                payload.put("todo_sequence", todoSequence);
            }
        }
        String workflow = AgentContext.getWorkflow();
        if (workflow != null && !workflow.isBlank()) {
            payload.put("workflow", workflow);
        }
    }
}
