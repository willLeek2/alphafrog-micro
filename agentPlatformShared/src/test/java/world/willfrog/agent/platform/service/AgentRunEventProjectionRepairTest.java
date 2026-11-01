package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接收事实投射修补的行为：按库里的行把缺的成员补进持久事件流，不重发实时事件，出错不带走调度线程。
 *
 * <p>这里量的是「谁是那个消费者」这件事本身：创建那条路投射失败之后，必须有人按数据库里的行
 * 再投一次，否则事件流永远缺这一条。补投只补缺的、不续期，也不重复写已经在里面的成员。</p>
 */
class AgentRunEventProjectionRepairTest {

    private static final DeploymentIdentity IDENTITY =
            new DeploymentIdentity("stable", "gen-" + "c".repeat(64));
    private static final Duration RETENTION = Duration.ofDays(7);

    private AgentRunEventMapper eventMapper;
    private AgentRunEventRedisStore eventRedisStore;
    private DeploymentIdentityProvider identityProvider;
    private AgentRunEventProjectionRepair repair;

    @BeforeEach
    void setUp() {
        eventMapper = Mockito.mock(AgentRunEventMapper.class);
        eventRedisStore = Mockito.mock(AgentRunEventRedisStore.class);
        identityProvider = Mockito.mock(DeploymentIdentityProvider.class);
        Mockito.lenient().when(identityProvider.current()).thenReturn(IDENTITY);
        Mockito.lenient().when(eventRedisStore.retention()).thenReturn(RETENTION);
        repair = new AgentRunEventProjectionRepair(
                eventMapper, eventRedisStore, identityProvider, 200, 16, 2000);
        Mockito.lenient().when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of());
        Mockito.lenient().when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of());
    }















    private static AgentRunEvent received(String runId, int seq, long id) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId(id);
        event.setRunId(runId);
        event.setSeq(seq);
        event.setEventType("RUN_RECEIVED");
        event.setPayloadJson("{}");
        event.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
        return event;
    }
}
