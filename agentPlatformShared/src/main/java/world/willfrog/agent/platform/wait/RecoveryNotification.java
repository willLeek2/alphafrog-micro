package world.willfrog.agent.platform.wait;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一条持久恢复通知：某个等待组的一次恢复资格。
 *
 * <p>同一个组同一个恢复代际只有一行（库里有唯一约束）。消费是一次条件更新：把
 * {@link #state} 从等待改成已取走，并发调用只有一个能改成功，所以「最后一个成员只产生一次恢复资格」
 * 这句话在库里是可以被证明的。</p>
 */
@Data
public class RecoveryNotification {

    private Long id;
    private Long groupId;
    private String runId;
    private Integer recoveryGeneration;
    /** 状态取值见 {@link RecoveryNotificationState}；库里是原始字符串，读出来必须显式解析。 */
    private String state;
    private OffsetDateTime nextVisibleAt;
    private OffsetDateTime consumedAt;
    private String consumedBy;
    /** 被关闭的原因（短文本）；只有关闭态才有值。 */
    private String closeReason;
    /** 被关闭的时刻；只有关闭态才有值。 */
    private OffsetDateTime closedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public RecoveryNotificationState stateEnum() {
        return RecoveryNotificationState.fromWire(state);
    }

    public boolean consumable() {
        return stateEnum() == RecoveryNotificationState.WAITING;
    }

    /** 这条通知是不是已经被收口成关闭态。 */
    public boolean closed() {
        return stateEnum() == RecoveryNotificationState.CLOSED;
    }
}
