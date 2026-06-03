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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
public class LangchainRunConcurrencyScheduler {

    private final ThreadPoolTaskExecutor executor;
    private final LangchainRunExecutorLimitsResolver limitsResolver;
    private final Object lock = new Object();
    private final Queue<PendingRun> queue = new ArrayDeque<>();
    private int running;
    private int reservedQueued;

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
                return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED);
            }
            if (running <= limits.getCorePoolSize() && running < limits.getMaxPoolSize()) {
                if (!queue.isEmpty()) {
                    PendingRun pending = queue.poll();
                    reservedQueued = Math.max(0, reservedQueued - 1);
                    running++;
                    submitRunning(pending.task());
                    reservedQueued++;
                    return new Reservation(UUID.randomUUID().toString(), SlotType.QUEUED);
                }
                running++;
                return new Reservation(UUID.randomUUID().toString(), SlotType.RUNNING);
            }
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
            queue.add(new PendingRun(run, task));
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

    private enum SlotType {
        RUNNING,
        QUEUED
    }

    private record PendingRun(AgentRun run, Runnable task) {
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
