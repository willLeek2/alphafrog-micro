package world.willfrog.agentlangchain.tooljob;

/**
 * Atomically persists a {@link ToolJobCheckpointRequest} to the durable anchor
 * before the pipeline suspends for a slow tool job.
 *
 * <p>Default stub returns false (fail-closed: blocks the pending transition).
 * When the pipeline wires a real implementation, it captures the registry
 * snapshot via {@code AgentRunDatasetRegistry.snapshot(runId)} and writes
 * {@code datasetSnapshotJson}, {@code datasetSnapshotDigest}, completed todos,
 * estimate, dataset refs, and other resume context to the anchor via
 * {@code ToolJobAnchorService.updateAnchor}.</p>
 *
 * <p>The checkpoint is consumed on resume by
 * {@link ToolJobResumeService#tryResume(String)} → buildResumeContext →
 * restoreDatasetRegistry, which calls
 * {@code AgentRunDatasetRegistry.restore(runId, snapshot)}.</p>
 */
@FunctionalInterface
public interface ToolJobCheckpointWriter {
    /**
     * Atomically writes the complete checkpoint to the anchor.
     * Called by the pipeline before suspending for a slow tool job.
     *
     * @param request the checkpoint payload (runId, todoId, completedTodos,
     *                dataset snapshot, estimate, dataset refs, toolCallsUsed)
     * @return true if the checkpoint was atomically persisted to the anchor
     */
    boolean captureAndSave(ToolJobCheckpointRequest request);
}
