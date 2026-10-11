package world.willfrog.agent.platform.workitem;

/**
 * 读到不认识的调度器版本时抛出。
 *
 * <p>这是一个明确的失败关闭信号：调用方不得 catch 之后落回 {@link SchedulerVersion#LEGACY} 或
 * {@link SchedulerVersion#DUAL_POOL_V1}，也不得跳过这条 Run。整个 Run 的准入、追问、启动恢复、
 * 工具恢复、取消与各类对账扫描都要按精确版本路由，认不出就不处理。</p>
 */
public class UnknownSchedulerVersionException extends IllegalStateException {

    public UnknownSchedulerVersionException(String message) {
        super(message);
    }
}
