package world.willfrog.agentlangchain.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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

@Component
@Slf4j
public class LangchainRunConcurrencyScheduler {

    private final ThreadPoolTaskExecutor executor;
    private final LangchainRunExecutorLimitsResolver limitsResolver;
    private final Object lock = new Object();
    private final Queue<PendingRun> queue = new ArrayDeque<>();
    private final AtomicLong rejectedCount = new AtomicLong();
    private int running;
    private int reservedQueued;
    private volatile long oldestQueuedAtMillis;

    // ── WARN throttle: per-type minimum interval ──
    private volatile long lastQueueHighWarnAt;
    private volatile long lastQueuedPromotedWarnAt;
    private volatile long lastOldestAgeWarnAt;
    private static final long QUEUE_HIGH_WARN_INTERVAL_MS = 30_000;
    private static final long QUEUED_PROMOTED_WARN_INTERVAL_MS = 30_000;
    private static final long OLDEST_AGE_WARN_INTERVAL_MS = 60_000;

    // ── Latest snapshot store (consumed by observability / actuator) ──
    private volatile Map<String, Object> latestSnapshot = Map.of();

    public LangchainRunConcurrencyScheduler(
            @Qualifier("agentLangchainRunTaskExecutor") ThreadPoolTaskExecutor executor,
            LangchainRunExecutorLimitsResolver limitsResolver) {
        this.executor = executor;
        this.limitsResolver = limitsResolver;
    }

    public Reservation reserve() {
        synchronized (lock) {
            LangchainRunExecutorLimits limits = limitsResolver.currentLimits();
            drainLocked(limits);
            if (queue.isEmpty() && running < limits.getCorePoolSize()) {
                running++;
                return new Reservation(UUID.randomUUID().toString(), SlotType.RUNNING);
            }
            if (reservedQueued < limits.getQueueCapacity()) {
                reservedQueued++;
                if (oldestQueuedAtMillis == 0) {
                    oldestQueuedAtMillis = System.currentTimeMillis();
                }
                int queuePct = limits.getQueueCapacity() > 0
                        ? reservedQueued * 100 / limits.getQueueCapacity()
                        : 0;
                if (queuePct >= 50) {
                    long now = System.currentTimeMillis();
                    if (now - lastQueueHighWarnAt >= QUEUE_HIGH_WARN_INTERVAL_MS) {
                        lastQueueHighWarnAt = now;
                        log.warn("Scheduler queue high: queued={}/{} ({}%) running={} core={} max={}",
                                reservedQueued, limits.getQueueCapacity(), queuePct,
                                running, limits.getCorePoolSize(), limits.getMaxPoolSize());
                    }
                }
                return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED);
            }
            if (running <= limits.getCorePoolSize() && running < limits.getMaxPoolSize()) {
                if (!queue.isEmpty()) {
                    PendingRun pending = queue.poll();
                    reservedQueued = Math.max(0, reservedQueued - 1);
                    refreshOldestQueuedAtMillisLocked();
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
                    running++;
                    submitRunning(pending.task());
                    reservedQueued++;
                    return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED);
                }
                running++;
                return new Reservation(UUID.randomUUID().toString(), SlotType.RUNNING);
            }
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
        if (reservation == null) {
            reservation = reserve();
        }
        reservation.activate();
        if (reservation.slotType == SlotType.RUNNING) {
            submitRunning(task);
            return;
        }
        synchronized (lock) {
            queue.add(new PendingRun(run, task, System.currentTimeMillis()));
            if (oldestQueuedAtMillis == 0) {
                oldestQueuedAtMillis = System.currentTimeMillis();
            }
            drainLocked();
        }
    }

    public void release(Reservation reservation) {
        if (reservation == null || !reservation.release()) {
            return;
        }
        synchronized (lock) {
            if (reservation.slotType == SlotType.RUNNING) {
                running = Math.max(0, running - 1);
                drainLocked();
            } else {
                reservedQueued = Math.max(0, reservedQueued - 1);
                if (reservedQueued == 0) {
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
        while (!queue.isEmpty() && running < limits.getCorePoolSize()) {
            PendingRun pending = queue.poll();
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
            running++;
            submitRunning(pending.task());
        }
    }

    private void submitRunning(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    onRunFinished();
                }
            });
        } catch (RejectedExecutionException e) {
            onRunFinished();
            throw new LangchainRunRejectedException("agent_run_executor_hard_rejected: " + e.getMessage());
        }
    }

    private void onRunFinished() {
        synchronized (lock) {
            running = Math.max(0, running - 1);
            drainLocked();
        }
    }

    /** Re-derive oldestQueuedAtMillis from the head of the queue or clear if empty. */
    private void refreshOldestQueuedAtMillisLocked() {
        if (queue.isEmpty()) {
            oldestQueuedAtMillis = 0;
        } else {
            PendingRun head = queue.peek();
            if (head != null) {
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
        return Map.of(
                "running", running,
                "queued", reservedQueued,
                "rejectedTotal", rejectedCount.get(),
                "corePoolSize", current.getCorePoolSize(),
                "maxPoolSize", current.getMaxPoolSize(),
                "queueCapacity", current.getQueueCapacity(),
                "hardCorePoolSize", hard.getCorePoolSize(),
                "hardMaxPoolSize", hard.getMaxPoolSize(),
                "hardQueueCapacity", hard.getQueueCapacity(),
                "oldestQueuedAgeMs", oldestAgeMs
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
            if (released) {
                throw new LangchainRunRejectedException("agent_run_executor_reservation_released");
            }
            this.activated = true;
        }

        private boolean release() {
            if (activated || released) {
                return false;
            }
            released = true;
            return true;
        }
    }
}
