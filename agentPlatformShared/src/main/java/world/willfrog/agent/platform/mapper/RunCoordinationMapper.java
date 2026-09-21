package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.coordination.RunCoordination;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Run 协调资格表的 SQL 入口。
 *
 * <p>取候选 Run 的语句把「最近协调轮次」放在排序第一位：从没被服务过的是 0，排最前面；服务过的 Run
 * 轮次号变大往后排，于是每一轮都会先照顾等得最久的图。扫描是全局的：旧版本、V1、V2 排在同一份候选里，
 * 每条记录自己带着冻结版本，由调用方按版本路由；分池会变成每种版本各取一份批次，谈不上共用轮转。</p>
 *
 * <p>写入分两类。版本与计划代际一律从 Run 主表派生，调用方说了不算；「推进」与「延期」都带期望值做
 * 条件更新，并且只前进不后退——迟到的旧线程、旧轮次、旧计划代际必须影响 0 行，而不是把新事实改回旧事实。</p>
 *
 * <p>轮转位置有协调与派发两组，各有自己的写入与刷新语句：两个轮次空间互不覆盖，谁服务过什么由
 * 各自的字段说话。</p>
 */
@Mapper
public interface RunCoordinationMapper {

    /**
     * 创建资格记录；已经有了就什么都不做，返回 0。
     *
     * <p>版本与计划代际取自 Run 主表：滚动部署期间同一条 Run 的冻结版本只有一个出处。</p>
     */
    int ensure(@Param("runId") String runId);

    /**
     * 记一次协调延期，必须带这一轮开始时读到的计划代际与协调轮次。
     *
     * <p>期望值对不上说明这张图已经被服务过或换了计划代际，这次延期是旧观察，写进去只会把
     * 新事实拉回旧事实，所以影响 0 行。</p>
     */
    int deferFor(@Param("runId") String runId,
                 @Param("deferReason") String deferReason,
                 @Param("nextVisibleAt") OffsetDateTime nextVisibleAt,
                 @Param("expectedPlanGeneration") int expectedPlanGeneration,
                 @Param("expectedServedRound") long expectedServedRound);

    /** Run 协调这一轮服务了这个 Run：清延期原因、记协调轮次、协调未获轮数归零；轮次只许前进。 */
    int markCoordinationServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber);

    /** 节点派发这一轮服务了这个 Run：只记派发轮次与派发未获轮数，不动延期原因；同样只许前进。 */
    int markDispatchServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber);

    /**
     * 把资格记录上的计划代际同步成 Run 主表的当前值。
     *
     * <p>调用方声明的这一代必须就是 Run 上的当前一代，且必须比记录上的新：拿着旧读到的代际
     * 写描述、或者让代际倒退，都要影响 0 行。</p>
     */
    int syncPlanGeneration(@Param("runId") String runId, @Param("planGeneration") int planGeneration);

    /** 这一轮可以被协调的 Run：全局一份候选，按最近协调轮次升序，已结束与取消中的 Run 不参与。 */
    List<RunCoordination> scanDue(@Param("limit") int limit);

    /**
     * 一轮结束时刷新连续未获协调的轮数：这一轮确实站着排队却没轮到的各加一轮。
     *
     * <p>按轮递增，不按全局轮次差补算：延期等待的那段时间它没在竞争，不该被算成没被服务。</p>
     */
    int refreshCoordinationMissedRounds(@Param("roundNumber") long roundNumber);

    /** 派发那一组的刷新：口径相同（每轮加一）、字段不同；只统计此刻确实有到期可领取节点的 Run。 */
    int refreshDispatchMissedRounds(@Param("roundNumber") long roundNumber);

    /** 读一条资格记录；没有就返回空。 */
    RunCoordination find(@Param("runId") String runId);
}
