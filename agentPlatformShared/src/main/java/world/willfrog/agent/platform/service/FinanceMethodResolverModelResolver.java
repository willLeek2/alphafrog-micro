package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>resolver 配置（default route 与 catalog/request/response/candidate/maxAttempts 边界）的唯一读取口径是
 * {@link #effectiveResolverConfig()}：本地文件（agent-llm.local.json）显式包含顶层
 * {@code financeMethodResolver} 节时整节取本地值（含显式 {@code "enabled": false} 这类默认值语义的显式配置），
 * 否则回退 Spring/Nacos 静态配置。路由与边界必须读同一份 effective config，避免取到不同快照。</p>
 */
@Service
@Slf4j
public class FinanceMethodResolverModelResolver {

    /** 本地配置中 resolver 配置节的顶层键名。 */
    public static final String LOCAL_SECTION = "financeMethodResolver";

    public enum ModelSource {
        STAGE_CONFIG,
        DEFAULT_ROUTE
    }

    public record ResolvedStageModel(StageLlmConfig config, ModelSource source) {
    }

    private final AgentLlmProperties llmProperties;
    private final AgentLlmLocalConfigLoader localConfigLoader;

    public FinanceMethodResolverModelResolver(AgentLlmProperties llmProperties) {
        this(llmProperties, null);
    }

    @Autowired
    public FinanceMethodResolverModelResolver(AgentLlmProperties llmProperties,
                                              AgentLlmLocalConfigLoader localConfigLoader) {
        this.llmProperties = llmProperties;
        this.localConfigLoader = localConfigLoader;
    }

    public Optional<ResolvedStageModel> resolve() {
        return resolveCandidates().stream().findFirst();
    }

    /**
     * resolver 配置的唯一读取口径：本地文件显式包含 {@code financeMethodResolver} 顶层节时取本地节，
     * 否则回退 Spring/Nacos 静态配置。调用方（路由与边界）必须使用同一返回值。
     *
     * <p>单次 {@link AgentLlmLocalConfigLoader#currentSnapshot()} 读取同时取得 config 与显式节集合，
     * 不组合两个 accessor，refresh 窗口内也不会出现旧 config 配新 sections 的错配快照。</p>
     */
    public AgentLlmProperties.FinanceMethodResolver effectiveResolverConfig() {
        AgentLlmLocalConfigLoader.LocalConfigSnapshot snapshot =
                localConfigLoader == null ? null : localConfigLoader.currentSnapshot();
        AgentLlmProperties local = snapshot == null ? null : snapshot.config();
        if (local != null && snapshot.topLevelSections().contains(LOCAL_SECTION)
                && local.getFinanceMethodResolver() != null) {
            return local.getFinanceMethodResolver();
        }
        return llmProperties == null ? null : llmProperties.getFinanceMethodResolver();
    }

    /**
     * 按优先级返回全部可用路由候选：dedicated stage 优先，其次 server default route。
     * 调用方应逐个尝试构建；只有全部不可用时才允许降级为技术失败——严禁继承 execution 大模型。
     */
    public List<ResolvedStageModel> resolveCandidates() {
        return resolveCandidates(effectiveResolverConfig());
    }

    /**
     * 同上，但 default route 腿读取调用方提供的 effective config（与本类
     * {@link #effectiveResolverConfig()} 同源）；dedicated stage 腿始终来自 run 上下文。
     */
    public List<ResolvedStageModel> resolveCandidates(AgentLlmProperties.FinanceMethodResolver resolverConfig) {
        List<ResolvedStageModel> candidates = new ArrayList<>(2);
        RunStageConfig stageConfig = AgentContext.getStageConfig();
        if (stageConfig != null && stageConfig.getFinanceMethodResolver() != null
                && stageConfig.getFinanceMethodResolver().isValid()) {
            candidates.add(new ResolvedStageModel(stageConfig.getFinanceMethodResolver(), ModelSource.STAGE_CONFIG));
        }

        if (resolverConfig != null && resolverConfig.getDefaultRoute() != null
                && Boolean.TRUE.equals(resolverConfig.getDefaultRoute().getEnabled())) {
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
