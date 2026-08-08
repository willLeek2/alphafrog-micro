package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.config.StageLlmConfig;
import world.willfrog.agent.platform.context.AgentContext;

import java.util.ArrayList;
import java.util.List;
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
 * {@link #resolveCandidates()} 返回按该顺序排列的全部候选；排在前的候选构建失败时调用方继续尝试
 * 下一个，只有全部不可用才降级。
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
        return resolveCandidates().stream().findFirst();
    }

    /**
     * 按优先级返回全部可用路由候选：dedicated stage 优先，其次 server default route。
     * 调用方应逐个尝试构建；只有全部不可用时才允许降级为技术失败——严禁继承 execution 大模型。
     */
    public List<ResolvedStageModel> resolveCandidates() {
        List<ResolvedStageModel> candidates = new ArrayList<>(2);
        RunStageConfig stageConfig = AgentContext.getStageConfig();
        if (stageConfig != null && stageConfig.getFinanceMethodResolver() != null
                && stageConfig.getFinanceMethodResolver().isValid()) {
            candidates.add(new ResolvedStageModel(stageConfig.getFinanceMethodResolver(), ModelSource.STAGE_CONFIG));
        }

        AgentLlmProperties.FinanceMethodResolver resolverConfig = llmProperties == null
                ? null
                : llmProperties.getFinanceMethodResolver();
        if (resolverConfig != null && Boolean.TRUE.equals(resolverConfig.getDefaultRoute().getEnabled())) {
            StageLlmConfig route = toStageLlmConfig(resolverConfig.getDefaultRoute());
            if (route.isValid()) {
                candidates.add(new ResolvedStageModel(route, ModelSource.DEFAULT_ROUTE));
            }
        }

        return candidates;
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
