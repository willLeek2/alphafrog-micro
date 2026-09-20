package world.willfrog.beta.tooljob;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import world.willfrog.beta.core.BetaDeploymentService;
import world.willfrog.beta.core.BetaDeploymentService.ToolJobTestTarget;
import world.willfrog.beta.core.ContainerRuntime;

@Service
@ConditionalOnProperty(prefix = "alphafrog.beta-controller.tool-job-test-control",
        name = "enabled", havingValue = "true")
public class ToolJobTestControlService {
    private final BetaDeploymentService deployments;
    private final ToolJobTestStore store;

    public ToolJobTestControlService(BetaDeploymentService deployments, ToolJobTestStore store) {
        this.deployments = deployments;
        this.store = store;
    }

    public FaultEvidence arm(String deploymentId, String generationId, String runId,
                             String checkpoint, String action, int ttlSeconds) {
        ToolJobTestTarget target = deployments.toolJobTestTarget(deploymentId, generationId,
                "PROCESS_HALT".equals(action));
        return new FaultEvidence(store.armFault(target, runId, checkpoint, action, ttlSeconds), evidence(target));
    }

    public FaultEvidence read(String deploymentId, String generationId, String runId, String scenarioId) {
        ToolJobTestTarget target = deployments.toolJobTestTarget(deploymentId, generationId, false);
        return new FaultEvidence(store.readFault(target, runId, scenarioId), evidence(target));
    }

    public ToolJobTestStore.PlanInvalidation invalidatePlan(String deploymentId, String generationId,
                                                            String runId, int expectedPlanGeneration,
                                                            long expectedRunControlVersion,
                                                            String expectedOperationId) {
        ToolJobTestTarget target = deployments.toolJobTestTarget(deploymentId, generationId, false);
        return store.invalidatePlan(target, runId, expectedPlanGeneration,
                expectedRunControlVersion, expectedOperationId);
    }

    public RuntimeEvidence restartAgent(String deploymentId, String generationId) {
        return evidence(deployments.restartToolJobAgent(deploymentId, generationId));
    }

    private RuntimeEvidence evidence(ToolJobTestTarget target) {
        ContainerRuntime.ToolJobTestRuntime runtime = target.runtime();
        return new RuntimeEvidence(target.deploymentId(), target.trafficScopeId(), target.generationId(),
                target.gitCommit(), runtime.containerId(), runtime.running(), runtime.healthy(),
                runtime.durableRecoveryEnabled(), runtime.faultInjectionEnabled(), runtime.processHaltEnabled(),
                runtime.restartUnlessStopped(), runtime.restartCount(), runtime.startedAt());
    }

    public record FaultEvidence(ToolJobTestStore.FaultRecord fault, RuntimeEvidence runtime) { }

    public record RuntimeEvidence(String deploymentId, String trafficScopeId, String generationId,
                                  String gitCommit, String containerId, boolean running, boolean healthy,
                                  boolean durableRecoveryEnabled, boolean faultInjectionEnabled,
                                  boolean processHaltEnabled, boolean restartUnlessStopped,
                                  long restartCount, String startedAt) { }
}
