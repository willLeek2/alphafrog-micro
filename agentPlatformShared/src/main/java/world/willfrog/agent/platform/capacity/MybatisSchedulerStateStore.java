package world.willfrog.agent.platform.capacity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.mapper.SchedulerStateMapper;

/**
 * {@link SchedulerStateStore} 的 MyBatis 实现。
 *
 * <p>判定走 {@link SchedulerPausePolicy}，输入里的暂停标记来自数据库，不是内存推断；写入后把库里的
 * 实际状态读回来返回，调用方据此上报指标与事件。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MybatisSchedulerStateStore implements SchedulerStateStore {

    /** 全局容量状态固定一条记录，作用域就是它。 */
    public static final String GLOBAL_SCOPE = "GLOBAL";

    private final SchedulerStateMapper mapper;

    @Override
    public SchedulerCapacityState loadCapacity() {
        return mapper.loadCapacityState(GLOBAL_SCOPE);
    }

    @Override
    public SchedulerPauseDecision decideAndRecord(long unfinishedCount,
                                                  int highWatermark,
                                                  int lowWatermark) {
        SchedulerCapacityState current = mapper.loadCapacityState(GLOBAL_SCOPE);
        if (current == null) {
            throw new IllegalStateException(
                    "全局容量状态记录不存在：迁移里应当已经写入 GLOBAL 这一行，缺了说明库没建好");
        }
        boolean currentlyPaused = current.paused();
        SchedulerPauseDecision decision = SchedulerPausePolicy.decide(
                currentlyPaused, unfinishedCount, highWatermark, lowWatermark);
        SchedulerCapacityState stored = mapper.applyCapacityDecision(
                GLOBAL_SCOPE, unfinishedCount, decision.paused(), highWatermark, lowWatermark);
        if (stored == null) {
            throw new IllegalStateException("容量状态写入没有返回结果行");
        }
        if (stored.paused() != decision.paused()) {
            // 库里的值才是权威：写进去的和算出来的不一致说明数据库那条记录被别的东西改了。
            throw new IllegalStateException("容量状态写入后的暂停标记与判定结果不一致：判定 "
                    + decision.paused() + "，库里 " + stored.paused());
        }
        log.debug("全局新增暂停判定：{}", decision.describe());
        return decision;
    }

    @Override
    public long advanceRound(SchedulerRoundScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("轮转作用域不能为空");
        }
        Long advanced = mapper.advanceRound(scope.name());
        if (advanced == null) {
            throw new IllegalStateException(
                    "轮次记录不存在：" + scope + "；迁移里应当已经写入这个作用域，缺了说明库没建好");
        }
        return advanced;
    }

    @Override
    public long currentRound(SchedulerRoundScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("轮转作用域不能为空");
        }
        Long current = mapper.currentRound(scope.name());
        return current == null ? 0L : current;
    }
}
