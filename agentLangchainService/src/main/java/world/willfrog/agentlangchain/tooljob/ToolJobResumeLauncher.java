package world.willfrog.agentlangchain.tooljob;

/**
 * resume service 与有界 Agent 调度器之间的启动边界。
 *
 * <p>实现必须把恢复任务重新提交给与普通 Run 相同的 concurrency scheduler；提交成功才表示重新取得
 * worker 的请求已被接受，而不是当前调用线程直接继续旧 pipeline。这样慢工具等待期不占 worker，
 * 返回后又仍受同一全局并发上限约束。</p>
 */
public interface ToolJobResumeLauncher {
    /**
     * 使用给定上下文启动挂起的 Run。实现应跳过 planner、恢复已完成 todo、把 terminal result
     * 注入原始挂起节点，再从其后继续执行。
     *
     * @return 调度器接受本次 token/version 对应的启动请求时返回 true，否则返回 false
     */
    boolean launch(String runId, ToolJobResumeContext context);

    /**
     * 查询指定 {@code (runId, token, version)} 的恢复声明是否仍在真实执行。
     *
     * <p>reconciler 判断 LAUNCHING 过期时不能只看 claimedAt：worker 可能已经取得租约并在执行较长的
     * 后续步骤。只有租约时间过期且 launcher 报告 inactive，才允许把状态回滚为 READY；否则会让第二个
     * worker 重复恢复同一 Run。默认返回 false，表示未接入活动声明跟踪。</p>
     *
     * @param runId   the agent run identifier
     * @param token   the resume token being queried
     * @param version the lease version being queried
     * @return true if the launcher is still actively processing this claim
     */
    default boolean isActive(String runId, String token, long version) {
        // 保守的 SPI 默认值仅适用于没有真实 launcher 的环境；生产实现必须覆盖并追踪活动三元组。
        return false;
    }
}
