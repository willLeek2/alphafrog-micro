package world.willfrog.externalinfo.search;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.externalinfo.idl.WebSearchRequest;
import world.willfrog.externalinfo.config.SearchLlmProperties;
import world.willfrog.externalinfo.search.backend.BackendSearchResult;
import world.willfrog.externalinfo.search.backend.SearchBackend;
import world.willfrog.externalinfo.service.SearchLlmLocalConfigLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendRouterTest {




    private BackendRouter router(SearchLlmProperties props) {
        SearchLlmLocalConfigLoader loader = mock(SearchLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        SearchLlmConfigResolver resolver = new SearchLlmConfigResolver(props, loader);
        return new BackendRouter(resolver, List.of(backend("perplexity"), backend("tavily"), backend("exa")));
    }

    private SearchLlmProperties properties() {
        SearchLlmProperties props = new SearchLlmProperties();
        props.setProviders(Map.of(
                "perplexity", provider(),
                "tavily", provider(),
                "exa", provider()
        ));
        return props;
    }

    private SearchLlmProperties.Provider provider() {
        SearchLlmProperties.Provider provider = new SearchLlmProperties.Provider();
        provider.setBaseUrl("https://example.test");
        provider.setApiKey("key");
        return provider;
    }

    private SearchLlmProperties.WebSearchPreset preset(String scene, String backend, String strength, int maxResults) {
        SearchLlmProperties.WebSearchPreset preset = new SearchLlmProperties.WebSearchPreset();
        preset.setScene(scene);
        preset.setBackend(backend);
        preset.setStrength(strength);
        preset.setMaxResults(maxResults);
        return preset;
    }

    private SearchBackend backend(String name) {
        return new SearchBackend() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public BackendSearchResult search(WebSearchExecutionContext context) {
                return null;
            }

            @Override
            public boolean supportsScene(String scene) {
                return true;
            }

            @Override
            public boolean supportsStrength(String strength) {
                return true;
            }
        };
    }
}
