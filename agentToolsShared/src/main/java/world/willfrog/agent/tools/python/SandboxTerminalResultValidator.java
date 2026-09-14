package world.willfrog.agent.tools.python;

import lombok.extern.slf4j.Slf4j;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

/** Canonical terminal-result proof validation shared by fast and background paths. */
@Slf4j
public final class SandboxTerminalResultValidator {

    private SandboxTerminalResultValidator() {
    }

    /**
     * @return the validated response, or {@code null} when identity, status, or
     * terminal payload completeness cannot be proven.
     */
    public static TaskResultResponse validate(
            String requestedTaskId,
            String runId,
            TaskResultResponse response,
            String expectedStatus) {
        if (response == null) {
            log.debug("validateTerminalResult: null response for taskId={}", requestedTaskId);
            return null;
        }
        String responseTaskId = response.getTaskId();
        if (responseTaskId == null || responseTaskId.isBlank()) {
            log.warn("validateTerminalResult: empty taskId in response for run={}", runId);
            return null;
        }
        if (!responseTaskId.equals(requestedTaskId)) {
            log.warn("validateTerminalResult: taskId mismatch requested={} got={} for run={}",
                    requestedTaskId, responseTaskId, runId);
            return null;
        }

        String status = response.getStatus();
        if (status == null || status.isBlank()) {
            log.warn("validateTerminalResult: empty status for taskId={}, run={}",
                    requestedTaskId, runId);
            return null;
        }
        if (!status.equals(expectedStatus)) {
            log.warn("validateTerminalResult: status mismatch for taskId={} expected={} got={}",
                    requestedTaskId, expectedStatus, status);
            return null;
        }

        if ("SUCCEEDED".equals(status)
                && response.getStdout().isBlank()
                && response.getDatasetDir().isBlank()) {
            log.warn("validateTerminalResult: SUCCEEDED with no stdout/datasetDir for taskId={}",
                    requestedTaskId);
            return null;
        }
        if (("FAILED".equals(status) || "CANCELED".equals(status))
                && response.getError().isBlank()
                && response.getStderr().isBlank()) {
            log.warn("validateTerminalResult: {} with no error/stderr for taskId={}",
                    status, requestedTaskId);
            return null;
        }
        return response;
    }
}
