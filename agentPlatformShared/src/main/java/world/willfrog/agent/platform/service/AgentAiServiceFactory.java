package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.input.ArchivingChatModel;
import world.willfrog.agent.platform.input.ArchivingHttpClientBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentAiServiceFactory {

    private final AgentLlmResolver llmResolver;
    private final AgentLlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final RawHttpLogger httpLogger;
    private final AgentRunObservabilityService observabilityService;
    private final OpenRouterCostService openRouterCostService;
    private final AgentRunEventService eventService;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final LangchainLlmLatencyWindow latencyWindow;

    @Autowired(required = false)
    private AgentRunBudgetService budgetService;

    @Value("${langchain4j.open-ai.api-key}")
    private String openAiApiKey;

    @Value("${langchain4j.open-ai.max-tokens:4096}")
    private Integer maxTokens;

    @Value("${langchain4j.open-ai.temperature:0.7}")
    private Double temperature;

    @Value("${agent.llm.openrouter.http-referer:}")
    private String openRouterHttpReferer;

    @Value("${agent.llm.openrouter.title:}")
    private String openRouterTitle;

    public ChatModel buildChatModel(String endpointName, String modelName) {
        return buildChatModelWithProviderOrder(resolveLlm(endpointName, modelName), List.of());
    }

    public AgentLlmResolver.ResolvedLlm resolveLlm(String endpointName, String modelName) {
        return llmResolver.resolve(endpointName, modelName);
    }

    public AgentLlmResolver.ResolvedLlm resolveLlmForPlanning(String endpointName, String modelName) {
        return llmResolver.resolveForPlanning(endpointName, modelName);
    }

    public ChatModel buildChatModel(AgentLlmResolver.ResolvedLlm resolved) {
        return buildChatModelWithProviderOrder(resolved, List.of());
    }

    public ChatModel buildChatModelWithTemperature(AgentLlmResolver.ResolvedLlm resolved, Double temperatureOverride) {
        return buildChatModelWithTemperature(resolved, temperatureOverride, null);
    }

    /** 托管输入入口：每次实际HTTP发送先取得保存确认，旧入口仍沿用原行为。 */
    public ChatModel buildArchivedChatModelWithTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                           Double temperatureOverride) {
        return buildChatModelWithTemperature(resolved, temperatureOverride, null, true);
    }

    /** 风险评分固定使用SDK客户端，关闭HTTP诊断正文；托管入口另行保存受限输入。 */
    public ChatModel buildPythonRiskReviewModel(AgentLlmResolver.ResolvedLlm resolved) {
        return buildPythonRiskReviewModel(resolved, false);
    }

    /** 风险审查复用原SDK约束和固定参数，每次发送前必须确认输入保存。 */
    public ChatModel buildArchivedPythonRiskReviewModel(AgentLlmResolver.ResolvedLlm resolved) {
        return buildPythonRiskReviewModel(resolved, true);
    }

    private ChatModel buildPythonRiskReviewModel(AgentLlmResolver.ResolvedLlm resolved, boolean archiveInput) {
        if (resolved == null || isDashScopeEndpoint(resolved)) {
            throw new IllegalArgumentException("Python 风险评分端点必须使用固定的 OpenAI 兼容客户端");
        }
        return buildChatModelWithTemperature(resolved, 0.0D, 256, archiveInput);
    }

    public ChatModel buildChatModelWithTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                 Double temperatureOverride,
                                                 Integer maxTokensOverride) {
        return buildChatModelWithTemperature(resolved, temperatureOverride, maxTokensOverride, false);
    }

    private ChatModel buildChatModelWithTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                  Double temperatureOverride,
                                                  Integer maxTokensOverride,
                                                  boolean archiveInput) {
        String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
        }
        double finalTemperature = temperatureOverride == null ? (temperature == null ? 0.7D : temperature) : temperatureOverride;
        int effectiveMaxTokens = resolveMaxTokens(resolved, maxTokensOverride);
        if (isDashScopeEndpoint(resolved)) {
            DashScopeChatModel model = buildDashScopeChatModel(resolved, apiKey, finalTemperature, effectiveMaxTokens);
            return archiveInput ? model.withInputArchiving() : model;
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(resolved.baseUrl())
                .modelName(resolved.modelName())
                .maxTokens(effectiveMaxTokens)
                .temperature(finalTemperature)
                // LC4j 内置 logRequests/logResponses 会打全量 HTTP body；langchain 压测时保持关闭
                .logRequests(false)
                .logResponses(false);
        if (archiveInput) {
            builder.httpClientBuilder(new ArchivingHttpClientBuilder(HttpClientBuilderLoader.loadHttpClientBuilder()));
        }

        Map<String, String> headers = buildCustomHeaders(resolved);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
        ChatModel model = builder.build();
        return archiveInput ? new ArchivingChatModel(model, resolved.endpointName()) : model;
    }

    public ChatModel buildChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                                           List<String> providerOrder,
                                                                           Double temperatureOverride) {
        return buildChatModelWithProviderOrderAndTemperature(resolved, providerOrder, temperatureOverride, null);
    }

    public ChatModel buildChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                                           List<String> providerOrder,
                                                                           Double temperatureOverride,
                                                                           Integer maxTokensOverride) {
        return buildChatModelWithProviderOrderAndTemperature(resolved, providerOrder, temperatureOverride,
                maxTokensOverride, false);
    }

    public ChatModel buildArchivedChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                                         List<String> providerOrder,
                                                                         Double temperatureOverride) {
        return buildArchivedChatModelWithProviderOrderAndTemperature(resolved, providerOrder, temperatureOverride, null);
    }

    /** 显式托管入口与原入口共用端点选择、供应商顺序和参数处理。 */
    public ChatModel buildArchivedChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                                         List<String> providerOrder,
                                                                         Double temperatureOverride,
                                                                         Integer maxTokensOverride) {
        return buildChatModelWithProviderOrderAndTemperature(resolved, providerOrder, temperatureOverride,
                maxTokensOverride, true);
    }

    private ChatModel buildChatModelWithProviderOrderAndTemperature(AgentLlmResolver.ResolvedLlm resolved,
                                                                   List<String> providerOrder,
                                                                   Double temperatureOverride,
                                                                   Integer maxTokensOverride,
                                                                   boolean archiveInput) {
        List<String> normalizedProviderOrder = sanitizeProviderOrder(providerOrder);
        int effectiveMaxTokens = resolveMaxTokens(resolved, maxTokensOverride);
        // ALP-25: 对所有端点使用 OpenRouterProviderRoutedChatModel 以支持 HTTP 捕获
        if (isDashScopeEndpoint(resolved)) {
            String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
            if (isBlank(apiKey)) {
                throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
            }
            double finalTemperature = temperatureOverride == null ? (temperature == null ? 0.7D : temperature) : temperatureOverride;
            DashScopeChatModel model = buildDashScopeChatModel(resolved, apiKey, finalTemperature, effectiveMaxTokens);
            return archiveInput ? model.withInputArchiving() : model;
        }
        if (shouldUseProviderRoutedModel(resolved)) {
            String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
            if (isBlank(apiKey)) {
                throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
            }
            double finalTemperature = temperatureOverride == null ? (temperature == null ? 0.7D : temperature) : temperatureOverride;
            Map<String, String> headers = buildCustomHeaders(resolved);
            OpenRouterProviderRoutedChatModel model = new OpenRouterProviderRoutedChatModel(
                    objectMapper,
                    resolved.baseUrl(),
                    apiKey,
                    headers,
                    resolved.modelName(),
                    finalTemperature,
                    effectiveMaxTokens,
                    normalizedProviderOrder,
                    httpLogger,
                    observabilityService,
                    openRouterCostService,
                    eventService,
                    resolved.endpointName(),
                    localConfigLoader,
                    latencyWindow
            );
            model.setBudgetService(budgetService);
            return archiveInput ? model.withInputArchiving() : model;
        }
        return buildChatModelWithTemperature(resolved, temperatureOverride, maxTokensOverride, archiveInput);
    }

    public ChatModel buildChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved, List<String> providerOrder) {
        return buildChatModelWithProviderOrder(resolved, providerOrder, null);
    }

    public ChatModel buildChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved,
                                                     List<String> providerOrder,
                                                     Integer maxTokensOverride) {
        return buildChatModelWithProviderOrder(resolved, providerOrder, maxTokensOverride, false);
    }

    public ChatModel buildArchivedChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved,
                                                             List<String> providerOrder) {
        return buildArchivedChatModelWithProviderOrder(resolved, providerOrder, null);
    }

    /** 每次发送必须先保存完整输入，构建时不读取当前线程的输入作用域。 */
    public ChatModel buildArchivedChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved,
                                                             List<String> providerOrder,
                                                             Integer maxTokensOverride) {
        return buildChatModelWithProviderOrder(resolved, providerOrder, maxTokensOverride, true);
    }

    private ChatModel buildChatModelWithProviderOrder(AgentLlmResolver.ResolvedLlm resolved,
                                                      List<String> providerOrder,
                                                      Integer maxTokensOverride,
                                                      boolean archiveInput) {
        String apiKey = isBlank(resolved.apiKey()) ? openAiApiKey : resolved.apiKey();
        if (isBlank(apiKey)) {
            throw new IllegalArgumentException("LLM api key 未配置: endpoint=" + resolved.endpointName());
        }
        List<String> normalizedProviderOrder = sanitizeProviderOrder(providerOrder);
        int effectiveMaxTokens = resolveMaxTokens(resolved, maxTokensOverride);
        // ALP-25: 对所有端点使用 OpenRouterProviderRoutedChatModel 以支持 HTTP 捕获
        if (isDashScopeEndpoint(resolved)) {
            DashScopeChatModel model = buildDashScopeChatModel(resolved, apiKey, temperature, effectiveMaxTokens);
            return archiveInput ? model.withInputArchiving() : model;
        }
        if (shouldUseProviderRoutedModel(resolved)) {
            Map<String, String> headers = buildCustomHeaders(resolved);
            OpenRouterProviderRoutedChatModel model = new OpenRouterProviderRoutedChatModel(
                    objectMapper,
                    resolved.baseUrl(),
                    apiKey,
                    headers,
                    resolved.modelName(),
                    temperature,
                    effectiveMaxTokens,
                    normalizedProviderOrder,
                    httpLogger,
                    observabilityService,
                    openRouterCostService,
                    eventService,
                    resolved.endpointName(),
                    localConfigLoader,
                    latencyWindow
            );
            model.setBudgetService(budgetService);
            return archiveInput ? model.withInputArchiving() : model;
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(resolved.baseUrl())
                .modelName(resolved.modelName())
                .maxTokens(effectiveMaxTokens)
                .temperature(temperature)
                .logRequests(false)
                .logResponses(false);
        if (archiveInput) {
            builder.httpClientBuilder(new ArchivingHttpClientBuilder(HttpClientBuilderLoader.loadHttpClientBuilder()));
        }

        Map<String, String> headers = buildCustomHeaders(resolved);
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
        }
        ChatModel model = builder.build();
        return archiveInput ? new ArchivingChatModel(model, resolved.endpointName()) : model;
    }

    private Map<String, String> buildCustomHeaders(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null || !OpenRouterProviderRoutedChatModel.isOpenRouter(
                resolved.baseUrl(), resolved.endpointName())) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        
        // 优先从热加载配置读取，其次从 @Value 注解读取
        String httpReferer = getOpenRouterHttpReferer();
        String title = getOpenRouterTitle();
        String categories = getOpenRouterCategories();
        
        // HTTP-Referer 是必需的（OpenRouter App Attribution）
        if (httpReferer != null && !httpReferer.isBlank()) {
            headers.put("HTTP-Referer", httpReferer);
        }
        // X-OpenRouter-Title 是新的标准 header，X-Title 仍兼容
        if (title != null && !title.isBlank()) {
            headers.put("X-OpenRouter-Title", title);
            // 同时发送 X-Title 以确保兼容性
            headers.put("X-Title", title);
        }
        // 可选的分类信息
        if (categories != null && !categories.isBlank()) {
            headers.put("X-OpenRouter-Categories", categories);
        }
        return headers;
    }
    
    /**
     * 获取 OpenRouter HTTP Referer，优先从热加载配置读取。
     * <p>这是 OpenRouter App Attribution 的必需字段。</p>
     */
    private String getOpenRouterHttpReferer() {
        if (localConfigLoader != null) {
            String fromConfig = localConfigLoader.current()
                    .map(cfg -> cfg.getOpenrouter())
                    .map(or -> or.getHttpReferer())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(null);
            if (fromConfig != null) {
                return fromConfig;
            }
        }
        return openRouterHttpReferer;
    }
    
    /**
     * 获取 OpenRouter Title，优先从热加载配置读取。
     * <p>建议使用新的 X-OpenRouter-Title header。</p>
     */
    private String getOpenRouterTitle() {
        if (localConfigLoader != null) {
            String fromConfig = localConfigLoader.current()
                    .map(cfg -> cfg.getOpenrouter())
                    .map(or -> or.getTitle())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(null);
            if (fromConfig != null) {
                return fromConfig;
            }
        }
        return openRouterTitle;
    }
    
    /**
     * 获取 OpenRouter Categories，优先从热加载配置读取。
     * <p>可选的分类信息，如 "cloud-agent,programming-app"。</p>
     */
    private String getOpenRouterCategories() {
        if (localConfigLoader != null) {
            return localConfigLoader.current()
                    .map(cfg -> cfg.getOpenrouter())
                    .map(or -> or.getCategories())
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(null);
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private DashScopeChatModel buildDashScopeChatModel(AgentLlmResolver.ResolvedLlm resolved,
                                                      String apiKey,
                                                      double finalTemperature,
                                                      int effectiveMaxTokens) {
        boolean enableThinking = resolveEnableThinking(resolved);
        DashScopeChatModel model = new DashScopeChatModel(
                objectMapper,
                resolveDashScopeBaseUrl(resolved),
                apiKey,
                resolved.modelName(),
                finalTemperature,
                effectiveMaxTokens,
                httpLogger,
                observabilityService,
                resolved.endpointName(),
                enableThinking,
                localConfigLoader,
                eventService,
                latencyWindow
        );
        model.setBudgetService(budgetService);
        return model;
    }

    private int resolveMaxTokens(AgentLlmResolver.ResolvedLlm resolved, Integer maxTokensOverride) {
        if (maxTokensOverride != null && maxTokensOverride > 0) {
            return maxTokensOverride;
        }
        if (resolved != null && resolved.maxTokens() != null && resolved.maxTokens() > 0) {
            return resolved.maxTokens();
        }
        return maxTokens != null && maxTokens > 0 ? maxTokens : 4096;
    }

    /**
     * 从 endpoint 模型元数据中解析 thinking 开关。
     * 默认开启；若模型 features 中不包含 "thinking" 则关闭。
     */
    private boolean resolveEnableThinking(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null || isBlank(resolved.endpointName()) || isBlank(resolved.modelName())) {
            return true; // 默认开启
        }
        AgentLlmProperties.Endpoint endpoint = null;
        if (llmProperties != null && llmProperties.getEndpoints() != null) {
            endpoint = llmProperties.getEndpoints().get(resolved.endpointName());
        }
        if (endpoint == null) {
            // fallback: 尝试从热加载配置读取
            endpoint = localConfigLoader.current()
                    .map(cfg -> cfg.getEndpoints() != null ? cfg.getEndpoints().get(resolved.endpointName()) : null)
                    .orElse(null);
        }
        if (endpoint == null || endpoint.getModels() == null) {
            return true; // 默认开启
        }
        AgentLlmProperties.ModelMetadata meta = endpoint.getModels().get(resolved.modelName());
        if (meta == null || meta.getFeatures() == null) {
            return true; // 默认开启
        }
        return meta.getFeatures().contains("thinking");
    }

    /**
     * 判断是否使用 OpenRouterProviderRoutedChatModel（支持 HTTP 捕获）
     * ALP-25: 对所有 OpenAI 兼容端点启用 HTTP 捕获
     */
    private boolean shouldUseProviderRoutedModel(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null || isBlank(resolved.baseUrl())) {
            return false;
        }
        // 支持所有 OpenAI 兼容 API：OpenRouter、Fireworks、OpenAI 等
        String baseUrl = resolved.baseUrl() == null ? "" : resolved.baseUrl().toLowerCase();
        if (baseUrl.contains("dashscope")) {
            return false;
        }
        if (OpenRouterProviderRoutedChatModel.isOpenRouter(resolved.baseUrl(), resolved.endpointName())
                || OpenRouterProviderRoutedChatModel.isFireworks(resolved.baseUrl(), resolved.endpointName())) {
            return true;
        }
        return baseUrl.contains("openai.com")
            || baseUrl.contains("api/v1");  // OpenAI 兼容 API 通用路径
    }
    
    /**
     * @deprecated 使用 {@link #shouldUseProviderRoutedModel} 替代
     */
    @Deprecated
    private boolean isOpenRouterEndpoint(AgentLlmResolver.ResolvedLlm resolved) {
        return shouldUseProviderRoutedModel(resolved);
    }

    private List<String> sanitizeProviderOrder(List<String> providerOrder) {
        if (providerOrder == null || providerOrder.isEmpty()) {
            return List.of();
        }
        List<String> providers = new java.util.ArrayList<>();
        for (String provider : providerOrder) {
            if (provider == null) {
                continue;
            }
            String value = provider.trim();
            if (!value.isBlank()) {
                providers.add(value);
            }
        }
        return providers;
    }

    private boolean isDashScopeEndpoint(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved == null) {
            return false;
        }
        String endpointName = resolved.endpointName();
        if (endpointName != null && endpointName.trim().equalsIgnoreCase("dashscope")) {
            return true;
        }
        String baseUrl = resolved.baseUrl();
        return baseUrl != null && baseUrl.toLowerCase().contains("dashscope");
    }

    private String resolveDashScopeBaseUrl(AgentLlmResolver.ResolvedLlm resolved) {
        if (resolved != null && !isBlank(resolved.baseUrl())) {
            return resolved.baseUrl();
        }
        String region = resolved == null ? null : resolved.region();
        String normalized = region == null ? "" : region.trim().toLowerCase();
        return switch (normalized) {
            case "us" -> "https://dashscope-us.aliyuncs.com/compatible-mode/v1";
            case "cn" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "singapore" -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
            default -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
        };
    }
}
