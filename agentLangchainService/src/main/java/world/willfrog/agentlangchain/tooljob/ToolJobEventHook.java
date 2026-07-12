package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * Hook for Codex appendOnce to emit the logical terminal event.
 * Default stub returns false (blocks finalizer CAS).
 * When Codex wires a real implementation, it returns true on success.
 *
 * <p><b>Idempotency:</b> The finalizer may re-enter this hook if the subsequent
 * DB step-marker write fails after a successful event emission. Implementations
 * MUST be idempotent on the stable key
 * {@code (runId, anchor.getToolCallId(), "terminal")} —
 * i.e. {@code runId:toolCallId:logical_terminal}.
 * A repeated call with the same key must produce a single durable event,
 * not a duplicate.</p>
 */
@FunctionalInterface
public interface ToolJobEventHook {
    /**
     * @param runId  the agent run identifier
     * @param anchor the durable anchor (use {@code runId + ":" + anchor.getToolCallId()
     *               + ":terminal"} as the stable idempotency key)
     * @return true if the terminal event was successfully emitted
     */
    boolean emitTerminalEvent(String runId, ToolJobAnchor anchor);
}
