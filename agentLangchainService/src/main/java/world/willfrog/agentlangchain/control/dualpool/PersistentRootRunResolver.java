package world.willfrog.agentlangchain.control.dualpool;

import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;

import java.util.List;

/** Database-backed root identity for admission and fair queueing. */
@Component
public class PersistentRootRunResolver implements RootRunResolver {
    private final ChildRunIntentStore childRunIntentStore;

    public PersistentRootRunResolver(ChildRunIntentStore childRunIntentStore) {
        this.childRunIntentStore = childRunIntentStore;
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
        return childRunIntentStore.listReservedRootRunIds(afterRootRunId, limit);
    }
}
