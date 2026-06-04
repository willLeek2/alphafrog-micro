package world.willfrog.agentlangchain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.service.LangchainLlmLatencyWindow;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conservative adaptive concurrency: adjusts current corePoolSize based on p90 LLM latency.
 *
 * <p>Design principles (per frog):
 * <ul>
 *   <li>Don't reduce too aggressively — floor prevents collapse under transient spikes.</li>
 *   <li>Cooldown between adjustments prevents oscillation.</li>
 *   <li>Recovery is gradual: one step at a time after sustained low latency.</li>
 *   <li>Every automatic change emits WARN log with before/after, p90, window size.</li>
 *   <li>Feature-gated via config, defaults off.</li>
 * </ul>
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
            return; // insufficient data, skip this cycle
        }

        int currentCore = limitsResolver.currentLimits().getCorePoolSize();
        int hardCore = limitsResolver.hardLimits().getCorePoolSize();
        int floor = Math.max(1, hardCore * floorPercent / 100);

        if (p90 > startReductionP90Ms) {
            // Reduce concurrency
            long elapsed = now - lastAdjustmentAt;
            if (elapsed < cooldownMs) return; // cooldown active

            int newCore = Math.max(floor, currentCore - 1);
            if (newCore < currentCore) {
                apply(newCore, "p90=" + p90 + "ms avg=" + avg + "ms window=" + windowSize
                        + " floor=" + floor + " hardCore=" + hardCore);
            }
        } else if (p90 < recoveryP90Ms && currentCore < originalCore) {
            // Gradual recovery: one step at a time back to originalCore
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

    // ── operator knobs ──

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        log.info("Adaptive concurrency enabled={}", enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }
}
