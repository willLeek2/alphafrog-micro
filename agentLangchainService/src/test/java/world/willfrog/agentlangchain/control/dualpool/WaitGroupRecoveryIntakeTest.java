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
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

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
 * 消费没成按语句给的原因分「推后」与「收口」两种；刚取得、还没接手的服务所有权要按持有者与代际让出去，
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

    private FrozenEffectiveSettings frozenEffectiveSettings;

    @BeforeEach
    void setUp() {
        waitGroupStore = Mockito.mock(WaitGroupStore.class);
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        ProcessInstanceIdentity identity = Mockito.mock(ProcessInstanceIdentity.class);
        Mockito.lenient().when(identity.value()).thenReturn(OWNER);
        frozenEffectiveSettings = new FrozenEffectiveSettings();
        intake = new WaitGroupRecoveryIntake(waitGroupStore, leaseStore, identity,
                admissionRegistry, 120L, frozenEffectiveSettings);
        Mockito.lenient().when(admissionRegistry.reserveForRecovery(RUN_ID))
                .thenReturn(new DualPoolRunAdmissionRegistry.Admission(true, 11L));
    }















    // ===== 造数据 =====

    /** 这条 Run 本来没有服务所有权：这一次是刚取得的，没接手就该让出去。 */
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
