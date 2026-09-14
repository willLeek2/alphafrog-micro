package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TodoRetryPolicyTest {

    @Test
    void retriesOneClassifiedFailureOnlyForReplaySafeTool() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        TodoRetryPolicy policy = new TodoRetryPolicy(
                new LangchainFailureMapper(), new ToolRetrySafetyCatalog(), provider);

        TodoRetryPolicy.Decision safe = policy.evaluate("todo_1", failure(
                "searchWeb", "{\"query\":\"alpha\"}", "upstream timeout"));
        TodoRetryPolicy.Decision unsafe = policy.evaluate("todo_1", failure(
                "spawnSubAgent", "{\"goal\":\"alpha\"}", "upstream timeout"));

        assertThat(safe.retry()).isTrue();
        assertThat(safe.safety()).isEqualTo(ToolRetrySafety.READ_ONLY);
        assertThat(safe.previousArguments()).contains("alpha");
        assertThat(unsafe.retry()).isFalse();
        assertThat(unsafe.safety()).isEqualTo(ToolRetrySafety.UNSAFE);

        policy.recordAttempt(safe);
        policy.recordFailure(safe);
        assertThat(registry.get("agent.todo.retry.attempts").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.todo.retry.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void nonRetryableFailureDoesNotRetryEvenForReadOnlyTool() {
        @SuppressWarnings("unchecked")
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        TodoRetryPolicy policy = new TodoRetryPolicy(
                new LangchainFailureMapper(), new ToolRetrySafetyCatalog(), provider);

        TodoRetryPolicy.Decision decision = policy.evaluate("todo_1", failure(
                "searchWeb", "{}", "tool execution failed"));

        assertThat(decision.retry()).isFalse();
    }

    private TodoToolExecutionException failure(String name, String arguments, String message) {
        return new TodoToolExecutionException(new IllegalStateException(message),
                ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build());
    }
}
