package world.willfrog.agent.platform.workitem;

import java.util.Arrays;
import java.util.List;

/**
 * 通用节点工作项的状态。
 *
 * <p>九个取值全部生效：{@link #RUNNABLE} 可运行、{@link #CLAIMED} 已领取、{@link #EXECUTING} 执行中、
 * {@link #RESULT_COMMITTED} 分段结果已提交、{@link #EXECUTION_FAILED} 执行失败、{@link #CANCELED} 被取消、
 * {@link #STALE} 已过期，以及挂起链上的两个：{@link #WAITING} 等待外部结果、{@link #RESUMABLE} 可以继续。
 * 后两个在双池骨架那一段只是数据库里预留的取值，阶段三起正式使用。</p>
 *
 * <p>终态是「分段结果已提交、执行失败、被取消、已过期」这四个。终态工作项不再发生状态迁移：需要继续下一段时，
 * 保留这一行的终态，另建一条 {@code segmentSequence + 1} 的新工作项。已过期只用在还没进终态的工作项上。</p>
 *
 * <p>等待链上的两个状态不是终态：{@link #WAITING} 表示这一段已把等待组交给外部作业、自身不再占用执行线程；
 * {@link #RESUMABLE} 表示等待组的成员结果已经齐备，这一段可以被重新领取继续执行。</p>
 */
public enum NodeWorkItemState {

    RUNNABLE("可运行", false),
    CLAIMED("已领取", false),
    EXECUTING("执行中", false),
    RESULT_COMMITTED("分段结果已提交", true),
    EXECUTION_FAILED("执行失败", true),
    CANCELED("被取消", true),
    STALE("已过期", true),
    WAITING("等待外部结果", false),
    RESUMABLE("可以继续", false);

    private final String label;
    private final boolean terminal;

    NodeWorkItemState(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    /** 中文说明，只用于日志与拒绝事实，不参与任何比较。 */
    public String label() {
        return label;
    }

    /** 终态工作项不再发生状态迁移。 */
    public boolean isTerminal() {
        return terminal;
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

    /**
     * 可以被领取的两个状态的库里取值：首次可运行与结果齐备后的可恢复。领取语句只从这两个状态领，
     * 其余状态（含等待中）都不能被领取。
     */
    public static List<String> claimableWireValues() {
        return List.of(RUNNABLE.name(), RESUMABLE.name());
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
