package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * 按工具权重在单节点内发放并行许可。
 *
 * <p><b>作用域（D25 / Risks 3.1.4）</b>：内部使用的是 <em>JVM 进程内</em>
 * {@link Semaphore}；多实例部署时各节点各自按本节点 {@code maxWeight} 计数，
 * <em>不是</em>分布式容量保证，也不得宣传为「全集群封顶」。运维估算时使用公式：
 * {@code 全局权重许可近似 ≈ 实例数 × 每节点 maxWeight}。
 * {@code checkParallelLimits} / {@code searchWeb} 等豁免路径不占权重；本类本轮不新增
 * 公共观测 endpoint，权重/permit/超时/缓存/credit/路由行为保持不变。</p>
 */
@Service
@RequiredArgsConstructor
public class ToolWeightedLimitService {

    private static final int DEFAULT_MAX_WEIGHT = 12;
    private static final int DEFAULT_TOOL_WEIGHT = 2;

    private final AgentLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    private volatile Semaphore semaphore;
    private volatile int configuredMaxWeight = -1;

    public Optional<WeightLease> tryAcquire(String toolName, Map<String, Object> params) {
        AgentLlmProperties.ToolWeightedLimit config = resolveConfig();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return Optional.of(WeightLease.noop());
        }
        if ("searchWeb".equals(toolName) || "checkParallelLimits".equals(toolName)) {
            return Optional.of(WeightLease.noop());
        }

        AgentLlmProperties.ToolWeightEntry entry = config.getTools().get(nvl(toolName));
        if (entry == null) {
            return Optional.of(WeightLease.noop());
        }
        if (entry.isRequiresAdjFactorEnabled() && !isAdjFactorEnabled()) {
            return Optional.of(WeightLease.noop());
        }

        int unitWeight = positiveOrDefault(entry.getWeight(), positiveOrDefault(config.getDefaultWeight(), DEFAULT_TOOL_WEIGHT));
        int maxBatchItems = positiveOrDefault(entry.getMaxBatchItems(), 1);
        int batchItems = Math.max(1, countBatchItems(toolName, params));
        int effectiveWeight = unitWeight * Math.min(batchItems, maxBatchItems);
        if (effectiveWeight <= 0) {
            return Optional.of(WeightLease.noop());
        }

        Semaphore activeSemaphore = resolveSemaphore(config);
        if (!activeSemaphore.tryAcquire(effectiveWeight)) {
            return Optional.empty();
        }
        return Optional.of(new WeightLease(effectiveWeight, activeSemaphore));
    }

    public int previewEffectiveWeight(String toolName, Map<String, Object> params) {
        AgentLlmProperties.ToolWeightedLimit config = resolveConfig();
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return 0;
        }
        AgentLlmProperties.ToolWeightEntry entry = config.getTools().get(nvl(toolName));
        if (entry == null) {
            return 0;
        }
        int unitWeight = positiveOrDefault(entry.getWeight(), positiveOrDefault(config.getDefaultWeight(), DEFAULT_TOOL_WEIGHT));
        int maxBatchItems = positiveOrDefault(entry.getMaxBatchItems(), 1);
        int batchItems = Math.max(1, countBatchItems(toolName, params));
        return unitWeight * Math.min(batchItems, maxBatchItems);
    }

    private AgentLlmProperties.ToolWeightedLimit resolveConfig() {
        if (localConfigLoader != null) {
            AgentLlmProperties.ToolWeightedLimit local = localConfigLoader.current()
                    .map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getParallel)
                    .map(AgentLlmProperties.Parallel::getToolWeightedLimit)
                    .orElse(null);
            if (local != null) {
                return local;
            }
        }
        if (llmProperties.getRuntime() != null
                && llmProperties.getRuntime().getParallel() != null
                && llmProperties.getRuntime().getParallel().getToolWeightedLimit() != null) {
            return llmProperties.getRuntime().getParallel().getToolWeightedLimit();
        }
        return new AgentLlmProperties.ToolWeightedLimit();
    }

    private Semaphore resolveSemaphore(AgentLlmProperties.ToolWeightedLimit config) {
        int maxWeight = positiveOrDefault(config.getMaxWeight(), DEFAULT_MAX_WEIGHT);
        if (semaphore == null || configuredMaxWeight != maxWeight) {
            synchronized (this) {
                if (semaphore == null || configuredMaxWeight != maxWeight) {
                    semaphore = new Semaphore(maxWeight, true);
                    configuredMaxWeight = maxWeight;
                }
            }
        }
        return semaphore;
    }

    private boolean isAdjFactorEnabled() {
        if (localConfigLoader != null) {
            Boolean local = localConfigLoader.current()
                    .map(AgentLlmProperties::getRuntime)
                    .map(AgentLlmProperties.Runtime::getExecution)
                    .map(AgentLlmProperties.Execution::getAdjFactorEnabled)
                    .orElse(null);
            if (local != null) {
                return local;
            }
        }
        if (llmProperties.getRuntime() != null && llmProperties.getRuntime().getExecution() != null) {
            Boolean enabled = llmProperties.getRuntime().getExecution().getAdjFactorEnabled();
            if (enabled != null) {
                return enabled;
            }
        }
        return false;
    }

    private int countBatchItems(String toolName, Map<String, Object> params) {
        Map<String, Object> source = params == null ? Map.of() : params;
        return switch (nvl(toolName)) {
            case "searchAssetInfo", "searchStock", "searchFund", "searchIndex" ->
                    countBatchValues(firstNonBlank(source, "query", "keyword", "arg0"));
            case "getExchangeAssetDaily", "getStockDaily", "getIndexDaily" ->
                    countBatchValues(firstNonBlank(source, "tsCode", "ts_code", "code", "stock_code", "index_code", "arg0"));
            case "isTradingDay" ->
                    countBatchValues(firstNonBlank(source, "date", "dates", "tradeDate", "tradeDates", "trade_date", "trade_dates", "arg0"));
            default -> 1;
        };
    }

    private int countBatchValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        List<String> values = parseBatchValues(raw.trim());
        return Math.max(1, values.size());
    }

    private List<String> parseBatchValues(String text) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<?> arr = objectMapper.readValue(text, List.class);
                for (Object item : arr) {
                    String value = item == null ? "" : String.valueOf(item).trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            } catch (Exception ignore) {
                // fallback to split mode
            }
        }
        if (values.isEmpty()) {
            for (String part : text.split("\\|")) {
                String value = part == null ? "" : part.trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            values.add(text);
        }
        return new ArrayList<>(values);
    }

    private String firstNonBlank(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    public static final class WeightLease {
        private static final WeightLease NOOP = new WeightLease(0, null);

        private final int weight;
        private final Semaphore semaphore;

        private WeightLease(int weight, Semaphore semaphore) {
            this.weight = weight;
            this.semaphore = semaphore;
        }

        static WeightLease noop() {
            return NOOP;
        }

        public void release() {
            if (weight > 0 && semaphore != null) {
                semaphore.release(weight);
            }
        }
    }
}
