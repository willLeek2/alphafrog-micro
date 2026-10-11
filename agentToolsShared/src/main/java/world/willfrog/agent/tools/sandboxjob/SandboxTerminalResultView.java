package world.willfrog.agent.tools.sandboxjob;

/**
 * 沙箱后台任务的终态结果视图（工具中立）。
 *
 * <p>各适配器把自家沙箱协议的终态响应映射成这个视图，框架与结果/计量适配器只面对它，
 * 不直接碰任何工具的 RPC 类型。字段全部来自现有 Python 沙箱终态响应里框架真正消费的子集。</p>
 *
 * @param statusName      规范化终态名：SUCCEEDED / FAILED / CANCELED / NOT_FOUND / RESULT_LOST
 * @param exitCode        运行器退出码；无退出码语义的终态传 null
 * @param stdout          标准输出全文（可能很大，消费方自行截断）
 * @param stderr          标准错误全文
 * @param resultJson      结构化结果 JSON（没有结构化结果时为 null）
 * @param usageJson       资源用量 JSON（cpuMillis、memoryPeakBytes 等键的原始映射，没有则为 null）
 * @param errorDetail     沙箱侧错误详情（没有则为 null）
 * @param retryable       沙箱明示的「可重试」标记；响应没带这个字段时为 null（语义等同 proto presence）
 * @param rawRef          原始结果引用（如沙箱产物目录）；没有则为 null
 * @param nativePayload   工具原生终态响应对象，框架不透明、只透传给工具的终态副作用钩子
 */
public record SandboxTerminalResultView(
        String statusName,
        Integer exitCode,
        String stdout,
        String stderr,
        String resultJson,
        String usageJson,
        String errorDetail,
        Boolean retryable,
        String rawRef,
        Object nativePayload) {

    public boolean succeeded() {
        return "SUCCEEDED".equals(statusName);
    }
}
