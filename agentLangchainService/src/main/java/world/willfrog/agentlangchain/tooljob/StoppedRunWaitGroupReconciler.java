package world.willfrog.agentlangchain.tooljob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;

import java.util.List;

/**
 * 取消请求可能在 Run 状态落库后、等待链停机任务入库前失败或遇到进程重启。
 * 迟到结果也可能把组关成 CANCELED；旧路径若没给已派发的 Python 兄弟成员建停机任务，
 * 需要从成员的持久派发证明补齐。两类扫描各自分页、各自回绕；真正的 Sandbox 停机
 * 由持久停机任务 worker 完成。
 */
@Component
@Slf4j
public class StoppedRunWaitGroupReconciler {
    private final WaitGroupStore groups;
    private final int batchSize;
    private long afterGroupId;
    private long afterCanceledGroupId;

    public StoppedRunWaitGroupReconciler(
            WaitGroupStore groups,
            @Value("${agent.langchain.wait-member.stop.reconcile-batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("已停止 Run 等待组补扫批量无效");
        }
        this.groups = groups;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agent.langchain.wait-member.stop.reconcile-interval-ms:1000}")
    public synchronized void poll() {
        try {
            runBatch();
        } catch (RuntimeException e) {
            log.error("已停止 Run 等待组补扫失败，下轮重试", e);
        }
    }

    /** 两类待补事实各处理至多一批；游标走到末尾后从头扫描，失败的组不会永久被跳过。 */
    public synchronized int runBatch() {
        List<WaitGroup> candidates = groups.scanOpenGroupsWithStoppedRun(afterGroupId, batchSize);
        int processed = 0;
        for (WaitGroup group : candidates) {
            if (group == null || group.getId() == null || group.getId() <= afterGroupId) {
                throw new IllegalStateException("已停止 Run 等待组补扫返回无效编号");
            }
            afterGroupId = group.getId();
            try {
                // cancelChain 在一个数据库事务里取消成员、下一分段，并写持久 Sandbox 停机任务。
                groups.cancelChain(group.getId());
                processed++;
            } catch (RuntimeException e) {
                log.warn("已停止 Run 等待链暂未收口，下轮继续补扫：run={} group={}",
                        group.getRunId(), group.getId(), e);
            }
        }
        if (candidates.size() < batchSize) {
            afterGroupId = 0;
        }
        List<WaitGroup> canceled = groups.scanCanceledGroupsMissingStopTasks(afterCanceledGroupId, batchSize);
        for (WaitGroup group : canceled) {
            if (group == null || group.getId() == null || group.getId() <= afterCanceledGroupId) {
                throw new IllegalStateException("已取消等待组停机补扫返回无效编号");
            }
            afterCanceledGroupId = group.getId();
            try {
                groups.ensureCanceledMemberStopTasks(group.getId());
                processed++;
            } catch (RuntimeException e) {
                log.warn("已取消等待组暂未补齐外部停机任务，下轮继续补扫：run={} group={}",
                        group.getRunId(), group.getId(), e);
            }
        }
        if (canceled.size() < batchSize) {
            afterCanceledGroupId = 0;
        }
        return processed;
    }
}
