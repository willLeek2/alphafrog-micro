package world.willfrog.agentlangchain.failure;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.exception.AgentRunFailureClass;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangchainFailureClassMappingTest {

    @Test
    void from_shouldCoverEveryCategory() {
        for (LangchainFailureCategory category : LangchainFailureCategory.values()) {
            AgentRunFailureClass mapped = LangchainFailureClassMapping.from(category);
            switch (category) {
                case BUDGET_EXCEEDED,
                     BUDGET_EXCEEDED_LLM_CALLS,
                     BUDGET_EXCEEDED_TOKENS,
                     BUDGET_EXCEEDED_TOOL_CALLS,
                     BUDGET_EXCEEDED_WALL_CLOCK,
                     BUDGET_EXCEEDED_HTTP_ATTEMPTS,
                     INFRA_RETRY,
                     PROVIDER_TRANSIENT,
                     PROVIDER_RATE_LIMIT,
                     PROVIDER_MODEL_UNAVAILABLE ->
                        assertEquals(AgentRunFailureClass.RESOURCE_SIGNAL, mapped, category.name());
                case TOOL_ERROR,
                     REPEATED_TOOL_CALL,
                     PARAM_RETRY_WITH_HINT,
                     EMPTY_OUTPUT,
                     PROVIDER_BAD_REQUEST,
                     PROVIDER_AUTH_REJECTED ->
                        assertEquals(AgentRunFailureClass.BUSINESS_REJECTION, mapped, category.name());
                case UNKNOWN, PROVIDER_UNKNOWN ->
                        assertEquals(AgentRunFailureClass.UNKNOWN_DEFECT, mapped, category.name());
            }
        }
    }

    @Test
    void from_null_shouldBeUnknownDefect() {
        assertEquals(AgentRunFailureClass.UNKNOWN_DEFECT, LangchainFailureClassMapping.from(null));
    }
}
