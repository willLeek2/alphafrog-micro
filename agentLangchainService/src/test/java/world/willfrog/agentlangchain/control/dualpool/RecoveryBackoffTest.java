package world.willfrog.agentlangchain.control.dualpool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 恢复退避的形状：越等越远、有上限、抖动可复现且能把同刻的通知错开。
 */
class RecoveryBackoffTest {

    private static final Duration BASE = Duration.ofMillis(500);
    private static final Duration MAX = Duration.ofSeconds(5);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-22T02:00:00Z");

    private final RecoveryBackoff backoff = new RecoveryBackoff(BASE, MAX);

    @Test
    void theLongerItWaitsTheFurtherItPushes() {
        RecoveryBackoff wide = new RecoveryBackoff(BASE, Duration.ofSeconds(30));
        assertThat(wide.delayFor(Duration.ZERO)).isEqualTo(BASE);
        assertThat(wide.delayFor(Duration.ofMillis(500))).isEqualTo(Duration.ofSeconds(1));
        assertThat(wide.delayFor(Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(2));
        assertThat(wide.delayFor(Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(8));
        assertThat(wide.delayFor(Duration.ofSeconds(4)))
                .as("每多等一个起步间隔就多翻一倍，直到上限")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theDelayIsCappedAndNeverNegative() {
        assertThat(backoff.delayFor(Duration.ofHours(3)))
                .as("等多久都封顶在上限，否则一条取不走的通知会永远不再被看到")
                .isEqualTo(MAX);
        assertThat(backoff.delayFor(Duration.ofMinutes(-5)))
                .as("创建时间比现在还晚（时钟回拨）按没等过算")
                .isEqualTo(BASE);
    }

    @Test
    void jitterIsReproducibleAndStaysInsideItsSpan() {
        Duration delay = Duration.ofSeconds(4);
        Duration first = backoff.jitterFor(77L, delay);
        assertThat(backoff.jitterFor(77L, delay))
                .as("同样的输入算出来一样，问题可复现")
                .isEqualTo(first);
        assertThat(first).isBetween(Duration.ZERO, Duration.ofSeconds(1));
    }

    @Test
    void notificationsWaitingTogetherDoNotRetryTogether() {
        List<OffsetDateTime> retries = new ArrayList<>();
        for (long id = 1; id <= 24; id++) {
            retries.add(backoff.nextVisibleAt(NOW, id, NOW.minusSeconds(30)));
        }
        assertThat(retries).doesNotHaveDuplicates();
        assertThat(retries).allSatisfy(next -> assertThat(next))
                .allSatisfy(next -> assertThat(next).isBetween(NOW.plus(MAX), NOW.plus(MAX).plusSeconds(2)));
    }

    @Test
    void aFreshNotificationIsRetriedSoon() {
        assertThat(backoff.nextVisibleAt(NOW, 9L, NOW))
                .as("刚写出来的通知不该等一个上限才被重试")
                .isBetween(NOW.plus(BASE), NOW.plusSeconds(1));
    }

    @Test
    void invalidConfigurationFailsClosed() {
        assertThatThrownBy(() -> new RecoveryBackoff(Duration.ZERO, MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryBackoff(BASE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecoveryBackoff(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .as("上限比起步间隔还小说明配置写反了")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
