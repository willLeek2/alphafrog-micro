package world.willfrog.agentlangchain.tooljob;

/**
 * Captures the current {@code AgentRunDatasetRegistry} state and persists it
 * to the durable anchor before the pipeline suspends for a slow tool job.
 *
 * <p>Default stub returns false (fail-closed: blocks the pending transition).
 * When the pipeline wires a real implementation, it captures the registry
 * snapshot via {@code AgentRunDatasetRegistry.snapshot(runId)} and writes
 * {@code datasetSnapshotJson} + {@code datasetSnapshotDigest} to the anchor
 * via {@code ToolJobAnchorService.updateAnchor}.</p>
 *
 * <p>The snapshot is consumed on resume by
 * {@link ToolJobResumeService#tryResume(String)} → restoreDatasetRegistry,
 * which calls {@code AgentRunDatasetRegistry.restore(runId, snapshot)}.</p>
 */
@FunctionalInterface
public interface ToolJobCheckpointWriter {
    /**
     * Captures the current dataset registry state and writes it to the anchor.
     * Called by the pipeline before suspending for a slow tool job.
     *
     * @param runId the agent run identifier
     * @return true if the snapshot was captured and persisted to the anchor
     */
    boolean captureAndSave(String runId);
}
