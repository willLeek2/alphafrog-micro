package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.SearchLlmProperties;
import world.willfrog.agent.model.MarketNewsModels.MarketNewsItem;
import world.willfrog.agent.model.MarketNewsModels.MarketNewsQuery;
import world.willfrog.agent.model.MarketNewsModels.MarketNewsResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketNewsService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final SearchLlmProperties properties;
    private final SearchLlmLocalConfigLoader localConfigLoader;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MarketNewsResult getTodayMarketNews(MarketNewsQuery query) {
        SearchLlmProperties local = localConfigLoader.current().orElse(null);
        Map<String, SearchLlmProperties.Provider> providers = mergeProviders(properties, local);
        if (providers.isEmpty()) {
            throw new IllegalStateException("search provider 未配置");
        }
        String providerKey = pickProvider(query.provider(), local, properties, providers);
        SearchLlmProperties.Provider provider = providers.get(providerKey);
        if (provider == null) {
            throw new IllegalArgumentException("未找到 provider: " + providerKey);
        }
        int limit = resolveLimit(query.limit(), provider, properties);
        String language = resolveLanguage(query.language(), provider, properties);
        String startPublishedDate = firstNonBlank(query.startPublishedDate(), properties.getDefaultStartPublishedDate());
        String endPublishedDate = firstNonBlank(query.endPublishedDate(), properties.getDefaultEndPublishedDate());
        String queryText = resolveQueryText(local, properties);

        if (startPublishedDate == null) {
            startPublishedDate = OffsetDateTime.now(DEFAULT_ZONE).toLocalDate().atStartOfDay(DEFAULT_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (endPublishedDate == null) {
            endPublishedDate = OffsetDateTime.now(DEFAULT_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        ProviderResult providerResult;
        if (isExa(provider)) {
            providerResult = fetchFromExa(provider, queryText, limit, language, startPublishedDate, endPublishedDate);
        } else {
            providerResult = fetchFromPerplexity(provider, queryText, limit, language);
        }
        String updatedAt = providerResult.updatedAt();
        if (updatedAt == null || updatedAt.isBlank()) {
            updatedAt = endPublishedDate;
        }
        return new MarketNewsResult(providerResult.items(), providerKey, updatedAt);
    }

    private ProviderResult fetchFromPerplexity(SearchLlmProperties.Provider provider,
                                               String queryText,
                                               int limit,
                                               String language) {
        String baseUrl = normalize(provider.getBaseUrl());
        if (baseUrl == null) {
            throw new IllegalStateException("perplexity baseUrl 未配置");
        }
        String path = normalize(provider.getSearchPath());
        if (path == null) {
            path = "/search";
        }
        String url = trimTrailingSlash(baseUrl) + path;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", queryText);
        payload.put("max_results", limit);
        String recency = firstNonBlank(provider.getDefaultRecency(), "day");
        payload.put("search_recency_filter", recency);
        List<String> languages = new ArrayList<>();
        if (language != null && !language.isBlank()) {
            languages.add(language);
        } else if (provider.getDefaultLanguages() != null) {
            languages.addAll(provider.getDefaultLanguages());
        }
        if (!languages.isEmpty()) {
            payload.put("search_language_filter", languages);
        }
        if (provider.getDefaultDomains() != null && !provider.getDefaultDomains().isEmpty()) {
            payload.put("search_domain_filter", provider.getDefaultDomains());
        }
        if (provider.getCountry() != null && !provider.getCountry().isBlank()) {
            payload.put("country", provider.getCountry());
        }
        if (provider.getMaxTokensPerPage() != null && provider.getMaxTokensPerPage() > 0) {
            payload.put("max_tokens_per_page", provider.getMaxTokensPerPage());
        }
        HttpRequest request = buildJsonPost(url, payload, provider.getApiKey());
        HttpResponse<String> response = send(request, "perplexity");
        PerplexitySearchResponse parsed = readJson(response.body(), PerplexitySearchResponse.class, "perplexity");
        List<MarketNewsItem> items = new ArrayList<>();
        if (parsed.results != null) {
            int idx = 0;
            for (PerplexitySearchResponse.Result r : parsed.results) {
                String ts = firstNonBlank(r.last_updated, r.date);
                items.add(new MarketNewsItem(
                        firstNonBlank(r.url, "perplexity_" + idx),
                        ts == null ? "" : ts,
                        nvl(r.title),
                        resolveSource(r.url, r.title),
                        "market",
                        nvl(r.url)
                ));
                idx++;
            }
        }
        return new ProviderResult(items, parsed.server_time);
    }

    private ProviderResult fetchFromExa(SearchLlmProperties.Provider provider,
                                        String queryText,
                                        int limit,
                                        String language,
                                        String startPublishedDate,
                                        String endPublishedDate) {
        String baseUrl = normalize(provider.getBaseUrl());
        if (baseUrl == null) {
            throw new IllegalStateException("exa baseUrl 未配置");
        }
        String path = normalize(provider.getSearchPath());
        if (path == null) {
            path = "/search";
        }
        String url = trimTrailingSlash(baseUrl) + path;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", queryText);
        payload.put("type", firstNonBlank(provider.getDefaultType(), "auto"));
        payload.put("category", firstNonBlank(provider.getDefaultCategory(), "news"));
        payload.put("numResults", limit);
        payload.put("startPublishedDate", startPublishedDate);
        payload.put("endPublishedDate", endPublishedDate);
        if (provider.getDefaultDomains() != null && !provider.getDefaultDomains().isEmpty()) {
            payload.put("includeDomains", provider.getDefaultDomains());
        }
        if (language != null && !language.isBlank()) {
            payload.put("language", language);
        } else if (provider.getDefaultLanguages() != null && !provider.getDefaultLanguages().isEmpty()) {
            payload.put("language", provider.getDefaultLanguages().get(0));
        }
        if (provider.getUserLocation() != null && !provider.getUserLocation().isBlank()) {
            payload.put("userLocation", provider.getUserLocation());
        }
        Map<String, Boolean> contents = new LinkedHashMap<>();
        contents.put("summary", true);
        contents.put("text", false);
        payload.put("contents", contents);

        HttpRequest request = buildJsonPost(url, payload, provider.getApiKey());
        HttpResponse<String> response = send(request, "exa");
        ExaSearchResponse parsed = readJson(response.body(), ExaSearchResponse.class, "exa");
        List<MarketNewsItem> items = new ArrayList<>();
        if (parsed.results != null) {
            int idx = 0;
            for (ExaSearchResponse.Result r : parsed.results) {
                items.add(new MarketNewsItem(
                        firstNonBlank(r.id, firstNonBlank(r.url, "exa_" + idx)),
                        nvl(r.publishedDate),
                        nvl(r.title),
                        resolveSource(r.url, r.author),
                        firstNonBlank(provider.getDefaultCategory(), "news"),
                        nvl(r.url)
                ));
                idx++;
            }
        }
        String updatedAt = parsed.results == null || parsed.results.isEmpty()
                ? null
                : Optional.ofNullable(parsed.results.get(0).publishedDate).orElse(null);
        return new ProviderResult(items, updatedAt);
    }

    private HttpRequest buildJsonPost(String url, Map<String, Object> payload, String apiKey) {
        ObjectNode node = objectMapper.valueToTree(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        try {
            return builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(node))).build();
        } catch (Exception e) {
            throw new IllegalStateException("构造请求失败", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String providerName) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp;
            }
            throw new IllegalStateException("调用 " + providerName + " 失败, http=" + resp.statusCode());
        } catch (Exception e) {
            throw new IllegalStateException("调用 " + providerName + " 失败", e);
        }
    }

    private <T> T readJson(String body, Class<T> clazz, String providerName) {
        try {
            return objectMapper.readValue(body, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("解析 " + providerName + " 响应失败", e);
        }
    }

    private Map<String, SearchLlmProperties.Provider> mergeProviders(SearchLlmProperties base,
                                                                     SearchLlmProperties local) {
        Map<String, SearchLlmProperties.Provider> merged = new LinkedHashMap<>();
        if (base != null && base.getProviders() != null) {
            base.getProviders().forEach((k, v) -> merged.put(k, copyProvider(v)));
        }
        if (local != null && local.getProviders() != null) {
            local.getProviders().forEach((k, v) -> {
                SearchLlmProperties.Provider target = merged.getOrDefault(k, new SearchLlmProperties.Provider());
                if (v != null) {
                    if (hasText(v.getType())) target.setType(v.getType());
                    if (hasText(v.getBaseUrl())) target.setBaseUrl(v.getBaseUrl());
                    if (hasText(v.getApiKey())) target.setApiKey(v.getApiKey());
                    if (hasText(v.getSearchPath())) target.setSearchPath(v.getSearchPath());
                    if (hasText(v.getChatPath())) target.setChatPath(v.getChatPath());
                    if (v.getDefaultLimit() != null) target.setDefaultLimit(v.getDefaultLimit());
                    if (v.getDefaultLanguages() != null && !v.getDefaultLanguages().isEmpty()) {
                        target.setDefaultLanguages(new ArrayList<>(v.getDefaultLanguages()));
                    }
                    if (v.getDefaultDomains() != null && !v.getDefaultDomains().isEmpty()) {
                        target.setDefaultDomains(new ArrayList<>(v.getDefaultDomains()));
                    }
                    if (hasText(v.getDefaultRecency())) target.setDefaultRecency(v.getDefaultRecency());
                    if (hasText(v.getCountry())) target.setCountry(v.getCountry());
                    if (hasText(v.getDefaultCategory())) target.setDefaultCategory(v.getDefaultCategory());
                    if (hasText(v.getDefaultType())) target.setDefaultType(v.getDefaultType());
                    if (hasText(v.getUserLocation())) target.setUserLocation(v.getUserLocation());
                    if (hasText(v.getSearchMode())) target.setSearchMode(v.getSearchMode());
                    if (hasText(v.getModel())) target.setModel(v.getModel());
                    if (v.getMaxResults() != null) target.setMaxResults(v.getMaxResults());
                    if (v.getMaxTokensPerPage() != null) target.setMaxTokensPerPage(v.getMaxTokensPerPage());
                }
                merged.put(k, target);
            });
        }
        return merged;
    }

    private SearchLlmProperties.Provider copyProvider(SearchLlmProperties.Provider source) {
        SearchLlmProperties.Provider target = new SearchLlmProperties.Provider();
        if (source == null) {
            return target;
        }
        target.setType(source.getType());
        target.setBaseUrl(source.getBaseUrl());
        target.setApiKey(source.getApiKey());
        target.setSearchPath(source.getSearchPath());
        target.setChatPath(source.getChatPath());
        target.setDefaultLimit(source.getDefaultLimit());
        target.setDefaultLanguages(source.getDefaultLanguages() == null ? List.of() : new ArrayList<>(source.getDefaultLanguages()));
        target.setDefaultDomains(source.getDefaultDomains() == null ? List.of() : new ArrayList<>(source.getDefaultDomains()));
        target.setDefaultRecency(source.getDefaultRecency());
        target.setCountry(source.getCountry());
        target.setDefaultCategory(source.getDefaultCategory());
        target.setDefaultType(source.getDefaultType());
        target.setUserLocation(source.getUserLocation());
        target.setSearchMode(source.getSearchMode());
        target.setModel(source.getModel());
        target.setMaxResults(source.getMaxResults());
        target.setMaxTokensPerPage(source.getMaxTokensPerPage());
        return target;
    }

    private String pickProvider(String requested,
                                SearchLlmProperties local,
                                SearchLlmProperties base,
                                Map<String, SearchLlmProperties.Provider> providers) {
        String candidate = normalize(requested);
        if (candidate != null && providers.containsKey(candidate)) {
            return candidate;
        }
        candidate = normalize(local == null ? null : local.getDefaultProvider());
        if (candidate != null && providers.containsKey(candidate)) {
            return candidate;
        }
        candidate = normalize(base == null ? null : base.getDefaultProvider());
        if (candidate != null && providers.containsKey(candidate)) {
            return candidate;
        }
        return providers.keySet().iterator().next();
    }

    private int resolveLimit(Integer requested, SearchLlmProperties.Provider provider, SearchLlmProperties base) {
        int limit = requested != null && requested > 0 ? requested : 0;
        if (limit <= 0) {
            limit = provider != null && provider.getDefaultLimit() != null ? provider.getDefaultLimit() : 0;
        }
        if (limit <= 0 && base != null && base.getDefaultLimit() != null) {
            limit = base.getDefaultLimit();
        }
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        limit = Math.min(limit, MAX_LIMIT);
        if (provider != null && provider.getMaxResults() != null && provider.getMaxResults() > 0) {
            limit = Math.min(limit, provider.getMaxResults());
        }
        return Math.max(1, limit);
    }

    private String resolveLanguage(String requested, SearchLlmProperties.Provider provider, SearchLlmProperties base) {
        if (hasText(requested)) {
            return requested.trim();
        }
        if (provider != null && provider.getDefaultLanguages() != null && !provider.getDefaultLanguages().isEmpty()) {
            return provider.getDefaultLanguages().get(0);
        }
        if (base != null && base.getDefaultLanguage() != null && !base.getDefaultLanguage().isBlank()) {
            return base.getDefaultLanguage();
        }
        return "zh";
    }

    private String resolveQueryText(SearchLlmProperties local, SearchLlmProperties base) {
        Set<String> queries = new LinkedHashSet<>();
        if (local != null && local.getPrompts() != null && hasText(local.getPrompts().getMarketNewsQuery())) {
            queries.add(local.getPrompts().getMarketNewsQuery().trim());
        }
        if (base != null && base.getPrompts() != null && hasText(base.getPrompts().getMarketNewsQuery())) {
            queries.add(base.getPrompts().getMarketNewsQuery().trim());
        }
        if (queries.isEmpty()) {
            queries.add("今日市场新闻 宏观政策 A股 美股 热点 板块");
        }
        return String.join(" ", queries);
    }

    private boolean isExa(SearchLlmProperties.Provider provider) {
        String type = normalize(provider.getType());
        if (type == null) {
            return false;
        }
        return type.toLowerCase(Locale.ROOT).contains("exa");
    }

    private String resolveSource(String url, String fallback) {
        if (hasText(fallback)) {
            return fallback;
        }
        if (!hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null) {
                return host.startsWith("www.") ? host.substring(4) : host;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            String n = normalize(v);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private record ProviderResult(List<MarketNewsItem> items, String updatedAt) {
    }

    private static class PerplexitySearchResponse {
        public List<Result> results;
        public String server_time;

        private static class Result {
            public String title;
            public String url;
            public String snippet;
            public String date;
            public String last_updated;
        }
    }

    private static class ExaSearchResponse {
        public List<Result> results;

        private static class Result {
            public String id;
            public String title;
            public String url;
            public String publishedDate;
            public String author;
        }
    }
}
