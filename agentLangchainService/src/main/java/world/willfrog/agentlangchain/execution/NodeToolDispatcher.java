package world.willfrog.agentlangchain.execution;

import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.util.Optional;

/**
 * 派发一次工具调用：新调度器版本的节点执行器在整组落库之后、交还节点执行名额之前，通过它执行这一次工具。
 *
 * <p>它与 LangChain4j 自动工具循环（框架拿到模型回复就直接执行工具）的区别，在于「谁决定什么时候执行」。
 * 这里必须先由调用方把整组请求、检查点与下一条等待分段写进数据库，再逐个派发；否则进程在外部副作用
 * 发生之后退出时，没人知道那一次调用本该属于哪个节点、哪一段、第几个成员。</p>
 *
 * <p>这一层只回答一个问题：这一次工具调用是当场有了结果，还是交给了后台作业。至于结果怎么写进数据库，
 * 由调用方按自己的存储接口处理，派发层不碰等待组与成员行。</p>
 */
public interface NodeToolDispatcher {

    /** 执行一次工具调用。控制信号（额度、取消、挂起）必须原样抛出，不能转成普通失败文本。 */
    DispatchOutcome dispatch(DispatchRequest request);

    /**
     * 会转后台的工具在派发之前的稳定外部作业身份；其他工具返回空。
     *
     * <p>整组落库时就把这个身份写进成员行，进程在任何时刻退出都能凭它找回同一个外部作业，不用等派发完成
     * 再补写。它由工具名、模型给出的工具调用身份与节点分段身份共同决定，同一段重复计算永远得到同一个值。</p>
     */
    Optional<String> stableOperationId(String toolName, String rawToolCallId, NodeWorkItemIdentity segment);

    /**
     * 这次工具调用是不是必须先有稳定外部作业身份。
     *
     * <p>转后台的工具如果没有模型给出的工具调用身份，就算不出稳定身份，也就没法在进程退出后找回
     * 同一个外部作业。这种情况整组落库之前就要停下，不能先存一组没有身份的成员。</p>
     */
    default boolean requiresStableOperationId(String toolName) {
        return false;
    }

    /**
     * 一次工具调用的输入。
     *
     * @param runId         这条 Run
     * @param segment       当前执行分段的五字段身份
     * @param groupId       这次调用所属的等待组
     * @param memberSeq     它在整组请求里的原始序号
     * @param toolCallId    模型给出的工具调用身份，可能为空
     * @param toolName      工具名
     * @param argumentsJson 模型给出的参数 JSON
     */
    record DispatchRequest(String runId,
                           NodeWorkItemIdentity segment,
                           long groupId,
                           int memberSeq,
                           String toolCallId,
                           String toolName,
                           String argumentsJson) {
    }

    /** 一次工具调用的结果。 */
    sealed interface DispatchOutcome {

        /** 当场拿到结果：结果正文原样交给调用方写进成员行。 */
        record Completed(String output) implements DispatchOutcome {
        }

        /** 已经交给后台作业：外部作业身份与后台任务编号一并返回。 */
        record Pending(String operationId, String taskId) implements DispatchOutcome {
        }

        /** 工具自己报了失败：失败文本交给调用方写进成员行，恢复后由模型决定下一步。 */
        record Failed(String reason) implements DispatchOutcome {
        }
    }
}
