package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * workspace dump 异步调度器。
 *
 * <p>在 {@code workspaceDumpExecutor} 线程池上异步执行 dump；失败时按指数/线性重试 3 次，
 * 仍失败则入内存 DLQ（容量 1000，FIFO 淘汰），避免静默丢失任务。
 *
 * <h3>协作关系</h3>
 * <ul>
 *   <li>调用方：{@link WorkspaceFinalizedEventListener}（同包，发布事件后回调）</li>
 *   <li>被调方：{@code WorkspaceDumpService}（其它 agent 并行编写，本类只持有引用不构造循环）</li>
 * </ul>
 *
 * @author wang
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceDumpScheduler {

    /** 最大重试次数（含首次执行），即 1 次成功或 N-1 次重试后入 DLQ */
    private static final int MAX_ATTEMPTS = 3;

    /** 内存 DLQ 容量上限 */
    private static final int DLQ_CAPACITY = 1000;

    private final WorkspaceDumpService dumpService;

    /** 失败任务内存 DLQ：FIFO 淘汰，仅做 v0 兜底；v0.1 接入持久化告警通道。 */
    private final Deque<String> dlq = new ArrayDeque<>();

    /**
     * 异步触发 run workspace dump。
     *
     * <p>由 {@code workspaceDumpExecutor} 线程执行；内部捕获所有异常，3 次重试后入 DLQ 并 warn。
     *
     * @param runId       run 主键
     * @param conservative true = EXPIRED 保守分支（缺消息/event 时只写状态 + 有限 meta）
     */
    @Async("workspaceDumpExecutor")
    public void enqueueDumpAsync(String runId, boolean conservative) {
        if (runId == null || runId.isBlank()) {
            log.warn("enqueueDumpAsync skipped: runId 为空");
            return;
        }
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("Workspace dump attempt {}/{}: runId={} conservative={}",
                        attempt, MAX_ATTEMPTS, runId, conservative);
                dumpService.dumpRun(runId, conservative);
                if (attempt > 1) {
                    log.info("Workspace dump succeeded on attempt {}/{}: runId={}",
                            attempt, MAX_ATTEMPTS, runId);
                }
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("Workspace dump attempt {}/{} failed: runId={} err={}",
                        attempt, MAX_ATTEMPTS, runId, e.getMessage());
            }
        }
        pushDlq(runId, conservative, lastError);
    }

    /**
     * 任务入内存 DLQ：超出容量时淘汰队首，warn 告警。
     */
    private void pushDlq(String runId, boolean conservative, Throwable lastError) {
        synchronized (dlq) {
            if (dlq.size() >= DLQ_CAPACITY) {
                String evicted = dlq.pollFirst();
                log.warn("Workspace dump DLQ full (cap={}), evicted oldest entry: {}",
                        DLQ_CAPACITY, evicted);
            }
            dlq.addLast(runId);
        }
        log.warn("Workspace dump exhausted retries ({}), runId={} conservative={} pushed to in-memory DLQ (size will be reported in v0.1 metrics); lastError={}",
                MAX_ATTEMPTS, runId, conservative,
                lastError == null ? "null" : lastError.getMessage());
    }

    /** 当前 DLQ 容量，供 v0.1 指标接入。 */
    public int dlqSize() {
        synchronized (dlq) {
            return dlq.size();
        }
    }
}
