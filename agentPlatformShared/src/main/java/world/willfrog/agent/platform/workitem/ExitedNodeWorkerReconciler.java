package world.willfrog.agent.platform.workitem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.mapper.NodeWorkItemMapper;

/** 进程崩溃丢失 finally 回执时，按持久领取身份和同容器重启事实补归还节点额度。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExitedNodeWorkerReconciler {
    private final NodeWorkItemMapper mapper;
    private final MybatisNodeWorkItemStore store;
    private long scanCursor;

    @Scheduled(initialDelayString = "${agent.langchain.dual-pool.node-exit-reconcile-initial-ms:10000}",
            fixedDelayString = "${agent.langchain.dual-pool.node-exit-reconcile-interval-ms:30000}")
    public void reconcile() {
        var candidates = mapper.scanUnreleasedExitedCandidates(scanCursor, 100);
        if (candidates.isEmpty()) {
            scanCursor = 0;
            return;
        }
        for (NodeWorkItem item : candidates) {
            scanCursor = item.getId();
            try {
                if (store.reconcileExitedWorker(item.identity(), item.getClaimEpoch())) {
                    log.info("已凭旧进程退出事实补归还节点额度：{} 代际 {}",
                            item.identity().describe(), item.getClaimEpoch());
                }
            } catch (RuntimeException e) {
                log.error("节点退出额度对账失败：{}", item.identity().describe(), e);
            }
        }
    }
}
