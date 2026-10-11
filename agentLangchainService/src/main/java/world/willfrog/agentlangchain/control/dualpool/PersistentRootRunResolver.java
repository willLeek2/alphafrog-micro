package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.util.List;

/** Database-backed root identity for admission and fair queueing. */
@Component
public class PersistentRootRunResolver implements RootRunResolver {
    private final ChildRunIntentStore childRunIntentStore;
    private final RunOwnershipGateway ownership;

    public PersistentRootRunResolver(ChildRunIntentStore childRunIntentStore,
                                     RunOwnershipGateway ownership) {
        this.childRunIntentStore = childRunIntentStore;
        this.ownership = ownership;
    }

    @Override
    public String rootRunId(String runId) {
        return childRunIntentStore.rootRunIdOf(runId)
                .orElseThrow(() -> new IllegalStateException("run root identity is unavailable: " + runId));
    }

    @Override
    public boolean hasUnsettledDescendants(String rootRunId) {
        return childRunIntentStore.hasUnsettledDescendants(rootRunId);
    }

    @Override
    public List<String> listReservedRootRunIds(String afterRootRunId, int limit) {
        DeploymentIdentity identity = ownership.requireIdentity();
        return childRunIntentStore.listReservedRootRunIdsForDeployment(afterRootRunId, limit,
                identity.deploymentId(), identity.generationId());
    }
}
