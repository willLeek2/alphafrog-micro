package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryNotificationState;
import world.willfrog.agent.platform.wait.RecoveryRejection;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

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
    private static final String DEPLOYMENT_ID = "stage5a-test";
    private static final String GENERATION_ID = "gen-" + "a".repeat(64);
    private static final int BATCH = 2;
    private static final NodeWorkItemIdentity NEXT_SEGMENT =
            new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3);

    @Mock
    private RunOwnershipGateway ownership;
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
        lenient().when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity(DEPLOYMENT_ID, GENERATION_ID));
        // 默认每轮给数据库补扫留 1 个名额，其余预算给内存提醒。
        recovery = new DualPoolRecoveryDispatcher(store, ownership, dispatcher, intake,
                recoverySettings(BATCH, 500L, 5_000L, 4, 1, 1024), 1000L, new FrozenEffectiveSettings());
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
        private final Map<Long, DeploymentIdentity> notificationOwners = new LinkedHashMap<>();
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
            addNotification(id, runId, nextVisibleAt, DEPLOYMENT_ID, GENERATION_ID);
        }

        void addNotification(long id, String runId, OffsetDateTime nextVisibleAt,
                             String deploymentId, String generationId) {
            RecoveryNotification notification = new RecoveryNotification();
            notification.setId(id);
            notification.setGroupId(77L);
            notification.setRunId(runId);
            notification.setRecoveryGeneration(1);
            notification.setState(RecoveryNotificationState.WAITING.name());
            notification.setNextVisibleAt(nextVisibleAt);
            notification.setCreatedAt(OffsetDateTime.now().minusSeconds(30));
            notifications.put(id, notification);
            notificationOwners.put(id, new DeploymentIdentity(deploymentId, generationId));
        }

        @Override
        public List<WaitMember> scanDueMembers(OffsetDateTime now, int limit) {
            // 成员结果接收不在这一组的用例范围里：给一个空扫描，够这个替身用。
            return List.of();
        }

        @Override
        public List<WaitMember> scanDueMembers(String deploymentId, String deploymentGenerationId,
                                                OffsetDateTime now, int limit) {
            return scanDueMembers(now, limit);
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
        public List<RecoveryNotification> scanDueRecoveryNotifications(String deploymentId,
                                                                         String deploymentGenerationId, int limit) {
            lastScanLimit = limit;
            OffsetDateTime now = OffsetDateTime.now();
            return notifications.values().stream()
                    .filter(notification -> {
                        DeploymentIdentity owner = notificationOwners.get(notification.getId());
                        return owner != null && deploymentId.equals(owner.deploymentId())
                                && deploymentGenerationId.equals(owner.generationId());
                    })
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
