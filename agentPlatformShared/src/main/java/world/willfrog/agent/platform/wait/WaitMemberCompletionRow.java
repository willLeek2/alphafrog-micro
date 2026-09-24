package world.willfrog.agent.platform.wait;

import lombok.Data;

/**
 * 成员结束语句返回的原始计数与状态行。
 *
 * <p>{@code writtenMembers} 为 0 表示这次上报没有写进任何成员行：这个成员已经落过终态，
 * 或者运行图已经推进到不该再接收结果的状态。</p>
 */
@Data
public class WaitMemberCompletionRow {

    private Long memberId;
    private Integer memberSeq;
    private String memberState;
    private Long groupId;
    private String groupState;
    private Integer completedMembers;
    private Integer expectedMembers;
    /** 这次刚好让组齐备时，新产生的恢复代际；没齐备时为 null。 */
    private Integer recoveryGeneration;
    /** 这次刚好让组齐备时，写出的恢复通知编号；没齐备时为 null。与库里的自增主键一样用 Long。 */
    private Long notificationId;
    private Integer writtenMembers;
    /** 这次连带停掉的兄弟成员数：只有迟到这条路径会用到。 */
    private Integer writtenSiblings;
}
