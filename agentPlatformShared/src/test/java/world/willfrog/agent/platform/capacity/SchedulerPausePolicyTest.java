package world.willfrog.agent.platform.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全局新增暂停的三条规则：到高水位就暂停、回落到低水位才恢复、落在中间就保持原样。
 */
class SchedulerPausePolicyTest {

    private static final int HIGH = 128;
    private static final int LOW = 96;

    @Test
    void belowHighWatermarkKeepsRunning() {
        SchedulerPauseDecision decision = SchedulerPausePolicy.decide(false, 127, HIGH, LOW);
        assertThat(decision.paused()).isFalse();
        assertThat(decision.changed()).isFalse();
    }

    @Test
    void reachingHighWatermarkPauses() {
        for (long count : new long[]{HIGH, HIGH + 1, HIGH + 5}) {
            SchedulerPauseDecision decision = SchedulerPausePolicy.decide(false, count, HIGH, LOW);
            assertThat(decision.paused()).as("数量 " + count + " 已经到高水位").isTrue();
            assertThat(decision.changed()).isTrue();
        }
    }

    @Test
    void betweenWatermarksStaysPaused() {
        for (long count : new long[]{LOW + 1, (LOW + HIGH) / 2, HIGH - 1}) {
            SchedulerPauseDecision decision = SchedulerPausePolicy.decide(true, count, HIGH, LOW);
            assertThat(decision.paused())
                    .as("数量 " + count + " 在高低水位之间，已经暂停的不能提前恢复")
                    .isTrue();
            assertThat(decision.changed()).isFalse();
        }
    }

    @Test
    void betweenWatermarksKeepsRunning() {
        SchedulerPauseDecision decision = SchedulerPausePolicy.decide(false, LOW + 1, HIGH, LOW);
        assertThat(decision.paused()).isFalse();
        assertThat(decision.changed()).isFalse();
    }

    @Test
    void fallingToLowWatermarkResumes() {
        for (long count : new long[]{LOW, LOW - 1, 0}) {
            SchedulerPauseDecision decision = SchedulerPausePolicy.decide(true, count, HIGH, LOW);
            assertThat(decision.paused()).as("数量 " + count + " 已经回到低水位").isFalse();
            assertThat(decision.changed()).isTrue();
        }
    }

    @Test
    void equalWatermarksBehaveAsSingleThreshold() {
        assertThat(SchedulerPausePolicy.decide(false, 10, 10, 10).paused()).isTrue();
        assertThat(SchedulerPausePolicy.decide(false, 9, 10, 10).paused()).isFalse();
        assertThat(SchedulerPausePolicy.decide(true, 10, 10, 10).paused()).isTrue();
        assertThat(SchedulerPausePolicy.decide(true, 9, 10, 10).paused()).isFalse();
    }

    @Test
    void hysteresisIsVisibleInTheDecisionRecord() {
        SchedulerPauseDecision paused = SchedulerPausePolicy.decide(false, 200, HIGH, LOW);
        assertThat(paused.describe()).contains("暂停新增").contains("200").contains("128").contains("96");
        SchedulerPauseDecision resumed = SchedulerPausePolicy.decide(true, 10, HIGH, LOW);
        assertThat(resumed.describe()).contains("恢复新增");
    }

    /**
     * 同样是「保持暂停」，数量高于高水位和落在高低水位之间是两回事：说明写错会让读日志的人
     * 以为数量已经掉下来了，白白等一场恢复。
     */
    @Test
    void descriptionOfHeldPauseFollowsTheActualCountInterval() {
        assertThat(SchedulerPausePolicy.decide(true, 200, HIGH, LOW).describe())
                .contains("仍不低于高水位");
        assertThat(SchedulerPausePolicy.decide(true, HIGH, HIGH, LOW).describe())
                .as("正好压在高水位上也还没到能恢复的时候")
                .contains("仍不低于高水位");
        assertThat(SchedulerPausePolicy.decide(true, 100, HIGH, LOW).describe())
                .contains("在高低水位之间");
    }

    @Test
    void descriptionOfRunningStateFollowsTheActualCountInterval() {
        assertThat(SchedulerPausePolicy.decide(false, 100, HIGH, LOW).describe())
                .contains("未到高水位");
        assertThat(SchedulerPausePolicy.decide(false, LOW, HIGH, LOW).describe())
                .as("数量已经低于低水位而且没有暂停，说明处于恢复后的常态")
                .contains("低于低水位");
    }

    /** 数量和标记不吻合时照实写出来，不粉饰成正常情形，免得日志把人带偏。 */
    @Test
    void contradictoryCombinationsAreDescribedAsSuch() {
        assertThat(new SchedulerPauseDecision(true, false, 10, HIGH, LOW).describe())
                .as("数量已经低于低水位，标记却还是暂停")
                .contains("与恢复规则不符");
        assertThat(new SchedulerPauseDecision(false, false, 200, HIGH, LOW).describe())
                .as("数量已经超过高水位，标记却不是暂停")
                .contains("与暂停规则不符");
    }

    @Test
    void misconfiguredWatermarksFailClosed() {
        assertThatThrownBy(() -> SchedulerPausePolicy.decide(false, -1, HIGH, LOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchedulerPausePolicy.decide(false, 0, -1, LOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchedulerPausePolicy.decide(false, 0, LOW, HIGH))
                .as("低水位高于高水位时不能猜，直接报错")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("低水位不能高于高水位");
    }
}
