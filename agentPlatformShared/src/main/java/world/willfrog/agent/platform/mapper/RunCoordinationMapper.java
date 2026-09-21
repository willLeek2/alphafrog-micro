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
 * 轮次号变大往后排，于是每一轮都会先照顾等得最久的图。</p>
 *
 * <p>轮转位置有协调与派发两组，各有自己的写入与刷新语句：两个轮次空间互不覆盖，谁服务过什么由
 * 各自的字段说话。</p>
 */
@Mapper
public interface RunCoordinationMapper {

    /** 创建资格记录；已经有了就什么都不做，返回 0。 */
    int ensure(@Param("runId") String runId,
               @Param("schedulerVersion") String schedulerVersion,
               @Param("planGeneration") int planGeneration);

    int deferFor(@Param("runId") String runId,
                 @Param("deferReason") String deferReason,
                 @Param("nextVisibleAt") OffsetDateTime nextVisibleAt);

    /** Run 协调这一轮服务了这个 Run：清延期原因、记协调轮次、协调未获轮数归零。 */
    int markCoordinationServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber);

    /** 节点派发这一轮服务了这个 Run：只记派发轮次与派发未获轮数，不动延期原因。 */
    int markDispatchServed(@Param("runId") String runId, @Param("roundNumber") long roundNumber);

    int updatePlanGeneration(@Param("runId") String runId, @Param("planGeneration") int planGeneration);

    List<RunCoordination> scanDue(@Param("schedulerVersion") String schedulerVersion,
                                  @Param("limit") int limit);

    int refreshCoordinationMissedRounds(@Param("schedulerVersion") String schedulerVersion,
                                        @Param("roundNumber") long roundNumber);

    int refreshDispatchMissedRounds(@Param("schedulerVersion") String schedulerVersion,
                                    @Param("roundNumber") long roundNumber);

    RunCoordination find(@Param("runId") String runId);
}
