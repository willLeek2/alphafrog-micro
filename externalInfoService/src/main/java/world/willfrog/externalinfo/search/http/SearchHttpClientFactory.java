package world.willfrog.externalinfo.search.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.externalinfo.config.SearchLlmProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Builds {@link java.net.http.HttpClient} for outbound search backends.
 *
 * <p>When proxy is enabled (default), mirrors agent LLM clients:
 * {@code HttpClient.newBuilder().proxy(ProxySelector.getDefault())} so container
 * {@code JAVA_TOOL_OPTIONS -Dhttp.proxyHost=...} and {@code HTTP_PROXY} env apply.</p>
 */
@Component
@Slf4j
public class SearchHttpClientFactory {

    private static final ProxySelector NO_PROXY_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // no-op
        }
    };

    private final SearchLlmProperties properties;

    public SearchHttpClientFactory(SearchLlmProperties properties) {
        this.properties = properties;
    }

    public java.net.http.HttpClient newClient(Duration connectTimeout) {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connectTimeout);
        builder.proxy(resolveProxySelector());
        return builder.build();
    }

    ProxySelector resolveProxySelector() {
        SearchLlmProperties.WebSearchProxy proxy = resolveProxyConfig();
        if (proxy == null || !proxy.isEnabled()) {
            log.debug("Search backend HTTP proxy disabled (direct)");
            return NO_PROXY_SELECTOR;
        }
        String host = firstNonBlank(proxy.getHost(), System.getProperty("http.proxyHost"));
        Integer port = proxy.getPort() != null ? proxy.getPort() : parsePort(System.getProperty("http.proxyPort"));
        if (isNotBlank(proxy.getHost()) && proxy.getPort() != null && proxy.getPort() > 0) {
            log.debug("Search backend HTTP proxy source=config host={} port={}", proxy.getHost(), proxy.getPort());
            return ProxySelector.of(InetSocketAddress.createUnresolved(proxy.getHost().trim(), proxy.getPort()));
        }
        if (host != null && port != null && port > 0) {
            log.debug("Search backend HTTP proxy source=jvm host={} port={}", host, port);
            return ProxySelector.of(InetSocketAddress.createUnresolved(host.trim(), port));
        }
        log.debug("Search backend HTTP proxy source=default-selector");
        return ProxySelector.getDefault();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private SearchLlmProperties.WebSearchProxy resolveProxyConfig() {
        if (properties.getFeatures() == null || properties.getFeatures().getWebSearch() == null) {
            SearchLlmProperties.WebSearchProxy defaults = new SearchLlmProperties.WebSearchProxy();
            defaults.setEnabled(true);
            return defaults;
        }
        SearchLlmProperties.WebSearchProxy proxy = properties.getFeatures().getWebSearch().getProxy();
        if (proxy == null) {
            proxy = new SearchLlmProperties.WebSearchProxy();
            proxy.setEnabled(true);
        }
        return proxy;
    }

    private Integer parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }
}
