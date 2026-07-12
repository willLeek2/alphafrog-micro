package world.willfrog.agentlangchain.tooljob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

/**
 * Shared terminal result validation used by both Reconciler and StartupRecovery.
 * Validates status match, taskId match, and payload completeness before a result
 * is passed to the finalizer.
 */
final class ToolJobResultValidator {

    private static final Logger log = LoggerFactory.getLogger(ToolJobResultValidator.class);

    private ToolJobResultValidator() {}

    /**
     * Validates a terminal result response against the expected taskId and status.
     * @return the validated response, or null if validation fails (retry later)
     */
    static TaskResultResponse validate(String requestedTaskId, String runId,
                                        TaskResultResponse resp, String expectedStatus) {
        if (resp == null) {
            log.debug("validateTerminalResult: null response for taskId={}", requestedTaskId);
            return null;
        }

        // taskId must match the requested task
        String respTaskId = resp.getTaskId();
        if (respTaskId == null || respTaskId.isBlank()) {
            log.warn("validateTerminalResult: empty taskId in response for run={}", runId);
            return null;
        }
        if (!respTaskId.equals(requestedTaskId)) {
            log.warn("validateTerminalResult: taskId mismatch requested={} got={} for run={}",
                    requestedTaskId, respTaskId, runId);
            return null;
        }

        // Status must be non-empty and match expected
        String status = resp.getStatus();
        if (status == null || status.isBlank()) {
            log.warn("validateTerminalResult: empty status for taskId={}, run={}", requestedTaskId, runId);
            return null;
        }
        if (!status.equals(expectedStatus)) {
            log.warn("validateTerminalResult: status mismatch for taskId={} expected={} got={}",
                    requestedTaskId, expectedStatus, status);
            return null;
        }

        // SUCCEEDED must have stdout or datasetDir
        if ("SUCCEEDED".equals(status)) {
            String stdout = resp.getStdout();
            String ds = resp.getDatasetDir();
            if ((stdout == null || stdout.isBlank()) && (ds == null || ds.isBlank())) {
                log.warn("validateTerminalResult: SUCCEEDED with no stdout/datasetDir for taskId={}", requestedTaskId);
                return null;
            }
        }

        // FAILED/CANCELED must have error or stderr
        if ("FAILED".equals(status) || "CANCELED".equals(status)) {
            if ((resp.getError() == null || resp.getError().isBlank())
                    && (resp.getStderr() == null || resp.getStderr().isBlank())) {
                log.warn("validateTerminalResult: {} with no error/stderr for taskId={}, retrying",
                        status, requestedTaskId);
                return null;
            }
        }

        return resp;
    }
}
