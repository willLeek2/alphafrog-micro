package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

/**
 * Hook for T4 recorder to upsert usage data.
 * Default stub returns false (blocks finalizer CAS).
 * When T4 wires a real implementation, it returns true on success.
 *
 * <p><b>Idempotency:</b> The finalizer may re-enter this hook if the subsequent
 * DB step-marker write fails after a successful upsert. Implementations MUST be
 * idempotent on {@code runId} — a repeated call with the same runId and anchor
 * must produce a single durable record, not a duplicate.</p>
 */
@FunctionalInterface
public interface ToolJobUsageHook {
    /** @return true if usage was successfully persisted (idempotent on runId) */
    boolean upsertUsage(String runId, ToolJobAnchor anchor);
}
