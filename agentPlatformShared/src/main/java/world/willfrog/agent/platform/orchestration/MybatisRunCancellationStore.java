package world.willfrog.agent.platform.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.AsyncToolTaskMapper;
import world.willfrog.agent.platform.mapper.RunCancellationMapper;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 取消一笔运行的默认事务实现。
 *
 * <p>整笔 PostgreSQL 事务里改运行主记录、等待组、节点工作项、未完结异步任务与停机意图，
 * 并写取消事件；方法返回即已提交。控制层拿到结果后再做提交后动作。</p>
 */
@Service
@RequiredArgsConstructor
public class MybatisRunCancellationStore implements RunCancellationStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("COMPLETED", "PARTIAL", "FAILED", "CANCELED", "EXPIRED");

    private final RunCancellationMapper mapper;
    private final AsyncToolTaskMapper asyncToolTaskMapper;
    private final WaitGroupStore waitGroupStore;

    @Override
    @Transactional
    public CancelRunOutcome cancelRunInTransaction(String runId, String userId,
                                                   String snapshotJson,
                                                   OffsetDateTime interruptedExpiresAt) {
        required(runId, "运行编号");
        required(userId, "用户编号");
        CancelableRun run = mapper.lockRunForUser(runId, userId);
        if (run == null) {
            // 与现码 RunOwnershipGateway.requireOwnedRunForUser 的归属失败保持同一异常与文案。
            throw new IllegalStateException("原测试部署已停用");
        }
        if (run.getStatus() == null || TERMINAL_STATUSES.contains(run.getStatus())) {
            return CancelRunOutcome.NOT_APPLIED;
        }
        boolean hasUnfinishedTask = mapper.hasUnfinishedTask(runId);
        boolean activeAnchor = Boolean.TRUE.equals(run.getActiveAnchor());
        if (activeAnchor || hasUnfinishedTask) {
            if (mapper.snapshotIfStatus(runId, userId, run.getStatus(), snapshotJson) != 1) {
                return CancelRunOutcome.STATE_CHANGED;
            }
            cancelChildren(runId);
            appendCanceledEvent(runId);
            return CancelRunOutcome.CANCEL_INTENT_RECORDED;
        }
        if (mapper.cancelTerminalNoActiveTask(runId, userId, snapshotJson,
                interruptedExpiresAt) != 1) {
            return CancelRunOutcome.NOT_APPLIED;
        }
        cancelChildren(runId);
        appendCanceledEvent(runId);
        return CancelRunOutcome.TERMINAL_WRITTEN;
    }

    /** 等待组、异步任务、工作项与恢复通知在同一事务里一起收口。 */
    private void cancelChildren(String runId) {
        long afterGroupId = 0;
        while (true) {
            List<WaitGroup> groups = waitGroupStore.listOpenGroupsByRun(runId, afterGroupId, 100);
            if (groups.isEmpty()) {
                break;
            }
            for (WaitGroup group : groups) {
                // 旧等待成员与停机记录的取消仍走原有语句；新表任务由下面这一条统一收口。
                waitGroupStore.cancelChain(group.getId());
                afterGroupId = group.getId();
            }
        }
        asyncToolTaskMapper.requestStopByRun(runId, OffsetDateTime.now());
        mapper.cancelWaitGroups(runId);
        mapper.cancelRecoveryNotifications(runId);
        mapper.cancelWorkItems(runId);
    }

    private void appendCanceledEvent(String runId) {
        try {
            String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                    "run_id", runId,
                    "engine", "agentLangchainService"));
            mapper.appendCanceledEvent(runId, payload);
        } catch (Exception e) {
            throw new IllegalStateException("取消事件序列化失败：run=" + runId, e);
        }
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }
}
