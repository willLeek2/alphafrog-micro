package world.willfrog.agent.platform.wait;

/**
 * 整组挂起这一次调用到底做成了什么。
 *
 * <p>三种结果都是正常返回值：真正的失败只有数据库报错。调用方要按这三种情况分别处理，
 * 不能把后两种当成成功，也不能重试到成功为止。</p>
 */
public enum WaitSuspensionOutcome {

    /** 分段已结束、组与成员已保存、下一段已建成等待态。 */
    SUSPENDED,

    /** 同一次模型回合的整组请求之前已经保存过：这一次什么都没写，直接用已有的组。 */
    ALREADY_SUSPENDED,

    /** 当前分段已经不在调用方手里（代际不符、已被结束、或运行图已经推进）：一行都没写。 */
    SEGMENT_NOT_MATCHED
}
