package world.willfrog.agent.platform.dataanalysis;

/**
 * 长工具锚点的 Run 处置常量。
 *
 * <p>DAG blocking 调用在原进程存活时由当前节点线程持有，后台协调器不能接管。
 * 只有 startup recovery 确认原进程已经退出后，才允许把处置从
 * {@link #DAG_BLOCKING_NO_RESUME} 升级为 {@link #DAG_BLOCKING_WORKER_LOST}，
 * 随后只做终态收尾、容量释放和 Run 失败，不恢复 DAG 工作流。</p>
 */
public final class ToolJobRunDisposition {

    public static final String DAG_BLOCKING_NO_RESUME = "DAG_BLOCKING_NO_RESUME";
    public static final String DAG_BLOCKING_WORKER_LOST = "DAG_BLOCKING_WORKER_LOST";
    public static final String DAG_BLOCKING_PREPARING_ABORT =
            "DAG_BLOCKING_PREPARING_ABORT";

    /**
     * 用户在长工具等待期暂停了 Run：锚点保留给收尾器做终态收尾（放配额、记账、发终态事件），
     * 但终态到达后不自动恢复执行，等用户手动恢复。
     */
    public static final String PAUSED = "PAUSED";

    private ToolJobRunDisposition() {
    }

    public static boolean isLiveDagBlocking(String disposition) {
        return DAG_BLOCKING_NO_RESUME.equals(disposition);
    }

    public static boolean isDagCleanupOnly(String disposition) {
        return DAG_BLOCKING_WORKER_LOST.equals(disposition);
    }

    public static boolean isDagPreparingAbort(String disposition) {
        return DAG_BLOCKING_PREPARING_ABORT.equals(disposition);
    }

    public static boolean isDagBlocking(String disposition) {
        return isLiveDagBlocking(disposition)
                || isDagCleanupOnly(disposition)
                || isDagPreparingAbort(disposition);
    }
}
