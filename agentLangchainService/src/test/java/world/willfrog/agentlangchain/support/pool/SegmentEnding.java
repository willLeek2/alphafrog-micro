package world.willfrog.agentlangchain.support.pool;

/**
 * 一个执行分段的结束方式。三种结束方式提交结果后都交还节点执行许可；
 * {@link #WAIT_EXTERNAL} 这一支在本段原地等，节点执行许可继续占着，它属于下一段「持久挂起与恢复」的范围。
 */
public enum SegmentEnding {

    /** 正常结果。 */
    NORMAL_RESULT,

    /** 执行基础设施失败（位置读不出来、上下文恢复不了这类）。工具返回的失败不算这一种。 */
    EXECUTION_FAILURE,

    /** 控制取消。工具自己返回的取消属于工具结果，不算这一种。 */
    CONTROL_CANCEL,

    /** 原地等外部结果。 */
    WAIT_EXTERNAL
}
