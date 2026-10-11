package world.willfrog.agent.platform.capacity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 全局新增暂停状态：一条「是否暂停新增」的持久事实，加上最近一次判定用的数量与水位。
 *
 * <p>暂停标记是权威：进程重启后读到「暂停」，就不能因为当前数量低于高水位而提前恢复。
 * 水位值是最近一次判定所用配置的留痕，判定口径以配置为准。</p>
 */
@Data
public class SchedulerCapacityState {

    private String scopeKey;
    private Integer unfinishedCount;
    private Boolean addPaused;
    private OffsetDateTime pausedSince;
    private Integer highWatermark;
    private Integer lowWatermark;
    private OffsetDateTime updatedAt;

    public boolean paused() {
        return Boolean.TRUE.equals(addPaused);
    }
}
