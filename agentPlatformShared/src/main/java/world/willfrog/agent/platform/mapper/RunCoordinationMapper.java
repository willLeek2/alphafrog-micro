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
 * 轮次号变大往后排，于是每一轮都会先照顾等得最久的图。候选集合是双池家族那一份：两个双池版本排在同一
 * 份候选里，每条记录自己带着冻结版本，由调用方按版本路由；分池会变成每种版本各取一份批次，谈不上共用
 * 轮转。旧版本的 Run 由旧引擎服务，不进这份候选——进来了也永远没人接走，只会白占扫描条数。</p>
 *
 * <p>写入分两类。版本与计划代际一律从 Run 主表派生，调用方说了不算；「推进」与「延期」都带期望值做
 * 条件更新，并且只前进不后退——迟到的旧线程、旧轮次、旧计划代际必须影响 0 行，而不是把新事实改回旧事实。
 * 期望值里既有资格记录上的计划代际，也有父 Run 上的当前代际：记录可能还没来得及同步，只比记录会让
 * 升代之后的旧回合照样写成功。</p>
 *
 * <p>轮转位置有协调与派发两组，各有自己的写入与刷新语句：两个轮次空间互不覆盖，谁服务过什么由
 * 各自的字段说话。</p>
 */
@Mapper
public interface RunCoordinationMapper {

    /**
     * 创建资格记录；已经有了就什么都不做，返回 0。
     *
     * <p>版本与计划代际取自 Run 主表：滚动部署期间同一条 Run 的冻结版本只有一个出处。
     * 只有双池家族的 Run 建得出来：这张表是双池执行层的入口，旧版本的 Run 走旧引擎。</p>
     */
    int ensure(@Param("runId") String runId);

    /**
     * 记一次协调延期，必须带这一轮开始时读到的计划代际与协调轮次，以及此刻的服务所有权凭据。
     *
     * <p>期望值对不上说明这张图已经被服务过或换了计划代际，这次延期是旧观察，写进去只会把
     * 新事实拉回旧事实，所以影响 0 行。计划代际既比资格记录上的，也比父 Run 上的当前值。</p>
     */
    int deferFor(@Param("runId") String runId,
                 @Param("deferReason") String deferReason,
                 @Param("nextVisibleAt") OffsetDateTime nextVisibleAt,
                 @Param("expectedPlanGeneration") int expectedPlanGeneration,
                 @Param("expectedServedRound") long expectedServedRound,
                 @Param("ownerInstanceId") String ownerInstanceId,
                 @Param("fencingToken") long fencingToken);

    /**
     * Run 协调这一轮服务了这个 Run：清延期原因、记协调轮次、协调未获轮数归零；轮次只许前进。
     *
     * <p>计划代际要带这一回合开始时读到的那一代，父 Run 或资格记录任一已经变了就影响 0 行：
     * 计划推进之后的旧回合不许清延期原因、不许改轮转位置。</p>
     */
    int markCoordinationServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber,
                               @Param("expectedPlanGeneration") int expectedPlanGeneration,
                               @Param("ownerInstanceId") String ownerInstanceId,
                               @Param("fencingToken") long fencingToken);

    /**
     * 节点派发这一轮服务了这个 Run：只记派发轮次与派发未获轮数，不动延期原因；同样只许前进。
     *
     * <p>计划代际的条件与协调那一组相同：换了计划之后，旧回合的领取不该再改写轮转位置。</p>
     */
    int markDispatchServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber,
                           @Param("expectedPlanGeneration") int expectedPlanGeneration,
                           @Param("ownerInstanceId") String ownerInstanceId,
                           @Param("fencingToken") long fencingToken);

    /**
     * 把资格记录上的计划代际同步成 Run 主表的当前值。
     *
     * <p>调用方声明的这一代必须就是 Run 上的当前一代，且必须比记录上的新：拿着旧读到的代际
     * 写描述、或者让代际倒退，都要影响 0 行。</p>
     */
    int syncPlanGeneration(@Param("runId") String runId, @Param("planGeneration") int planGeneration,
                           @Param("ownerInstanceId") String ownerInstanceId,
                           @Param("fencingToken") long fencingToken);

    /**
     * 旧版本 Run 交给旧入口这一步的记账：与上面两条同形，但不带服务所有权条件。
     *
     * <p>授权来源不同：这一笔由「本进程刚决定把这条旧 Run 交给旧入口」这个动作授权，
     * 双池这一层对它从来没有所有权，所以没有 token 可核。</p>
     */
    int markHandoffServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber,
                          @Param("expectedPlanGeneration") int expectedPlanGeneration);

    /** 旧版本候选按所有权原因推后一步的记账：同上，不带服务所有权条件。 */
    int deferHandoff(@Param("runId") String runId,
                     @Param("deferReason") String deferReason,
                     @Param("nextVisibleAt") OffsetDateTime nextVisibleAt,
                     @Param("expectedPlanGeneration") int expectedPlanGeneration,
                     @Param("expectedServedRound") long expectedServedRound);

    /**
     * 这一轮可以被协调的 Run：双池家族一份候选，按最近协调轮次升序，已结束与取消中的 Run 不参与。
     *
     * <p>候选里只有双池家族：旧版本的 Run 归旧引擎，混进来只会占满扫描条数。</p>
     */
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
