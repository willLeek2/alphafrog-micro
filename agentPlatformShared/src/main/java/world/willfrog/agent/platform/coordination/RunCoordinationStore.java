package world.willfrog.agent.platform.coordination;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Run 协调资格的存储接口：延期原因、下次可见时间与两个轮次空间的轮转位置。
 *
 * <p>它不解释编排语义，只回答三件事：哪些 Run 现在可以被协调、谁最近被服务过、这个 Run 为什么被推迟。</p>
 */
public interface RunCoordinationStore {

    /**
     * 确保这个 Run 有协调资格记录；已经有了就什么都不做。
     *
     * <p>记录里的调度器版本与计划代际由存储层从 Run 主表派生，调用方不传：滚动部署期间同一条 Run
     * 的冻结版本只有一个出处，让调用方说话就会出现主记录与子记录各说一套。</p>
     */
    boolean ensure(String runId);

    /**
     * 记一次 Run 协调延期：写原因与下次可见时间。返回 false 表示这个 Run 没有资格记录，
     * 或者这次延期是旧观察（期望的计划代际或协调轮次已经对不上）。
     *
     * @param expectedPlanGeneration  这一轮开始时读到的计划代际
     * @param expectedCoordinationRound 这一轮开始时读到的最近协调轮次
     */
    boolean deferFor(String runId, RunCoordinationDeferReason reason, OffsetDateTime nextVisibleAt,
                     int expectedPlanGeneration, long expectedCoordinationRound);

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

    /**
     * 把计划代际同步到资格记录上，让提醒去重键与 Run 主记录保持一致。
     *
     * <p>只在前移时生效：调用方声明的这一代必须是 Run 主表上的当前一代，且必须比记录里的新；
     * 拿着旧代际来写、或者想让代际倒退，都返回 false 且不写。</p>
     */
    boolean syncPlanGeneration(String runId, int planGeneration);

    /**
     * 取这一轮可以被协调的 Run：全局一份候选，不按调度器版本分池——旧版本、V1、V2 一起排序竞争，
     * 每条记录带着自己的冻结版本，由调用方按版本路由。顺序是最近协调轮次升序（从没被服务过的排最前），
     * 再按最早可见时间，最后按 Run 身份，保证顺序稳定、没有图会连续错过两个完整轮次。
     */
    List<RunCoordination> scanDue(int limit);

    /**
     * 一轮结束时刷新连续未获协调的轮数，给公平性观测用：这一轮有资格却没被服务的各加一轮。
     *
     * <p>按轮递增而不是按轮次差补算，否则延期等待一段时间后重新可见，会把没竞争的轮数一次算进去。</p>
     */
    int refreshCoordinationMissedRounds(long roundNumber);

    /** 一轮结束时刷新连续未获派发的轮数，口径与协调那一组相同（每轮加一）、空间不同。 */
    int refreshDispatchMissedRounds(long roundNumber);

    Optional<RunCoordination> find(String runId);
}
