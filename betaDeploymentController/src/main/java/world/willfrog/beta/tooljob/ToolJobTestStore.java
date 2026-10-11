package world.willfrog.beta.tooljob;

import world.willfrog.beta.core.BetaDeploymentService.ToolJobTestTarget;

public interface ToolJobTestStore {
    FaultRecord armFault(ToolJobTestTarget target, String runId, String checkpoint,
                         String action, int ttlSeconds);
    FaultRecord readFault(ToolJobTestTarget target, String runId, String scenarioId);
    PlanInvalidation invalidatePlan(ToolJobTestTarget target, String runId,
                                    int expectedPlanGeneration, long expectedRunControlVersion,
                                    String expectedOperationId);

    record FaultRecord(String scenarioId, String deploymentId, String trafficScopeId,
                       String generationId, String runId, String checkpoint, String action,
                       String status, String enabledAt, String expiresAt, String consumedAt,
                       String triggeredAt, String restartObservedAt, String triggerInstance) { }

    record PlanInvalidation(String deploymentId, String trafficScopeId, String generationId,
                            String runId, int previousPlanGeneration, int currentPlanGeneration,
                            long runControlVersion, String operationId) { }
}
