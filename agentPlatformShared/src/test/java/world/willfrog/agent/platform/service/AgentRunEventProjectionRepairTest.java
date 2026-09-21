package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接收事实投射修补的行为：把窗口内的行补投进持久事件流，不重发实时事件，出错不带走调度线程。
 *
 * <p>这里量的是「谁是那个消费者」这件事本身：创建那条路投射失败之后，必须有人按数据库里的行
 * 再投一次，否则事件流永远缺这一条。</p>
 */
class AgentRunEventProjectionRepairTest {

    private static final DeploymentIdentity IDENTITY =
            new DeploymentIdentity("stable", "gen-" + "c".repeat(64));

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
        repair = new AgentRunEventProjectionRepair(eventMapper, eventRedisStore, identityProvider, 7, 200);
        Mockito.lenient().when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void everyRowInTheWindowIsAppendedBackIntoTheStream() {
        AgentRunEvent first = received("run-a", 1);
        AgentRunEvent second = received("run-b", 1);
        when(eventMapper.listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(first, second));

        assertThat(repair.repair())
                .as("这一轮补投的条数就是读回来的行数")
                .isEqualTo(2);

        ArgumentCaptor<AgentRunEvent> appended = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRedisStore, Mockito.times(2)).append(appended.capture());
        assertThat(appended.getAllValues()).containsExactly(first, second);
        assertThat(repair.snapshot())
                .containsEntry("receivedProjectionRepairedTotal", 2L)
                .containsEntry("receivedProjectionRepairRounds", 1L);
    }

    @Test
    void theScanIsBoundToTheEventStreamRetentionAndToThisDeployment() {
        OffsetDateTime retentionUpperBound = OffsetDateTime.now().minusDays(7).plusMinutes(1);
        OffsetDateTime retentionLowerBound = OffsetDateTime.now().minusDays(7).minusMinutes(1);
        when(eventMapper.listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of());

        repair.repair();

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(eventMapper).listReceivedFactsForRepair(
                Mockito.eq(IDENTITY.deploymentId()), Mockito.eq(IDENTITY.generationId()),
                since.capture(), Mockito.eq(200));
        verify(eventMapper).listReceivedFactsAfterCursor(
                Mockito.eq(IDENTITY.deploymentId()), Mockito.eq(IDENTITY.generationId()),
                Mockito.eq(since.getValue()), Mockito.eq(since.getValue()), Mockito.eq(0L),
                Mockito.eq(200));
        assertThat(since.getValue())
                .as("扫描边界绑在事件流自己的保留期上，不是随手一个「最近几分钟」")
                .isAfter(retentionLowerBound)
                .isBefore(retentionUpperBound);
    }

    @Test
    void theCursorWalksForwardAndRestartsAtTheRetentionEdgeOnceExhausted() {
        // 第一轮：游标页刚好取满，游标停在最后一行的位置。
        AgentRunEvent cursorRow = received("run-cursor", 1);
        when(eventMapper.listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of());
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of(cursorRow));
        // 只有一页（limit=1），所以这一页就是「取满」，游标前进。
        AgentRunEventProjectionRepair singleRowRepair =
                new AgentRunEventProjectionRepair(eventMapper, eventRedisStore, identityProvider, 7, 1);
        singleRowRepair.repair();
        assertThat(singleRowRepair.snapshot())
                .as("游标停在这一页最后一行上，下一轮从这里往后扫")
                .containsEntry("receivedProjectionRepairCursorBehind", cursorRow.getCreatedAt().toString())
                .containsEntry("receivedProjectionRepairCycles", 0L);

        // 下一轮：游标页没取满 ⇒ 这一段扫到尾巴，游标回到保留期起点。
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of());
        singleRowRepair.repair();
        assertThat(singleRowRepair.snapshot())
                .as("扫到尾巴就从头再来，保留期内的行不会被漏掉")
                .containsEntry("receivedProjectionRepairCursorBehind", "start")
                .containsEntry("receivedProjectionRepairCycles", 1L);
    }

    @Test
    void nothingIsAppendedWhenTheWindowIsEmpty() {
        when(eventMapper.listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of());
        assertThat(repair.repair()).isZero();
        verify(eventRedisStore, Mockito.never()).append(any());
    }

    @Test
    void aFailingRoundIsSwallowedAndTheNextRoundStillRuns() {
        when(eventMapper.listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("库读不了"));

        assertThat(repair.safeRepair())
                .as("修补自己出错不能把调度线程带走")
                .isZero();
        assertThat(repair.snapshot())
                .as("这一轮确实跑过（读库时就失败了），但什么都没补投")
                .containsEntry("receivedProjectionRepairRounds", 1L)
                .containsEntry("receivedProjectionRepairedTotal", 0L);
    }

    @Test
    void anUnavailableDeploymentIdentityLeavesTheStreamUntouched() {
        when(identityProvider.current()).thenThrow(new IllegalStateException("部署身份还没就绪"));
        assertThat(repair.safeRepair()).isZero();
        verify(eventMapper, Mockito.never())
                .listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt());
    }

    /** 补投只走事件流的写入，不碰实时频道：这里的事件流替身上没有任何发布入口，能编译就说明没走那条路。 */
    @Test
    void repairOnlyWritesTheDurableStream() {
        when(eventMapper.listReceivedFactsForRepair(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of(received("run-c", 1)));
        repair.repair();
        verify(eventRedisStore).append(any());
        verify(eventRedisStore, Mockito.never()).flush(anyString());
    }

    private static AgentRunEvent received(String runId, int seq) {
        AgentRunEvent event = new AgentRunEvent();
        event.setId((long) seq);
        event.setRunId(runId);
        event.setSeq(seq);
        event.setEventType("RUN_RECEIVED");
        event.setPayloadJson("{}");
        event.setCreatedAt(OffsetDateTime.now());
        return event;
    }
}
