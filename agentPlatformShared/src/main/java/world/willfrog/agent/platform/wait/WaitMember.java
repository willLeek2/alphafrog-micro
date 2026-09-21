package world.willfrog.agent.platform.wait;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一条等待成员记录：组里的一次工具调用。
 *
 * <p>{@link #memberIdentity} 是稳定身份，优先用模型给出的工具调用身份，模型没给就按「组 + 原始序号」
 * 生成；组内唯一。{@link #externalOperationId} 是异步外部作业身份，同一个 Run 内唯一，重投同一作业时
 * 靠它定位回同一个成员。</p>
 */
@Data
public class WaitMember {

    private Long id;
    private Long groupId;
    private String runId;
    private Integer memberSeq;
    private String memberIdentity;
    private String toolCallId;
    private String toolName;
    private String externalOperationId;
    /** 状态取值见 {@link WaitMemberState}；库里是原始字符串，读出来必须显式解析。 */
    private String state;
    /** 结果引用：指向持久结果载荷的位置，不是结果正文。 */
    private String resultRefJson;
    private OffsetDateTime nextPollAt;
    private Integer pollCount;
    private Integer backoffStep;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public WaitMemberState stateEnum() {
        return WaitMemberState.fromWire(state);
    }

    /** 这一行还会不会再变。 */
    public boolean terminal() {
        return stateEnum().isTerminal();
    }

    /** 这一次结束算不算组的有效结束（成功与失败算，取消与迟到不算）。 */
    public boolean countsAsCompleted() {
        return stateEnum().countsAsCompleted();
    }
}
