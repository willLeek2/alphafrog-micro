package world.willfrog.agent.platform.dataanalysis;

import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

public sealed interface PythonSandboxDispatchOutcome
        permits PythonSandboxDispatchOutcome.Completed, PythonSandboxDispatchOutcome.Pending {

    record Completed(
            String operationId,
            String requestFingerprint,
            String taskId,
            String outputJson,
            DataAnalysisResourceUsage resourceUsage,
            DataAnalysisReservation reservation)
            implements PythonSandboxDispatchOutcome {

        public Completed {
            operationId = DataAnalysisContractSupport.requireText(operationId, "operationId");
            requestFingerprint = DataAnalysisContractSupport.requireText(
                    requestFingerprint,
                    "requestFingerprint");
            taskId = DataAnalysisContractSupport.requireText(taskId, "taskId");
            outputJson = DataAnalysisContractSupport.requireText(outputJson, "outputJson");
            validateReservation(operationId, taskId, reservation);
            if (reservation.state() != DataAnalysisReservationState.TERMINAL_CONFIRMED) {
                throw new IllegalArgumentException("completed reservation must be terminal-confirmed");
            }
            if (resourceUsage == null) {
                throw new IllegalArgumentException("resourceUsage must not be null");
            }
            if (resourceUsage.resourceClass() != reservation.resourceClass()) {
                throw new IllegalArgumentException("resource usage class must match reservation");
            }
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
            validateReservation(operationId, taskId, reservation);
            if (reservation.state() != DataAnalysisReservationState.PENDING_TRANSFERRED) {
                throw new IllegalArgumentException("pending reservation must be transferred");
            }
        }
    }

    private static void validateReservation(
            String operationId,
            String taskId,
            DataAnalysisReservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("reservation must not be null");
        }
        if (!reservation.operationId().equals(operationId)) {
            throw new IllegalArgumentException("reservation operationId must match dispatch operationId");
        }
        if (reservation.taskId() == null || !reservation.taskId().equals(taskId)) {
            throw new IllegalArgumentException("reservation taskId must match dispatch taskId");
        }
        if (!reservation.reservationId().equals(reservation.identity().reservationId())) {
            throw new IllegalArgumentException("reservationId must match dispatch identity");
        }
    }
}
