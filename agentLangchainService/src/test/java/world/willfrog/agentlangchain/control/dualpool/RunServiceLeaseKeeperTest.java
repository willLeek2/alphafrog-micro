package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import world.willfrog.agent.platform.lease.ProcessInstanceIdentity;
import world.willfrog.agent.platform.lease.RunServiceLease;
import world.willfrog.agent.platform.lease.RunServiceLeaseStore;
import world.willfrog.agent.platform.lease.ServiceOwnershipScope;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务所有权续期的行为：手上的租约趁没过期先续上，哪一条续不上就说明那一条被别人接手了。
 *
 * <p>这里量的是四件事：清点是有界的一页，整批续期负责把这一页之外的租约也续上；判定是逐条按
 * 代际号条件续期，不看条数对比；续不上的当场撤销本进程的准入；这一轮出错不带走调度线程，下一轮照跑。</p>
 */
class RunServiceLeaseKeeperTest {

    private static final String OWNER = "instance-under-test";
    private static final String DEPLOYMENT_ID = "beta-test";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final ServiceOwnershipScope SCOPE =
            new ServiceOwnershipScope(DEPLOYMENT_ID, GENERATION, null);

    private RunServiceLeaseStore leaseStore;
    private DualPoolRunAdmissionRegistry admissionRegistry;
    private ProcessInstanceIdentity identity;
    private RunOwnershipGateway ownership;
    private DualPoolSchedulerSettings settings;
    private RunServiceLeaseKeeper keeper;
    private FrozenEffectiveSettings frozenEffectiveSettings;

    @BeforeEach
    void setUp() {
        leaseStore = Mockito.mock(RunServiceLeaseStore.class);
        admissionRegistry = Mockito.mock(DualPoolRunAdmissionRegistry.class);
        identity = Mockito.mock(ProcessInstanceIdentity.class);
        Mockito.lenient().when(identity.value()).thenReturn(OWNER);
        ownership = Mockito.mock(RunOwnershipGateway.class);
        Mockito.lenient().when(ownership.requireIdentity())
                .thenReturn(new DeploymentIdentity(DEPLOYMENT_ID, GENERATION));
        settings = TestSchedulerSettings.propertyOnly();
        frozenEffectiveSettings = new FrozenEffectiveSettings();
        keeper = new RunServiceLeaseKeeper(leaseStore, admissionRegistry, identity, ownership, settings,
                120L, 512, 40_000L, frozenEffectiveSettings);
    }











    /** 只为了让上面那条用例的容器打开定时任务处理。 */
    @EnableScheduling
    static class SchedulingEnabler {
    }

    private static RunServiceLease lease(String runId, long fencingToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RunServiceLease(runId, OWNER, fencingToken, now, now, now.plusMinutes(2));
    }
}
