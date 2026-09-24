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
                new AgentRunEventProjectionRepair(
                        eventMapper, eventRedisStore, identityProvider, 1, 16, 2000);
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
     * 一个补不进去的坏行不能把扫描挡住：这一行记下来，游标照常往前走。
     *
     * <p>满页里出现坏行时游标仍然推进到这一页末尾——满页外的下一页必须能被看到，
     * 不然一个永远补不进去的行会把保留期里更老的所有缺口一起关在门外。</p>
     */
    @Test
    void aFailingRowDoesNotStopTheScan() {
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
                .containsEntry("receivedProjectionRepairAddedTotal", 1L)
                .as("最新一页里失败的行也进重试清单")
                .containsEntry("receivedProjectionRepairFailedPending", 1);

        // 游标页取满且带一个坏行：游标仍然走到这一页末尾，不停在坏行前面。
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of(bad));
        AgentRunEventProjectionRepair singleRowRepair =
                new AgentRunEventProjectionRepair(
                        eventMapper, eventRedisStore, identityProvider, 1, 16, 2000);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt())).thenReturn(List.of());
        singleRowRepair.repair();
        assertThat(singleRowRepair.snapshot())
                .as("游标走到这一页末尾：坏行不会把满页外的下一页挡在外面")
                .containsEntry("receivedProjectionRepairCursor", bad.getCreatedAt().toString());
    }

    /**
     * 坏行的重试走的是库里当前那一行：下一轮重新读回来再补，补上就从清单里去掉。
     */
    @Test
    void aFailedRowIsRetriedFromTheDatabaseOnTheNextRound() {
        AgentRunEvent bad = received("run-bad", 1, 31L);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(bad));
        when(eventRedisStore.repairMissing(bad)).thenThrow(new IllegalStateException("这一行写不进去"));
        repair.repair();
        assertThat(repair.snapshot()).containsEntry("receivedProjectionRepairFailedPending", 1);

        // 库里那一行还是能被读回来的：下一轮读回换了个对象再补一次，这次成功。
        AgentRunEvent freshCopy = received("run-bad", 1, 31L);
        when(eventMapper.findByRunIdAndSeq("run-bad", 1)).thenReturn(freshCopy);
        when(eventRedisStore.repairMissing(freshCopy)).thenReturn(true);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt())).thenReturn(List.of());

        assertThat(repair.repair())
                .as("重试补上的也算这一轮补投")
                .isEqualTo(1);
        assertThat(repair.snapshot())
                .containsEntry("receivedProjectionRepairFailedRetriedTotal", 1L)
                .containsEntry("receivedProjectionRepairFailedPending", 0);
    }

    /** 清单里那一行在库中已经读不回来：没有可补的东西，从清单里去掉，不当成失败。 */
    @Test
    void aRowThatIsGoneFromTheDatabaseLeavesTheRetryList() {
        AgentRunEvent bad = received("run-vanished", 1, 33L);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt()))
                .thenReturn(List.of(bad));
        when(eventRedisStore.repairMissing(bad)).thenThrow(new IllegalStateException("这一行写不进去"));
        repair.repair();

        when(eventMapper.findByRunIdAndSeq("run-vanished", 1)).thenReturn(null);
        when(eventMapper.listReceivedFactsForRepair(anyString(), any(), anyInt())).thenReturn(List.of());
        assertThat(repair.repair()).isZero();
        assertThat(repair.snapshot())
                .containsEntry("receivedProjectionRepairFailedPending", 0)
                .containsEntry("receivedProjectionRepairFailedAbandonedTotal", 0L);
    }

    /**
     * 一轮里能往后走多页：一拍一页时，只要新事实来得比扫得快，游标就永远落在窗口中段，
     * 中间那一截轮不到就被保留期甩掉。这一轮走两页，两页都在同一轮里补上了。
     */
    @Test
    void oneRoundWalksSeveralCursorPages() {
        AgentRunEvent first = received("run-p1", 1, 61L);
        AgentRunEvent second = received("run-p2", 1, 62L);
        AgentRunEvent third = received("run-p3", 1, 63L);
        when(eventRedisStore.repairMissing(any())).thenReturn(true);
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of(first, second), List.of(third));
        AgentRunEventProjectionRepair walker = new AgentRunEventProjectionRepair(
                eventMapper, eventRedisStore, identityProvider, 2, 16, 2000);

        assertThat(walker.repair())
                .as("一轮里把两页都补了，不是只补一页")
                .isEqualTo(3);
        // 第一页取满、第二页没取满 ⇒ 扫到尾巴，游标回到起点等下一轮。
        assertThat(walker.snapshot())
                .containsEntry("receivedProjectionRepairPagesThisRound", 2)
                .containsEntry("receivedProjectionRepairCycles", 1L)
                .containsEntry("receivedProjectionRepairCursor", "start");
    }

    /** 页数上限真的会拦住这一轮：到了就收工，剩下的留给下一拍。 */
    @Test
    void aRoundStopsAtItsPageBudget() {
        AgentRunEvent row = received("run-pages", 1, 71L);
        when(eventRedisStore.repairMissing(any())).thenReturn(true);
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenReturn(List.of(row));
        AgentRunEventProjectionRepair bounded = new AgentRunEventProjectionRepair(
                eventMapper, eventRedisStore, identityProvider, 1, 3, 2000);

        bounded.repair();
        verify(eventMapper, Mockito.times(3)).listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt());
        assertThat(bounded.snapshot())
                .as("到了页数上限就停，不是无限往后走")
                .containsEntry("receivedProjectionRepairPagesThisRound", 3)
                .containsEntry("receivedProjectionRepairMaxPagesPerRound", 3);
    }

    /** 一轮的时间用完也停：不能为了追进度一直占着调度线程。 */
    @Test
    void aRoundStopsWhenItsTimeIsUp() {
        when(eventRedisStore.repairMissing(any())).thenReturn(true);
        when(eventMapper.listReceivedFactsAfterCursor(
                anyString(), any(), any(), Mockito.anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(40);
                    return List.of(received("run-slow-page", 1, 81L));
                });
        AgentRunEventProjectionRepair timeBoxed = new AgentRunEventProjectionRepair(
                eventMapper, eventRedisStore, identityProvider, 1, 16, 20);

        timeBoxed.repair();
        assertThat(timeBoxed.snapshot())
                .as("一页就超过了整轮的预算：走完这一页就收工")
                .containsEntry("receivedProjectionRepairPagesThisRound", 1)
                .containsEntry("receivedProjectionRepairRoundBudgetMs", 20L);
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
