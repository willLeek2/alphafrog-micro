package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.wait.WaitChainCancelResult;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoppedRunWaitGroupReconcilerTest {
    private final WaitGroupStore groups = mock(WaitGroupStore.class);




    private static WaitGroup group(long id, String runId) {
        WaitGroup group = new WaitGroup();
        group.setId(id);
        group.setRunId(runId);
        return group;
    }
}
