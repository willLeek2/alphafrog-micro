package world.willfrog.agent.platform.workitem;

import java.time.Duration;
import java.time.OffsetDateTime;
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

    /**
     * 创建一条工作项。同一个身份已经有一行时返回被拒事实（原因 {@code DUPLICATE_IDENTITY}）。
     *
     * <p>这是 Run 级写入：{@code fence} 是本进程此刻的服务所有权凭据，语句里核持有人与代际号；
     * 没有凭据、或凭据已经被别人换掉时写不进去，返回被拒事实。</p>
     */
    NodeWorkItemMutationResult create(NodeWorkItem item, ServiceOwnershipFence fence);

    /** 扫描可领取的工作项，按调度器版本过滤。按版本查看进度时用它；节点派发不要用，见下一个方法。 */
    List<NodeWorkItem> scanClaimable(SchedulerVersion schedulerVersion, int limit);

    /**
     * 节点派发的一次全局扫描：双池家族的到期可领取分段放在同一份候选里取回，每条记录带着自己的冻结版本。
     *
     * <p>派发器必须用这一个：新旧版本共用同一个节点池，按版本各扫一次等于每种版本各取一份名额，
     * 轮转顺序就散了。顺序里排在第一位的是这张图最近被派发的轮次，从没被派发过的排最前。</p>
     */
    List<NodeWorkItem> scanClaimableAcrossDualPool(int limit);

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
                                      SchedulerVersion schedulerVersion,
                                      ServiceOwnershipFence fence);

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

    /**
     * 长工具已经转入后台后，把当前执行分段持久化为等待态。Run 必须已经处于
     * {@code WAITING_TOOL_JOB}，工具锚点必须仍绑定同一工作项身份与版本。
     */
    NodeWorkItemMutationResult suspendForToolJob(NodeWorkItemIdentity identity,
                                                 NodeWorkItemVersions versions,
                                                 String claimant,
                                                 String operationId,
                                                 String toolCallId,
                                                 int attempt);

    /**
     * 工具终态完成收尾后，把 Run 恢复为执行中，并把原工作项推进到可恢复态。
     * Run、工具锚点和工作项在一个数据库事务里一起推进。
     */
    NodeWorkItemMutationResult promoteToolJobResumable(NodeWorkItemIdentity identity,
                                                       NodeWorkItemVersions versions,
                                                       String operationId,
                                                       String anchorJson,
                                                       String resumePayloadJson);

    /**
     * 可恢复分段提交结果时，同时清理精确匹配的工具锚点。条件不匹配时两边都不改。
     */
    NodeWorkItemMutationResult commitResumedToolJobResult(NodeWorkItemIdentity identity,
                                                          NodeWorkItemVersions versions,
                                                          String operationId,
                                                          String payloadPatchJson,
                                                          String externalSideEffectRef);

    /**
     * 启动恢复专用：把一段死在「已领取/执行中」的分段放回可领取状态，并把领取代际加一废掉旧领取者。
     *
     * <p>只在调用方已经取得这条 Run 的服务租约之后使用。分段的执行路径由载荷与等待组事实决定，
     * 不由状态决定，所以放回可运行状态就是要从头再执行一遍这一段。</p>
     *
     * <p>服务所有权凭据与父 Run 的版本状态写在同一句里核：先查租约再写，中间隔着租约到期与
     * 别人接管的时间窗，那个窗口里旧所有者不该还能改这一行。</p>
     */
    NodeWorkItemMutationResult requeueAbandonedClaim(NodeWorkItemIdentity identity,
                                                     NodeWorkItemVersions versions,
                                                     ServiceOwnershipFence fence,
                                                     SchedulerVersion schedulerVersion);

    /** 服务退出打断了恢复分段时，精确核对锚点与领取代际后重新开放领取。 */
    NodeWorkItemMutationResult requeueInterruptedToolJob(NodeWorkItemIdentity identity,
                                                         NodeWorkItemVersions versions,
                                                         String operationId);

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
     * 派发失败留痕：写下原因并把下次可见时间推后，只对可运行、可恢复两种状态生效。
     *
     * <p>这条不是「挡住的开关」：能不能再次被取到由 nextVisibleAt 决定。被拒说明这条工作项此刻
     * 不可派发（已经被领走或已进终态），调用方按拒绝处理，不要重试到把它当成成功。</p>
     */
    NodeWorkItemMutationResult deferDispatch(NodeWorkItemIdentity identity,
                                             NodeDispatchDeferReason reason,
                                             OffsetDateTime nextVisibleAt);

    /** 派发成功留痕：清掉上一次的失败原因；被拒说明这条工作项此刻不可派发。 */
    NodeWorkItemMutationResult markDispatched(NodeWorkItemIdentity identity);

    /**
     * 显式转交所有权，只给验收控制面用：调用方必须先确认原执行者已经停止，再走这一步；
     * 常规路径不靠租约到期换领取者。返回空表示期望代际不匹配或这一行已经不在可转交状态。
     */
    Optional<NodeWorkItemClaim> handOverClaim(NodeWorkItemIdentity identity,
                                              int expectedClaimEpoch,
                                              String newOwner,
                                              Duration lease);

    /** 节点线程实际退出后确认同代际终态；取消或过期本身不能证明执行线程已经停止。 */
    void acknowledgeWorkerExit(NodeWorkItemIdentity identity, int claimEpoch);

    Optional<NodeWorkItem> findByIdentity(NodeWorkItemIdentity identity);

    /**
     * 一个计划代际下每个逻辑节点的最新分段：同一个节点按尝试次数、分段序号取最大的那一行。
     *
     * <p>一次等待会把自己的分段写成已提交、同时建出下一段，所以判断「这个节点做完了没有」必须看最新分段：
     * 拿第一段去问，会得到一个「已提交但没有成功结果」的中间行。返回的每一行都带它自己的分段身份，
     * 提交结果与取消都按这一行来。</p>
     */
    List<NodeWorkItem> listLatestSegments(String runId, int planGeneration);

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

    /**
     * 按「Run 的调度器版本」取这个 Run 上的全部未完成分段，行自己的版本不参与筛选。
     *
     * <p>启动残留与遗留接管用它把一条 Run 的未完成行读全，再逐行比对版本；按行版本先筛会漏掉
     * 与 Run 版本不一致的那些行，让「全部都属于这一版」这个判断失去意义。</p>
     */
    List<NodeWorkItem> listUnfinishedByRunSchedulerVersion(SchedulerVersion schedulerVersion, int limit);

    /** 按 Run 的调度器版本统计未完成分段，与上面那条配套做「有没有读全」的核对。 */
    int countUnfinishedByRunSchedulerVersion(SchedulerVersion schedulerVersion);

    /** 某个调度器版本是否有遗留记录——有就必须禁止该版本的执行扫描、暂停该版本的新建准入。 */
    default boolean hasResidueFor(SchedulerVersion schedulerVersion) {
        return countUnfinishedBySchedulerVersion(schedulerVersion) > 0;
    }
}
