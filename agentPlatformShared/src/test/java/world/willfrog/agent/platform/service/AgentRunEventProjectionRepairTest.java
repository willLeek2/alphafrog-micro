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
        repair = new AgentRunEventProjectionRepair(eventMapper, eventRedisStore, identityProvider, 200);
        Mockito.lenient().when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of());
        Mockito.lenient().when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void everyMissingRowInTheWindowIsRepairedIntoTheStream() {
        AgentRunEvent first = received("run-a", 1, 11L);
        AgentRunEvent second = received("run-b", 1, 12L);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(first, second));
        when(eventRedisStore.repairMissing(any())).thenReturn(true);

        assertThat(repair.repair())
                .as("这一轮真正补进去的条数就是读回来的行数")
                .isEqualTo(2);

        ArgumentCaptor<AgentRunEvent> repairedEvents = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventRedisStore, Mockito.times(2)).repairMissing(repairedEvents.capture());
        assertThat(repairedEvents.getAllValues()).containsExactly(first, second);
        assertThat(repair.snapshot())
                .containsEntry("receivedProjectionRepairAddedTotal", 2L)
                .containsEntry("receivedProjectionRepairExaminedTotal", 2L)
                .containsEntry("receivedProjectionRepairRounds", 1L);
    }

    @Test
    void rowsAlreadyInTheStreamAreCountedButNotRewritten() {
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(received("run-a", 1, 11L)));
        when(eventRedisStore.repairMissing(any())).thenReturn(false);

        assertThat(repair.repair())
                .as("已经在流里的成员不算补投成功")
                .isZero();
        assertThat(repair.snapshot())
                .as("「补投总数」只能是真实新增：重复扫到已经在里面的行不该被算成修复")
                .containsEntry("receivedProjectionRepairAddedTotal", 0L)
                .containsEntry("receivedProjectionRepairAlreadyPresentTotal", 1L)
                .containsEntry("receivedProjectionRepairExaminedTotal", 1L);
    }

    @Test
    void theScanIsBoundToTheEventStreamRetentionAndToThisDeployment() {
        OffsetDateTime retentionUpperBound = OffsetDateTime.now().minus(RETENTION).plusMinutes(1);
        OffsetDateTime retentionLowerBound = OffsetDateTime.now().minus(RETENTION).minusMinutes(1);

        repair.repair();

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(eventMapper).listReceivedFactsForRepair(
                Mockito.eq(IDENTITY.deploymentId()), since.capture(), Mockito.eq(200));
        // 游标页的签名里没有构建代际：滚动替换时旧代际留下的缺口也要能被扫到。
        verify(eventMapper).listReceivedFactsAfterCursor(
                Mockito.eq(IDENTITY.deploymentId()), Mockito.eq(since.getValue()),
                Mockito.eq(since.getValue()), Mockito.eq(0L), Mockito.eq(200));
        assertThat(since.getValue())
                .as("扫描边界绑在事件流自己的保留期上，与写入事件流用的是同一份值")
                .isAfter(retentionLowerBound)
                .isBefore(retentionUpperBound);
        assertThat(repair.snapshot())
                .containsEntry("receivedProjectionRetentionDays", RETENTION.toDays());
    }

    @Test
    void theCursorWalksForwardAndRestartsAtTheRetentionEdgeOnceExhausted() {
        // 第一轮：游标页刚好取满，游标停在最后一行的位置。
        AgentRunEvent cursorRow = received("run-cursor", 1, 21L);
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of(cursorRow));
        when(eventRedisStore.repairMissing(any())).thenReturn(true);
        // 只有一页（limit=1），所以这一页就是「取满」，游标前进。
        AgentRunEventProjectionRepair singleRowRepair =
                new AgentRunEventProjectionRepair(eventMapper, eventRedisStore, identityProvider, 1);
        singleRowRepair.repair();
        assertThat(singleRowRepair.snapshot())
                .as("游标停在这一页最后一行上，下一轮从这里往后扫")
                .containsEntry("receivedProjectionRepairCursor", cursorRow.getCreatedAt().toString())
                .containsEntry("receivedProjectionRepairCycles", 0L);

        // 下一轮：游标页没取满 ⇒ 这一段扫到尾巴，游标回到保留期起点。
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of());
        singleRowRepair.repair();
        assertThat(singleRowRepair.snapshot())
                .as("扫到尾巴就从头再来，保留期内的行不会被漏掉")
                .containsEntry("receivedProjectionRepairCursor", "start")
                .containsEntry("receivedProjectionRepairCycles", 1L);
    }

    /**
     * 一个反复失败的坏行不能把更老的缺口挡在外面：这一行隔离掉，后面的行照旧处理，
     * 游标停在它前面等下一轮重试。
     */
    @Test
    void aFailingRowIsIsolatedAndTheCursorStopsBeforeIt() {
        AgentRunEvent bad = received("run-bad", 1, 31L);
        AgentRunEvent good = received("run-good", 1, 32L);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(bad, good));
        when(eventRedisStore.repairMissing(bad)).thenThrow(new IllegalStateException("这一行写不进去"));
        when(eventRedisStore.repairMissing(good)).thenReturn(true);

        assertThat(repair.repair())
                .as("坏行后面的行照旧补上了")
                .isEqualTo(1);
        assertThat(repair.snapshot())
                .containsEntry("receivedProjectionRepairFailedTotal", 1L)
                .containsEntry("receivedProjectionRepairAddedTotal", 1L);

        // 游标页里出现失败时游标不前进：下一轮从同一位置重来。
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of(bad));
        repair.repair();
        assertThat(repair.snapshot())
                .as("有行失败时游标停在它前面，不让一个坏行挡住更老的缺口")
                .containsEntry("receivedProjectionRepairCursor", "start");
    }

    @Test
    void aFailingRoundIsSwallowedAndTheNextRoundStillRuns() {
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenThrow(new IllegalStateException("库读不了"));

        assertThat(repair.safeRepair())
                .as("修补自己出错不能把调度线程带走")
                .isZero();
        assertThat(repair.snapshot())
                .as("这一轮确实跑过（读库时就失败了），但什么都没补投")
                .containsEntry("receivedProjectionRepairRounds", 1L)
                .containsEntry("receivedProjectionRepairAddedTotal", 0L);
    }

    @Test
    void anUnavailableDeploymentIdentityLeavesTheStreamUntouched() {
        when(identityProvider.current()).thenThrow(new IllegalStateException("部署身份还没就绪"));
        assertThat(repair.safeRepair()).isZero();
        verify(eventMapper, Mockito.never()).listReceivedFactsForRepair(anyString(), any(), anyInt());
    }

    /**
     * 上一轮还没走完时这一拍直接跳过：启动入口与周期入口不并发推进同一个游标。
     *
     * <p>用一道闸门把第一轮卡在补投那一行上，再从另一个线程调一次；第二次必须直接返回 0，
     * 而且游标不能被两轮同时改。</p>
     */
    @Test
    void overlappingRoundsDoNotWalkTheCursorTwice() throws Exception {
        CountDownLatch inRound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(received("run-slow", 1, 41L)));
        when(eventRedisStore.repairMissing(any())).thenAnswer(invocation -> {
            inRound.countDown();
            release.await(5, TimeUnit.SECONDS);
            return true;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(repair::safeRepair);
            assertThat(inRound.await(5, TimeUnit.SECONDS)).as("第一轮已经进到补投那一步").isTrue();
            assertThat(repair.safeRepair())
                    .as("上一轮还在走：这一拍不进来")
                    .isZero();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(repair.snapshot())
                    .containsEntry("receivedProjectionRepairSkippedRounds", 1L)
                    .containsEntry("receivedProjectionRepairRounds", 1L);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 补投只走事件流的写入，不碰实时频道：这里的事件流替身上没有任何发布入口，能编译就说明没走那条路。 */
    @Test
    void repairOnlyWritesTheDurableStream() {
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(received("run-c", 1, 51L)));
        when(eventRedisStore.repairMissing(any())).thenReturn(true);
        repair.repair();
        verify(eventRedisStore).repairMissing(any());
        verify(eventRedisStore, Mockito.never()).flush(anyString());
        verify(eventRedisStore, Mockito.never()).append(any());
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
