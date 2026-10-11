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

    /**
     * 读当前的全局容量状态，并把这一行锁到调用方的事务结束。
     *
     * <p>容量判定是「读—判定—写」三步：只读不锁时，两个线程会各自拿着同一份旧标记判定，
     * 后写的那个可以把前一个刚做出的暂停或恢复撤销掉。判定入口必须在同一事务里先调用它。</p>
     */
    SchedulerCapacityState lockCapacityState(@Param("scopeKey") String scopeKey);

    /** 写入一次判定结果并返回写库后的状态；只允许在锁行之后、同一事务里调用。 */
    SchedulerCapacityState applyCapacityDecision(@Param("scopeKey") String scopeKey,
                                                 @Param("unfinishedCount") long unfinishedCount,
                                                 @Param("paused") boolean paused,
                                                 @Param("highWatermark") int highWatermark,
                                                 @Param("lowWatermark") int lowWatermark);

    /** 轮次加一并返回新的轮次号。 */
    Long advanceRound(@Param("scopeKey") String scopeKey);

    /** 读当前轮次号；没有种子行时返回 {@code null}，由调用方失败关闭。 */
    Long currentRound(@Param("scopeKey") String scopeKey);
}
