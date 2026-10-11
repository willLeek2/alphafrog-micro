package world.willfrog.agent.platform.workitem;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 通用节点工作项的一行。
 *
 * <p>表 {@code alphafrog_agent_run_work_item} 上只放热路径必须查询和比较的列：五个身份字段、状态、
 * 四类版本、调度器版本、领取者、租约、下次可领取时间。{@link #payloadJson} 只放节点本地的载荷
 * （节点输入、执行位置、检查点引用、执行器专用内容）；整图的 frontier 不在这一行上，它的唯一权威位置
 * 是 Run 主记录。</p>
 */
@Data
public class NodeWorkItem {

    private Long id;

    // ===== 五个身份字段：一组唯一，不可变 =====
    private String runId;
    private Integer planGeneration;
    private String nodeId;
    private Integer nodeAttempt;
    private Integer segmentSequence;

    /** 状态取值见 {@link NodeWorkItemState}；库里是原始字符串，读出来必须显式解析。 */
    private String state;

    // ===== 四个版本列（计划代际在身份里） =====
    private Long contextVersion;
    private Long runControlVersion;
    private Integer claimEpoch;

    /** 调度器版本的冗余复制，只用于按版本过滤；权威在 Run 主记录。 */
    private String schedulerVersion;

    // ===== 领取者与租约 =====
    private String claimedBy;
    private OffsetDateTime leaseExpiresAt;
    private OffsetDateTime nextVisibleAt;
    /**
     * 这一段进入可运行或可恢复状态的时间，排队等待计时的起点。
     *
     * <p>退避只推后 {@link #nextVisibleAt}，不改这一列，所以「在队列里排了多久」用当前时间减它，
     * 不能用当前时间减 {@code nextVisibleAt}。</p>
     */
    private OffsetDateTime runnableSince;

    /**
     * 最近一次把这条工作项派发出去却失败的原因，取值见
     * {@link NodeDispatchDeferReason}；派发成功（入队、领取、交接）时清空。
     *
     * <p>它是<em>当前状态</em>而不是计数器：要看「被挡了多少次」得用派发路径上的事件计数，
     * 不能统计这一列。挡不挡得住由 {@code nextVisibleAt} 决定，这一列只解释「为什么它一直坐在这里」。</p>
     */
    private String dispatchDeferReason;

    /** 节点本地载荷（JSON 字符串）。 */
    private String payloadJson;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 状态枚举读法；未知取值失败关闭。 */
    public NodeWorkItemState stateEnum() {
        return NodeWorkItemState.fromWire(state);
    }

    /** 调度器版本读法；未知取值失败关闭。 */
    public SchedulerVersion schedulerVersionEnum() {
        return SchedulerVersion.fromWire(schedulerVersion);
    }

    /** 派发延期原因读法；没有延期时返回空，读到不认识的取值失败关闭。 */
    public Optional<NodeDispatchDeferReason> dispatchDeferReasonEnum() {
        return dispatchDeferReason == null || dispatchDeferReason.isBlank()
                ? Optional.empty()
                : Optional.of(NodeDispatchDeferReason.fromWire(dispatchDeferReason));
    }

    public NodeWorkItemIdentity identity() {
        return NodeWorkItemIdentity.of(this);
    }

    public NodeWorkItemVersions versions() {
        return NodeWorkItemVersions.of(this);
    }

    public boolean terminal() {
        return stateEnum().isTerminal();
    }
}
