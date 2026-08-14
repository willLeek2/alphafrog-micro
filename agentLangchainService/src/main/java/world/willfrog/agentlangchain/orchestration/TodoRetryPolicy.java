package world.willfrog.agentlangchain.orchestration;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import world.willfrog.agentlangchain.failure.LangchainFailureDecision;
import world.willfrog.agentlangchain.failure.LangchainFailureMapper;

/**
 * Todo 语义重试的唯一决策入口。只允许一次，并同时要求失败可重试、工具可安全重放。
 */
@Component
@RequiredArgsConstructor
public class TodoRetryPolicy {

    private final LangchainFailureMapper failureMapper;
    private final ToolRetrySafetyCatalog safetyCatalog;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public Decision evaluate(String todoId, TodoToolExecutionException failure) {
        String toolName = failure == null ? null : failure.getToolName();
        LangchainFailureDecision mapped = failureMapper.map(
                "todo_execution", todoId, toolName,
                failure == null ? "tool_execution_failed" : failure.getMessage(),
                null, failure, null);
        ToolRetrySafety safety = safetyCatalog.safetyOf(toolName);
        if (mapped.isRetryable()) {
            increment("agent.todo.retryable.failures", mapped.getFailureCategory(), safety.name());
        }
        return new Decision(mapped.isRetryable() && safety != ToolRetrySafety.UNSAFE,
                mapped.isRetryable(),
                mapped.getFailureCategory(), mapped.getReason(), toolName,
                failure == null ? null : failure.getToolArguments(), safety);
    }

    public void recordAttempt(Decision decision) {
        increment("agent.todo.retry.attempts", decision.failureCategory(), decision.safety().name());
    }

    public void recordFailure(Decision decision) {
        increment("agent.todo.retry.failures", decision.failureCategory(), decision.safety().name());
    }

    private void increment(String name, String category, String safety) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter(name,
                    "failure_category", category == null ? "unknown" : category,
                    "tool_safety", safety == null ? ToolRetrySafety.UNSAFE.name() : safety).increment();
        }
    }

    public record Decision(boolean retry,
                           boolean failureRetryable,
                           String failureCategory,
                           String failureSummary,
                           String toolName,
                           String previousArguments,
                           ToolRetrySafety safety) {
    }
}
