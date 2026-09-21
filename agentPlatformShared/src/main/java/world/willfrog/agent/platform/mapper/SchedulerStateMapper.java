package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import world.willfrog.agent.platform.capacity.SchedulerCapacityState;

/**
 * 调度器全局状态的 SQL 入口：全局新增暂停状态与跨图轮转轮次。
 *
 * <p>这两张表都只有一条记录（作用域是主键），写入都是条件之外的单条更新，读到的值就是权威。</p>
 */
@Mapper
public interface SchedulerStateMapper {

    SchedulerCapacityState loadCapacityState(@Param("scopeKey") String scopeKey);

    /** 写入一次判定结果并返回写库后的状态。 */
    SchedulerCapacityState applyCapacityDecision(@Param("scopeKey") String scopeKey,
                                                 @Param("unfinishedCount") long unfinishedCount,
                                                 @Param("paused") boolean paused,
                                                 @Param("highWatermark") int highWatermark,
                                                 @Param("lowWatermark") int lowWatermark);

    /** 轮次加一并返回新的轮次号。 */
    Long advanceRound(@Param("scopeKey") String scopeKey);

    Long currentRound(@Param("scopeKey") String scopeKey);
}
