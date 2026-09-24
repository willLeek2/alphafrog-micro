package world.willfrog.agent.platform.capacity;

import java.util.Arrays;
import java.util.List;

/**
 * 跨图轮转计数器的两个作用域。
 *
 * <p>Run 协调与节点派发各有一个独立计数器：两类轮转互不干扰，各自的公平性单独成立。</p>
 */
public enum SchedulerRoundScope {

    /** Run 协调回合的轮次。 */
    RUN_COORDINATION,

    /** 节点派发的轮次。 */
    NODE_DISPATCH;

    /** 全部作用域，顺序固定，便于契约测试与迁移脚本的种子行逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由库里取值解析作用域；未知取值直接抛错。 */
    public static SchedulerRoundScope fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("轮转作用域为空");
        }
        String trimmed = raw.strip();
        for (SchedulerRoundScope scope : values()) {
            if (scope.name().equals(trimmed)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("未知的轮转作用域：" + raw);
    }
}
