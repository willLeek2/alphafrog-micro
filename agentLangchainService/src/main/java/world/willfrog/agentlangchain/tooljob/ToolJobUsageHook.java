package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * Hook for T4 recorder to upsert usage data.
 * Default stub returns false (blocks finalizer CAS).
 * When T4 wires a real implementation, it returns true on success.
 */
@FunctionalInterface
public interface ToolJobUsageHook {
    /** @return true if usage was successfully persisted */
    boolean upsertUsage(String runId, ToolJobAnchor anchor);
}
