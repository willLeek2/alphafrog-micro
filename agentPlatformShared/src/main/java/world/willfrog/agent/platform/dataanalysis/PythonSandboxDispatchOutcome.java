package world.willfrog.agent.platform.dataanalysis;

import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

public sealed interface PythonSandboxDispatchOutcome
        permits PythonSandboxDispatchOutcome.Completed, PythonSandboxDispatchOutcome.Pending {

    record Completed(
            String taskId,
            String outputJson,
            DataAnalysisResourceUsage resourceUsage)
            implements PythonSandboxDispatchOutcome {

        public Completed {
            taskId = DataAnalysisContractSupport.requireText(taskId, "taskId");
            outputJson = DataAnalysisContractSupport.requireText(outputJson, "outputJson");
        }
    }

    record Pending(
            String operationId,
            String requestFingerprint,
            String taskId,
            long timeoutAtMillis,
            long nextPollAtMillis,
            AgentRunDatasetSnapshot datasetSnapshot,
            DataAnalysisReservation reservation)
            implements PythonSandboxDispatchOutcome {

        public Pending {
            operationId = DataAnalysisContractSupport.requireText(operationId, "operationId");
            requestFingerprint = DataAnalysisContractSupport.requireText(
                    requestFingerprint,
                    "requestFingerprint");
            taskId = DataAnalysisContractSupport.requireText(taskId, "taskId");
            if (timeoutAtMillis <= 0) {
                throw new IllegalArgumentException("timeoutAtMillis must be positive");
            }
            if (nextPollAtMillis <= 0 || nextPollAtMillis > timeoutAtMillis) {
                throw new IllegalArgumentException(
                        "nextPollAtMillis must be positive and not exceed timeoutAtMillis");
            }
            if (datasetSnapshot == null) {
                throw new IllegalArgumentException("datasetSnapshot must not be null");
            }
            if (reservation == null) {
                throw new IllegalArgumentException("reservation must not be null");
            }
            if (reservation.taskId() == null || !reservation.taskId().equals(taskId)) {
                throw new IllegalArgumentException("reservation taskId must match pending taskId");
            }
        }
    }
}
