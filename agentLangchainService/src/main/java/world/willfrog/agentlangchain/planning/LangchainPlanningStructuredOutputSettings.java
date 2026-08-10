package world.willfrog.agentlangchain.planning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.workflow.StructuredPlanningSupport;

import java.util.Map;
import java.util.Optional;

/**
 * Reads Nacos/local planning structured-output flags for langchain planner parity with legacy {@code TodoPlanner}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LangchainPlanningStructuredOutputSettings {

    private static final int MAX_PLANNING_ATTEMPTS = 10;

    private final AgentLlmProperties llmProperties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    public boolean structuredEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getEnabled)
                .orElse(null);
        return base == null || base;
    }

    public boolean structuredStrict() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrict)
                .orElse(null);
        return Boolean.TRUE.equals(base);
    }

    /**
     * OpenRouter: do not set {@code provider.require_parameters=true} for planning — it narrows routing
     * to providers that natively support every request field (often only deepseek for Kimi), which
     * conflicts with explicit client provider order.
     */
    public boolean requireProviderParameters(String planningEndpointName) {
        if (isOpenRouterPlanningEndpoint(planningEndpointName)) {
            return false;
        }
        return requireProviderParametersFromConfig();
    }

    private boolean requireProviderParametersFromConfig() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getRequireProviderParameters)
                .orElse(null);
        return base == null || base;
    }

    private static boolean isOpenRouterPlanningEndpoint(String planningEndpointName) {
        return planningEndpointName != null
                && "openrouter".equalsIgnoreCase(planningEndpointName.trim());
    }

    public boolean allowProviderFallbacks() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getAllowProviderFallbacks)
                .orElse(null);
        return base != null && base;
    }

    /**
     * JSON schema aligned with legacy {@code StructuredPlanningSupport#todoPlanningJsonSchema()}.
     */
    public boolean strategyStageEnabled() {
        Optional<Boolean> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyStageEnabled);
        if (local.isPresent()) {
            return Boolean.TRUE.equals(local.get());
        }
        Boolean base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyStageEnabled)
                .orElse(null);
        return base == null || base;
    }

    public int strategyMaxDetailLength() {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyMaxDetailLength)
                .orElse(0);
        if (local > 0) {
            return local;
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getStrategyMaxDetailLength)
                .orElse(0);
        return base > 0 ? base : 500;
    }

    /**
     * 读取规划结构校验的最大尝试次数。
     *
     * <p>热加载配置优先于 Spring/Nacos 静态配置；缺失或非法值回落到调用方默认值。
     * 为避免错误配置造成无界模型调用，最终值限制在 1 到 10 之间。</p>
     */
    public int planningMaxAttempts(int defaultValue) {
        int fallback = clamp(defaultValue, 1, MAX_PLANNING_ATTEMPTS);
        Optional<Integer> local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getMaxAttempts);
        if (local.isPresent()) {
            return normalizePlanningMaxAttempts(local.get(), fallback, "local");
        }
        Integer base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getStructuredOutput)
                .map(AgentLlmProperties.StructuredOutput::getMaxAttempts)
                .orElse(null);
        if (base == null) {
            return fallback;
        }
        return normalizePlanningMaxAttempts(base, fallback, "static");
    }

    private int normalizePlanningMaxAttempts(int configured, int fallback, String source) {
        if (configured <= 0 || configured > MAX_PLANNING_ATTEMPTS) {
            log.warn("planning_max_attempts_invalid configured={} source={} fallback={}",
                    configured, source, fallback);
            return fallback;
        }
        return configured;
    }

    public int resolveMaxTodos(int defaultMaxTodos) {
        int local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (local > 0) {
            return clamp(local, 1, 50);
        }
        int base = Optional.ofNullable(llmProperties.getRuntime())
                .map(AgentLlmProperties.Runtime::getPlanning)
                .map(AgentLlmProperties.Planning::getMaxTodos)
                .orElse(0);
        if (base > 0) {
            return clamp(base, 1, 50);
        }
        return clamp(defaultMaxTodos, 1, 50);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public Map<String, Object> todoPlanningJsonSchema() {
        return StructuredPlanningSupport.todoPlanningJsonSchema();
    }
}
