package world.willfrog.externalinfo.search;

import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.util.List;

/**
 * WebSearch 内部执行上下文。
 * 后端参数与已经核对的原运行提示词绑定随本次调用传递。
 */
public record WebSearchExecutionContext(
        WebSearchRequest request,
        String backend,
        String scene,
        String strength,
        int maxResults,
        String timeRange,
        List<String> includeDomains,
        List<String> excludeDomains,
        SearchLlmProperties.WebSearchPreset preset,
        SearchLlmConfigResolver.ResolvedBackendConfig backendConfig,
        SearchPromptPreparation.Binding promptBinding
) {
    public WebSearchExecutionContext(WebSearchRequest request, String backend, String scene, String strength,
            int maxResults, String timeRange, List<String> includeDomains, List<String> excludeDomains,
            SearchLlmProperties.WebSearchPreset preset, SearchLlmConfigResolver.ResolvedBackendConfig backendConfig) {
        this(request, backend, scene, strength, maxResults, timeRange, includeDomains, excludeDomains,
                preset, backendConfig, null);
    }

    public WebSearchExecutionContext withPromptBinding(SearchPromptPreparation.Binding binding) {
        return new WebSearchExecutionContext(request, backend, scene, strength, maxResults, timeRange,
                includeDomains, excludeDomains, preset, backendConfig, binding);
    }
}
