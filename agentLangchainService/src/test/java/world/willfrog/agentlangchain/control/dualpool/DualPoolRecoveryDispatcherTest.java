package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryNotificationState;
import world.willfrog.agent.platform.wait.RecoveryRejection;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import world.willfrog.agentlangchain.control.dualpool.TestSchedulerSettings;
import org.springframework.mock.env.MockEnvironment;

/**
 * 恢复分发器每一轮做什么：数据库补扫每轮都有固定名额、内存提醒只在剩下的预算里跑；
 * 每轮条数有上限；取不走的推后下次可见时间；不会再被服务的由受理层收口。
 *
 * <p>这里验的是分发器的控制流：一轮怎么分预算、拿到的结局怎么记数、放行的下一段怎么投。
 * 一条通知能不能取走由 {@link WaitGroupRecoveryIntake} 判定，它自己的行为在它自己的用例里量；
 * 真实的条件更新与并发行为由真 PostgreSQL 那一层回答。</p>
 */
@ExtendWith(MockitoExtension.class)
class DualPoolRecoveryDispatcherTest {

    private static final String RUN_ID = "run-recovery";
    private static final int BATCH = 2;
    private static final NodeWorkItemIdentity NEXT_SEGMENT =
            new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3);

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private DualPoolDispatcher dispatcher;
    @Mock
    private WaitGroupRecoveryIntake intake;

    private FakeWaitGroupStore store;
    private DualPoolRecoveryDispatcher recovery;

    @BeforeEach
    void setUp() {
        store = new FakeWaitGroupStore();
        lenient().when(dispatcher.isReady()).thenReturn(true);
        // 默认每轮给数据库补扫留 1 个名额，其余预算给内存提醒。
        recovery = new DualPoolRecoveryDispatcher(store, runMapper, dispatcher, intake,
                recoverySettings(BATCH, 500L, 5_000L, 4, 1, 1024), 1000L, new FrozenEffectiveSettings());
    }

    /**
     * 启动扫描真正跑的那一刻，把这一次用到的翻页数登记下来。
     *
     * <p>翻页数不是启动时冻结的字段（它每次按当前配置取），所以「在用的值」只有跑的那一刻才说得清：
     * 读数是给验收看板与排查看的，报的必须是真跑过的那个数。</p>
     */
    @Test
    void theStartupBackfillRegistersThePagesItActuallyUses() {
        FrozenEffectiveSettings inUse = new FrozenEffectiveSettings();
        DualPoolRecoveryDispatcher withReading = new DualPoolRecoveryDispatcher(store, runMapper, dispatcher,
                intake, recoverySettings(BATCH, 500L, 5_000L, 4, 1, 1024), 1000L, inUse);

        withReading.recoverOnStartup();

        assertThat(inUse.inUseBy(DualPoolSchedulerSettings.KEY_RECOVERY_STARTUP_PAGES))
                .containsEntry("DualPoolRecoveryDispatcher", 4);
    }

    @Test
    void aDueNotificationIsConsumedAndItsNextSegmentIsHandedToTheNodePool() {
        store.addNotification(11L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(WaitGroupRecoveryIntake.IntakeResult.consumed(NEXT_SEGMENT));
        when(dispatcher.offerNode(any())).thenReturn(true);

        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);

        assertThat(store.deferred).isEmpty();
        verify(dispatcher).offerNode(NEXT_SEGMENT);
        assertThat(recovery.snapshot())
                .containsEntry("recoveryConsumedTotal", 1L)
                .containsEntry("recoveryScannedTotal", 1L);
    }

    /** 投递用的是受理层返回的那一段：数据库放行了哪一段就投哪一段。 */
    @Test
    void theSegmentThatWasActuallyReleasedIsTheOneHandedToTheNodePool() {
        store.addNotification(11L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        NodeWorkItemIdentity authoritative =
                new NodeWorkItemIdentity(RUN_ID, 7, "node-authoritative", 2, 9);
        when(intake.take(any(), any(), anyString()))
                .thenReturn(WaitGroupRecoveryIntake.IntakeResult.consumed(authoritative));
        when(dispatcher.offerNode(any())).thenReturn(true);

        recovery.safeRound(BATCH);

        verify(dispatcher).offerNode(authoritative);
        verify(dispatcher, never()).offerNode(NEXT_SEGMENT);
    }

    @Test
    void aNotificationThatCannotBeConsumedIsPushedLaterInsteadOfBeingRetriedEveryRound() {
        store.addNotification(12L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.DEFERRED, null,
                        RecoveryRejection.LEASE_NOT_OWNED, "owner-elsewhere"));

        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);

        assertThat(store.deferred).containsExactly(12L);
        assertThat(store.notifications.get(12L).getNextVisibleAt())
                .as("取不走就推后，否则它会一直占着候选队头")
                .isAfter(OffsetDateTime.now());
        verify(dispatcher, never()).offerNode(any());
        assertThat(recovery.snapshot()).containsEntry("recoveryDeferredTotal", 1L);
    }

    /** 受理层收口了的通知：分发器不再推后、不再投递，只记一笔收口数。 */
    @Test
    void aNotificationThatWillNeverBeServedIsClosedAndNotDeferred() {
        store.addNotification(13L, "run-terminal", OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById("run-terminal")).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.CLOSED, null, null, "run_terminal:COMPLETED"));

        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);

        assertThat(store.deferred).isEmpty();
        verify(dispatcher, never()).offerNode(any());
        assertThat(recovery.snapshot())
                .containsEntry("recoveryClosedTotal", 1L)
                .containsEntry("recoveryDeferredTotal", 0L);
    }

    /** 别人先取走的那条：什么都不做，也不算我们的退避。 */
    @Test
    void aNotificationSomeoneElseTookIsCountedAsALostRaceNotAsABackoff() {
        store.addNotification(15L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.LOST_RACE, null,
                        RecoveryRejection.NOTIFICATION_NOT_WAITING, null));

        recovery.safeRound(BATCH);

        assertThat(store.deferred).isEmpty();
        assertThat(recovery.snapshot()).containsEntry("recoveryLostRaceTotal", 1L);
    }

    @Test
    void aWakeupIsHandledBeforeTheDatabaseScanAndOnlyOnce() {
        store.addNotification(14L, RUN_ID, OffsetDateTime.now().plusSeconds(600));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(WaitGroupRecoveryIntake.IntakeResult.consumed(NEXT_SEGMENT));

        assertThat(recovery.wake(14L)).isTrue();
        assertThat(recovery.wake(14L)).as("同一条通知只留一份提醒").isFalse();
        assertThat(recovery.pendingWakeupIds()).containsExactly(14L);

        // 这条通知还没到期，数据库扫描取不到它；唤醒这条路照样把它处理掉。
        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);
        assertThat(recovery.pendingWakeupIds()).isEmpty();
        assertThat(recovery.snapshot()).containsEntry("recoveryWakeupsHandledTotal", 1L);
    }

    @Test
    void oneRoundNeverHandlesMoreThanTheBatchSize() {
        for (long id = 21L; id <= 25L; id++) {
            store.addNotification(id, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        }
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(WaitGroupRecoveryIntake.IntakeResult.consumed(NEXT_SEGMENT));
        when(dispatcher.offerNode(any())).thenReturn(true);

        assertThat(recovery.safeRound(BATCH))
                .as("一轮最多处理这么多条，剩下的留给下一轮")
                .isEqualTo(BATCH);
    }

    /**
     * 内存提醒一直满着，数据库到期的那些也必须一轮一轮往前走。
     *
     * <p>提醒是无界的即时消息，光靠它自己排队就能把整轮预算吃光；数据库才是事实来源，所以每轮先给
     * 补扫留固定名额。这里把提醒灌满：每一轮都必须有一条到期的库行被处理，直到全部处理完。</p>
     */
    @Test
    void theDatabaseScanKeepsAdvancingEvenWhenRemindersAlwaysFillTheRound() {
        // 三条到期的库行，一条已经到期的提醒一直在队列里。
        for (long id = 31L; id <= 33L; id++) {
            store.addNotification(id, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        }
        store.addNotification(34L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(WaitGroupRecoveryIntake.IntakeResult.consumed(NEXT_SEGMENT));
        when(dispatcher.offerNode(any())).thenReturn(true);
        recovery.wake(34L);

        int handled = 0;
        for (int round = 0; round < 8 && handled < 4; round++) {
            handled += recovery.safeRound(BATCH);
        }

        assertThat(handled)
                .as("有一份固定名额，提醒再怎么排队也挡不住库里的到期行")
                .isEqualTo(4);
        assertThat(store.deferred).isEmpty();
        assertThat(recovery.snapshot())
                .containsEntry("recoveryConsumedTotal", 4L)
                .containsEntry("recoveryScanQuota", 1);
    }

    /**
     * 一批到期的库行 + 一直有货的内存提醒：每一轮都必须有一条库行被处理。
     *
     * <p>提醒是无界的即时消息，光靠它自己排队能把整轮预算吃光；固定名额就是给这种情形准备的。</p>
     */
    @Test
    void dueRowsKeepAdvancingWhileRemindersStayAvailable() {
        for (long id = 31L; id <= 33L; id++) {
            store.addNotification(id, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        }
        for (long id = 41L; id <= 50L; id++) {
            store.addNotification(id, RUN_ID, OffsetDateTime.now().plusSeconds(600));
            recovery.wake(id);
        }
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString())).thenAnswer(invocation -> {
            RecoveryNotification notification = invocation.getArgument(0);
            if (notification.getId() <= 40L) {
                // 真语句消费成功会把通知改成已取走；替身照做，否则同一行每一轮都会被当成到期。
                store.markConsumed(notification.getId());
                return WaitGroupRecoveryIntake.IntakeResult.consumed(NEXT_SEGMENT);
            }
            return new WaitGroupRecoveryIntake.IntakeResult(
                    WaitGroupRecoveryIntake.Outcome.DEFERRED, null,
                    RecoveryRejection.NOTIFICATION_NOT_DUE, null);
        });
        when(dispatcher.offerNode(any())).thenReturn(true);

        for (int round = 0; round < 6; round++) {
            recovery.safeRound(BATCH);
        }

        ArgumentCaptor<RecoveryNotification> asked = ArgumentCaptor.forClass(RecoveryNotification.class);
        verify(intake, org.mockito.Mockito.atLeastOnce()).take(asked.capture(), any(), anyString());
        assertThat(asked.getAllValues().stream().map(RecoveryNotification::getId).toList())
                .as("三条到期的库行在提醒一直有货的情况下也都被问到了")
                .containsAll(List.of(31L, 32L, 33L));
        assertThat(recovery.snapshot())
                .containsEntry("recoveryScanQuota", 1)
                .containsEntry("recoveryConsumedTotal", 3L);
    }

    /** 按回合读的参数改完下一轮就生效：批次从 1 改成 2，下一次补扫就按 2 向数据库要。 */
    @Test
    void aChangedBatchSizeTakesEffectOnTheNextRound() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.langchain.dual-pool.recovery.batch-size", "1");
        DualPoolRecoveryDispatcher live = new DualPoolRecoveryDispatcher(store, runMapper, dispatcher, intake,
                new DualPoolSchedulerSettings(null, environment), 1000L, new FrozenEffectiveSettings());

        live.rediscover();
        assertThat(store.lastScanLimit).isEqualTo(1);

        environment.setProperty("agent.langchain.dual-pool.recovery.batch-size", "2");
        live.rediscover();
        assertThat(store.lastScanLimit).as("改了配置不用重启，下一轮就用新批次").isEqualTo(2);
    }

    /** 提醒队列有上限：满了就丢提醒，数据库事实不受影响。 */
    @Test
    void aFullReminderQueueDropsRemindersInsteadOfGrowing() {
        DualPoolRecoveryDispatcher bounded = new DualPoolRecoveryDispatcher(store, runMapper, dispatcher,
                intake, recoverySettings(BATCH, 500L, 5_000L, 4, 1, 3), 1000L,
                new FrozenEffectiveSettings());
        assertThat(bounded.wake(1L)).isTrue();
        assertThat(bounded.wake(2L)).isTrue();
        assertThat(bounded.wake(3L)).isTrue();
        assertThat(bounded.wake(4L))
                .as("提醒满了就不再收：数据库才是事实来源，丢的是提醒不是事实")
                .isFalse();
        assertThat(bounded.wake(1L)).as("已经在队列里的不算新提醒").isFalse();
        assertThat(bounded.snapshot())
                .containsEntry("recoveryDroppedWakeupsTotal", 1L)
                .containsEntry("recoveryWakeupCapacity", 3)
                .containsEntry("recoveryPendingWakeups", 3);
    }

    /** 受理层说成了却拿不到下一段身份：这是故障，不许悄悄放过。 */
    @Test
    void aConsumedNotificationWithoutASegmentIdentityIsReportedAsAFailure() {
        store.addNotification(16L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.CONSUMED, null, null, null));

        recovery.safeRound(BATCH);

        verify(dispatcher, never()).offerNode(any());
        assertThat(recovery.snapshot()).containsEntry("recoveryHintFailedTotal", 1L);
    }

    /** 恢复那一组参数：与生产同一套解析，只是把值写成环境属性。 */
    private static DualPoolSchedulerSettings recoverySettings(int batchSize, long backoffBaseMs, long backoffMaxMs,
                                                              int startupPages, int scanQuota, int wakeupCapacity) {
        return TestSchedulerSettings.propertyOnly(
                "agent.langchain.dual-pool.recovery.batch-size", String.valueOf(batchSize),
                "agent.langchain.dual-pool.recovery.backoff-base-ms", String.valueOf(backoffBaseMs),
                "agent.langchain.dual-pool.recovery.backoff-max-ms", String.valueOf(backoffMaxMs),
                "agent.langchain.dual-pool.recovery.startup-pages", String.valueOf(startupPages),
                "agent.langchain.dual-pool.recovery.scan-quota", String.valueOf(scanQuota),
                "agent.langchain.dual-pool.recovery.wakeup-capacity", String.valueOf(wakeupCapacity));
    }

    /** 扫描配额比这一轮的总上限还大时，一轮处理的条数与向数据库索要的条数都不许越过上限。 */
    @Test
    void aScanQuotaLargerThanTheRoundBudgetStaysInsideTheBudget() {
        DualPoolRecoveryDispatcher wideQuota = new DualPoolRecoveryDispatcher(store, runMapper, dispatcher,
                intake, recoverySettings(1, 500L, 5_000L, 4, 4, 1024), 1000L,
                new FrozenEffectiveSettings());
        store.addNotification(41L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        store.addNotification(42L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.DEFERRED, null, null, "admission_unavailable"));

        assertThat(wideQuota.safeRound(1)).as("一轮最多处理一条").isEqualTo(1);
        assertThat(store.lastScanLimit).as("向数据库索要的也不超过一轮上限").isEqualTo(1);
    }

    private static AgentRun runningRun() {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setSchedulerVersion("DUAL_POOL_V2");
        run.setRunControlVersion(4L);
        return run;
    }

    /** 恢复分发器专用替身：只实现它用到的那几条语义。 */
    private static final class FakeWaitGroupStore implements WaitGroupStore {

        @Override
        public List<world.willfrog.agent.platform.wait.WaitGroup> listOpenGroupsByRun(
                String runId, long afterGroupId, int limit) {
            return List.of();
        }

        private final Map<Long, RecoveryNotification> notifications = new LinkedHashMap<>();
        private final List<Long> consumed = new ArrayList<>();
        private final List<Long> deferred = new ArrayList<>();
        private final List<Long> closed = new ArrayList<>();
        private final Map<Long, String> closedReasons = new LinkedHashMap<>();
        private boolean consumable;
        private RecoveryRejection rejection = RecoveryRejection.UNKNOWN;
        private String rejectionDetail;

        /** 消费成功：这条通知不再参与下一轮的候选（真语句里是状态改成已取走）。 */
        void markConsumed(long id) {
            RecoveryNotification notification = notifications.get(id);
            if (notification != null) {
                notification.setState(RecoveryNotificationState.CONSUMED.name());
                consumed.add(id);
            }
        }

        void addNotification(long id, String runId, OffsetDateTime nextVisibleAt) {
            RecoveryNotification notification = new RecoveryNotification();
            notification.setId(id);
            notification.setGroupId(77L);
            notification.setRunId(runId);
            notification.setRecoveryGeneration(1);
            notification.setState(RecoveryNotificationState.WAITING.name());
            notification.setNextVisibleAt(nextVisibleAt);
            notification.setCreatedAt(OffsetDateTime.now().minusSeconds(30));
            notifications.put(id, notification);
        }

        @Override
        public List<WaitMember> scanDueMembers(OffsetDateTime now, int limit) {
            // 成员结果接收不在这一组的用例范围里：给一个空扫描，够这个替身用。
            return List.of();
        }

        @Override
        public Optional<RecoveryNotification> findNotification(long notificationId) {
            return Optional.ofNullable(notifications.get(notificationId));
        }

        /** 最近一次向数据库索要的条数：用来核对一轮预算没有被配额撑破。 */
        private volatile int lastScanLimit;

        @Override
        public List<RecoveryNotification> scanDueRecoveryNotifications(int limit) {
            lastScanLimit = limit;
            // 与真语句同一个口径：只取还在等待态、且已经到了下次可见时间的那批。
            OffsetDateTime now = OffsetDateTime.now();
            return notifications.values().stream()
                    .filter(RecoveryNotification::consumable)
                    .filter(notification -> notification.getNextVisibleAt() == null
                            || !notification.getNextVisibleAt().isAfter(now))
                    .sorted(java.util.Comparator
                            .comparing((RecoveryNotification notification) -> notification.getNextVisibleAt())
                            .thenComparingLong(RecoveryNotification::getId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public RecoveryConsumptionResult consumeRecovery(long notificationId, String dispatcherId,
                                                         long runControlVersion,
                                                         String ownerInstanceId, long fencingToken) {
            RecoveryNotification notification = notifications.get(notificationId);
            if (notification == null || !notification.consumable()) {
                return new RecoveryConsumptionResult(false, false, null, null,
                        RecoveryRejection.NOTIFICATION_NOT_WAITING, null);
            }
            if (!consumable) {
                return new RecoveryConsumptionResult(false, false, null, null, rejection, rejectionDetail);
            }
            notification.setState(RecoveryNotificationState.CONSUMED.name());
            consumed.add(notificationId);
            return new RecoveryConsumptionResult(true, true, notification.getGroupId(),
                    new NodeWorkItemIdentity(notification.getRunId(), 0, "node-1", 0, 3), null, null);
        }

        @Override
        public boolean closeRecoveryNotification(long notificationId, String reason) {
            RecoveryNotification notification = notifications.get(notificationId);
            if (notification == null || !notification.consumable()) {
                return false;
            }
            notification.setState(RecoveryNotificationState.CLOSED.name());
            notification.setCloseReason(reason);
            notification.setClosedAt(OffsetDateTime.now());
            closed.add(notificationId);
            closedReasons.put(notificationId, reason);
            return true;
        }

        @Override
        public boolean deferRecoveryNotification(long notificationId, OffsetDateTime nextVisibleAt) {
            RecoveryNotification notification = notifications.get(notificationId);
            if (notification == null || !notification.consumable()) {
                return false;
            }
            notification.setNextVisibleAt(nextVisibleAt);
            deferred.add(notificationId);
            return true;
        }

        // 下面这些恢复分发器用不到，调用即失败，免得替身悄悄放宽了什么。
        @Override
        public boolean markMemberDispatched(long groupId, String memberIdentity, String externalOperationId,
                                            String dispatchProofJson, OffsetDateTime nextPollAt,
                                            long runControlVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean rescheduleMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt,
                                        int maxBackoffStep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean holdMember(long groupId, String memberIdentity, OffsetDateTime nextPollAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<world.willfrog.agent.platform.wait.WaitGroup> findGroup(
                world.willfrog.agent.platform.wait.WaitGroupIdentity identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<world.willfrog.agent.platform.wait.WaitGroup> findGroup(long groupId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<world.willfrog.agent.platform.wait.WaitMember> listMembers(long groupId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<world.willfrog.agent.platform.wait.WaitMember> findMemberByOperation(
                String runId, String externalOperationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<world.willfrog.agent.platform.wait.WaitMember> findMemberByIdentity(
                long groupId, String memberIdentity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RecoveryNotification> listNotifications(long groupId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public world.willfrog.agent.platform.wait.WaitSuspensionResult suspendSegment(
                world.willfrog.agent.platform.wait.WaitSuspensionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public world.willfrog.agent.platform.wait.MemberCompletionResult completeMember(
                world.willfrog.agent.platform.wait.MemberCompletionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public world.willfrog.agent.platform.wait.MemberCompletionResult reportLateMember(
                world.willfrog.agent.platform.wait.LateMemberRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public world.willfrog.agent.platform.wait.WaitChainCancelResult cancelChain(long groupId) {
            throw new UnsupportedOperationException();
        }
    }
}
