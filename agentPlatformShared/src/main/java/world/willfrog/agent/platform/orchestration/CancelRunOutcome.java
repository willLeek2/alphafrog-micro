package world.willfrog.agent.platform.orchestration;

/**
 * 取消一笔运行的事务结果，控制层据此决定提交后动作与是否重试。
 */
public enum CancelRunOutcome {

    /** 无锚点且运行主记录的终态写入成功；控制层随后执行四件提交后动作。 */
    TERMINAL_WRITTEN,

    /** 有锚点，主记录保持等待长工具，取消意图已写入；终态留给 ToolJobFinalizer。 */
    CANCEL_INTENT_RECORDED,

    /**
     * 有锚点路径里快照条件更新影响 0 行：运行状态在读取后已经变化。
     * 控制层只恢复 Redis 并返回当前运行，不重试也不抛出，与现码 222 至 227 行一致。
     */
    STATE_CHANGED,

    /** 终态条件更新影响 0 行；控制层重读运行后最多再试一次。 */
    NOT_APPLIED
}
