package world.willfrog.agent.platform.coordination;

import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Run 协调资格的存储接口：延期原因、下次可见时间与两个轮次空间的轮转位置。
 *
 * <p>它不解释编排语义，只回答三件事：哪些 Run 现在可以被协调、谁最近被服务过、这个 Run 为什么被推迟。</p>
 */
public interface RunCoordinationStore {

    /** 确保这个 Run 有协调资格记录；已经有了就什么都不做。 */
    boolean ensure(String runId, SchedulerVersion schedulerVersion, int planGeneration);

    /** 记一次 Run 协调延期：写原因与下次可见时间。返回 false 表示这个 Run 没有资格记录。 */
    boolean deferFor(String runId, RunCoordinationDeferReason reason, OffsetDateTime nextVisibleAt);

    /**
     * 记一次 Run 协调成功推进：清掉延期原因、把最近协调轮次改成这一轮、连续未获协调轮数归零。
     *
     * <p>它不碰节点派发的轮转位置：那两个数属于另一个轮次空间，各记各的。</p>
     */
    boolean markCoordinationServed(String runId, long roundNumber);

    /**
     * 记一次节点派发成功推进：把最近派发轮次改成这一轮、连续未获派发轮数归零。
     *
     * <p>它不改延期原因：延期原因是 Run 协调这一层的事实，节点派发失败的原因记在工作项自己身上。
     * 读取侧的派发扫描随派发器一起落地，这里先把写入位置备好，免得两个轮次空间共用一个字段。</p>
     */
    boolean markDispatchServed(String runId, long roundNumber);

    /** 把计划代际同步到资格记录上，让提醒去重键与 Run 主记录保持一致。 */
    boolean updatePlanGeneration(String runId, int planGeneration);

    /**
     * 取这一轮可以被协调的 Run：先按最近协调轮次升序（从没被服务过的排最前），再按最早可见时间，
     * 最后按 Run 身份，保证顺序稳定、没有图会连续错过两个完整轮次。
     */
    List<RunCoordination> scanDue(SchedulerVersion schedulerVersion, int limit);

    /** 一轮结束时刷新连续未获协调的轮数，给公平性观测用。 */
    int refreshCoordinationMissedRounds(SchedulerVersion schedulerVersion, long roundNumber);

    /** 一轮结束时刷新连续未获派发的轮数，口径与协调那一组相同、空间不同。 */
    int refreshDispatchMissedRounds(SchedulerVersion schedulerVersion, long roundNumber);

    Optional<RunCoordination> find(String runId);
}
