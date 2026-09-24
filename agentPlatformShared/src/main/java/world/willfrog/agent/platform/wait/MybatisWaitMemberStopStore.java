package world.willfrog.agent.platform.wait;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.WaitMemberStopMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MybatisWaitMemberStopStore implements WaitMemberStopStore {
    private final WaitMemberStopMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WaitMemberStopTask> claimDue(String owner, String claimToken,
                                                  OffsetDateTime now, OffsetDateTime leaseUntil) {
        required(owner, "领取者");
        required(claimToken, "领取令牌");
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("停机任务租期必须晚于领取时刻");
        }
        Long id = mapper.claimDue(owner, claimToken, now, leaseUntil);
        if (id == null) return Optional.empty();
        WaitMemberStopTask task = mapper.findById(id);
        if (task == null) throw new IllegalStateException("已领取的停机任务不存在");
        return Optional.of(task);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean retry(long stopId, String claimToken, OffsetDateTime nextAttemptAt, String reason) {
        positive(stopId);
        required(claimToken, "领取令牌");
        required(reason, "重试原因");
        if (reason.length() > 512) throw new IllegalArgumentException("重试原因过长");
        if (nextAttemptAt == null || !nextAttemptAt.isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("下次尝试时刻必须晚于现在");
        }
        return mapper.retry(stopId, claimToken, nextAttemptAt, reason) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean confirmSandboxTerminal(long stopId, String claimToken,
                                          String taskId, String terminalStatus) {
        positive(stopId);
        required(claimToken, "领取令牌");
        required(taskId, "Sandbox 任务编号");
        if (!"SUCCEEDED".equals(terminalStatus) && !"FAILED".equals(terminalStatus)
                && !"CANCELED".equals(terminalStatus)) {
            throw new IllegalArgumentException("Sandbox 任务状态不是终态");
        }
        return mapper.confirmSandboxTerminal(stopId, claimToken, taskId, terminalStatus) == 1;
    }

    @Override
    public Optional<WaitMemberStopTask> findByWaitMemberId(long waitMemberId) {
        positive(waitMemberId);
        return Optional.ofNullable(mapper.findByWaitMemberId(waitMemberId));
    }

    @Override
    public List<WaitMemberStopTask> listUnconfirmedByRun(String runId, long afterStopId, int limit) {
        required(runId, "Run 编号");
        if (afterStopId < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("停机任务分页参数无效");
        }
        return mapper.listUnconfirmedByRun(runId, afterStopId, limit);
    }

    private static void positive(long id) {
        if (id <= 0) throw new IllegalArgumentException("停机任务编号必须为正数");
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
    }
}
