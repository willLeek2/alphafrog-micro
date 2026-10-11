package world.willfrog.agent.platform.wait;

/**
 * 取消一条等待链的结果：每个动作各自影响了几行。
 *
 * @param groupsCanceled        被停下的等待组数，正常是 1；0 表示这个组已经不在可取消的状态
 * @param membersCanceled       被停下的还没结束的成员数
 * @param segmentsCanceled      被停下的下一段数
 * @param notificationsCanceled 被停下的还没被取走的恢复通知数
 */
public record WaitChainCancelResult(
        int groupsCanceled,
        int membersCanceled,
        int segmentsCanceled,
        int notificationsCanceled) {

    public boolean canceled() {
        return groupsCanceled > 0;
    }
}
