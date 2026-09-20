package world.willfrog.agent.platform.workitem;

import java.util.Arrays;
import java.util.List;

/**
 * 通用节点工作项的状态。
 *
 * <p>本段生效七个：{@link #RUNNABLE} 可运行、{@link #CLAIMED} 已领取、{@link #EXECUTING} 执行中、
 * {@link #RESULT_COMMITTED} 分段结果已提交、{@link #EXECUTION_FAILED} 执行失败、{@link #CANCELED} 被取消、
 * {@link #STALE} 已过期。{@link #WAITING} 与 {@link #RESUMABLE} 是「持久挂起与恢复」那一段的保留值：
 * 本段允许它们出现在数据库列里（约束一次写全，下一段不再改约束），但不产生任何状态迁移。</p>
 *
 * <p>终态是「分段结果已提交、执行失败、被取消、已过期」这四个。终态工作项不再发生状态迁移：需要继续下一段时，
 * 保留这一行的终态，另建一条 {@code segmentSequence + 1} 的新工作项。已过期只用在还没进终态的工作项上。</p>
 */
public enum NodeWorkItemState {

    RUNNABLE("可运行", false, false),
    CLAIMED("已领取", false, false),
    EXECUTING("执行中", false, false),
    RESULT_COMMITTED("分段结果已提交", true, false),
    EXECUTION_FAILED("执行失败", true, false),
    CANCELED("被取消", true, false),
    STALE("已过期", true, false),
    WAITING("等待外部结果", false, true),
    RESUMABLE("可以继续", false, true);

    private final String label;
    private final boolean terminal;
    private final boolean reserved;

    NodeWorkItemState(String label, boolean terminal, boolean reserved) {
        this.label = label;
        this.terminal = terminal;
        this.reserved = reserved;
    }

    /** 中文说明，只用于日志与拒绝事实，不参与任何比较。 */
    public String label() {
        return label;
    }

    /** 终态工作项不再发生状态迁移。 */
    public boolean isTerminal() {
        return terminal;
    }

    /** 保留值：下一段才生效，本段只占位。 */
    public boolean isReserved() {
        return reserved;
    }

    /**
     * 终态的库里取值。热扫描与部分索引用的是同一组取值，「不在终态里」与「在某个状态里」两种写法
     * 必须落在这一组上，否则部分索引不会被用上。
     */
    public static List<String> terminalWireValues() {
        return Arrays.stream(values())
                .filter(NodeWorkItemState::isTerminal)
                .map(Enum::name)
                .toList();
    }

    /** 全部库里取值，顺序固定，便于契约测试逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /**
     * 由库里取值解析状态。未知取值失败关闭：直接抛错，不落回任何一个已知状态，
     * 避免把一条不认识的记录当成可运行或已完成。
     */
    public static NodeWorkItemState fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("工作项状态为空，无法判断这行属于哪个状态");
        }
        String trimmed = raw.strip();
        for (NodeWorkItemState state : values()) {
            if (state.name().equals(trimmed)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知的工作项状态：" + raw);
    }
}
