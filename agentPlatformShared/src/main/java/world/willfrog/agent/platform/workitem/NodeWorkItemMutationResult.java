package world.willfrog.agent.platform.workitem;

/**
 * 一次条件更新的结果：写进去了，还是被拒了。
 *
 * <p>所有状态迁移都靠「带版本条件的更新语句影响行数」判定成败：影响行数为 0 就是没写进去，
 * 不允许调用方把它当成成功，也不允许先读后写绕过条件更新。</p>
 */
public record NodeWorkItemMutationResult(boolean applied, NodeWorkItemRejection rejection) {

    public static NodeWorkItemMutationResult success() {
        return new NodeWorkItemMutationResult(true, null);
    }

    public static NodeWorkItemMutationResult rejected(NodeWorkItemRejection rejection) {
        return new NodeWorkItemMutationResult(false, rejection);
    }

    public boolean rejectedStaleSubmission() {
        return !applied && rejection != null && rejection.staleSubmission();
    }

    /** 被拒时抛错，用于「这一步没写进去就不能往下做」的调用点。 */
    public NodeWorkItemMutationResult orThrow() {
        if (!applied) {
            throw new IllegalStateException("工作项条件更新被拒：" + (rejection == null ? "原因未知" : rejection.describe()));
        }
        return this;
    }
}
