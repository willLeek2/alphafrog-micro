package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryRejection;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交之后马上唤醒这一条路：投递只用数据库放行出来的那一段。
 *
 * <p>这里量三件事：没消费成时不投递、只叫一声补扫；消费成了就投数据库返回的那一段（调用方手上的
 * 那一段不用来投递）；通知读不回来时什么都不做。</p>
 */
class DualPoolResumedSegmentPublisherTest {

    private static final long NOTIFICATION_ID = 9L;
    private static final String RUN_ID = "run-publish";
    private static final long RUN_CONTROL_VERSION = 4L;

    private WaitGroupStore waitGroupStore;
    private AgentRunMapper runMapper;
    private DualPoolDispatcher dispatcher;
    private DualPoolRecoveryDispatcher recoveryDispatcher;
    private WaitGroupRecoveryIntake intake;
    private DualPoolResumedSegmentPublisher publisher;

    @BeforeEach
    void setUp() {
        waitGroupStore = Mockito.mock(WaitGroupStore.class);
        runMapper = Mockito.mock(AgentRunMapper.class);
        dispatcher = Mockito.mock(DualPoolDispatcher.class);
        recoveryDispatcher = Mockito.mock(DualPoolRecoveryDispatcher.class);
        intake = Mockito.mock(WaitGroupRecoveryIntake.class);
        publisher = new DualPoolResumedSegmentPublisher(waitGroupStore, runMapper, dispatcher,
                recoveryDispatcher, intake);
        Mockito.lenient().when(waitGroupStore.findNotification(NOTIFICATION_ID))
                .thenReturn(Optional.of(notification()));
        Mockito.lenient().when(runMapper.findById(RUN_ID)).thenReturn(runningRun());
    }

    /** 消费成了：投的是数据库放行的那一段，不是调用方以为的那一段。 */
    @Test
    void onlyTheSegmentTheDatabaseReleasedIsHandedToTheNodePool() {
        NodeWorkItemIdentity claimed = new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3);
        NodeWorkItemIdentity authoritative = new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 5);
        when(intake.take(any(), any(), anyString()))
                .thenReturn(WaitGroupRecoveryIntake.IntakeResult.consumed(authoritative));
        when(dispatcher.offerNode(any())).thenReturn(true);

        assertThat(publisher.publish(NOTIFICATION_ID, RUN_CONTROL_VERSION, claimed)).isTrue();

        verify(dispatcher).offerNode(authoritative);
        verify(dispatcher, never()).offerNode(claimed);
    }

    /** 没消费成：不投递，只叫一声周期补扫让它下一轮再来。 */
    @Test
    void aDeferredNotificationOnlyWakesThePeriodicDispatcher() {
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.DEFERRED, null,
                        RecoveryRejection.LEASE_NOT_OWNED, "owner-elsewhere"));

        assertThat(publisher.publish(NOTIFICATION_ID, RUN_CONTROL_VERSION,
                new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3))).isFalse();

        verify(recoveryDispatcher).wake(NOTIFICATION_ID);
        verify(dispatcher, never()).offerNode(any());
    }

    /** 不会再被服务：不投递，也不用叫醒谁——通知已经收口了。 */
    @Test
    void aClosedNotificationDoesNotWakeAnybody() {
        when(intake.take(any(), any(), anyString()))
                .thenReturn(new WaitGroupRecoveryIntake.IntakeResult(
                        WaitGroupRecoveryIntake.Outcome.CLOSED, null, null, "run_terminal:COMPLETED"));

        assertThat(publisher.publish(NOTIFICATION_ID, RUN_CONTROL_VERSION,
                new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3))).isFalse();

        verify(recoveryDispatcher, never()).wake(anyLong());
        verify(dispatcher, never()).offerNode(any());
    }

    /** 通知读不回来：什么都不做。 */
    @Test
    void aNotificationThatCannotBeReadIsLeftAlone() {
        when(waitGroupStore.findNotification(eq(NOTIFICATION_ID))).thenReturn(Optional.empty());

        assertThat(publisher.publish(NOTIFICATION_ID, RUN_CONTROL_VERSION,
                new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3))).isFalse();

        verify(intake, never()).take(any(), any(), anyString());
        verify(dispatcher, never()).offerNode(any());
    }

    private static RecoveryNotification notification() {
        RecoveryNotification notification = new RecoveryNotification();
        notification.setId(NOTIFICATION_ID);
        notification.setGroupId(77L);
        notification.setRunId(RUN_ID);
        notification.setRecoveryGeneration(1);
        notification.setState("WAITING");
        notification.setNextVisibleAt(OffsetDateTime.now().minusSeconds(1));
        return notification;
    }

    private static AgentRun runningRun() {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setSchedulerVersion("DUAL_POOL_V2");
        run.setRunControlVersion(RUN_CONTROL_VERSION);
        return run;
    }
}
