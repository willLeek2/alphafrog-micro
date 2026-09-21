package world.willfrog.agent.platform.wait;

import java.util.Arrays;
import java.util.List;

/**
 * 等待成员的状态。
 *
 * <p>成员的一生：{@link #PENDING} 已经保存进组、还没交给外部作业；{@link #RUNNING} 已经派发或正在执行；
 * 之后落到四个结束取值之一。取消与迟到是两种「结束但没有可接入结果」的形态：{@link #CANCELED} 是随组一起被
 * 取消，{@link #LATE} 是结果在取消或计划代际失效之后才到，只留审计。</p>
 *
 * <p>两个判据必须分开用：{@link #isTerminal()} 回答「这一行还会不会再变」，{@link #countsAsCompleted()}
 * 回答「它算不算组的一次有效结束」。组能不能变成齐备只看后者；被取消和迟到的成员即使已经终态，
 * 也不能把组推向恢复。</p>
 */
public enum WaitMemberState {

    PENDING("已保存待派发", false, false),
    RUNNING("已派发执行中", false, false),
    SUCCEEDED("成功", true, true),
    FAILED("失败", true, true),
    CANCELED("已取消", true, false),
    LATE("迟到不计入", true, false);

    private final String label;
    private final boolean terminal;
    private final boolean completed;

    WaitMemberState(String label, boolean terminal, boolean completed) {
        this.label = label;
        this.terminal = terminal;
        this.completed = completed;
    }

    /** 中文说明，只用于日志与拒绝事实，不参与任何比较。 */
    public String label() {
        return label;
    }

    /** 终态成员不再发生状态迁移：再次到达的结果算重复或迟到。 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 算作组的一次有效结束：只有成功与失败。 */
    public boolean countsAsCompleted() {
        return completed;
    }

    /** 终态成员还需要结束时间，非终态不允许有结束时间。 */
    public static List<String> terminalWireValues() {
        return Arrays.stream(values()).filter(WaitMemberState::isTerminal).map(Enum::name).toList();
    }

    /** 算作有效结束的取值，组完成计数的口径就是这一组。 */
    public static List<String> completedWireValues() {
        return Arrays.stream(values()).filter(WaitMemberState::countsAsCompleted).map(Enum::name).toList();
    }

    /** 库里全部取值，顺序固定，便于契约测试与迁移脚本逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由库里取值解析状态；未知取值直接抛错，不落回任何一个已知状态。 */
    public static WaitMemberState fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("等待成员状态为空，无法判断这个成员处在哪一步");
        }
        String trimmed = raw.strip();
        for (WaitMemberState state : values()) {
            if (state.name().equals(trimmed)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知的等待成员状态：" + raw);
    }
}
