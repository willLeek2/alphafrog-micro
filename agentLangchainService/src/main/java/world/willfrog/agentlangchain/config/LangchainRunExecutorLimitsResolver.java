package world.willfrog.agentlangchain.config;

import lombok.extern.slf4j.Slf4j;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

/**
 * 合并静态硬上限、本地热配置和自适应 core 覆盖，产出调度器每次准入使用的限制快照。
 *
 * <p>hardLimits 在启动时冻结，运行期配置只能在硬上限内缩放；adaptiveCoreOverride 只改
 * core，不得扩大 max 或 queue。这样运维热调节和延迟自适应都不能突破实例启动时的安全边界。</p>
 */
@Component
@Slf4j
public class LangchainRunExecutorLimitsResolver {

    private final AgentLlmLocalConfigLoader configLoader;
    private final LangchainRunExecutorLimits hardLimits;
    /** null 表示不用自适应覆盖；volatile 保证调度线程立即看到控制器的新值。 */
    private volatile Integer adaptiveCoreOverride;

    public LangchainRunExecutorLimitsResolver(
            @Value("${agent.langchain.run.executor.core-pool-size:4}") int defaultCorePoolSize,
            @Value("${agent.langchain.run.executor.max-pool-size:8}") int defaultMaxPoolSize,
            @Value("${agent.langchain.run.executor.queue-capacity:50}") int defaultQueueCapacity,
            @Value("${agent.langchain.run.executor.thread-name-prefix:agent-langchain-run-}") String defaultThreadNamePrefix,
            AgentLlmLocalConfigLoader configLoader) {
        this.configLoader = configLoader;
        AgentLlmProperties.ExecutorConfig executorConfig = configLoader.current()
                .map(AgentLlmProperties::getExecutor)
                .orElse(null);
        this.hardLimits = resolveHardLimits(
                executorConfig,
                defaultCorePoolSize,
                defaultMaxPoolSize,
                defaultQueueCapacity,
                defaultThreadNamePrefix);
        log.info("Langchain run hard executor limits initialized: {}", hardLimits.summary());
    }

    public LangchainRunExecutorLimits hardLimits() {
        return hardLimits;
    }

    public LangchainRunExecutorLimits currentLimits() {
        // 每次准入重新读本地配置，使 current.parallel.current 的缩放无需重启。
        AgentLlmProperties.ExecutorConfig executorConfig = configLoader.current()
                .map(AgentLlmProperties::getExecutor)
                .orElse(null);
        AgentLlmProperties.ExecutorConfig currentConfig = null;
        if (executorConfig != null && executorConfig.getParallel() != null) {
            currentConfig = executorConfig.getParallel().getCurrent();
            if (currentConfig == null) {
                return applyOverrideIfSet(hardLimits);
            }
        }
        if (currentConfig == null) {
            currentConfig = executorConfig;
        }
        return applyOverrideIfSet(clampCurrent(currentConfig));
    }

    private LangchainRunExecutorLimits applyOverrideIfSet(LangchainRunExecutorLimits base) {
        Integer override = adaptiveCoreOverride;
        if (override == null) return base;
        // 自适应只能收缩/恢复 core，不能越过硬 core 或当前 max。
        int core = Math.max(1, Math.min(override, Math.min(hardLimits.getCorePoolSize(), base.getMaxPoolSize())));
        return new LangchainRunExecutorLimits(core, base.getMaxPoolSize(), base.getQueueCapacity(), base.getThreadNamePrefix());
    }

    /**
     * 暴露启动冻结 hard 与当前配置 requested 之间的差距，供 health/snapshot 端点查询。
     *
     * <p>单次读取热配置，同时计算 requested 与 effective，避免快照中
     * A 版本的 requested 与 B 版本的 effective 拼在一起。</p>
     *
     * <p>restartRequired 只由 requested 超过 hard 或 threadNamePrefix 变化决定；
     * adaptive core 覆盖造成的临时缩放不触发 restartRequired，但会通过
     * adaptiveOverride 字段单独暴露。</p>
     */
    public Map<String, Object> getHardVersusEffectiveGap() {
        // 一次读取热配置，同时用于 requested 和 effective 的计算
        AgentLlmProperties.ExecutorConfig executorConfig = configLoader.current()
                .map(AgentLlmProperties::getExecutor)
                .orElse(null);
        AgentLlmProperties.ExecutorConfig currentConfig = null;
        if (executorConfig != null && executorConfig.getParallel() != null) {
            currentConfig = executorConfig.getParallel().getCurrent();
        }
        if (currentConfig == null) {
            currentConfig = executorConfig;
        }

        int requestedCore = valueOrDefault(currentConfig == null ? null : currentConfig.getCorePoolSize(),
                hardLimits.getCorePoolSize());
        int requestedMax = valueOrDefault(currentConfig == null ? null : currentConfig.getMaxPoolSize(),
                hardLimits.getMaxPoolSize());
        int requestedQueue = valueOrDefault(currentConfig == null ? null : currentConfig.getQueueCapacity(),
                hardLimits.getQueueCapacity());
        String requestedPrefix = firstNonBlank(
                currentConfig == null ? null : currentConfig.getThreadNamePrefix(),
                hardLimits.getThreadNamePrefix());

        // 用已捕获的 currentConfig 计算 effective，不二次读取热配置
        LangchainRunExecutorLimits clamped = clampCurrent(currentConfig);
        LangchainRunExecutorLimits effective = applyOverrideIfSet(clamped);

        Integer adaptiveOverride = this.adaptiveCoreOverride;
        // adaptive 是否实际改变了 core（overridden effective ≠ un-overridden clamped）
        boolean adaptiveAdjusted = adaptiveOverride != null
                && effective.getCorePoolSize() != clamped.getCorePoolSize();

        // clamped = effective 与 requested 不同（捕获硬门、跨维度 core-to-max、负数归一）
        Map<String, Object> coreDim = clampedDim(hardLimits.getCorePoolSize(), requestedCore,
                effective.getCorePoolSize());

        Map<String, Object> maxDim = clampedDim(hardLimits.getMaxPoolSize(), requestedMax,
                effective.getMaxPoolSize());

        Map<String, Object> queueDim = clampedDim(hardLimits.getQueueCapacity(), requestedQueue,
                effective.getQueueCapacity());

        boolean prefixChanged = !requestedPrefix.equals(hardLimits.getThreadNamePrefix());
        Map<String, Object> prefixDim = new LinkedHashMap<>();
        prefixDim.put("hard", hardLimits.getThreadNamePrefix());
        prefixDim.put("requested", requestedPrefix);
        prefixDim.put("effective", effective.getThreadNamePrefix());
        prefixDim.put("clamped", prefixChanged);

        // restartRequired 只看 requested 是否超过 hard 或前缀已变化；
        // adaptive core 覆盖不触发 restartRequired。
        boolean restartRequired = requestedCore > hardLimits.getCorePoolSize()
                || requestedMax > hardLimits.getMaxPoolSize()
                || requestedQueue > hardLimits.getQueueCapacity()
                || prefixChanged;

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("corePoolSize", coreDim);
        dimensions.put("maxPoolSize", maxDim);
        dimensions.put("queueCapacity", queueDim);
        dimensions.put("threadNamePrefix", prefixDim);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("restartRequired", restartRequired);
        result.put("dimensions", dimensions);
        if (adaptiveOverride != null) {
            result.put("adaptiveOverride", adaptiveOverride);
        }
        if (adaptiveAdjusted) {
            result.put("adaptiveAdjusted", true);
        }
        return result;
    }

