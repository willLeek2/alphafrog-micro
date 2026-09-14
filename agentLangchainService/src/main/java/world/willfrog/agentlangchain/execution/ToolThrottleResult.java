package world.willfrog.agentlangchain.execution;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Result of {@link LangchainToolConcurrencyThrottle#tryAcquire(String)}.
 * {@code acquired()} must be checked before calling {@link LangchainToolConcurrencyThrottle#release}.
 *
 * <p>{@link #markReleased()} is called by the throttle to ensure each acquired token
 * is released exactly once. Callers should not invoke this method.</p>
 */
public final class ToolThrottleResult {

    private final String toolName;
    private final boolean acquired;
    private final boolean timeout;
    private final boolean interrupted;
    private final String failureReason;
    private final AtomicBoolean released = new AtomicBoolean();

    private ToolThrottleResult(String toolName, boolean acquired, boolean timeout, boolean interrupted,
                               String failureReason) {
        this.toolName = toolName;
        this.acquired = acquired;
        this.timeout = timeout;
        this.interrupted = interrupted;
        this.failureReason = failureReason;
    }

    /**
     * 标记许可已释放，仅首次调用返回 true。
     * 仅供 {@link world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle} 使用。
     */
    public boolean markReleased() {
        return released.compareAndSet(false, true);
    }

    public String toolName() { return toolName; }
    public boolean acquired() { return acquired; }
    public boolean timeout() { return timeout; }
    public boolean interrupted() { return interrupted; }
    public String failureReason() { return failureReason; }

    // ── factories ──

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
