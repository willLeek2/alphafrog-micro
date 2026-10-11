package world.willfrog.agent.platform.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三层名额的计数：各自独立、满了就拒、交还必须成对。
 */
class SchedulerPermitLedgerTest {

    @Test
    void layersAreCountedIndependently() {
        SchedulerPermitLedger ledger = new SchedulerPermitLedger();
        ledger.setLimit(SchedulerPermitLayer.RUN_COORDINATION_TURN, 1);
        ledger.setLimit(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT, 2);

        assertThat(ledger.tryAcquire(SchedulerPermitLayer.RUN_COORDINATION_TURN)).isTrue();
        assertThat(ledger.tryAcquire(SchedulerPermitLayer.RUN_COORDINATION_TURN))
                .as("协调许可是 1，第二个就该被拒").isFalse();
        assertThat(ledger.tryAcquire(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT))
                .as("别的层满了不影响这一层").isTrue();

        SchedulerPermitSnapshot snapshot = ledger.snapshot();
        assertThat(snapshot.usage(SchedulerPermitLayer.RUN_COORDINATION_TURN).inUse()).isEqualTo(1);
        assertThat(snapshot.usage(SchedulerPermitLayer.RUN_COORDINATION_TURN).available()).isZero();
        assertThat(snapshot.usage(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT).inUse()).isEqualTo(1);
        assertThat(snapshot.usage(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT).available()).isEqualTo(1);
        assertThat(snapshot.describe()).contains("Run 协调许可", "节点执行许可", "业务准入");
    }

    @Test
    void releaseMakesRoomAgain() {
        SchedulerPermitLedger ledger = new SchedulerPermitLedger();
        ledger.setLimit(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT, 1);
        assertThat(ledger.tryAcquire(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT)).isTrue();
        assertThat(ledger.tryAcquire(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT)).isFalse();
        ledger.release(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT);
        assertThat(ledger.tryAcquire(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT)).isTrue();
    }

    @Test
    void releasingWithoutHoldingThrowsInsteadOfGoingNegative() {
        SchedulerPermitLedger ledger = new SchedulerPermitLedger();
        assertThatThrownBy(() -> ledger.release(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("成对");
        assertThat(ledger.usage(SchedulerPermitLayer.NODE_EXECUTION_SEGMENT).inUse())
                .as("拒掉这次交还之后计数不能被改坏").isZero();
    }

    @Test
    void unlimitedLayerKeepsAccepting() {
        SchedulerPermitLedger ledger = new SchedulerPermitLedger();
        for (int i = 0; i < 50; i++) {
            assertThat(ledger.tryAcquire(SchedulerPermitLayer.BUSINESS_ADMISSION)).isTrue();
        }
        assertThat(ledger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).unlimited()).isTrue();
        assertThat(ledger.usage(SchedulerPermitLayer.BUSINESS_ADMISSION).inUse()).isEqualTo(50);
    }

    @Test
    void snapshotAlwaysCarriesAllThreeLayers() {
        SchedulerPermitSnapshot snapshot = new SchedulerPermitLedger().snapshot();
        for (SchedulerPermitLayer layer : SchedulerPermitLayer.values()) {
            assertThat(snapshot.usage(layer).layer()).isEqualTo(layer);
        }
    }

    @Test
    void badLimitIsRefused() {
        SchedulerPermitLedger ledger = new SchedulerPermitLedger();
        assertThatThrownBy(() -> ledger.setLimit(SchedulerPermitLayer.BUSINESS_ADMISSION, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
