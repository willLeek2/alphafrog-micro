package world.willfrog.agent.platform.wait;

import java.util.Arrays;
import java.util.List;

/**
 * 等待组的状态。
 *
 * <p>一个等待组从保存那一刻起只往前走一次，不会回到上一个状态：{@link #WAITING} 成员还没全结束；
 * {@link #READY} 成员已经全结束、恢复资格已经写出；{@link #RESUMED} 恢复资格已经被分发器消费、
 * 下一段已经变成可恢复；{@link #CANCELED} 取消或计划代际失效，组和下一段一起停下。</p>
 *
 * <p>终态是「这个组的事情已经办完，后面只会由别的对象继续推进」的两种：{@link #RESUMED} 把执行权
 * 交接给了下一段，{@link #CANCELED} 整条链停下。它们都不再接收成员结果、不再产生恢复资格、
 * 也不再被取消路径改写（取消路径找的是还活着的组）。</p>
 *
 * <p>「成员全结束」只数真正有结果的成员（成功与失败）。被取消和迟到的成员不算结束，它们把整组带向取消，
 * 不能变成恢复资格，否则被取消的图会被迟到结果拉回执行。</p>
 */
public enum WaitGroupState {

    WAITING("等待成员结果", false),
    READY("结果齐备待恢复", false),
    RESUMED("已交接给恢复分段", true),
    CANCELED("已取消", true);

    private final String label;
    private final boolean terminal;

    WaitGroupState(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    /** 中文说明，只用于日志与拒绝事实，不参与任何比较。 */
    public String label() {
        return label;
    }

    /** 终态组不再发生状态迁移，也不会再产生恢复资格。 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 还能接收成员结果的状态：只有等待中。已经齐备的组再收到结果是迟到结果，只做审计。 */
    public boolean acceptsMemberResult() {
        return this == WAITING;
    }

    /** 库里全部取值，顺序固定，便于契约测试与迁移脚本逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由库里取值解析状态；未知取值直接抛错，不落回任何一个已知状态。 */
    public static WaitGroupState fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("等待组状态为空，无法判断这个组处在哪一步");
        }
        String trimmed = raw.strip();
        for (WaitGroupState state : values()) {
            if (state.name().equals(trimmed)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知的等待组状态：" + raw);
    }
}
