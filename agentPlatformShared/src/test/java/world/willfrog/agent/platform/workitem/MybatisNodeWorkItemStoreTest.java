package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link MybatisNodeWorkItemStore} 的失败分类：条件更新影响行数为 0 时，到底算陈旧提交、终态、还是别的条件不匹配。
 *
 * <p>纯 mock 测试，不连数据库、不启动容器：这里验的是「0 行之后怎么判」这段判断，
 * 真实的条件更新行为由 Postgres 集成测试那一层负责。</p>
 */
@ExtendWith(MockitoExtension.class)
class MybatisNodeWorkItemStoreTest {

    private static final NodeWorkItemIdentity IDENTITY = new NodeWorkItemIdentity("run-1", 0, "node-1", 0, 0);
    /** Run 级写入要带的服务所有权凭据：这里只证明它被原样交给语句。 */
    private static final ServiceOwnershipFence FENCE = new ServiceOwnershipFence("instance-a", 7L);
    private static final SchedulerVersion VERSION = SchedulerVersion.DUAL_POOL_V1;

    @Mock
    private NodeWorkItemMapper mapper;

    private MybatisNodeWorkItemStore store;

    @BeforeEach
    void setUp() {
        store = new MybatisNodeWorkItemStore(mapper);
    }

    private NodeWorkItem row(String state, int claimEpoch) {
        NodeWorkItem item = new NodeWorkItem();
        item.setRunId(IDENTITY.runId());
        item.setPlanGeneration(IDENTITY.planGeneration());
        item.setNodeId(IDENTITY.nodeId());
        item.setNodeAttempt(IDENTITY.nodeAttempt());
        item.setSegmentSequence(IDENTITY.segmentSequence());
        item.setState(state);
        item.setClaimEpoch(claimEpoch);
        item.setContextVersion(7L);
        item.setRunControlVersion(3L);
        item.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V1.name());
        return item;
    }


















}
