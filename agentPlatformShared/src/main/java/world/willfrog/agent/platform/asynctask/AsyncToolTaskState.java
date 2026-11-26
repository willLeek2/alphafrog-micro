package world.willfrog.agent.platform.asynctask;

/**
 * 异步工具任务的五个状态。
 *
 * <p>没有迟到终态：取消或计划代际失效之后才到的结果，按领取轮次做条件更新，
 * 影响 0 行就记日志忽略，不改任务行，也不推进运行。</p>
 */
public enum AsyncToolTaskState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED;

    /** 库里存的是原始字符串，读出来必须显式解析，不能按字符串猜。 */
    public static AsyncToolTaskState fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("异步工具任务状态不能为空");
        }
        return valueOf(wire);
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
