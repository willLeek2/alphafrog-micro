package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.workitem.NodeWorkItem;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 通用节点工作项表的 SQL 入口。
 *
 * <p>所有状态迁移都是「带版本条件的更新语句」，成败只看影响行数：0 就是没写进去，调用方不得先读后写绕过条件。
 * 领取语句用 {@code UPDATE ... RETURNING claim_epoch} 原子加一并返回新的领取代际；加不到行时返回 {@code null}，
 * 表示这一行已经被别人领走。</p>
 *
 * <p>状态取值见 {@link world.willfrog.agent.platform.workitem.NodeWorkItemState}；终态的库里取值由
 * {@code <sql id="terminalStates">} 一处声明，热扫描与部分索引用的是同一组。</p>
 */
@Mapper
public interface NodeWorkItemMapper {

    /**
     * 创建一条工作项。同一个身份已经有一行时返回 0（唯一约束上 {@code DO NOTHING}），
     * 不是异常：条件更新管不了「同一个身份出现两行」，这条唯一约束才是那道闸。
     */
    int insert(NodeWorkItem item);

    /** 按五个身份字段读一行。 */
    NodeWorkItem findByIdentity(@Param("runId") String runId,
                                @Param("planGeneration") int planGeneration,
                                @Param("nodeId") String nodeId,
                                @Param("nodeAttempt") int nodeAttempt,
                                @Param("segmentSequence") int segmentSequence);

    /**
     * 扫描可领取的工作项：状态可运行、已到下次可领取时间，按调度器版本过滤。
     * 内存提示队列只是唤醒提示，在线扫描用它把工作项重新发现。
     */
    List<NodeWorkItem> scanClaimable(@Param("schedulerVersion") String schedulerVersion,
                                     @Param("limit") int limit);

    /**
     * 节点派发的一次全局扫描：双池家族的到期可领取分段放在同一份候选里，一次取回，
     * 顺序按这张图最近被派发的轮次升序（从没被派发过的排最前），再按到期时间与行号。
     *
     * <p>它不按版本分池：分池时每种版本各取一份 {@code LIMIT}，谁先谁后由各自的池子决定，
     * 谈不上共用一个节点池的轮转。调用方拿到的每一行都带着自己的冻结版本。</p>
     */
    List<NodeWorkItem> scanClaimableAcrossDualPool(@Param("limit") int limit);

