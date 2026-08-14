package world.willfrog.agentlangchain.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimits;
import world.willfrog.agentlangchain.config.LangchainRunExecutorLimitsResolver;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;
import world.willfrog.agentlangchain.orchestration.scheduler.RunAdmissionPolicy;
import world.willfrog.agentlangchain.orchestration.scheduler.RunCapacityLedger;
import world.willfrog.agentlangchain.orchestration.scheduler.RunPriority;
import world.willfrog.agentlangchain.orchestration.scheduler.RunPriorityPolicy;
import world.willfrog.agentlangchain.orchestration.scheduler.RunPriorityQueue;
import world.willfrog.agentlangchain.orchestration.scheduler.RunWeightPolicy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent Run 的有界、容量加权的在线调度器。
 *
 * <p>普通启动和长工具续接都必须经过本类。线程池只是执行层：先由
 * {@link RunAdmissionPolicy} 决定 RUNNING / QUEUED / ELASTIC / REJECTED，
 * {@link RunCapacityLedger} 按权重记账，{@link RunPriorityQueue} 保证
 * 同优先级 FIFO、多优先级轮转不饿死。一个 Run 因工具 pending 返回后，
 * pipeline 的 Runnable 会结束，{@link #onRunFinished} 立刻归还 running 槽位
 * 与容量；工具终态由进程内 continuation tracker 重新提交，再次经过同一套
 * 准入规则。</p>
 *
 * <p>Reservation 只预占“可以运行或可以排队”的名额，真正线程由
 * {@code agentLangchainRunTaskExecutor} 提供。所有计数、队列和容量账本
 * 都在同一把锁内修改，避免释放与恢复入队并发时超卖。</p>
 */
@Component
@Slf4j
public class LangchainRunConcurrencyScheduler {

    // executor 只执行已经获得 RUNNING 槽位的 Runnable。
    private final ThreadPoolTaskExecutor executor;
    // limitsResolver 每次读取当前动态限制，同时保留不可突破的硬上限。
    private final LangchainRunExecutorLimitsResolver limitsResolver;
    private final RunAdmissionPolicy admissionPolicy;
    private final RunPriorityPolicy priorityPolicy;
    private final RunWeightPolicy weightPolicy;
    private final RunCapacityLedger capacityLedger;
    private final LangchainSchedulerMetrics metrics;
    // advancedDiagnosticsEnabled 只控制 snapshot 是否携带 instanceId；周期全量
    // 诊断日志由独立的 LangchainSchedulerDiagnostics 组件按同一开关控制。
    private final boolean advancedDiagnosticsEnabled;
    // running、reservedQueued、queue 和容量账本必须由同一把锁保护，形成一个原子调度状态。
    private final Object lock = new Object();
    // queue 保存已经激活但尚未拿到线程的 Run；恢复 Run 与新 Run 使用同一队列。
    private final RunPriorityQueue<PendingRun> queue = new RunPriorityQueue<>();
    // rejectedCount 只用于观测，不参与准入决策。
    private final AtomicLong rejectedCount = new AtomicLong();
    // running 表示已交给线程池、尚未从 Runnable finally 退出的 Run 数量。
    private int running;
    // reservedQueued 同时包含已预留但未 submit 和已经进入 queue 的排队名额。
    private int reservedQueued;
    // oldestQueuedAtMillis 缓存队首入队时间，避免诊断接口遍历队列。
    private volatile long oldestQueuedAtMillis;

    // ── WARN throttle: per-type minimum interval ──
    private volatile long lastQueueHighWarnAt;
    private volatile long lastQueuedPromotedWarnAt;
    private volatile long lastOldestAgeWarnAt;
    private static final long QUEUE_HIGH_WARN_INTERVAL_MS = 30_000;
    private static final long QUEUED_PROMOTED_WARN_INTERVAL_MS = 30_000;
    private static final long OLDEST_AGE_WARN_INTERVAL_MS = 60_000;

    // ── 单实例标识（仅高级诊断开启时进入 snapshot） ──
    // 格式: <applicationName>@<hostname>@<pid>; hostname 解析失败时 fallback 为
    // unknown-host-<uuid8> (JVM 启动时一次性解析), 防止容器场景下相同 PID (例如 1) 碰撞.
    private final String instanceId;

    private static String resolveInstanceId(String applicationName) {
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            hostname = "unknown-host-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return applicationName + "@" + hostname + "@" + ProcessHandle.current().pid();
    }

    // ── Latest snapshot store (consumed by observability / actuator) ──
    private volatile Map<String, Object> latestSnapshot = Map.of();

    public LangchainRunConcurrencyScheduler(
            @Qualifier("agentLangchainRunTaskExecutor") ThreadPoolTaskExecutor executor,
            LangchainRunExecutorLimitsResolver limitsResolver,
            RunAdmissionPolicy admissionPolicy,
            RunPriorityPolicy priorityPolicy,
            RunWeightPolicy weightPolicy,
            RunCapacityLedger capacityLedger,
            LangchainSchedulerMetrics metrics,
            @Value("${spring.application.name:unknown-app}") String applicationName,
            @Value("${agent.langchain.run.scheduler.advanced-diagnostics-enabled:false}")
            boolean advancedDiagnosticsEnabled) {
        this.executor = executor;
        this.limitsResolver = limitsResolver;
        this.admissionPolicy = admissionPolicy;
        this.priorityPolicy = priorityPolicy;
        this.weightPolicy = weightPolicy;
        this.capacityLedger = capacityLedger;
        this.metrics = metrics;
        this.advancedDiagnosticsEnabled = advancedDiagnosticsEnabled;
        this.instanceId = resolveInstanceId(applicationName);
        // 基础指标始终开启；instanceId 与周期诊断默认关闭。
        metrics.bindGauges(this::runningCount, this::queuedCount, capacityLedger::usedUnits);
    }

    public Reservation reserve() {
        // 整个准入过程必须原子执行，否则两个请求可能同时看到同一个空槽位。
        synchronized (lock) {
            // 每次准入都读取最新动态限制，让 Nacos 调整能立即生效。
            LangchainRunExecutorLimits limits = limitsResolver.currentLimits();
            // 先把已有排队任务提升到可用核心槽位，避免新请求插队。
            drainLocked(limits);
            // 准入策略是纯函数：持锁构建一致的状态快照，策略只返回决定。
            int weight = weightPolicy.weightUnitsFor(null);
            RunAdmissionPolicy.AdmissionState state = new RunAdmissionPolicy.AdmissionState(
                    running,
                    queue.isEmpty(),
                    reservedQueued,
                    limits.getQueueCapacity(),
                    capacityLedger.usedUnits(),
                    capacityLedger.maxUnits(),
                    limits.getCorePoolSize(),
                    limits.getMaxPoolSize(),
                    weight);
            RunAdmissionPolicy.AdmissionDecision decision = admissionPolicy.evaluate(state);
            RunPriority priority = priorityPolicy.priorityFor(null);
            return switch (decision) {
                case RUNNING -> grantRunningLocked(limits, priority, weight);
                case QUEUED -> reserveQueuedLocked(limits, priority, weight);
                case ELASTIC -> elasticLocked(limits, priority, weight);
                case REJECTED -> rejectLocked(limits, decision.rejectReason(state));
            };
        }
    }

    private Reservation grantRunningLocked(LangchainRunExecutorLimits limits,
                                           RunPriority priority, int weight) {
        String key = UUID.randomUUID().toString();
        if (!capacityLedger.tryAcquire(key, weight)) {
            // 策略判定容量放得下但账本拒绝（策略与账本不一致时 fail-closed）。
            rejectLocked(limits, "capacity_full");
        }
        // 先递增计数再返回 reservation，防止 submit 前被另一个请求抢占。
        running++;
        // UUID 仅用于日志/诊断；释放幂等由 Reservation 内部状态保证。
        return new Reservation(key, SlotType.RUNNING, priority, weight);
    }

    private Reservation reserveQueuedLocked(LangchainRunExecutorLimits limits,
                                            RunPriority priority, int weight) {
        // 核心槽已满（或容量不足）时优先预留一个有限队列名额。
        reservedQueued++;
        // 第一名排队者建立队龄起点；后续 submit 会用真实 PendingRun 时间校正。
        if (oldestQueuedAtMillis == 0) {
            oldestQueuedAtMillis = System.currentTimeMillis();
        }
        warnQueueHighLocked(limits);
        // QUEUED reservation 尚未进入 queue，必须由 submit 激活或由调用方 release。
        return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED, priority, weight);
    }

    /**
     * 队列名额用尽后，允许在 core 与 max 之间临时扩容。
     * 若已有排队任务，先提升队首，再把当前请求保留为 QUEUED，保持先来先服务。
     * 队首若被容量阻塞（权重放不下），当前请求同样拒绝，防止跳队破坏 FIFO。
     */
    private Reservation elasticLocked(LangchainRunExecutorLimits limits,
                                      RunPriority priority, int weight) {
        if (!queue.isEmpty()) {
            if (!promoteHeadLocked(limits)) {
                rejectLocked(limits, "capacity_full");
            }
            // 当前请求接管刚释放的排队名额，计数恢复到原值。
            reservedQueued++;
            if (oldestQueuedAtMillis == 0) {
                oldestQueuedAtMillis = System.currentTimeMillis();
            }
            return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED, priority, weight);
        }
        // 没有实体排队任务时，当前请求直接使用弹性槽位（仍需容量放得下）。
        return grantRunningLocked(limits, priority, weight);
    }

    private Reservation rejectLocked(LangchainRunExecutorLimits limits, String reason) {
        // running 与 queue 都达到动态/硬限制时 fail-fast，避免无界堆积占满 JVM。
        rejectedCount.incrementAndGet();
        metrics.recordRejected(reason);
        log.warn("Scheduler rejected reason={}: running={} queued={} rejectedTotal={} hardLimits={} currentLimits={}",
                reason, running, reservedQueued, rejectedCount.get(),
                limitsResolver.hardLimits().summary(),
                limits.summary());
        throw new LangchainRunRejectedException("agent_run_executor_queue_full: reason=" + reason
                + ", running=" + running
                + ", queued=" + reservedQueued
                + ", current=" + limits.summary()
                + ", hard=" + limitsResolver.hardLimits().summary(), reason);
    }

    public void submit(Reservation reservation, AgentRun run, Runnable task) {
        // 恢复入口通常没有提前 reserve；在这里统一走相同准入规则。
        if (reservation == null) {
            reservation = reserve();
        }
        // activate 后，槽位归还责任从调用方转移给任务包装层。
        reservation.activate();
        // RUNNING 类型已经在 reserve 中计入 running，可以立即提交线程池。
        if (reservation.slotType == SlotType.RUNNING) {
            try {
                submitRunning(task, reservation.id);
            } catch (LangchainRunRejectedException e) {
                // 物理线程池硬拒绝时回滚已经占用的槽位与容量，再让队首补位。
                synchronized (lock) {
                    capacityLedger.release(reservation.id);
                    running = Math.max(0, running - 1);
                    drainLocked();
                }
                throw e;
            }
            return;
        }
        // QUEUED 类型需要把真实 Runnable 放入优先级队列。
        synchronized (lock) {
            // run 只用于排队诊断与排队取消定位；task 才是恢复/普通执行的实际闭包。
            queue.enqueue(reservation.priority,
                    new PendingRun(run, task, System.currentTimeMillis(),
                            reservation.priority, reservation.id, reservation.weight));
            // 理论上 reserve 已设置时间；这里兼容历史状态并兜底。
            if (oldestQueuedAtMillis == 0) {
                oldestQueuedAtMillis = System.currentTimeMillis();
            }
            // 入队与前一个 running 退出可能交错，因此立即尝试提升。
            drainLocked();
        }
    }

    public void release(Reservation reservation) {
        // 这里只回收“已经 reserve、尚未 submit”的名额。
        // 已激活任务由 Runnable finally 归还，避免双减计数。
        if (reservation == null || !reservation.release()) {
            return;
        }
        synchronized (lock) {
            if (reservation.slotType == SlotType.RUNNING) {
                // 提交前放弃 RUNNING 名额，立刻归还容量并让队首补位。
                capacityLedger.release(reservation.id);
                running = Math.max(0, running - 1);
                drainLocked();
            } else {
                // 提交前放弃 QUEUED 名额，只需回收预留计数。
                reservedQueued = Math.max(0, reservedQueued - 1);
                if (reservedQueued == 0) {
                    // 无排队预留时清空陈旧队龄。
                    oldestQueuedAtMillis = 0;
                }
            }
        }
    }

    /**
     * 按 runId 取消仍在排队中的 Run：从队列直接移出并归还名额。
     * 执行中的 Run 取消由状态守卫与取消信号传播负责，不走本方法。
     *
     * @return true 表示确实移除了一个排队项；false 表示队列中没有该 Run
     */
    public boolean cancelQueued(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        synchronized (lock) {
            boolean removed = queue.removeIf(pending ->
                    pending.run() != null && runId.equals(pending.run().getId()));
            if (!removed) {
                return false;
            }
            reservedQueued = Math.max(0, reservedQueued - 1);
            refreshOldestQueuedAtMillisLocked();
            metrics.recordCancelled("queued");
            log.info("Scheduler queued run cancelled: runId={} remainingQueued={}",
                    runId, reservedQueued);
            return true;
        }
    }

    public int runningCount() {
        synchronized (lock) {
            return running;
        }
    }

    public int queuedCount() {
        synchronized (lock) {
            return reservedQueued;
        }
    }

    @Scheduled(fixedDelayString = "${agent.langchain.run.executor.drain-interval-ms:1000}")
    public void drain() {
        synchronized (lock) {
            drainLocked();
        }
    }

    private void drainLocked() {
        drainLocked(limitsResolver.currentLimits());
    }

    private void drainLocked(LangchainRunExecutorLimits limits) {
        // 调用方已持有 lock；一次循环填满当前 corePoolSize 的全部空槽。
        while (running < limits.getCorePoolSize()) {
            if (!promoteHeadLocked(limits)) {
                return;
            }
        }
    }

    /**
     * 提升队首一个排队任务。容量放不下时队首保留原位并停止提升，
     * 后面的任务不能跳队（FIFO 公平）。
     *
     * @return true 表示提升成功；false 表示队列为空或队首被容量阻塞
     */
    private boolean promoteHeadLocked(LangchainRunExecutorLimits limits) {
        if (queue.isEmpty()) {
            return false;
        }
        PendingRun head = queue.peek();
        // 先占容量再移出队列：账本不足时队首保持原位，等待下一个 finish 释放容量。
        if (!capacityLedger.tryAcquire(head.reservationKey(), head.weight())) {
            log.warn("Scheduler head-of-queue blocked on capacity: runId={} weight={} used={}/{}",
                    head.run() != null ? head.run().getId() : "unknown",
                    head.weight(), capacityLedger.usedUnits(), capacityLedger.maxUnits());
            return false;
        }
        PendingRun pending = queue.poll();
        // 实体任务被提升后，释放它占用的 queue reservation。
        reservedQueued = Math.max(0, reservedQueued - 1);
        refreshOldestQueuedAtMillisLocked();
        // 必须先记 running 再 submit，防止另一个 drain 同时超发。
        running++;
        try {
            submitRunning(pending.task(), pending.reservationKey());
        } catch (LangchainRunRejectedException e) {
            // 物理线程池暂时饱和（core==max 且本调用来自最后一个 worker 的
            // finally）：任务放回队首、回滚计数与容量，等待下一轮 drain 重试。
            // 不丢失 Run，只延后一个 drain 周期。
            queue.enqueueFront(pending.priority(), pending);
            reservedQueued++;
            if (oldestQueuedAtMillis == 0) {
                oldestQueuedAtMillis = pending.queuedAtMillis();
            }
            capacityLedger.release(pending.reservationKey());
            running = Math.max(0, running - 1);
            log.warn("Scheduler promotion deferred for runId={}: executor temporarily saturated",
                    pending.run() != null ? pending.run().getId() : "unknown");
            return false;
        }
        long queuedDurationMs = pending.queuedAtMillis() > 0
                ? System.currentTimeMillis() - pending.queuedAtMillis()
                : 0;
        if (queuedDurationMs > 0) {
            metrics.recordQueueWait(Duration.ofMillis(queuedDurationMs));
        }
        if (queuedDurationMs > 60_000) {
            long now = System.currentTimeMillis();
            if (now - lastQueuedPromotedWarnAt >= QUEUED_PROMOTED_WARN_INTERVAL_MS) {
                lastQueuedPromotedWarnAt = now;
                log.warn("Scheduler queued run promoted after {}s: runId={}",
                        queuedDurationMs / 1000, pending.run() != null ? pending.run().getId() : "unknown");
            }
        }
        return true;
    }

    private void submitRunning(Runnable task, String reservationKey) {
        try {
            // 包装层无条件归还 running 槽位与容量。
            executor.execute(() -> {
                try {
                    // 普通 Run 执行完整 pipeline；续接 Run 执行 resume pipeline。
                    task.run();
                } finally {
                    // pending 是正常 return，也会走这里释放 worker。
                    onRunFinished(reservationKey);
                }
            });
        } catch (RejectedExecutionException e) {
            // 转成统一业务异常；计数回滚由调用方（submit 或 promote）负责，
            // 避免双重回滚与队列状态不一致。
            throw new LangchainRunRejectedException(
                    "agent_run_executor_hard_rejected: " + e.getMessage(),
                    "executor_hard_rejected");
        }
    }

    private void onRunFinished(String reservationKey) {
        // 成功、失败、取消和工具 pending 都走同一个原子出口。
        synchronized (lock) {
            // 正常情况下每个激活 RUNNING 只归还一次；max(0) 是防御下限。
            capacityLedger.release(reservationKey);
            running = Math.max(0, running - 1);
            // 归还后立即提升队首，实现“挂起一个 Run，马上运行另一个 Run”。
            drainLocked();
        }
    }

    /**
     * 在持锁状态下从新队首重算最老排队时间。
     * 无入参、无返回值，只更新观测字段，不改变调度顺序。
     */
    private void refreshOldestQueuedAtMillisLocked() {
        // 空队列没有等待年龄。
        if (queue.isEmpty()) {
            oldestQueuedAtMillis = 0;
        } else {
            // peek 只读队首，不移除元素。
            PendingRun head = queue.peek();
            if (head != null) {
                // 使用任务实际入队时间，而不是 reservation 创建时间。
                oldestQueuedAtMillis = head.queuedAtMillis();
            }
        }
    }

    private void warnQueueHighLocked(LangchainRunExecutorLimits limits) {
        // 队列占用比例只用于节流告警，不改变调度结果。
        int queuePct = limits.getQueueCapacity() > 0
                ? reservedQueued * 100 / limits.getQueueCapacity()
                : 0;
        // 超过一半容量时提示运维，但避免每次请求都刷日志。
        if (queuePct >= 50) {
            long now = System.currentTimeMillis();
            // 同类型告警至少间隔固定时间。
            if (now - lastQueueHighWarnAt >= QUEUE_HIGH_WARN_INTERVAL_MS) {
                lastQueueHighWarnAt = now;
                log.warn("Scheduler queue high: queued={}/{} ({}%) running={} core={} max={}",
                        reservedQueued, limits.getQueueCapacity(), queuePct,
                        running, limits.getCorePoolSize(), limits.getMaxPoolSize());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Metrics / snapshot — consumable by observability and actuator
    // ═══════════════════════════════════════════════════════════════

    /**
     * Lightweight snapshot for actuator / JMX / debug endpoints.
     * All concurrency metrics are derived under the lock for consistency.
     */
    public Map<String, Object> schedulerSnapshot() {
        synchronized (lock) {
            return buildSnapshot();
        }
    }

    /** Latest snapshot updated periodically by diagLog(). Non-blocking read. */
    public Map<String, Object> latestSnapshot() {
        return latestSnapshot;
    }

    private Map<String, Object> buildSnapshot() {
        LangchainRunExecutorLimits current = limitsResolver.currentLimits();
        LangchainRunExecutorLimits hard = limitsResolver.hardLimits();
        long oldestAgeMs = oldestQueuedAtMillis > 0
                ? System.currentTimeMillis() - oldestQueuedAtMillis
                : 0;
        Map<String, Object> snap = new LinkedHashMap<>();
        // instanceId 只在高级诊断开启时暴露，基础快照不携带单实例身份。
        if (advancedDiagnosticsEnabled) {
            snap.put("instanceId", instanceId);
        }
        snap.put("running", running);
        snap.put("queued", reservedQueued);
        snap.put("rejectedTotal", rejectedCount.get());
        snap.put("corePoolSize", current.getCorePoolSize());
        snap.put("maxPoolSize", current.getMaxPoolSize());
        snap.put("queueCapacity", current.getQueueCapacity());
        snap.put("hardCorePoolSize", hard.getCorePoolSize());
        snap.put("hardMaxPoolSize", hard.getMaxPoolSize());
        snap.put("hardQueueCapacity", hard.getQueueCapacity());
        snap.put("oldestQueuedAgeMs", oldestAgeMs);
        snap.put("capacityUsedUnits", capacityLedger.usedUnits());
        snap.put("maxCapacityUnits", capacityLedger.maxUnits());
        snap.put("hardVsEffectiveGap", limitsResolver.getHardVersusEffectiveGap());
        return snap;
    }

    /**
     * 周期全量诊断日志。调度方法本身由独立的
     * LangchainSchedulerDiagnostics 组件按高级诊断开关控制；本方法保持
     * 无 @Scheduled，便于测试直接调用。
     */
    public void diagLog() {
        Map<String, Object> snap = schedulerSnapshot();
        latestSnapshot = snap; // publish for external consumers

        int runningVal = ((Number) snap.get("running")).intValue();
        int queuedVal = ((Number) snap.get("queued")).intValue();
        long oldestAgeMs = ((Number) snap.get("oldestQueuedAgeMs")).longValue();

        if (queuedVal > 0 || runningVal > 0) {
            log.info("Scheduler diag: running={} queued={} oldestQueuedAge={}s core={}/{} queueCap={} rejected={} capacity={}/{}",
                    runningVal, queuedVal, oldestAgeMs / 1000,
                    snap.get("corePoolSize"), snap.get("maxPoolSize"), snap.get("queueCapacity"),
                    snap.get("rejectedTotal"), snap.get("capacityUsedUnits"), snap.get("maxCapacityUnits"));
        }
        if (oldestAgeMs > 120_000) {
            long now = System.currentTimeMillis();
            if (now - lastOldestAgeWarnAt >= OLDEST_AGE_WARN_INTERVAL_MS) {
                lastOldestAgeWarnAt = now;
                log.warn("Scheduler oldest queued age exceeds 120s: {}s queued={} running={}",
                        oldestAgeMs / 1000, queuedVal, runningVal);
            }
        }
    }

    private enum SlotType {
        RUNNING,
        QUEUED
    }

    private record PendingRun(AgentRun run, Runnable task, long queuedAtMillis,
                              RunPriority priority, String reservationKey, int weight) {
    }

    /**
     * reserve 与 submit 之间的本地名额凭证。
     *
     * <p>activate 前由调用方负责释放；activate 后由 Runnable 的 finally 负责释放。
     * 这条责任转移规则保证工具挂起返回时只归还一次 worker 与容量。</p>
     */
    public static class Reservation {
        private final String id;
        private final SlotType slotType;
        private final RunPriority priority;
        private final int weight;
        private boolean activated;
        private boolean released;

        private Reservation(String id, SlotType slotType, RunPriority priority, int weight) {
            this.id = id;
            this.slotType = slotType;
            this.priority = priority;
            this.weight = weight;
        }

        public String id() {
            return id;
        }

        private void activate() {
            // 已经由调用方释放的 reservation 不能再次提交任务。
            if (released) {
                throw new LangchainRunRejectedException("agent_run_executor_reservation_released");
            }
            // 激活后槽位归还责任转移给 submitRunning 的 finally。
            this.activated = true;
        }

        private boolean release() {
            // 已激活说明 Runnable 会自行归还；已释放说明这是重复调用。
            if (activated || released) {
                return false;
            }
            // 先标记再返回 true，确保重复 release 最多一次修改全局计数。
            released = true;
            return true;
        }
    }
}
