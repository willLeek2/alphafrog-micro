package world.willfrog.agentlangchain.tooljob;

/**
 * Callback seam implemented by Codex pipeline to actually launch a suspended run.
 * T3 resume service calls this after preparing the resume context.
 */
@FunctionalInterface
public interface ToolJobResumeLauncher {
    /**
     * Launch a suspended run with the given resume context.
     * The implementation should: skip planner, restore completed todos,
     * inject terminal result into current todo, and resume execution.
     *
     * @return true if launch was accepted, false if rejected
     */
    boolean launch(String runId, ToolJobResumeContext context);
}
