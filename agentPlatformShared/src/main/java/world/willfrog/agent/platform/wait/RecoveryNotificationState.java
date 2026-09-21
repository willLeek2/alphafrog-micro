package world.willfrog.agent.platform.wait;

import java.util.Arrays;
import java.util.List;

/**
 * 持久恢复通知的状态。
 *
 * <p>通知是「这个等待组的结果齐备了、可以恢复了」这条事实的持久载体。{@link #WAITING} 等待被恢复分发器取走；
 * {@link #CONSUMED} 已经被取走一次，同一代际不再有第二次机会；{@link #CANCELED} 随组一起取消，
 * 迟到的结果不会再唤醒任何东西。</p>
 *
 * <p>消费是一次条件更新，不是先读后写：只有把 {@code WAITING} 改成 {@code CONSUMED} 的那一次调用算消费成功，
 * 并发的第二个分发器会得到影响行数 0。这也是「最后一个成员只产生一次恢复资格」能被证明的地方。</p>
 */
public enum RecoveryNotificationState {

    WAITING("待恢复分发器取走", false),
    CONSUMED("已被取走一次", true),
    CANCELED("已随组取消", true);

    private final String label;
    private final boolean terminal;

    RecoveryNotificationState(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    /** 中文说明，只用于日志与拒绝事实，不参与任何比较。 */
    public String label() {
        return label;
    }

    /** 终态通知不再被消费。 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 库里全部取值，顺序固定，便于契约测试与迁移脚本逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由库里取值解析状态；未知取值直接抛错，不落回任何一个已知状态。 */
    public static RecoveryNotificationState fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("恢复通知状态为空，无法判断这条通知还能不能被消费");
        }
        String trimmed = raw.strip();
        for (RecoveryNotificationState state : values()) {
            if (state.name().equals(trimmed)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知的恢复通知状态：" + raw);
    }
}
