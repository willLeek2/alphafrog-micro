package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.treebudget.RootTreeActivityBudget;
import world.willfrog.agent.platform.treebudget.RootTreeActivityLimitException;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MybatisNodeWorkItemActivityTest {
    private final NodeWorkItemMapper mapper = mock(NodeWorkItemMapper.class);
    private final RootTreeActivityBudget activity = mock(RootTreeActivityBudget.class);
    private final MybatisNodeWorkItemStore store = new MybatisNodeWorkItemStore(mapper, activity);
    private final NodeWorkItemIdentity identity = new NodeWorkItemIdentity("run-a", 1, "node-a", 0, 0);
    private final ServiceOwnershipFence fence = new ServiceOwnershipFence("owner", 1);









    private NodeWorkItem item(String state, int epoch) {
        NodeWorkItem item = new NodeWorkItem();
        item.setId(17L);
        item.setRunId(identity.runId());
        item.setPlanGeneration(identity.planGeneration());
        item.setNodeId(identity.nodeId());
        item.setNodeAttempt(identity.nodeAttempt());
        item.setSegmentSequence(identity.segmentSequence());
        item.setState(state);
        item.setClaimEpoch(epoch);
        item.setContextVersion(2L);
        item.setRunControlVersion(0L);
        item.setSchedulerVersion("DUAL_POOL_V2");
        return item;
    }
}
