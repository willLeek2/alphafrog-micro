package world.willfrog.agentlangchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agentlangchain.orchestration.ToolThrottleResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用公平 {@link Semaphore} 限制 executePython 的前台并发。
 *
 * <p>当前 allowlist 固定为 executePython，其他工具直接通过。permit 只保护工具调用入口，
 * 不能替代 durable capacity reservation，也不会让同步工具自动具备后台恢复能力。等待时间、
 * 超时数和执行耗时按工具累计，供观测与后续自适应使用。</p>
 *
 * <p><b>作用域（D25 / Risks 3.1.4）</b>：本类使用的是 <em>JVM 进程内</em> Semaphore；
 * 多实例部署时各实例各自计数、各自封顶，<em>不是</em>分布式容量保证，也不得宣传为
 * 「全集群封顶」。运维估算时使用公式：
 * {@code 全局许可近似 ≈ 实例数 × 每实例 maxPermits}（本类默认/配置的每实例 permits）。
 * 观测快照字段 {@code scope} 固定为 {@code "per-node"}，低基数、无用户/run 标识。</p>
 */
@Component
@Slf4j
public class LangchainToolConcurrencyThrottle {

    private final boolean enabled;
    private final Set<String> throttledTools;
    private final Semaphore semaphore;
    private final long timeoutSeconds;
    private final int maxPermits;

    // 每个工具的低基数累计指标；这里只保存计数/总量，不保存用户或 runId。
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
        // 当前仅精确匹配 executePython；注释不得误导为已经支持配置化 allowlist。
        this.throttledTools = Set.of("executePython");
        this.semaphore = new Semaphore(this.maxPermits, true); // 公平模式按等待顺序发 permit。
    }

    /**
     * 尝试为工具拿到 permit。
     *
     * @return 只有真正取得 permit 时 acquired 才为 true；调用 release 前必须检查该标志。
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
            Thread.currentThread().interrupt(); // 恢复中断位，让上层取消逻辑仍能观察到。
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
     * 释放 permit；ToolThrottleResult 内部做一次性标记，重复 finally 不会多释放。
     */
    public void release(ToolThrottleResult result) {
        if (result == null || !result.acquired()) return;
        if (result.markReleased()) {
            semaphore.release();
        }
    }

    /**
     * 记录所有工具的执行耗时作为基线；等待/超时指标只覆盖被限流工具，这是有意的不对称。
     */
    public void recordExecution(String toolName, long durationMs) {
        if (durationMs <= 0) return;
        execCount.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        execMsTotal.computeIfAbsent(toolName, k -> new AtomicLong()).addAndGet(durationMs);
    }

    // ── 观测快照：返回副本/标量，调用方不能修改 semaphore 状态 ──

    /**
     * 返回本实例工具前台限流观测快照。
     *
     * <p>稳定字段 {@code scope} 恒为 {@code "per-node"}，标明计数仅覆盖本 JVM；
     * 多实例时请按「实例数 × 每实例 maxPermits」估算全局许可，勿当作集群配额。</p>
     */
    public Map<String, Object> throttleMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("scope", "per-node");
        metrics.put("enabled", enabled);
        metrics.put("maxPermits", maxPermits);
        metrics.put("availablePermits", semaphore.availablePermits());
        metrics.put("queueLength", semaphore.getQueueLength());
        metrics.put("timeoutSeconds", timeoutSeconds);
        metrics.put("timeoutCounts", toLongMap(timeoutCounts));
        metrics.put("waitMsTotal", toLongMap(waitMsTotal));
        metrics.put("waitCount", toLongMap(waitCount));
        metrics.put("execMsTotal", toLongMap(execMsTotal));
        metrics.put("execCount", toLongMap(execCount));
        return metrics;
    }

    private static Map<String, Long> toLongMap(Map<String, AtomicLong> source) {
        Map<String, Long> result = new ConcurrentHashMap<>();
        source.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

}
