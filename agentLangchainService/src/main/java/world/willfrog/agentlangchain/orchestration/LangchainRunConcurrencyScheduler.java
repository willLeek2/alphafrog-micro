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

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent Run 的有界执行槽位调度器。
 *
 * <p>普通启动和外部工具恢复都必须经过本类。一个 Run 因工具 pending 返回后，
 * pipeline 的 Runnable 会结束，{@link #onRunFinished()} 立刻归还 running 槽位；
 * 终态结果到达后，resume launcher 再提交一个新的 Runnable，重新公平排队。</p>
 *
 * <p>Reservation 只预占“可以运行或可以排队”的名额，真正线程由
 * {@code agentLangchainRunTaskExecutor} 提供。所有计数和队列都在同一把锁内修改，
 * 避免释放与恢复入队并发时超卖。</p>
 */
@Component
@Slf4j
public class LangchainRunConcurrencyScheduler {

    // executor 只执行已经获得 RUNNING 槽位的 Runnable。
    private final ThreadPoolTaskExecutor executor;
    // limitsResolver 每次读取当前动态限制，同时保留不可突破的硬上限。
    private final LangchainRunExecutorLimitsResolver limitsResolver;
    // running、reservedQueued 和 queue 必须由同一把锁保护，形成一个原子调度状态。
    private final Object lock = new Object();
    // queue 保存已经激活但尚未拿到线程的 Run；恢复 Run 与新 Run 使用同一队列。
    private final Queue<PendingRun> queue = new ArrayDeque<>();
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

    // ── 单实例标识 (用于跨实例 snapshot 聚合时的来源区分) ──
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
            @Value("${spring.application.name:unknown-app}") String applicationName) {
        this.executor = executor;
        this.limitsResolver = limitsResolver;
        this.instanceId = resolveInstanceId(applicationName);
    }

    public Reservation reserve() {
        // 整个准入过程必须原子执行，否则两个请求可能同时看到同一个空槽位。
        synchronized (lock) {
            // 每次准入都读取最新动态限制，让 Nacos 调整能立即生效。
            LangchainRunExecutorLimits limits = limitsResolver.currentLimits();
            // 先把已有排队任务提升到可用核心槽位，避免新请求插队。
            drainLocked(limits);
            // 没有历史排队者且核心槽未满时，直接预占 RUNNING 名额。
            if (queue.isEmpty() && running < limits.getCorePoolSize()) {
                // 先递增计数再返回 reservation，防止 submit 前被另一个请求抢占。
                running++;
                // UUID 仅用于日志/诊断；释放幂等由 Reservation 内部状态保证。
                return new Reservation(UUID.randomUUID().toString(), SlotType.RUNNING);
            }
            // 核心槽已满时优先预留一个有限队列名额。
            if (reservedQueued < limits.getQueueCapacity()) {
                // reserve 与 submit 分两步，所以此处先记账，避免并发超出 queueCapacity。
                reservedQueued++;
                // 第一名排队者建立队龄起点；后续 submit 会用真实 PendingRun 时间校正。
                if (oldestQueuedAtMillis == 0) {
                    oldestQueuedAtMillis = System.currentTimeMillis();
                }
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
                // QUEUED reservation 尚未进入 queue，必须由 submit 激活或由调用方 release。
                return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED);
            }
            // 队列名额用尽后，允许在 core 与 max 之间临时扩容。
            // 若已有排队任务，先提升队首，再把当前请求保留为 QUEUED，保持先来先服务。
            /*
             * 只要还没达到 max 就可以继续逐个启用弹性槽。旧条件额外要求
             * running<=core，导致第一次从 core 扩到 core+1 后后续弹性槽永久不可达，
             * 例如 core=1/max=3 实际最多只能跑 2 个。
             */
            if (running < limits.getMaxPoolSize()) {
                if (!queue.isEmpty()) {
                    // poll 与计数扣减在锁内完成，队首只能被一个线程提升。
                    PendingRun pending = queue.poll();
                    reservedQueued = Math.max(0, reservedQueued - 1);
                    // 队首变化后刷新最老排队时间。
                    refreshOldestQueuedAtMillisLocked();
                    // 计算真实等待时长用于慢排队诊断。
                    long queuedDurationMs = pending.queuedAtMillis() > 0
                            ? System.currentTimeMillis() - pending.queuedAtMillis()
                            : 0;
                    if (queuedDurationMs > 60_000) {
                        long now = System.currentTimeMillis();
                        if (now - lastQueuedPromotedWarnAt >= QUEUED_PROMOTED_WARN_INTERVAL_MS) {
                            lastQueuedPromotedWarnAt = now;
                            log.warn("Scheduler queued run promoted after {}s (via reserve): runId={}",
                                    queuedDurationMs / 1000, pending.run() != null ? pending.run().getId() : "unknown");
                        }
                    }
                    // 提升历史任务前先占用 running，线程池拒绝时 submitRunning 会回滚。
                    running++;
                    submitRunning(pending.task());
                    // 当前请求接管刚释放的排队名额，计数恢复到原值。
                    reservedQueued++;
                    return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED);
                }
                // 没有实体排队任务时，当前请求直接使用弹性槽位。
                running++;
                return new Reservation(UUID.randomUUID().toString(), SlotType.RUNNING);
            }
            // running 与 queue 都达到动态/硬限制时 fail-fast，避免无界堆积占满 JVM。
            rejectedCount.incrementAndGet();
            log.warn("Scheduler rejected: running={} queued={} rejectedTotal={} hardLimits={} currentLimits={}",
                    running, reservedQueued, rejectedCount.get(),
                    limitsResolver.hardLimits().summary(),
                    limits.summary());
            throw new LangchainRunRejectedException("agent_run_executor_queue_full: running=" + running
                    + ", queued=" + reservedQueued
                    + ", current=" + limits.summary()
                    + ", hard=" + limitsResolver.hardLimits().summary());
        }
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
            submitRunning(task);
            return;
        }
        // QUEUED 类型需要把真实 Runnable 放入 FIFO 队列。
        synchronized (lock) {
            // run 只用于排队诊断；task 才是恢复/普通执行的实际闭包。
            queue.add(new PendingRun(run, task, System.currentTimeMillis()));
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
                // 提交前放弃 RUNNING 名额，立刻让队首补位。
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
        while (!queue.isEmpty() && running < limits.getCorePoolSize()) {
            // FIFO 取队首，恢复 Run 不会绕过更早进入队列的新 Run。
            PendingRun pending = queue.poll();
            // 实体任务被提升后，释放它占用的 queue reservation。
            reservedQueued = Math.max(0, reservedQueued - 1);
            refreshOldestQueuedAtMillisLocked();
            long queuedDurationMs = pending.queuedAtMillis() > 0
                    ? System.currentTimeMillis() - pending.queuedAtMillis()
                    : 0;
            if (queuedDurationMs > 60_000) {
                long now = System.currentTimeMillis();
                if (now - lastQueuedPromotedWarnAt >= QUEUED_PROMOTED_WARN_INTERVAL_MS) {
                    lastQueuedPromotedWarnAt = now;
                    log.warn("Scheduler queued run promoted after {}s: runId={}",
                            queuedDurationMs / 1000, pending.run() != null ? pending.run().getId() : "unknown");
                }
            }
            // 必须先记 running 再 submit，防止另一个 drain 同时超发。
            running++;
            submitRunning(pending.task());
        }
    }

    private void submitRunning(Runnable task) {
        try {
            // 包装层无条件归还 running 槽位。
            executor.execute(() -> {
                try {
                    // 普通 Run 执行完整 pipeline；恢复 Run 执行 resume pipeline。
                    task.run();
                } finally {
                    // pending 是正常 return，也会走这里释放 worker。
                    onRunFinished();
                }
            });
        } catch (RejectedExecutionException e) {
            // 底层线程池仍可能硬拒绝，必须回滚已经增加的 running。
            onRunFinished();
            // 转成统一业务异常，调用方不会误以为任务已排队成功。
            throw new LangchainRunRejectedException("agent_run_executor_hard_rejected: " + e.getMessage());
        }
    }

    private void onRunFinished() {
        // 成功、失败、取消和工具 pending 都走同一个原子出口。
        synchronized (lock) {
            // 正常情况下每个激活 RUNNING 只归还一次；max(0) 是防御下限。
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
        return Map.ofEntries(
                Map.entry("instanceId", instanceId),
                Map.entry("running", running),
                Map.entry("queued", reservedQueued),
                Map.entry("rejectedTotal", rejectedCount.get()),
                Map.entry("corePoolSize", current.getCorePoolSize()),
                Map.entry("maxPoolSize", current.getMaxPoolSize()),
                Map.entry("queueCapacity", current.getQueueCapacity()),
                Map.entry("hardCorePoolSize", hard.getCorePoolSize()),
                Map.entry("hardMaxPoolSize", hard.getMaxPoolSize()),
                Map.entry("hardQueueCapacity", hard.getQueueCapacity()),
                Map.entry("oldestQueuedAgeMs", oldestAgeMs)
        );
    }

    @Scheduled(fixedDelayString = "${agent.langchain.run.executor.diag-interval-ms:30000}")
    public void diagLog() {
        Map<String, Object> snap = schedulerSnapshot();
        latestSnapshot = snap; // publish for external consumers

        int runningVal = ((Number) snap.get("running")).intValue();
        int queuedVal = ((Number) snap.get("queued")).intValue();
        long oldestAgeMs = ((Number) snap.get("oldestQueuedAgeMs")).longValue();

        if (queuedVal > 0 || runningVal > 0) {
            log.info("Scheduler diag: running={} queued={} oldestQueuedAge={}s core={}/{} queueCap={} rejected={}",
                    runningVal, queuedVal, oldestAgeMs / 1000,
                    snap.get("corePoolSize"), snap.get("maxPoolSize"), snap.get("queueCapacity"),
                    snap.get("rejectedTotal"));
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

    private record PendingRun(AgentRun run, Runnable task, long queuedAtMillis) {
    }

    /**
     * reserve 与 submit 之间的本地名额凭证。
     *
     * <p>activate 前由调用方负责释放；activate 后由 Runnable 的 finally 负责释放。
     * 这条责任转移规则保证工具挂起返回时只归还一次 worker。</p>
     */
    public static class Reservation {
        private final String id;
        private final SlotType slotType;
        private boolean activated;
        private boolean released;

        private Reservation(String id, SlotType slotType) {
            this.id = id;
            this.slotType = slotType;
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
