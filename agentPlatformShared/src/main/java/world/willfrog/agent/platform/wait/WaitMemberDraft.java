package world.willfrog.agent.platform.wait;

import lombok.Data;

/**
 * 整组落库时的一个成员。
 *
 * <p>它是写库时的输入，带 getter：SQL 里按成员逐条取参数，不用记录式的取值方法。
 * 成员身份由 {@link WaitMemberIdentity#stableIdentity} 在服务端一次算好，这里只负责携带。</p>
 */
@Data
public class WaitMemberDraft {

    /** 成员在这组请求里的原始序号，从 0 开始。 */
    private final int memberSeq;
    /** 模型给出的工具调用身份，可能为空。 */
    private final String toolCallId;
    private final String toolName;
    /**
     * 异步外部作业身份，可能为空。
     *
     * <p>唯一范围是全局，不是同一个 Run 内：库里对它有全局唯一约束，跨 Run 撞同一个身份会被拒。
     * {@code runId + externalOperationId} 这条查询里，Run 是额外的身份核对，不是唯一性范围。</p>
     */
    private final String externalOperationId;

    public WaitMemberDraft(int memberSeq, String toolCallId, String toolName, String externalOperationId) {
        if (memberSeq < 0) {
            throw new IllegalArgumentException("成员原始序号不能是负数：" + memberSeq);
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("成员必须有工具名，否则恢复时分不清要接回什么结果");
        }
        this.memberSeq = memberSeq;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.externalOperationId = externalOperationId;
    }
}
