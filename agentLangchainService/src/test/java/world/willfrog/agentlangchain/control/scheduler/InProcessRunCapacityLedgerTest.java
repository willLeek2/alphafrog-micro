package world.willfrog.agentlangchain.control.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InProcessRunCapacityLedgerTest {

    @Test
    void acquireAndReleaseKeepsLedgerBalanced() {
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);

        assertThat(ledger.tryAcquire("a", 1)).isTrue();
        assertThat(ledger.tryAcquire("b", 3)).isTrue();
        assertThat(ledger.usedUnits()).isEqualTo(4);

        ledger.release("a");
        assertThat(ledger.usedUnits()).isEqualTo(3);

        ledger.release("b");
        assertThat(ledger.usedUnits()).isZero();
    }

    @Test
    void acquireFailsWhenWeightWouldExceedMaxUnits() {
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);

        assertThat(ledger.tryAcquire("a", 3)).isTrue();
        // 3 + 2 > 4：必须拒绝而不是超卖。
        assertThat(ledger.tryAcquire("b", 2)).isFalse();
        assertThat(ledger.usedUnits()).isEqualTo(3);
    }

    @Test
    void weightAboveTotalCapacityIsAlwaysRejected() {
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);

        assertThat(ledger.tryAcquire("heavy", 5)).isFalse();
        assertThat(ledger.usedUnits()).isZero();
    }

    @Test
    void duplicateKeyAcquireIsRejectedAndRolledBack() {
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);

        assertThat(ledger.tryAcquire("a", 1)).isTrue();
        // 同一 key 二次 acquire：拒绝并回滚本次计数。
        assertThat(ledger.tryAcquire("a", 1)).isFalse();
        assertThat(ledger.usedUnits()).isEqualTo(1);
    }

    @Test
    void releaseOfUnknownKeyIsIdempotent() {
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);

        assertThat(ledger.tryAcquire("a", 1)).isTrue();
        ledger.release("unknown");
        assertThat(ledger.usedUnits()).isEqualTo(1);
        ledger.release("a");
        ledger.release("a");
        assertThat(ledger.usedUnits()).isZero();
    }

    @Test
    void invalidWeightsFailClosed() {
        InProcessRunCapacityLedger ledger = new InProcessRunCapacityLedger(4);

        assertThat(ledger.tryAcquire(null, 1)).isFalse();
        assertThatThrownBy(() -> ledger.tryAcquire("a", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.tryAcquire("a", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
