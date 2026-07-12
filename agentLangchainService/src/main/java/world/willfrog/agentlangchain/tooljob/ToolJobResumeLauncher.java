package world.willfrog.agentlangchain.tooljob;

/**
 * Callback seam implemented by Codex pipeline to launch and track suspended runs.
 * T3 resume service uses this to launch resumed execution and check active status.
 */
public interface ToolJobResumeLauncher {
    /**
     * Launch a suspended run with the given resume context.
     * The implementation should: skip planner, restore completed todos,
     * inject terminal result into current todo, and resume execution.
     *
     * @return true if launch was accepted, false if rejected
     */
    boolean launch(String runId, ToolJobResumeContext context);

    /**
     * Queries whether the launcher is still actively processing the given
     * (runId, token, version) claim. Used by stale LAUNCHING detection:
     * even if the claimedAt TTL has passed, the launcher may still be
     * running (e.g. long sandbox execution). The rollback is only safe
     * when both the TTL has expired AND the launcher reports inactive.
     *
     * <p>Default: returns false (no launcher wired → always consider inactive).
     * A real launcher implementation should track active claims and return
     * true while execution is still in progress.</p>
     *
     * @param runId   the agent run identifier
     * @param token   the resume token being queried
     * @param version the lease version being queried
     * @return true if the launcher is still actively processing this claim
     */
    default boolean isActive(String runId, String token, long version) {
        return false;
    }
}
