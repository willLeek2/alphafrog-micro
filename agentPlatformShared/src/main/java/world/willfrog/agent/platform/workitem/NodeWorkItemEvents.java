package world.willfrog.agent.platform.workitem;

/**
 * 通用节点工作项对外的事件名。
 *
 * <p>本段新增的公开事件只有 {@link #STALE_SUBMISSION_REJECTED}；其余拒绝原因用
 * {@link #SUBMISSION_REJECTED} 上报，靠 payload 里的原因区分。</p>
 */
public final class NodeWorkItemEvents {

    /** 旧领取者带着旧领取代际来提交，条件更新影响行数为 0：不接入上下文、不推进 Run，只留痕。 */
    public static final String STALE_SUBMISSION_REJECTED = "STALE_SUBMISSION_REJECTED";

    /** 其它原因导致的条件更新影响行数为 0。 */
    public static final String SUBMISSION_REJECTED = "SUBMISSION_REJECTED";

    private NodeWorkItemEvents() {
    }
}
