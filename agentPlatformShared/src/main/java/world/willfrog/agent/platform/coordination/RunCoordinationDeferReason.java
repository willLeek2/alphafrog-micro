package world.willfrog.agent.platform.coordination;

import java.util.Arrays;
import java.util.List;

/**
 * Run 协调这一层被推迟的原因。
 *
 * <p>协调器每轮处理一批 Run。没能推进的 Run 会写下一个明确原因和下次可见时间，而不是安静地跳过：
 * 只靠日志看不出「这个 Run 是被容量挡住，还是根本没被挑中」，而验收要看的就是这两类拒绝数。</p>
 *
 * <p>这里只放 Run 这一层的拒绝原因。节点派发那一层（比如内存提醒队列满）的原因记在工作项自己身上，
 * 见 {@link world.willfrog.agent.platform.workitem.NodeDispatchDeferReason}：两者作用对象不同，
 * 合成一份会让「这个 Run 被挡」和「这条工作项没入队」互相覆盖。</p>
 */
public enum RunCoordinationDeferReason {

    /** Run 协调名额已满：本实例这一轮能同时推进的 Run 数已达上限。 */
    RUN_COORDINATION_PERMIT_FULL,

    /** 本回合新增节点数已达上限：这一轮能新建的节点分段已经用满。 */
    PER_ROUND_NEW_NODE_LIMIT,

    /** 这个 Run 自己未完成的节点数已达上限：先让它把已有的节点跑完。 */
    PER_RUN_UNFINISHED_LIMIT,

    /** 全局未完成工作项到了高水位，新增已经暂停：要等回落到低水位才恢复。 */
    GLOBAL_UNFINISHED_PAUSED;

    /** 库里全部取值，顺序固定，便于契约测试与迁移脚本逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由库里取值解析原因；未知取值直接抛错，避免把不认识的拒绝原因当成正常延期。 */
    public static RunCoordinationDeferReason fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("延期原因为空");
        }
        String trimmed = raw.strip();
        for (RunCoordinationDeferReason reason : values()) {
            if (reason.name().equals(trimmed)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知的协调延期原因：" + raw);
    }
}
