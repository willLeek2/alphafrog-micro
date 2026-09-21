package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.RecoveryConsumptionResult;
import world.willfrog.agent.platform.wait.RecoveryNotification;
import world.willfrog.agent.platform.wait.RecoveryRejection;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.time.Duration;
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
 * 恢复受理的顺序：先服务所有权、再业务名额、最后才取走那条不可逆的恢复资格。
 *
 * <p>这里量四件事：不是所有者时不消费也不动通知；名额拿不到时通知留在等待态（而不是先消费掉再要名额）；
 * 消费没成按语句给的原因分「推后」与「收口」两种；刚取得、还没接手的租约要按持有者与代际让出去，
 * 本来就是我持有的那条留着。</p>
 */
class WaitGroupRecoveryIntakeTest {

    private static final String RUN_ID = "run-intake";
    private static final String OWNER = "instance-under-test";
    private static final long TOKEN = 7L;
    private static final NodeWorkItemIdentity NEXT_SEGMENT =
            new NodeWorkItemIdentity(RUN_ID, 0, "node-1", 0, 3);

    private WaitGroupStore waitGroupStore;
    private RunServiceLeaseStore leaseStore;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private WaitGroupRecoveryIntake intake;

    @BeforeEach
    void setUp() {
        waitGroupStore = Mockito.mock(WaitGroupStore.class);
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        Mockito.lenient().when(identity.value()).thenReturn(OWNER);
        intake = new WaitGroupRecoveryIntake(waitGroupStore, leaseStore, identity,
                admissionRegistry, 120L);
        Mockito.lenient().when(admissionRegistry.reserveForRecovery(RUN_ID))
                .thenReturn(new DualPoolRunAdmissionRegistry.Admission(true, 11L));
    }