    private Map<String, Object> clampedDim(int hard, int requested, int effective) {
        Map<String, Object> dim = new LinkedHashMap<>();
        dim.put("hard", hard);
        dim.put("requested", requested);
        dim.put("effective", effective);
        // clamped 捕获一切形式的静态夹断：硬门、跨维度 core-to-max、负数/零归一
        dim.put("clamped", effective != requested);
        return dim;
    }

    /** 设置运行期 core 覆盖；传 null 清除覆盖并回到当前配置值。 */
    public void setAdaptiveCoreOverride(Integer core) {
        this.adaptiveCoreOverride = core;
    }

    public Integer getAdaptiveCoreOverride() {
        return adaptiveCoreOverride;
    }

    private LangchainRunExecutorLimits resolveHardLimits(AgentLlmProperties.ExecutorConfig executorConfig,
                                                         int defaultCorePoolSize,
                                                         int defaultMaxPoolSize,
                                                         int defaultQueueCapacity,
                                                         String defaultThreadNamePrefix) {
        // parallel.hard 优先；旧配置没有分层时兼容直接使用 executor 根节点。
        AgentLlmProperties.ExecutorConfig hardConfig = null;
        if (executorConfig != null && executorConfig.getParallel() != null) {
            hardConfig = executorConfig.getParallel().getHard();
        }
        if (hardConfig == null) {
            hardConfig = executorConfig;
        }
        int core = valueOrDefault(hardConfig == null ? null : hardConfig.getCorePoolSize(), defaultCorePoolSize);
        int max = valueOrDefault(hardConfig == null ? null : hardConfig.getMaxPoolSize(), defaultMaxPoolSize);
        int queue = valueOrDefault(hardConfig == null ? null : hardConfig.getQueueCapacity(), defaultQueueCapacity);
        String prefix = firstNonBlank(
                hardConfig == null ? null : hardConfig.getThreadNamePrefix(),
                executorConfig == null ? null : executorConfig.getThreadNamePrefix(),
                defaultThreadNamePrefix);
        return new LangchainRunExecutorLimits(core, max, queue, prefix);
    }

    private LangchainRunExecutorLimits clampCurrent(AgentLlmProperties.ExecutorConfig currentConfig) {
        // current 可以缩小，但所有维度都必须夹在启动时 hardLimits 内。
        int requestedCore = valueOrDefault(currentConfig == null ? null : currentConfig.getCorePoolSize(),
                hardLimits.getCorePoolSize());
        int requestedMax = valueOrDefault(currentConfig == null ? null : currentConfig.getMaxPoolSize(),
                hardLimits.getMaxPoolSize());
        int requestedQueue = valueOrDefault(currentConfig == null ? null : currentConfig.getQueueCapacity(),
                hardLimits.getQueueCapacity());
        String requestedPrefix = firstNonBlank(currentConfig == null ? null : currentConfig.getThreadNamePrefix(),
                hardLimits.getThreadNamePrefix());

        int max = Math.min(Math.max(1, requestedMax), hardLimits.getMaxPoolSize());
        int core = Math.min(Math.max(1, requestedCore), Math.min(max, hardLimits.getCorePoolSize()));
        int queue = Math.min(Math.max(0, requestedQueue), hardLimits.getQueueCapacity());
        // threadNamePrefix 启动冻结，hot-reload 无法重命名已创建线程
        String prefix = hardLimits.getThreadNamePrefix();
        if (core != requestedCore || max != requestedMax || queue != requestedQueue
                || !requestedPrefix.equals(prefix)) {
            log.warn("Langchain run current executor limits exceed hard gate, clamped from core={}, max={}, queue={}, prefix={} to {}",
                    requestedCore, requestedMax, requestedQueue, requestedPrefix,
                    new LangchainRunExecutorLimits(core, max, queue, prefix).summary());
        }
        return new LangchainRunExecutorLimits(core, max, queue, prefix);
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
