package world.willfrog.agentlangchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.service.LangchainLlmLatencyWindow;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 LLM p90 延迟保守调节 Run core 并发。
 *
 * <p>高延迟时每轮最多减 1 且不低于 floor；低延迟时每轮最多恢复 1，直到启动时 core。
 * 两次调整之间必须经过 cooldown，样本不足时不决策，避免短时尖峰造成并发振荡。功能默认
 * 关闭，每次自动变化都用 WARN 记录前后值、p90、均值和窗口大小。</p>
 */
@Component
@Slf4j
public class LangchainAdaptiveConcurrencyController {

    private final LangchainRunExecutorLimitsResolver limitsResolver;
    private final LangchainLlmLatencyWindow latencyWindow;

    private final AtomicBoolean enabled;
    private final int floorPercent;
    private final int startReductionP90Ms;
    private final int recoveryP90Ms;
    private final long cooldownMs;

    private volatile long lastAdjustmentAt;
    private final int originalCore;

    public LangchainAdaptiveConcurrencyController(
            LangchainRunExecutorLimitsResolver limitsResolver,
            LangchainLlmLatencyWindow latencyWindow,
            @Value("${agent.langchain.adaptive.enabled:false}") boolean enabled,
            @Value("${agent.langchain.adaptive.floor-percent:70}") int floorPercent,
            @Value("${agent.langchain.adaptive.start-reduction-p90-ms:30000}") int startReductionP90Ms,
            @Value("${agent.langchain.adaptive.recovery-p90-ms:15000}") int recoveryP90Ms,
            @Value("${agent.langchain.adaptive.cooldown-seconds:120}") int cooldownSeconds) {
        this.limitsResolver = limitsResolver;
        this.latencyWindow = latencyWindow;
        this.enabled = new AtomicBoolean(enabled);
        this.floorPercent = Math.min(90, Math.max(30, floorPercent));
        this.startReductionP90Ms = startReductionP90Ms;
        this.recoveryP90Ms = recoveryP90Ms;
        this.cooldownMs = Math.max(30_000, cooldownSeconds * 1000L);
        this.originalCore = limitsResolver.currentLimits().getCorePoolSize();
    }

    @Scheduled(fixedDelayString = "${agent.langchain.adaptive.check-interval-ms:30000}")
    public void evaluate() {
        if (!enabled.get()) return;

        long now = System.currentTimeMillis();
        long p90 = latencyWindow.p90();
        long avg = latencyWindow.avg();
        int windowSize = latencyWindow.size();

        if (p90 <= 0 || windowSize < 3) {
            return; // 少于三个有效样本不能代表稳定延迟，跳过本轮。
        }

        int currentCore = limitsResolver.currentLimits().getCorePoolSize();
        int hardCore = limitsResolver.hardLimits().getCorePoolSize();
        int floor = Math.max(1, hardCore * floorPercent / 100);

        if (p90 > startReductionP90Ms) {
            // 高延迟时逐级收缩，floor 防止瞬时抖动把服务压到不可用。
            long elapsed = now - lastAdjustmentAt;
            if (elapsed < cooldownMs) return; // 冷却期内保持当前并发，防止来回震荡。

            int newCore = Math.max(floor, currentCore - 1);
            if (newCore < currentCore) {
                apply(newCore, "p90=" + p90 + "ms avg=" + avg + "ms window=" + windowSize
                        + " floor=" + floor + " hardCore=" + hardCore);
            }
        } else if (p90 < recoveryP90Ms && currentCore < originalCore) {
            // 延迟恢复后也逐级加 1，不直接跳回原始 core。
            long elapsed = now - lastAdjustmentAt;
            if (elapsed < cooldownMs) return;

            int newCore = Math.min(originalCore, currentCore + 1);
            if (newCore > currentCore) {
                apply(newCore, "recovery step: p90=" + p90 + "ms avg=" + avg + "ms window=" + windowSize
                        + " target=" + originalCore);
            }
        }
    }

    private void apply(int newCore, String reason) {
        int oldCore = limitsResolver.currentLimits().getCorePoolSize();
        limitsResolver.setAdaptiveCoreOverride(newCore);
        log.warn("Adaptive concurrency corePoolSize {} -> {} (reason: {})",
                oldCore, newCore, reason);
        lastAdjustmentAt = System.currentTimeMillis();
    }

    // ── 运维开关：只启停控制器，不直接修改 hard/max/queue ──

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        log.info("Adaptive concurrency enabled={}", enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }
}
