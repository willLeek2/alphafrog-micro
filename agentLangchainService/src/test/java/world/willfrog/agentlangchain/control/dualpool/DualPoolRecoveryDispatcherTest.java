package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryNotificationState;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
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

/**
 * 恢复分发器每一轮做什么：先处理刚被唤醒的、再按数据库取到期的；每轮条数有上限；
 * 取不走的推后下次可见时间；归属核对不上的只隔离不处理。
 *
 * <p>这里验的是分发器的控制流，等待组存储本身用替身。真实的条件更新与并发行为由真 PostgreSQL
 * 那一层回答。</p>
 */
@ExtendWith(MockitoExtension.class)
class DualPoolRecoveryDispatcherTest {

    private static final String RUN_ID = "run-recovery";
    private static final int BATCH = 2;

    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private DualPoolDispatcher dispatcher;
    @Mock
    private DualPoolRunAdmissionRegistry admissionRegistry;

    private FakeWaitGroupStore store;
    private DualPoolRecoveryDispatcher recovery;

    @BeforeEach
    void setUp() {
        store = new FakeWaitGroupStore();
        lenient().when(dispatcher.isReady()).thenReturn(true);
        recovery = new DualPoolRecoveryDispatcher(store, runMapper, dispatcher, admissionRegistry,
                BATCH, 500L, 5_000L, 4);
    }

    @Test
    void aDueNotificationIsConsumedAndItsNextSegmentIsHandedToTheNodePool() {
        store.addNotification(11L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        store.consumable = true;
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(admissionRegistry.restorePersistedToolJob(RUN_ID)).thenReturn(true);
        when(dispatcher.offerNode(any())).thenReturn(true);

        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);

        assertThat(store.consumed).containsExactly(11L);
        assertThat(store.deferred).isEmpty();
        verify(dispatcher).offerNode(new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3));
        assertThat(recovery.snapshot())
                .containsEntry("recoveryConsumedTotal", 1L)
                .containsEntry("recoveryScannedTotal", 1L);
    }

    @Test
    void aNotificationThatCannotBeConsumedIsPushedLaterInsteadOfBeingRetriedEveryRound() {
        store.addNotification(12L, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        store.consumable = false;
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());

        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);

        assertThat(store.deferred).containsExactly(12L);
        assertThat(store.notifications.get(12L).getNextVisibleAt())
                .as("取不走就推后，否则它会一直占着候选队头")
                .isAfter(OffsetDateTime.now());
        verify(dispatcher, never()).offerNode(any());
    }

    @Test
    void aNotificationWhoseRunIsGoneIsOnlyIsolated() {
        store.addNotification(13L, "run-missing", OffsetDateTime.now().minusSeconds(30));
        when(runMapper.findById("run-missing")).thenReturn(null);

        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);

        assertThat(store.consumed).isEmpty();
        assertThat(store.deferred).isEmpty();
        verify(dispatcher, never()).offerNode(any());
        assertThat(recovery.snapshot()).containsEntry("recoveryIsolatedTotal", 1L);
    }

    @Test
    void aWakeupIsHandledBeforeTheDatabaseScanAndOnlyOnce() {
        store.addNotification(14L, RUN_ID, OffsetDateTime.now().plusSeconds(600));
        store.consumable = true;
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(admissionRegistry.restorePersistedToolJob(RUN_ID)).thenReturn(true);

        assertThat(recovery.wake(14L)).isTrue();
        assertThat(recovery.wake(14L)).as("同一条通知只留一份提醒").isFalse();
        assertThat(recovery.pendingWakeupIds()).containsExactly(14L);

        // 这条通知还没到期，数据库扫描取不到它；唤醒这条路照样把它处理掉。
        assertThat(recovery.safeRound(BATCH)).isEqualTo(1);
        assertThat(store.consumed).containsExactly(14L);
        assertThat(recovery.pendingWakeupIds()).isEmpty();
        assertThat(recovery.snapshot()).containsEntry("recoveryWakeupsHandledTotal", 1L);
    }

    @Test
    void oneRoundNeverHandlesMoreThanTheBatchSize() {
        for (long id = 21L; id <= 25L; id++) {
            store.addNotification(id, RUN_ID, OffsetDateTime.now().minusSeconds(30));
        }
        store.consumable = true;
        when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
        when(admissionRegistry.restorePersistedToolJob(RUN_ID)).thenReturn(true);
        when(dispatcher.offerNode(any())).thenReturn(true);

        assertThat(recovery.safeRound(BATCH))
                .as("一轮最多处理这么多条，剩下的留给下一轮")
                .isEqualTo(BATCH);
        assertThat(store.consumed).hasSize(BATCH);
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

        private final Map<Long, RecoveryNotification> notifications = new LinkedHashMap<>();
        private final List<Long> consumed = new ArrayList<>();
        private final List<Long> deferred = new ArrayList<>();
        private final ArrayDeque<Long> due = new ArrayDeque<>();
        private boolean consumable;

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
            due.add(id);
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

        @Override
        public List<RecoveryNotification> scanDueRecoveryNotifications(int limit) {
            List<RecoveryNotification> result = new ArrayList<>();
            while (!due.isEmpty() && result.size() < limit) {
                RecoveryNotification notification = notifications.get(due.poll());
                if (notification != null && notification.consumable()) {
                    result.add(notification);
                }
            }
            return result;
        }

        @Override
        public RecoveryConsumptionResult consumeRecovery(long notificationId, String dispatcherId,
                                                         long runControlVersion) {
            RecoveryNotification notification = notifications.get(notificationId);
            if (notification == null || !notification.consumable() || !consumable) {
                return new RecoveryConsumptionResult(false, false, null, null);
            }
            notification.setState(RecoveryNotificationState.CONSUMED.name());
            consumed.add(notificationId);
            return new RecoveryConsumptionResult(true, true, notification.getGroupId(),
                    new NodeWorkItemIdentity(notification.getRunId(), 0, "node-1", 0, 3));
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
