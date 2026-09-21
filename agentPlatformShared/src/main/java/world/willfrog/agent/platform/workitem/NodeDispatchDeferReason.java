package world.willfrog.agent.platform.workitem;

import java.util.Arrays;
import java.util.List;

/**
 * 一条节点工作项这次没能被派发出去的原因。
 *
 * <p>派发是把「这条工作项该跑了」变成「执行侧收到提醒」的那一步。失败的原因落在工作项自己身上，
 * 别的排队的 Run 不受影响：同一个 Run 里一条工作项没入队，不代表这个 Run 被容量挡住。</p>
 */
public enum NodeDispatchDeferReason {

    /** 内存提醒队列已满：这次没能入队，留下下次检查时间等补扫。 */
    HINT_QUEUE_FULL;

    /** 库里全部取值，顺序固定，便于契约测试与迁移脚本逐项比对。 */
    public static List<String> allWireValues() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 由库里取值解析原因；未知取值直接抛错，避免把不认识的拒绝原因当成正常延期。 */
    public static NodeDispatchDeferReason fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("派发延期原因为空");
        }
        String trimmed = raw.strip();
        for (NodeDispatchDeferReason reason : values()) {
            if (reason.name().equals(trimmed)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("未知的节点派发延期原因：" + raw);
    }
}
