package world.willfrog.agent.platform.wait;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.treebudget.RootTreeActivityBudget;
import world.willfrog.agent.platform.treebudget.RootTreeActivityLimitException;
import world.willfrog.agent.platform.treebudget.RootTreeBudgetStore;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MybatisWaitGroupActivityTest {
    private final WaitGroupMapper mapper = mock(WaitGroupMapper.class);
    private final NodeWorkItemMapper workItems = mock(NodeWorkItemMapper.class);
    private final RootTreeActivityBudget activity = mock(RootTreeActivityBudget.class);
    private final MybatisWaitGroupStore store = new MybatisWaitGroupStore(mapper, workItems, activity);
    private final NodeWorkItemIdentity segment = new NodeWorkItemIdentity("run-a", 1, "node-a", 0, 0);




    private WaitSuspensionRequest request() {
        return new WaitSuspensionRequest(segment, new NodeWorkItemVersions(1, 0, 1), "worker", 2,
                SchedulerVersion.DUAL_POOL_V2,
                List.of(new WaitMemberDraft(0, "call-a", "executePython", "op-a")),
                "{}", "{}");
    }
}