    /**
     * 条件领取：状态必须是可运行，三类期望版本（计划代际、上下文版本、控制版本）必须匹配。
     * 领取成功把领取代际加一并返回新值；返回 {@code null} 表示这次没领到。
     */
    Integer claim(@Param("runId") String runId,
                  @Param("planGeneration") int planGeneration,
                  @Param("nodeId") String nodeId,
                  @Param("nodeAttempt") int nodeAttempt,
                  @Param("segmentSequence") int segmentSequence,
                  @Param("schedulerVersion") String schedulerVersion,
                  @Param("contextVersion") long contextVersion,
                  @Param("runControlVersion") long runControlVersion,
                  @Param("claimedBy") String claimedBy,
                  @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    /**
     * 显式转交所有权（只给验收控制面用）：确认原执行者已经停止之后，把这一行交给新的领取者，
     * 领取代际加一，状态回到已领取。返回 {@code null} 表示期望代际不匹配或这一行已经不在可转交状态。
     */
    Integer handOverClaim(@Param("runId") String runId,
                          @Param("planGeneration") int planGeneration,
                          @Param("nodeId") String nodeId,
                          @Param("nodeAttempt") int nodeAttempt,
                          @Param("segmentSequence") int segmentSequence,
                          @Param("expectedClaimEpoch") int expectedClaimEpoch,
                          @Param("newOwner") String newOwner,
                          @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    /** 已领取 → 执行中：要求领取者与领取代际都匹配，不匹配立即停止。 */
    int startExecution(@Param("runId") String runId,
                       @Param("planGeneration") int planGeneration,
                       @Param("nodeId") String nodeId,
                       @Param("nodeAttempt") int nodeAttempt,
                       @Param("segmentSequence") int segmentSequence,
                       @Param("claimEpoch") int claimEpoch,
                       @Param("claimedBy") String claimedBy);

    /**
     * 提交分段结果：执行中 → 分段结果已提交。条件带五个身份字段与四类版本；
     * 分段结果按浅合并并入节点本地载荷（同键以本次提交为准）。
     */
    int commitSegmentResult(@Param("runId") String runId,
                            @Param("planGeneration") int planGeneration,
                            @Param("nodeId") String nodeId,
                            @Param("nodeAttempt") int nodeAttempt,
                            @Param("segmentSequence") int segmentSequence,
                            @Param("contextVersion") long contextVersion,
                            @Param("runControlVersion") long runControlVersion,
                            @Param("claimEpoch") int claimEpoch,
                            @Param("payloadPatchJson") String payloadPatchJson);

    int suspendForToolJob(@Param("runId") String runId,
                          @Param("planGeneration") int planGeneration,
                          @Param("nodeId") String nodeId,
                          @Param("nodeAttempt") int nodeAttempt,
                          @Param("segmentSequence") int segmentSequence,
                          @Param("contextVersion") long contextVersion,
                          @Param("runControlVersion") long runControlVersion,
                          @Param("claimEpoch") int claimEpoch,
                          @Param("claimedBy") String claimedBy,
                          @Param("operationId") String operationId,
                          @Param("toolCallId") String toolCallId,
                          @Param("attempt") int attempt);

    int promoteToolJobResumable(@Param("runId") String runId,
                                @Param("planGeneration") int planGeneration,
                                @Param("nodeId") String nodeId,
                                @Param("nodeAttempt") int nodeAttempt,
                                @Param("segmentSequence") int segmentSequence,
                                @Param("contextVersion") long contextVersion,
                                @Param("runControlVersion") long runControlVersion,
                                @Param("claimEpoch") int claimEpoch,
                                @Param("operationId") String operationId,
                                @Param("anchorJson") String anchorJson,
                                @Param("resumePayloadJson") String resumePayloadJson);

    int commitResumedToolJobResult(@Param("runId") String runId,
                                   @Param("planGeneration") int planGeneration,
                                   @Param("nodeId") String nodeId,
                                   @Param("nodeAttempt") int nodeAttempt,
                                   @Param("segmentSequence") int segmentSequence,
                                   @Param("contextVersion") long contextVersion,
                                   @Param("runControlVersion") long runControlVersion,
                                   @Param("claimEpoch") int claimEpoch,
                                   @Param("operationId") String operationId,
                                   @Param("payloadPatchJson") String payloadPatchJson);

    int requeueInterruptedToolJob(@Param("runId") String runId,
                                  @Param("planGeneration") int planGeneration,
                                  @Param("nodeId") String nodeId,
                                  @Param("nodeAttempt") int nodeAttempt,
                                  @Param("segmentSequence") int segmentSequence,
                                  @Param("contextVersion") long contextVersion,
                                  @Param("runControlVersion") long runControlVersion,
                                  @Param("claimEpoch") int claimEpoch,
                                  @Param("operationId") String operationId);

    /** 报执行失败：执行中 → 执行失败。只有执行基础设施自己出错走这条，工具返回的失败不算。 */
    int reportExecutionFailure(@Param("runId") String runId,
                               @Param("planGeneration") int planGeneration,
                               @Param("nodeId") String nodeId,
                               @Param("nodeAttempt") int nodeAttempt,
                               @Param("segmentSequence") int segmentSequence,
                               @Param("claimEpoch") int claimEpoch,
                               @Param("claimedBy") String claimedBy,
                               @Param("reason") String reason);

    /** 续租：只更新租约到期时间，不改状态与代际。 */
    int renewLease(@Param("runId") String runId,
                   @Param("planGeneration") int planGeneration,
                   @Param("nodeId") String nodeId,
                   @Param("nodeAttempt") int nodeAttempt,
                   @Param("segmentSequence") int segmentSequence,
                   @Param("claimEpoch") int claimEpoch,
                   @Param("claimedBy") String claimedBy,
                   @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt);

    /** 控制取消：任意非终态 → 被取消，条件带控制版本与领取代际。 */
    int cancel(@Param("runId") String runId,
               @Param("planGeneration") int planGeneration,
               @Param("nodeId") String nodeId,
               @Param("nodeAttempt") int nodeAttempt,
               @Param("segmentSequence") int segmentSequence,
               @Param("runControlVersion") long runControlVersion,
               @Param("claimEpoch") int claimEpoch,
               @Param("reason") String reason);

    /**
     * 判定版本失效：任意非终态 → 已过期，条件带状态、计划代际、上下文版本与控制版本。
     * 已经进终态的工作项不会被这条语句改动。
     */
    int markStale(@Param("runId") String runId,
                  @Param("planGeneration") int planGeneration,
                  @Param("nodeId") String nodeId,
                  @Param("nodeAttempt") int nodeAttempt,
                  @Param("segmentSequence") int segmentSequence,
                  @Param("contextVersion") long contextVersion,
                  @Param("runControlVersion") long runControlVersion,
                  @Param("reason") String reason);

    /**
     * 派发失败：写下原因并把下次可见时间推后，只对可派发的两种状态生效。
     * 影响行数为 0 表示这条工作项此刻不可派发（已被领走或已进终态），调用方不要当成成功。
     */
    int deferDispatch(@Param("runId") String runId,
                      @Param("planGeneration") int planGeneration,
                      @Param("nodeId") String nodeId,
                      @Param("nodeAttempt") int nodeAttempt,
                      @Param("segmentSequence") int segmentSequence,
                      @Param("reason") String reason,
                      @Param("nextVisibleAt") OffsetDateTime nextVisibleAt);

    /** 派发成功：清掉上一次的失败原因，条件与 {@link #deferDispatch} 对称。 */
    int markDispatched(@Param("runId") String runId,
                       @Param("planGeneration") int planGeneration,
                       @Param("nodeId") String nodeId,
                       @Param("nodeAttempt") int nodeAttempt,
                       @Param("segmentSequence") int segmentSequence);

    /**
     * 某个计划代际下每个逻辑节点的最新分段：同一个节点里尝试次数与分段序号最大的那一行。
     * 外层推进判断节点是否做完、以及拿哪一段的结果回复上游，都用这一行。
     */
    List<NodeWorkItem> listLatestSegments(@Param("runId") String runId,
                                          @Param("planGeneration") int planGeneration);

    /** 某个 Run 上还没完成的工作项（清理准入与背压读数用）。 */
    List<NodeWorkItem> listUnfinishedByRun(@Param("runId") String runId);

    /** 数据库里未完成的工作项总数：背压四个数之一。 */
    int countUnfinished();

    /** 某个 Run 上未完成的工作项数量：每个 Run 未完成工作项上限用。 */
    int countUnfinishedByRun(@Param("runId") String runId);

    /** 同一 runId 已落过的最大计划代际；没有记录时返回 -1。 */
    int maxPlanGenerationByRun(@Param("runId") String runId);

    /** 所有 Run 里未完成工作项最多的那个数量，用来和「每个 Run 的上限」对照着看。 */
    int maxUnfinishedPerRun();

    /**
     * 某个调度器版本下未完成的工作项：进程启动时用它查遗留记录。
     * 只要查到就禁止该版本的执行扫描、暂停该版本的新建准入。
     */
    List<NodeWorkItem> listUnfinishedBySchedulerVersion(@Param("schedulerVersion") String schedulerVersion,
                                                        @Param("limit") int limit);

    /** 某个调度器版本下未完成的工作项数量。 */
    int countUnfinishedBySchedulerVersion(@Param("schedulerVersion") String schedulerVersion);
}
