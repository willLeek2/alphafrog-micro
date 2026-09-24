package world.willfrog.agent.platform.wait;

import lombok.Data;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;

import java.time.OffsetDateTime;

/**
 * 一条等待组记录：某个节点分段的某一次模型回合里并列发出的整组工具请求。
 *
 * <p>身份的六个字段（节点五字段身份加模型回合）在库里有唯一约束，同一身份只会有一行；
 * {@link #nextSegmentSequence} 指向这次挂起之后继续执行的那一段，它在同一个事务里被建成等待态。</p>
 */
@Data
public class WaitGroup {

    private Long id;
    private String runId;
    private Integer planGeneration;
    private String nodeId;
    private Integer nodeAttempt;
    private Integer segmentSequence;
    private Integer modelTurn;
    private String schedulerVersion;
    private Integer nextSegmentSequence;
    /** 状态取值见 {@link WaitGroupState}；库里是原始字符串，读出来必须显式解析。 */
    private String state;
    private Integer expectedMembers;
    private Integer completedMembers;
    private Integer recoveryGeneration;
    private OffsetDateTime readyAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public WaitGroupState stateEnum() {
        return WaitGroupState.fromWire(state);
    }

    public WaitGroupIdentity identity() {
        return new WaitGroupIdentity(
                new NodeWorkItemIdentity(runId, planGeneration, nodeId, nodeAttempt, segmentSequence),
                modelTurn);
    }

    /** 成员是否已经全部真正结束（成功与失败都算）。被取消或迟到的成员不算结束。 */
    public boolean allMembersCompleted() {
        return expectedMembers != null && completedMembers != null
                && completedMembers >= expectedMembers;
    }
}
