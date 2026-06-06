package world.willfrog.agentlangchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agentlangchain.orchestration.ToolThrottleResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sandbox tool concurrency throttle using a fair {@link Semaphore}.
 *
 * <p>Only tools in the allowlist are throttled; all others pass through.
 * Metrics (acquire wait, timeout count, exec duration) are collected per tool
 * for consumption by adaptive concurrency and observability.</p>
 *
 * <p><b>Allowlist:</b> hardcoded to {@code ["executePython"]} in Phase 1a.
 * Phase 1b will make this configurable via {@code agent.langchain.tool.throttle.enabledTools}.</p>
 */
@Component
@Slf4j
public class LangchainToolConcurrencyThrottle {

    private final boolean enabled;
    private final Set<String> throttledTools;
    private final Semaphore semaphore;
    private final long timeoutSeconds;
    private final int maxPermits;

    // per-tool metrics
    private final Map<String, AtomicLong> timeoutCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> waitMsTotal = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> waitCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> execMsTotal = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> execCount = new ConcurrentHashMap<>();

    public LangchainToolConcurrencyThrottle(
            @Value("${agent.langchain.tool.throttle.enabled:false}") boolean enabled,
            @Value("${agent.langchain.tool.throttle.sandbox-max-concurrent:20}") int maxConcurrent,
            @Value("${agent.langchain.tool.throttle.timeout-seconds:60}") long timeoutSeconds) {
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
        this.maxPermits = Math.max(1, maxConcurrent);
        // Phase 1a: exact-match allowlist. Phase 1b: configurable via agent.langchain.tool.throttle.enabledTools
        this.throttledTools = Set.of("executePython");
        this.semaphore = new Semaphore(this.maxPermits, true); // fair mode
    }

    /**
     * Attempt to acquire a permit for the given tool.
     *
     * @return result with {@code acquired=true} iff a permit was obtained.
     *         Always check {@code result.acquired()} before calling {@link #release}.
     */
    public ToolThrottleResult tryAcquire(String toolName) {
        if (!enabled || !throttledTools.contains(toolName)) {
            return ToolThrottleResult.notThrottled(toolName);
        }

        long waitStartedAt = System.currentTimeMillis();
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // restore interrupt flag
            long waitedMs = System.currentTimeMillis() - waitStartedAt;
            timeoutCounts.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
            log.warn("Tool throttle interrupted: tool={} waitMs={} availablePermits={}",
                    toolName, waitedMs, semaphore.availablePermits());
            return ToolThrottleResult.interrupted(toolName, waitedMs);
        }

        long waitedMs = System.currentTimeMillis() - waitStartedAt;
        waitMsTotal.computeIfAbsent(toolName, k -> new AtomicLong()).addAndGet(waitedMs);
        waitCount.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();

        if (!acquired) {
            timeoutCounts.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
            log.warn("Tool throttle timeout: tool={} waitMs={} availablePermits={} queueLength={}",
                    toolName, waitedMs, semaphore.availablePermits(), semaphore.getQueueLength());
            return ToolThrottleResult.timeout(toolName, waitedMs, semaphore.availablePermits());
        }

        return ToolThrottleResult.acquired(toolName);
    }

    /**
     * Release a permit. Call only when {@code result.acquired() == true}.
     */
    public void release(ToolThrottleResult result) {
        if (result == null || !result.acquired()) return;
        if (result.markReleased()) {
            semaphore.release();
        }
    }

    /**
     * Record tool execution duration after completion (for metrics).
     * Called for ALL tools (not just throttled ones) to provide baseline
     * exec duration data. This is intentionally asymmetric with wait/timeout
     * metrics which only cover throttled tools.
     */
    public void recordExecution(String toolName, long durationMs) {
        if (durationMs <= 0) return;
        execCount.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        execMsTotal.computeIfAbsent(toolName, k -> new AtomicLong()).addAndGet(durationMs);
    }

    // ── Metric accessors (for observability / D4) ──

    public Map<String, Object> throttleMetrics() {
        return Map.of(
                "enabled", enabled,
                "maxPermits", maxPermits,
                "availablePermits", semaphore.availablePermits(),
                "queueLength", semaphore.getQueueLength(),
                "timeoutSeconds", timeoutSeconds,
                "timeoutCounts", toLongMap(timeoutCounts),
                "waitMsTotal", toLongMap(waitMsTotal),
                "waitCount", toLongMap(waitCount),
                "execMsTotal", toLongMap(execMsTotal),
                "execCount", toLongMap(execCount)
        );
    }

    private static Map<String, Long> toLongMap(Map<String, AtomicLong> source) {
        Map<String, Long> result = new ConcurrentHashMap<>();
        source.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

}
