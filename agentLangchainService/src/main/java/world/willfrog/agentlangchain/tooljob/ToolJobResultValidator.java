package world.willfrog.agentlangchain.tooljob;

import world.willfrog.agent.tools.python.SandboxTerminalResultValidator;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

/**
 * Shared terminal result validation used by both Reconciler and StartupRecovery.
 * Validates status match, taskId match, and payload completeness before a result
 * is passed to the finalizer.
 */
final class ToolJobResultValidator {

    private ToolJobResultValidator() {}

    /**
     * Validates a terminal result response against the expected taskId and status.
     * @return the validated response, or null if validation fails (retry later)
     */
    static TaskResultResponse validate(String requestedTaskId, String runId,
                                        TaskResultResponse resp, String expectedStatus) {
        return SandboxTerminalResultValidator.validate(
                requestedTaskId, runId, resp, expectedStatus);
    }
}
