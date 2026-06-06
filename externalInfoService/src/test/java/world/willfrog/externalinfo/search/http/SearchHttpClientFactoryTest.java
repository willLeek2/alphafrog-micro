package world.willfrog.externalinfo.search.http;

import org.junit.jupiter.api.Test;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchHttpClientFactoryTest {

    @Test
    void resolveProxySelector_shouldUseExplicitHostWhenConfigured() {
        SearchLlmProperties properties = new SearchLlmProperties();
        SearchLlmProperties.WebSearchProxy proxy = new SearchLlmProperties.WebSearchProxy();
        proxy.setEnabled(true);
        proxy.setHost("proxy.example");
        proxy.setPort(8080);
        properties.getFeatures().getWebSearch().setProxy(proxy);

        ProxySelector selector = new SearchHttpClientFactory(properties).resolveProxySelector();
        List<Proxy> proxies = selector.select(URI.create("https://api.perplexity.ai/v1/sonar"));

        assertEquals(1, proxies.size());
        assertEquals(Proxy.Type.HTTP, proxies.get(0).type());
        assertTrue(proxies.get(0).address().toString().contains("proxy.example"));
    }

    @Test
    void resolveProxySelector_shouldBypassProxyWhenDisabled() {
        SearchLlmProperties properties = new SearchLlmProperties();
        SearchLlmProperties.WebSearchProxy proxy = new SearchLlmProperties.WebSearchProxy();
        proxy.setEnabled(false);
        properties.getFeatures().getWebSearch().setProxy(proxy);

        ProxySelector selector = new SearchHttpClientFactory(properties).resolveProxySelector();
        List<Proxy> proxies = selector.select(URI.create("https://api.perplexity.ai/v1/sonar"));

        assertEquals(1, proxies.size());
        assertEquals(Proxy.NO_PROXY, proxies.get(0));
    }
}
