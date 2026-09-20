package world.willfrog.agent.platform.workitem;

/**
 * 一次被拒的条件更新，连同要留痕的东西。
 *
 * <p>已经发生的执行和外部副作用要如实留痕：不接入上下文，不等于当它没发生过。所以拒绝事实里除了身份、
 * 四类版本与拒绝原因，还留一个外部副作用的摘要或引用（例如 Sandbox 任务编号），由调用方填写。</p>
 */
public record NodeWorkItemRejection(
        NodeWorkItemRejectionReason reason,
        NodeWorkItemIdentity identity,
        NodeWorkItemVersions versions,
        String detail,
        String externalSideEffectRef) {

    public static NodeWorkItemRejection of(NodeWorkItemRejectionReason reason,
                                           NodeWorkItemIdentity identity,
                                           NodeWorkItemVersions versions,
                                           String externalSideEffectRef) {
        return new NodeWorkItemRejection(reason, identity, versions, reason.detail(), externalSideEffectRef);
    }

    /** 是否是「旧领取者迟到提交」这一类；这一类要用 {@link NodeWorkItemEvents#STALE_SUBMISSION_REJECTED} 留痕。 */
    public boolean staleSubmission() {
        return reason == NodeWorkItemRejectionReason.STALE_SUBMISSION;
    }

    public String describe() {
        return NodeWorkItemEvents.STALE_SUBMISSION_REJECTED.equals(eventName())
                ? eventName() + "｜" + identity.describe() + "｜" + versions.describe()
                        + "｜原因：" + detail + "｜外部副作用：" + (externalSideEffectRef == null ? "无" : externalSideEffectRef)
                : "拒绝｜" + identity.describe() + "｜" + versions.describe() + "｜原因：" + detail;
    }

    /** 拒绝事实对外的事件名。 */
    public String eventName() {
        return staleSubmission() ? NodeWorkItemEvents.STALE_SUBMISSION_REJECTED : NodeWorkItemEvents.SUBMISSION_REJECTED;
    }
}
