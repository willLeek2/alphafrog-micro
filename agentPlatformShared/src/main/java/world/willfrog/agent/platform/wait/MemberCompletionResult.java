package world.willfrog.agent.platform.wait;

/**
 * 一次成员结束上报的结果。
 *
 * @param applied           这次上报有没有真的写进成员行；false 表示重复上报或该拒的迟到上报
 * @param memberState       成员行现在的状态
 * @param groupState        组现在的状态
 * @param completedMembers  组里已经真正结束的成员数
 * @param expectedMembers   组里应有的成员数
 * @param notificationId    这次刚好让组齐备时写出的恢复通知编号；没齐备时为 null
 */
public record MemberCompletionResult(
        boolean applied,
        WaitMemberState memberState,
        WaitGroupState groupState,
        int completedMembers,
        int expectedMembers,
        Long notificationId) {

    /** 这一次上报把组推到了齐备，并且写出了恢复通知。 */
    public boolean groupBecameReady() {
        return notificationId != null;
    }

    /** 组里所有成员都已经真正结束。 */
    public boolean groupComplete() {
        return completedMembers >= expectedMembers;
    }
}
