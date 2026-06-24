package world.willfrog.agentlangchain.failure;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.exception.ProviderChatException;
import world.willfrog.agent.platform.exception.ProviderFailureCategory;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LangchainFailureMapperTest {

    private final LangchainFailureMapper mapper = new LangchainFailureMapper();

    @Test
    void map_shouldMapBudgetExceeded() {
        LangchainFailureDecision decision = mapper.map(
                "tool_execution",
                "todo_1",
                "executePython",
                "RUN_BUDGET_EXCEEDED:wall_clock_ms:651976/600000",
                null,
                null,
                12);

        assertEquals(AgentRunStatus.FAILED, decision.getRunStatus());
        assertEquals("RUN_BUDGET_EXCEEDED", decision.getEventType());
        assertEquals(LangchainFailureCategory.BUDGET_EXCEEDED, decision.getCategory());
        assertFalse(decision.isRetryable());
        assertEquals("RunBudgetExceeded", decision.getObservabilityFailureType());
        assertEquals("todo_1", decision.getEventPayload().get("todo_id"));
        assertEquals(12, decision.getEventPayload().get("tool_calls_used"));
        assertEquals("budget_exceeded_wall_clock", decision.getEventPayload().get("failure_category"));
        assertEquals("wall_clock_ms", decision.getEventPayload().get("dimension"));
    }

    @Test
    void map_shouldMapTypedRunBudgetException() {
        RunBudgetException ex = new RunBudgetException("llm_calls", 50, 50, false);

        LangchainFailureDecision decision = mapper.map(
                "execution", "todo_2", null, null, null, ex, 5);

        assertEquals(AgentRunStatus.FAILED, decision.getRunStatus());
        assertEquals("RUN_BUDGET_EXCEEDED", decision.getEventType());
        assertEquals(LangchainFailureCategory.BUDGET_EXCEEDED_LLM_CALLS, decision.getCategory());
        assertFalse(decision.isRetryable());
        assertEquals("budget_exceeded_llm_calls", decision.getEventPayload().get("failure_category"));
        assertEquals("llm_calls", decision.getEventPayload().get("dimension"));
        assertEquals(50L, decision.getEventPayload().get("actual"));
        assertEquals(50L, decision.getEventPayload().get("limit"));
        assertEquals(false, decision.getEventPayload().get("partial"));
    }

    @Test
    void map_shouldMapTypedProviderChatException() {
        ProviderChatException ex = ProviderChatException.of(
                502,
                "bad_gateway",
                List.of("fireworks"),
                "moonshotai/kimi-k2.5",
                "openrouter",
                "bad gateway",
                ProviderFailureCategory.TRANSIENT_NETWORK,
                null
        );

        LangchainFailureDecision decision = mapper.map(
                "execution", "todo_3", null, null, null, ex, 2);

        assertEquals(AgentRunStatus.FAILED, decision.getRunStatus());
        assertEquals("WORKFLOW_FAILED", decision.getEventType());
        assertEquals(LangchainFailureCategory.PROVIDER_TRANSIENT, decision.getCategory());
        assertTrue(decision.isRetryable());
        assertEquals("ProviderTransientNetwork", decision.getObservabilityFailureType());
        assertEquals("provider_transient_network", decision.getEventPayload().get("failure_category"));
        assertEquals("bad_gateway", decision.getEventPayload().get("error_code"));
        assertEquals(List.of("fireworks"), decision.getEventPayload().get("provider_order"));
        assertEquals("moonshotai/kimi-k2.5", decision.getEventPayload().get("model"));
        assertEquals("openrouter", decision.getEventPayload().get("endpoint"));
    }

    @Test
    void map_shouldFindTypedProviderCauseThroughWrapper() {
        ProviderChatException inner = ProviderChatException.of(
                429,
                "rate_limit_exceeded",
                List.of("fireworks"),
                "moonshotai/kimi-k2.5",
                "openrouter",
                "rate limited",
                ProviderFailureCategory.RATE_LIMIT,
                null
        );
        RuntimeException wrapper = new RuntimeException("wrapper", inner);

        LangchainFailureDecision decision = mapper.map(
                "execution", "todo_4", null, "some failure", null, wrapper, 1);

        assertEquals(LangchainFailureCategory.PROVIDER_RATE_LIMIT, decision.getCategory());
        assertTrue(decision.isRetryable());
        assertEquals("provider_rate_limit", decision.getEventPayload().get("failure_category"));
    }

    @Test
    void map_shouldMapRepeatedToolCallAsRetryableToolError() {
        LangchainFailureDecision decision = mapper.map(
                "tool_execution",
                "todo_2",
                "searchWeb",
                null,
                "{\"success\":false,\"code\":\"REPEATED_TOOL_CALL\",\"message\":\"repeated_tool_call\"}",
                null,
                3);

        assertEquals("TOOL_ERROR", decision.getEventType());
        assertEquals(LangchainFailureCategory.REPEATED_TOOL_CALL, decision.getCategory());
        assertTrue(decision.isRetryable());
        assertEquals("RepeatedToolCall", decision.getObservabilityFailureType());
        assertEquals("REPEATED_TOOL_CALL", decision.getEventPayload().get("error_code"));
    }

    @Test
    void map_shouldMapDatasetParameterErrors() {
        LangchainFailureDecision decision = mapper.map(
                "tool_execution",
                "todo_3",
                "executePython",
                null,
                "{\"ok\":false,\"error\":{\"code\":\"TASK_FAILED\",\"message\":\"dataset_id directory not found\"}}",
                null,
                2);

        assertEquals("TOOL_ERROR", decision.getEventType());
        assertEquals(LangchainFailureCategory.PARAM_RETRY_WITH_HINT, decision.getCategory());
        assertTrue(decision.isRetryable());
        assertEquals("ParameterRetryWithHint", decision.getObservabilityFailureType());
    }

    @Test
    void map_shouldMapBlankFinalAnswerAsWorkflowFailure() {
        LangchainFailureDecision decision = mapper.map("empty_final_answer");

        assertEquals("WORKFLOW_FAILED", decision.getEventType());
        assertEquals(LangchainFailureCategory.EMPTY_OUTPUT, decision.getCategory());
        assertFalse(decision.isRetryable());
    }

    @Test
    void map_shouldMapUnknownToWorkflowFailed() {
        LangchainFailureDecision decision = mapper.map(
                "summarizing",
                null,
                null,
                "unexpected failure",
                null,
                new IllegalStateException("boom"),
                null);

        assertEquals("WORKFLOW_FAILED", decision.getEventType());
        assertEquals(LangchainFailureCategory.UNKNOWN, decision.getCategory());
        assertFalse(decision.isRetryable());
        assertTrue(decision.getReason().contains("unexpected failure"));
        assertTrue(decision.getReason().contains("boom"));
    }
}
