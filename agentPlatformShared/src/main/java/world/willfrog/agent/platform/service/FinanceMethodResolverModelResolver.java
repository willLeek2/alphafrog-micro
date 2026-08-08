package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves which LLM config the financial MethodSpec resolver should use.
 *
 * <p>Routing order (strict, never silently inherit the execution model):
 * <ol>
 *   <li>Explicit {@code stage_config.finance_method_resolver} from the run context.</li>
 *   <li>Server-configured lightweight default route ({@code agent.llm.financeMethodResolver.defaultRoute}).</li>
 *   <li>Nothing — caller must fail open with {@code RESOLVER_UNAVAILABLE}.</li>
 * </ol>
 */
@Service
@Slf4j
public class FinanceMethodResolverModelResolver {

    public enum ModelSource {
        STAGE_CONFIG,
        DEFAULT_ROUTE
    }

    public record ResolvedStageModel(StageLlmConfig config, ModelSource source) {
    }

    private final AgentLlmProperties llmProperties;

    public FinanceMethodResolverModelResolver(AgentLlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    public Optional<ResolvedStageModel> resolve() {
        RunStageConfig stageConfig = AgentContext.getStageConfig();
        if (stageConfig != null && stageConfig.getFinanceMethodResolver() != null
                && stageConfig.getFinanceMethodResolver().isValid()) {
            return Optional.of(new ResolvedStageModel(stageConfig.getFinanceMethodResolver(), ModelSource.STAGE_CONFIG));
        }

        AgentLlmProperties.FinanceMethodResolver resolverConfig = llmProperties == null
                ? null
                : llmProperties.getFinanceMethodResolver();
        if (resolverConfig != null && Boolean.TRUE.equals(resolverConfig.getDefaultRoute().getEnabled())) {
            StageLlmConfig route = toStageLlmConfig(resolverConfig.getDefaultRoute());
            if (route.isValid()) {
                return Optional.of(new ResolvedStageModel(route, ModelSource.DEFAULT_ROUTE));
            }
        }

        return Optional.empty();
    }

    private static StageLlmConfig toStageLlmConfig(AgentLlmProperties.FinanceMethodResolver.DefaultRoute route) {
        StageLlmConfig config = new StageLlmConfig();
        config.setEndpointName(route.getEndpointName());
        config.setModelName(route.getModelName());
        config.setProviderOrder(route.getProviderOrder());
        config.setTemperature(route.getTemperature());
        config.setMaxTokens(route.getMaxTokens());
        return config;
    }
}
