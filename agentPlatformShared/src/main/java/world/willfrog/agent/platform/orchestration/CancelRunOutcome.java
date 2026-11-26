package world.willfrog.agent.platform.orchestration;

/**
 * 取消一笔运行的事务结果，控制层据此决定提交后动作与是否重试。
 */
public enum CancelRunOutcome {

    /** 无锚点且运行主记录的终态写入成功；控制层随后执行四件提交后动作。 */
    TERMINAL_WRITTEN,

    /** 有锚点，主记录保持等待长工具，取消意图已写入；终态留给 ToolJobFinalizer。 */
    CANCEL_INTENT_RECORDED,

    /** 条件更新影响 0 行；控制层重读运行后最多再试一次。 */
    NOT_APPLIED
}
