package world.willfrog.agentlangchain.orchestration;

/**
 * Result of {@link LangchainToolConcurrencyThrottle#tryAcquire(String)}.
 * {@code acquired()} must be checked before calling {@link LangchainToolConcurrencyThrottle#release}.
 */
public record ToolThrottleResult(String toolName, boolean acquired, boolean timeout, boolean interrupted,
                                  String failureReason) {

    public static ToolThrottleResult notThrottled(String toolName) {
        return new ToolThrottleResult(toolName, false, false, false, null);
    }

    public static ToolThrottleResult acquired(String toolName) {
        return new ToolThrottleResult(toolName, true, false, false, null);
    }

    public static ToolThrottleResult timeout(String toolName, long waitMs, int permits) {
        return new ToolThrottleResult(toolName, false, true, false,
                "TOOL_THROTTLE_TIMEOUT: tool=" + toolName + " waitMs=" + waitMs + " permits=" + permits);
    }

    public static ToolThrottleResult interrupted(String toolName, long waitMs) {
        return new ToolThrottleResult(toolName, false, false, true,
                "TOOL_THROTTLE_INTERRUPTED: tool=" + toolName + " waitMs=" + waitMs);
    }
}
