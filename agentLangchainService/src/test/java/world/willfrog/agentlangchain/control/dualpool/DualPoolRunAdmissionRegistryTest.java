package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.capacity.SchedulerPermitLayer;
import world.willfrog.agent.platform.capacity.SchedulerPermitLedger;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DualPoolRunAdmissionRegistryTest {

    @Test
    void oldLifecycleCannotReleaseNewFollowUpAdmission() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        DualPoolRunAdmissionRegistry registry = new DualPoolRunAdmissionRegistry(
                permitLedger, mock(NodeWorkItemStore.class));

        assertThat(registry.admitNewRun("run-1")).isTrue();
        long oldEpoch = registry.currentAdmissionEpoch("run-1");
        assertThat(registry.admitExistingRun("run-1")).isTrue();
        long followUpEpoch = registry.currentAdmissionEpoch("run-1");
        assertThat(followUpEpoch).isGreaterThan(oldEpoch);

        AtomicBoolean oldCleanupRan = new AtomicBoolean();
        assertThat(registry.releaseBusinessPermitIfCurrent(
                "run-1", oldEpoch, () -> oldCleanupRan.set(true))).isFalse();
        assertThat(oldCleanupRan).isFalse();
        assertThat(registry.isAdmitted("run-1")).isTrue();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isEqualTo(1);

        AtomicBoolean currentCleanupRan = new AtomicBoolean();
        assertThat(registry.releaseBusinessPermitIfCurrent(
                "run-1", followUpEpoch, () -> currentCleanupRan.set(true))).isTrue();
        assertThat(currentCleanupRan).isTrue();
        assertThat(registry.isAdmitted("run-1")).isFalse();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isZero();
    }

    @Test
    void losingLaterReservationCannotRemoveEarlierDurableWinner() {
        SchedulerPermitLedger permitLedger = new SchedulerPermitLedger();
        permitLedger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, 1);
        DualPoolRunAdmissionRegistry registry = new DualPoolRunAdmissionRegistry(
                permitLedger, mock(NodeWorkItemStore.class));

        assertThat(registry.admitNewRun("run-1")).isTrue();
        DualPoolRunAdmissionRegistry.Admission earlier =
                registry.admitExistingRunWithLease("run-1");
        DualPoolRunAdmissionRegistry.Admission later =
                registry.admitExistingRunWithLease("run-1");

        assertThat(registry.activateReservedAdmission("run-1", earlier)).isTrue();
        assertThat(registry.rollbackReservedAdmission("run-1", later)).isTrue();
        assertThat(registry.currentAdmissionEpoch("run-1")).isEqualTo(earlier.epoch());
        assertThat(registry.isAdmitted("run-1")).isTrue();
        assertThat(permitLedger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse())
                .isEqualTo(1);
    }
}
