package world.willfrog.agent.platform.wait;

import java.util.Arrays;
import java.util.List;

/**
 * 一次恢复消费没有成功的原因，由消费语句自己给出。
 *
 * <p>以前只有一个「取不走」的布尔值，调用方只能拿自己手上的那份 Run 快照猜原因：猜出来的原因可能已经
 * 过期，于是「过一会儿就能取」和「永远取不了」被当成同一件事，永远取不了的那些就一直回到扫描队头。
 * 现在原因从语句里出来——判断与结果出自同一份库状态，调用方按原因决定是推后还是收口。</p>
 *
 * <p>每一种原因自带一句话和一个「还能不能有下次」的判断。{@link #permanent()} 为真表示这条通知不会
 * 再被服务：Run 已经终态、计划或控制版本已经作废、下一段已经不在等待态——这些要落成关闭态并且写明
 * 原因，而不是一直退避。为假表示过一会儿再看可能就成了（还没到时间、组还没齐备、Run 正在取消中，
 * 或者这一次没抢到服务所有权）。</p>
 */
public enum RecoveryRejection {

    /** 通知已经不处于等待态：被别人取走了，或者已经关闭。既不是永久失效，也不该再退避重试。 */
    NOTIFICATION_NOT_WAITING("通知已经不处于等待态", false),
    /** 还没到下次可见时间。 */
    NOTIFICATION_NOT_DUE("还没到下次可见时间", false),
    /** 通知指向的 Run 行不存在：这条链已经没有可归属的对象。 */
    RUN_MISSING("通知指向的 Run 不存在", true),
    /** 通知指向的等待组行不存在。 */
    GROUP_MISSING("通知指向的等待组不存在", true),
    /** 通知的恢复代际落后于组当前代际：这条资格已经作废。 */
    GENERATION_STALE("通知的恢复代际已经落后", true),
    /** 等待链已经取消。 */
    GROUP_CANCELED("等待链已经取消", true),
    /** 等待组已经恢复过一轮：这条资格已经用掉了。 */
    GROUP_ALREADY_RESUMED("等待组已经恢复过一轮", true),
    /** 等待组还没齐备：成员还在跑，过一会儿再看。 */
    GROUP_NOT_READY("等待组还没齐备", false),
    /** Run 已经进入终态：不会再有下一步。 */
    RUN_TERMINAL("Run 已经进入终态", true),
    /** Run 正在取消：等它真正进入终态后按终态收口，先短暂退避。 */
    RUN_CANCELING("Run 正在取消", false),
    /** Run 不在执行中（暂停、待恢复等）：由控制路径决定它下一步怎么走，先退避。 */
    RUN_NOT_EXECUTING("Run 不在执行中", false),
    /** 计划代际已经推进：这条通知属于作废计划。 */
    PLAN_GENERATION_STALE("计划代际已经推进", true),
    /** 调用方手上的 Run 控制版本已经过期：重新读一次再试。 */
    RUN_CONTROL_VERSION_CHANGED("调用方手上的 Run 控制版本已过期", false),
    /** 下一段工作项自己保存的控制版本与 Run 当前控制版本不一致：这一段属于作废的控制代际。 */
    SEGMENT_CONTROL_VERSION_STALE("下一段工作项的控制版本已经作废", true),
    /** 下一段工作项不存在：库里没有这一行可放行。 */
    NEXT_SEGMENT_MISSING("下一段工作项不存在", true),
    /** 下一段已经在执行链上（可领取、已领取或执行中）：资格已经用掉。 */
    NEXT_SEGMENT_ACTIVE("下一段已经在执行链上", true),
    /** 下一段已经结束：这条链不会再从这里继续。 */
    NEXT_SEGMENT_TERMINAL("下一段已经结束", true),
    /** 服务所有权不在本进程，或者手上的代际已经被接手作废。 */
    LEASE_NOT_OWNED("服务所有权不在本进程或代际已作废", false),
    /** 条件不满足但没有匹配到已知原因。 */
    UNKNOWN("条件不满足但没有匹配到已知原因", false);

    private final String label;
    private final boolean permanent;

    RecoveryRejection(String label, boolean permanent) {
        this.label = label;
        this.permanent = permanent;
    }

    /** 中文说明，只用于日志与关闭原因，不参与任何比较。 */
    public String label() {
        return label;
    }

    /** 这条通知是不是不会再被服务：为真就该收口成关闭态，而不是继续退避。 */
    public boolean permanent() {
        return permanent;
    }

    /**
     * 写进关闭原因的那段短文本。
     *
     * <p>原因要能区分几大类，事后一眼看出这条通知为什么被关掉：不可归属、Run 终态收口、计划或控制版本
     * 失效、下一段已经不在等待态。{@code detail} 是同一行里读到的补充（比如 Run 的实际状态、
     * 下一段的实际状态），没有就省掉冒号后半段。</p>
     */
    public String closeReason(String detail) {
        String base = switch (this) {
            case RUN_MISSING -> "run_missing";
            case GROUP_MISSING -> "group_missing";
            case GENERATION_STALE -> "generation_stale";
            case GROUP_CANCELED -> "group_canceled";
            case GROUP_ALREADY_RESUMED -> "group_already_resumed";
            case RUN_TERMINAL -> "run_terminal";
            case PLAN_GENERATION_STALE -> "plan_generation_stale";
            case SEGMENT_CONTROL_VERSION_STALE -> "segment_control_version_stale";
            case NEXT_SEGMENT_MISSING -> "next_segment_missing";
            case NEXT_SEGMENT_ACTIVE -> "next_segment_already_active";
            case NEXT_SEGMENT_TERMINAL -> "next_segment_terminal";
            default -> name().toLowerCase();
        };
        if (detail == null || detail.isBlank()) {
            return base;
        }
        String trimmed = detail.strip();
        String suffix = trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
        return base + ":" + suffix;
    }

    /** 库里可能出现的原因集合，顺序固定，便于契约测试与迁移脚本逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由语句返回的取值解析原因；空值表示这次没有拒绝（消费成功）。 */
    public static RecoveryRejection fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        for (RecoveryRejection rejection : values()) {
            if (rejection.name().equals(trimmed)) {
                return rejection;
            }
        }
        throw new IllegalArgumentException("未知的恢复消费拒绝原因：" + raw);
    }
}
