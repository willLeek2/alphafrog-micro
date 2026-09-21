package world.willfrog.agent.platform.coordination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.mapper.RunCoordinationMapper;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link RunCoordinationStore} 的 MyBatis 实现。
 *
 * <p>写入都是「影响行数就是事实」：没有资格记录时延期写入影响 0 行，返回 false，调用方按「这个 Run
 * 还没有资格记录」处理，不在这里替它补一行。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MybatisRunCoordinationStore implements RunCoordinationStore {

    private final RunCoordinationMapper mapper;

    @Override
    public boolean ensure(String runId, SchedulerVersion schedulerVersion, int planGeneration) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("协调资格必须挂在某个 Run 上");
        }
        if (schedulerVersion == null) {
            throw new IllegalArgumentException("协调资格必须带调度器版本");
        }
        if (planGeneration < -1) {
            throw new IllegalArgumentException("计划代际不能小于 -1：" + planGeneration);
        }
        return mapper.ensure(runId, schedulerVersion.name(), planGeneration) > 0;
    }

    @Override
    public boolean deferFor(String runId, RunCoordinationDeferReason reason, OffsetDateTime nextVisibleAt) {
        if (reason == null) {
            throw new IllegalArgumentException("延期必须给出原因，否则观测里看不出为什么没推进");
        }
        if (nextVisibleAt == null) {
            throw new IllegalArgumentException("延期必须给出下次可见时间");
        }
        return mapper.deferFor(runId, reason.name(), nextVisibleAt) > 0;
    }

    @Override
    public boolean markCoordinationServed(String runId, long roundNumber) {
        requireRoundNumber(roundNumber);
        return mapper.markCoordinationServed(runId, roundNumber) > 0;
    }

    @Override
    public boolean markDispatchServed(String runId, long roundNumber) {
        requireRoundNumber(roundNumber);
        return mapper.markDispatchServed(runId, roundNumber) > 0;
    }

    @Override
    public boolean updatePlanGeneration(String runId, int planGeneration) {
        if (planGeneration < -1) {
            throw new IllegalArgumentException("计划代际不能小于 -1：" + planGeneration);
        }
        return mapper.updatePlanGeneration(runId, planGeneration) > 0;
    }

    @Override
    public List<RunCoordination> scanDue(SchedulerVersion schedulerVersion, int limit) {
        if (schedulerVersion == null) {
            throw new IllegalArgumentException("扫描候选 Run 必须带调度器版本");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("扫描条数必须为正数：" + limit);
        }
        return mapper.scanDue(schedulerVersion.name(), limit);
    }

    @Override
    public int refreshCoordinationMissedRounds(SchedulerVersion schedulerVersion, long roundNumber) {
        requireRefresh(schedulerVersion, roundNumber);
        return mapper.refreshCoordinationMissedRounds(schedulerVersion.name(), roundNumber);
    }

    @Override
    public int refreshDispatchMissedRounds(SchedulerVersion schedulerVersion, long roundNumber) {
        requireRefresh(schedulerVersion, roundNumber);
        return mapper.refreshDispatchMissedRounds(schedulerVersion.name(), roundNumber);
    }

    @Override
    public Optional<RunCoordination> find(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("协调资格必须挂在某个 Run 上");
        }
        return Optional.ofNullable(mapper.find(runId));
    }

    private static void requireRoundNumber(long roundNumber) {
        if (roundNumber < 0) {
            throw new IllegalArgumentException("轮次号不能是负数：" + roundNumber);
        }
    }

    private static void requireRefresh(SchedulerVersion schedulerVersion, long roundNumber) {
        if (schedulerVersion == null) {
            throw new IllegalArgumentException("刷新轮数必须带调度器版本");
        }
        requireRoundNumber(roundNumber);
    }
}
