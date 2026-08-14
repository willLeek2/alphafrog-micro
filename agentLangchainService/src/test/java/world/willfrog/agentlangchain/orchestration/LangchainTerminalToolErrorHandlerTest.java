package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolErrorContext;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LangchainTerminalToolErrorHandlerTest {

    @Test
    void handle_shouldRethrowBudgetExceededSignal() {
        IllegalStateException error = new IllegalStateException("RUN_BUDGET_EXCEEDED:tool_calls:30/30");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> LangchainTerminalToolErrorHandler.handle(error, null));

        assertEquals("RUN_BUDGET_EXCEEDED:tool_calls:30/30", thrown.getMessage());
    }

    @Test
    void handle_shouldRethrowInterruptedSignalFromCause() {
        RuntimeException error = new RuntimeException(
                "wrapped",
                new IllegalStateException("RUN_INTERRUPTED:CANCELING"));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> LangchainTerminalToolErrorHandler.handle(error, null));

        assertEquals("wrapped", thrown.getMessage());
    }

    @Test
    void handle_shouldEscalateOrdinaryToolErrorToTodoBoundaryWithRequestContext() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("searchWeb")
                .arguments("{\"query\":\"alpha\"}")
                .build();
        ToolErrorContext context = ToolErrorContext.builder()
                .toolExecutionRequest(request)
                .invocationContext(InvocationContext.builder()
                        .userMessage(UserMessage.from("test"))
                        .timestampNow()
                        .build())
                .build();

        TodoToolExecutionException thrown = assertThrows(
                TodoToolExecutionException.class,
                () -> LangchainTerminalToolErrorHandler.handle(
                        new IllegalArgumentException("invalid parameter"), context));

        assertEquals("invalid parameter", thrown.getMessage());
        assertEquals("searchWeb", thrown.getToolName());
        assertEquals("{\"query\":\"alpha\"}", thrown.getToolArguments());
    }

    @Test
    void handle_shouldRethrowPendingSignalFromCause() {
        ExternalToolJobPendingException pending =
                new ExternalToolJobPendingException("r1", "tc1", 2, "pending");
        RuntimeException wrapper = new RuntimeException("wrapped", pending);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> LangchainTerminalToolErrorHandler.handle(wrapper, null));

        assertEquals("wrapped", thrown.getMessage());
    }
}
