package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * Hook for Codex appendOnce to emit the logical terminal event.
 * Default stub returns false (blocks finalizer CAS).
 * When Codex wires a real implementation, it returns true on success.
 */
@FunctionalInterface
public interface ToolJobEventHook {
    /** @return true if the terminal event was successfully emitted */
    boolean emitTerminalEvent(String runId, ToolJobAnchor anchor);
}