    /** 不是所有者：不消费、不动通知、不占名额。 */
    @Test
    void aRunOwnedBySomebodyElseIsLeftAlone() {
        when(leaseStore.find(RUN_ID)).thenReturn(Optional.empty());
        when(leaseStore.acquire(eq(RUN_ID), eq(OWNER), any(Duration.class))).thenReturn(Optional.empty());

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.DEFERRED);
        assertThat(result.rejection()).isEqualTo(RecoveryRejection.LEASE_NOT_OWNED);
        verify(waitGroupStore, never()).consumeRecovery(anyLong(), anyString(), anyLong(), anyString(), anyLong());
        verify(waitGroupStore, never()).closeRecoveryNotification(anyLong(), anyString());
        verify(admissionRegistry, never()).reserveForRecovery(anyString());
    }

    /** 名额拿不到：先不消费，通知留在等待态，刚取得的租约让出去。 */
    @Test
    void withoutABusinessPermitTheEntitlementIsNotConsumed() {
        givenFreshlyTakenLease();
        when(admissionRegistry.reserveForRecovery(RUN_ID))
                .thenReturn(new DualPoolRunAdmissionRegistry.Admission(false, -1L));

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.DEFERRED);
        assertThat(result.detail()).isEqualTo("admission_unavailable");
        verify(waitGroupStore, never()).consumeRecovery(anyLong(), anyString(), anyLong(), anyString(), anyLong());
        verify(leaseStore).release(RUN_ID, OWNER, TOKEN);
        assertThat(intake.snapshot())
                .containsEntry("recoveryIntakeAdmissionUnavailableTotal", 1L)
                .containsEntry("recoveryIntakeLeaseReleasedTotal", 1L);
    }

    /** 本来就归我持有的租约：名额拿不到时留着，因为这条 Run 确实归我服务。 */
    @Test
    void aLeaseThatWasAlreadyMineIsKeptWhenTheAttemptDoesNotHandOff() {
        when(leaseStore.find(RUN_ID)).thenReturn(Optional.of(lease(TOKEN)));
        when(leaseStore.acquire(eq(RUN_ID), eq(OWNER), any(Duration.class)))
                .thenReturn(Optional.of(lease(TOKEN)));
        when(admissionRegistry.reserveForRecovery(RUN_ID))
                .thenReturn(new DualPoolRunAdmissionRegistry.Admission(false, -1L));

        intake.take(notification(), runningRun(), "test");

        verify(leaseStore, never()).release(anyString(), anyString(), anyLong());
        assertThat(intake.snapshot()).containsEntry("recoveryIntakeLeaseReleasedTotal", 0L);
    }

    /** 消费成功：激活预留、回报权威的那一段。 */
    @Test
    void aSuccessfulConsumeActivatesTheReservedAdmission() {
        givenOwnedLease();
        givenConsume(new RecoveryConsumptionResult(true, true, 77L, NEXT_SEGMENT, null, null));

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.CONSUMED);
        assertThat(result.nextSegment()).isEqualTo(NEXT_SEGMENT);
        verify(admissionRegistry).activateReservedAdmission(RUN_ID,
                new DualPoolRunAdmissionRegistry.Admission(true, 11L));
        verify(admissionRegistry, never()).rollbackReservedAdmission(anyString(), any());
        verify(leaseStore, never()).release(anyString(), anyString(), anyLong());
    }

    /** 已经有活的准入生命周期：不新占预留，也不去激活一个不存在的预留。 */
    @Test
    void anAlreadyAdmittedRunNeedsNoReservation() {
        givenOwnedLease();
        when(admissionRegistry.reserveForRecovery(RUN_ID))
                .thenReturn(new DualPoolRunAdmissionRegistry.Admission(true, -1L));
        givenConsume(new RecoveryConsumptionResult(true, true, 77L, NEXT_SEGMENT, null, null));

        assertThat(intake.take(notification(), runningRun(), "test").consumed()).isTrue();

        verify(admissionRegistry, never()).activateReservedAdmission(anyString(), any());
        verify(admissionRegistry, never()).rollbackReservedAdmission(anyString(), any());
    }

    /** 不会再被服务的原因：收口关闭并写明原因，同时把预留与租约还回去。 */
    @Test
    void aPermanentRejectionClosesTheNotification() {
        givenFreshlyTakenLease();
        givenConsume(new RecoveryConsumptionResult(false, false, null, null,
                RecoveryRejection.RUN_TERMINAL, "COMPLETED"));
        when(waitGroupStore.closeRecoveryNotification(9L, "run_terminal:COMPLETED")).thenReturn(true);

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.CLOSED);
        verify(waitGroupStore).closeRecoveryNotification(9L, "run_terminal:COMPLETED");
        verify(admissionRegistry).rollbackReservedAdmission(RUN_ID,
                new DualPoolRunAdmissionRegistry.Admission(true, 11L));
        verify(leaseStore).release(RUN_ID, OWNER, TOKEN);
        assertThat(intake.snapshot()).containsEntry("recoveryIntakeClosedTotal", 1L);
    }

    /** 暂时性的原因：退避重试，通知不动，刚取得的租约也让出去。 */
    @Test
    void aTemporaryRejectionOnlyDefers() {
        givenFreshlyTakenLease();
        givenConsume(new RecoveryConsumptionResult(false, false, null, null,
                RecoveryRejection.RUN_NOT_EXECUTING, "PAUSED"));

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.DEFERRED);
        assertThat(result.rejection()).isEqualTo(RecoveryRejection.RUN_NOT_EXECUTING);
        verify(waitGroupStore, never()).closeRecoveryNotification(anyLong(), anyString());
        verify(leaseStore).release(RUN_ID, OWNER, TOKEN);
        assertThat(intake.snapshot()).containsEntry("recoveryIntakeDeferredTotal", 1L);
    }

    /** 别人先取走了：什么都不做，也绝不收口——那条通知已经有主。 */
    @Test
    void aNotificationSomebodyElseTookIsNotClosed() {
        givenOwnedLease();
        givenConsume(new RecoveryConsumptionResult(false, false, null, null,
                RecoveryRejection.NOTIFICATION_NOT_WAITING, "CONSUMED"));

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.LOST_RACE);
        verify(waitGroupStore, never()).closeRecoveryNotification(anyLong(), anyString());
        assertThat(intake.snapshot()).containsEntry("recoveryIntakeLostRaceTotal", 1L);
    }

    /** 语句没给原因：不敢收口也不能当成功，退避重试并记一笔。 */
    @Test
    void aRejectionWithoutAReasonIsRetriedInsteadOfClosed() {
        givenOwnedLease();
        givenConsume(new RecoveryConsumptionResult(false, false, null, null, null, null));

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), runningRun(), "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.DEFERRED);
        verify(waitGroupStore, never()).closeRecoveryNotification(anyLong(), anyString());
    }

    /** Run 读不回来：这条链没有可归属的对象，收口。 */
    @Test
    void aNotificationWhoseRunIsUnreadableIsClosed() {
        when(waitGroupStore.closeRecoveryNotification(9L, "run_missing")).thenReturn(true);

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), null, "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.CLOSED);
        verify(waitGroupStore).closeRecoveryNotification(9L, "run_missing");
        verify(leaseStore, never()).acquire(anyString(), anyString(), any(Duration.class));
    }

    /** 版本不用等待组：通知不该由这一套来服务，收口。 */
    @Test
    void aVersionWithoutWaitGroupsIsClosed() {
        when(waitGroupStore.closeRecoveryNotification(9L, "version_without_wait_groups")).thenReturn(true);
        AgentRun run = runningRun();
        run.setSchedulerVersion("DUAL_POOL_V1");

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), run, "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.CLOSED);
        verify(leaseStore, never()).acquire(anyString(), anyString(), any(Duration.class));
    }

    /** 收口时别人先动手了（语句影响 0 行）：当成抢不到，不重复记收口。 */
    @Test
    void closingSomethingAlreadyTakenIsALostRace() {
        when(waitGroupStore.closeRecoveryNotification(9L, "run_missing")).thenReturn(false);

        WaitGroupRecoveryIntake.IntakeResult result = intake.take(notification(), null, "test");

        assertThat(result.outcome()).isEqualTo(WaitGroupRecoveryIntake.Outcome.LOST_RACE);
        assertThat(intake.snapshot())
                .containsEntry("recoveryIntakeClosedTotal", 0L)
                .containsEntry("recoveryIntakeLostRaceTotal", 1L);
    }

    // ===== 造数据 =====

    /** 这条 Run 本来没有租约：这一次是刚取得的，没接手就该让出去。 */
    private void givenFreshlyTakenLease() {
        when(leaseStore.find(RUN_ID)).thenReturn(Optional.empty());
        when(leaseStore.acquire(eq(RUN_ID), eq(OWNER), any(Duration.class)))
                .thenReturn(Optional.of(lease(TOKEN)));
        Mockito.lenient().when(leaseStore.release(eq(RUN_ID), eq(OWNER), anyLong())).thenReturn(true);
    }

    private void givenOwnedLease() {
        when(leaseStore.find(RUN_ID)).thenReturn(Optional.of(lease(TOKEN)));
        when(leaseStore.acquire(eq(RUN_ID), eq(OWNER), any(Duration.class)))
                .thenReturn(Optional.of(lease(TOKEN)));
    }

    private void givenConsume(RecoveryConsumptionResult result) {
        when(waitGroupStore.consumeRecovery(eq(9L), anyString(), anyLong(), eq(OWNER), eq(TOKEN)))
                .thenReturn(result);
    }

    private static RecoveryNotification notification() {
        RecoveryNotification notification = new RecoveryNotification();
        notification.setId(9L);
        notification.setGroupId(77L);
        notification.setRunId(RUN_ID);
        notification.setRecoveryGeneration(1);
        notification.setState("WAITING");
        notification.setNextVisibleAt(OffsetDateTime.now().minusSeconds(5));
        return notification;
    }

    private static AgentRun runningRun() {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setStatus(AgentRunStatus.EXECUTING);
        run.setSchedulerVersion("DUAL_POOL_V2");
        run.setPlanGeneration(0);
        run.setRunControlVersion(4L);
        return run;
    }

    private static RunServiceLease lease(long token) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RunServiceLease(RUN_ID, OWNER, token, now, now, now.plusMinutes(2));
    }
}
