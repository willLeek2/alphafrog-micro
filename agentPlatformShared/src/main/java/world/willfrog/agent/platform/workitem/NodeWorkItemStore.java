package world.willfrog.agent.platform.workitem;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 通用节点工作项的持久化与领取接口：节点侧拿结果、Run 协调回合派活，两边都经过它。
 *
 * <p>它的职责边界：只做「一行工作项的状态迁移与条件更新」，不解释编排语义。怎么算下一个节点、什么时候终结一张图，
 * 由 Run 协调回合决定；节点池只按工作项上的内容干活。</p>
 *
 * <p>写入分工：节点侧只提交「带完整身份与版本的分段结果」；一个分段结果只有这一条路能写进去。Run 协调回合读到
 * 已提交的结果之后再推进编排，两边不直接改同一组编排状态。</p>
 *
 * <p>所有迁移都是条件更新：影响行数为 0 时返回被拒事实（{@link NodeWorkItemRejection}），调用方负责按
 * {@link NodeWorkItemEvents} 上报，不要把它当成成功，也不要重试到成功为止。</p>
 */
public interface NodeWorkItemStore {

    /** 创建一条工作项。同一个身份已经有一行时返回被拒事实（原因 {@code DUPLICATE_IDENTITY}）。 */
    NodeWorkItemMutationResult create(NodeWorkItem item);

    /** 扫描可领取的工作项，按调度器版本过滤。 */
    List<NodeWorkItem> scanClaimable(SchedulerVersion schedulerVersion, int limit);

    /**
     * 条件领取：状态可运行、三类期望版本匹配，成功时返回新的领取代际。
     *
     * <p>领不到就是领不到（返回空），不区分「被别人领走」与「版本已变」：这两种情况下本次都该放弃，
     * 由在线扫描下一轮重新发现。领取成功即占用一个节点执行许可，由调用方在结束这个分段时交还。</p>
     */
    Optional<NodeWorkItemClaim> claim(NodeWorkItemIdentity identity,
                                      NodeWorkItemVersions expected,
                                      String claimant,
                                      Duration lease,
                                      SchedulerVersion schedulerVersion);

    /** 已领取 → 执行中：领取者与领取代际都要匹配，不匹配立即停止。 */
    NodeWorkItemMutationResult startExecution(NodeWorkItemIdentity identity, int claimEpoch, String claimant);

    /**
     * 提交分段结果：执行中 → 分段结果已提交。
     *
     * <p>{@code payloadPatch} 是可空的分段结果 JSON，按浅合并并入节点本地载荷；{@code externalSideEffectRef}
     * 是这次执行产生的外部副作用摘要或引用（例如 Sandbox 任务编号），提交被拒时会放进拒绝事实里，
     * 让「已经发生过的执行」如实留痕。</p>
     */
    NodeWorkItemMutationResult commitSegmentResult(NodeWorkItemIdentity identity,
                                                   NodeWorkItemVersions versions,
                                                   String payloadPatchJson,
                                                   String externalSideEffectRef);

    /** 报执行失败：执行中 → 执行失败。只有执行基础设施自己出错走这条。 */
    NodeWorkItemMutationResult reportExecutionFailure(NodeWorkItemIdentity identity,
                                                      int claimEpoch,
                                                      String claimant,
                                                      String reason);

    /** 续租：只更新租约到期时间。租约到期本身不换领取者。 */
    NodeWorkItemMutationResult renewLease(NodeWorkItemIdentity identity,
                                          int claimEpoch,
                                          String claimant,
                                          Duration lease);

    /** 控制取消：任意非终态 → 被取消，条件带控制版本与领取代际。 */
    NodeWorkItemMutationResult cancel(NodeWorkItemIdentity identity,
                                      long runControlVersion,
                                      int claimEpoch,
                                      String reason);

    /** 判定版本失效：任意非终态 → 已过期。已经进终态的工作项不会被改动。 */
    NodeWorkItemMutationResult markStale(NodeWorkItemIdentity identity,
                                         long contextVersion,
                                         long runControlVersion,
                                         String reason);

    /**
     * 显式转交所有权，只给验收控制面用：调用方必须先确认原执行者已经停止，再走这一步；
     * 常规路径不靠租约到期换领取者。返回空表示期望代际不匹配或这一行已经不在可转交状态。
     */
    Optional<NodeWorkItemClaim> handOverClaim(NodeWorkItemIdentity identity,
                                              int expectedClaimEpoch,
                                              String newOwner,
                                              Duration lease);

    Optional<NodeWorkItem> findByIdentity(NodeWorkItemIdentity identity);

    List<NodeWorkItem> listUnfinishedByRun(String runId);

    /** 数据库里未完成的工作项总数：背压四个数之一。 */
    int countUnfinished();

    int countUnfinishedByRun(String runId);

    /**
     * 读取一个 Run 已使用过的最大计划代际；从未创建工作项时返回 -1。
     * 追问和同进程手动恢复复用 runId，必须从数据库已有代际继续递增。
     */
    int maxPlanGenerationByRun(String runId);

    /** 所有 Run 里未完成工作项最多的那个数量，与每个 Run 的上限对照着看。 */
    int maxUnfinishedPerRun();

    /** 某个调度器版本下未完成的工作项：进程启动时查遗留记录用的就是它。 */
    List<NodeWorkItem> listUnfinishedBySchedulerVersion(SchedulerVersion schedulerVersion, int limit);

    int countUnfinishedBySchedulerVersion(SchedulerVersion schedulerVersion);

    /** 某个调度器版本是否有遗留记录——有就必须禁止该版本的执行扫描、暂停该版本的新建准入。 */
    default boolean hasResidueFor(SchedulerVersion schedulerVersion) {
        return countUnfinishedBySchedulerVersion(schedulerVersion) > 0;
    }
}
