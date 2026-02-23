package world.willfrog.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import world.willfrog.agent.config.SearchLlmProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarketNewsService {

    private final ObjectMapper objectMapper;
    private final SearchLlmProperties properties;
    private final SearchLlmLocalConfigLoader localConfigLoader;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public MarketNewsResult getTodayMarketNews(MarketNewsQuery query) {
        SearchLlmProperties local = localConfigLoader.current().orElse(null);
        SearchLlmProperties.MarketNews marketNews = resolveMarketNews(local);
        SearchLlmProperties.Prompts prompts = resolvePrompts(local);
        String providerName = resolveProviderName(query.provider(), local);
        SearchLlmProperties.Provider provider = resolveProvider(providerName, local);
        if (provider == null || isBlank(provider.getBaseUrl())) {
            throw new IllegalArgumentException("search provider not configured: " + providerName);
        }

        int limit = query.limit() > 0 ? query.limit() : defaultLimit(marketNews);
        int maxResults = marketNews.getMaxResults() == null || marketNews.getMaxResults() <= 0
                ? Math.max(limit, 10)
                : marketNews.getMaxResults();
        int fetchSize = Math.min(limit, maxResults);
        List<String> languages = resolveLanguages(query.languages(), marketNews);
        OffsetDateTime startTime = parseOffsetDateTime(query.startPublishedDate());
        OffsetDateTime endTime = parseOffsetDateTime(query.endPublishedDate());
        String queryText = resolveQueryText(marketNews, prompts);

        MarketNewsResponse response;
        if ("exa".equalsIgnoreCase(providerName)) {
            response = fetchFromExa(provider, marketNews, queryText, fetchSize, startTime, endTime);
        } else if ("perplexity".equalsIgnoreCase(providerName)) {
            response = fetchFromPerplexity(provider, marketNews, queryText, fetchSize, languages, startTime, endTime);
        } else {
            throw new IllegalArgumentException("unsupported provider: " + providerName);
        }

        List<MarketNewsItem> filtered = filterItems(
                response.items(),
                languages,
                startTime,
                endTime,
                limit
        );
        String updatedAt = resolveUpdatedAt(response.updatedAt(), filtered);
        return new MarketNewsResult(filtered, updatedAt, providerName);
    }

    MarketNewsResponse fetchFromPerplexity(SearchLlmProperties.Provider provider,
                                           SearchLlmProperties.MarketNews marketNews,
                                           String queryText,
                                           int limit,
                                           List<String> languages,
                                           OffsetDateTime startTime,
                                           OffsetDateTime endTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryText);
        body.put("max_results", Math.min(limit, 20));
        String recency = resolveRecencyFilter(startTime, endTime);
        if (hasText(recency)) {
            body.put("search_recency_filter", recency);
        }
        if (marketNews.getMaxTokensPerPage() != null && marketNews.getMaxTokensPerPage() > 0) {
            body.put("max_tokens_per_page", marketNews.getMaxTokensPerPage());
        }
        List<String> domainFilter = resolveDomainFilter(marketNews);
        if (!domainFilter.isEmpty()) {
            body.put("search_domain_filter", domainFilter);
        }
        if (!languages.isEmpty()) {
            body.put("search_language_filter", languages);
        }
        if (hasText(marketNews.getCountry())) {
            body.put("country", marketNews.getCountry());
        }
        String responseBody = postJson(provider, body);
        try {
            PerplexitySearchResponse response = objectMapper.readValue(responseBody, PerplexitySearchResponse.class);
            List<MarketNewsItem> items = mapPerplexityResults(
                    response == null ? List.of() : response.results(),
                    marketNews
            );
            return new MarketNewsResponse(items, response == null ? null : response.serverTime());
        } catch (Exception e) {
            throw new IllegalStateException("perplexity response parse failed", e);
        }
    }

    MarketNewsResponse fetchFromExa(SearchLlmProperties.Provider provider,
                                    SearchLlmProperties.MarketNews marketNews,
                                    String queryText,
                                    int limit,
                                    OffsetDateTime startTime,
                                    OffsetDateTime endTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryText);
        body.put("type", hasText(marketNews.getExaSearchType()) ? marketNews.getExaSearchType() : "auto");
        if (hasText(marketNews.getExaCategory())) {
            body.put("category", marketNews.getExaCategory());
        }
        body.put("numResults", Math.min(limit, 100));
        if (startTime != null) {
            body.put("startPublishedDate", startTime.toInstant().toString());
        }
        if (endTime != null) {
            body.put("endPublishedDate", endTime.toInstant().toString());
        }
        if (!marketNews.getIncludeDomains().isEmpty()) {
            body.put("includeDomains", marketNews.getIncludeDomains());
        }
        if (!marketNews.getExcludeDomains().isEmpty()) {
            body.put("excludeDomains", marketNews.getExcludeDomains());
        }
        if (hasText(marketNews.getUserLocation())) {
            body.put("userLocation", marketNews.getUserLocation());
        }
        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("summary", true);
        contents.put("text", false);
        contents.put("highlights", true);
        body.put("contents", contents);

        String responseBody = postJson(provider, body);
        try {
            ExaSearchResponse response = objectMapper.readValue(responseBody, ExaSearchResponse.class);
            List<MarketNewsItem> items = mapExaResults(
                    response == null ? List.of() : response.results(),
                    marketNews
            );
            return new MarketNewsResponse(items, null);
        } catch (Exception e) {
            throw new IllegalStateException("exa response parse failed", e);
        }
    }

    List<MarketNewsItem> mapPerplexityResults(List<PerplexitySearchResult> results,
                                              SearchLlmProperties.MarketNews marketNews) {
        List<MarketNewsItem> items = new ArrayList<>();
        if (results == null) {
            return items;
        }
        int index = 0;
        for (PerplexitySearchResult result : results) {
            if (result == null) {
                continue;
            }
            String title = nvl(result.title());
            String url = nvl(result.url());
            String timestamp = firstNonBlank(result.lastUpdated(), result.date());
            String source = deriveSource(url, null);
            String category = resolveCategory(title, marketNews);
            String id = generateId(url, title, index++);
            items.add(new MarketNewsItem(id, timestamp, title, source, category, url));
        }
        return items;
    }

    List<MarketNewsItem> mapExaResults(List<ExaSearchResult> results,
                                       SearchLlmProperties.MarketNews marketNews) {
        List<MarketNewsItem> items = new ArrayList<>();
        if (results == null) {
            return items;
        }
        int index = 0;
        for (ExaSearchResult result : results) {
            if (result == null) {
                continue;
            }
            String title = nvl(result.title());
            String url = nvl(result.url());
            String timestamp = nvl(result.publishedDate());
            String source = deriveSource(url, result.author());
            String category = resolveCategory(title, marketNews);
            String id = firstNonBlank(result.id(), generateId(url, title, index++));
            items.add(new MarketNewsItem(id, timestamp, title, source, category, url));
        }
        return items;
    }

    List<MarketNewsItem> filterItems(List<MarketNewsItem> items,
                                     List<String> languages,
                                     OffsetDateTime startTime,
                                     OffsetDateTime endTime,
                                     int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int safeLimit = limit <= 0 ? items.size() : limit;
        List<MarketNewsItem> filtered = new ArrayList<>();
        for (MarketNewsItem item : items) {
            if (item == null) {
                continue;
            }
            if (!matchesLanguage(item.title(), languages)) {
                continue;
            }
            OffsetDateTime ts = parseOffsetDateTime(item.timestamp());
            if (!matchesTimeRange(ts, startTime, endTime)) {
                continue;
            }
            filtered.add(item);
            if (filtered.size() >= safeLimit) {
                break;
            }
        }
        return filtered;
    }

    private SearchLlmProperties.MarketNews resolveMarketNews(SearchLlmProperties local) {
        SearchLlmProperties.MarketNews base = properties.getMarketNews();
        SearchLlmProperties.MarketNews override = local == null ? null : local.getMarketNews();
        SearchLlmProperties.MarketNews merged = new SearchLlmProperties.MarketNews();
        merged.setDefaultLimit(firstNonNull(override == null ? null : override.getDefaultLimit(), base.getDefaultLimit()));
        merged.setMaxResults(firstNonNull(override == null ? null : override.getMaxResults(), base.getMaxResults()));
        merged.setMaxTokensPerPage(firstNonNull(override == null ? null : override.getMaxTokensPerPage(), base.getMaxTokensPerPage()));
        merged.setCountry(firstNonBlank(override == null ? null : override.getCountry(), base.getCountry()));
        merged.setUserLocation(firstNonBlank(override == null ? null : override.getUserLocation(), base.getUserLocation()));
        merged.setExaSearchType(firstNonBlank(override == null ? null : override.getExaSearchType(), base.getExaSearchType()));
        merged.setExaCategory(firstNonBlank(override == null ? null : override.getExaCategory(), base.getExaCategory()));
        merged.setLanguages(copyList(isEmpty(override == null ? null : override.getLanguages())
                ? base.getLanguages()
                : override.getLanguages()));
        merged.setQueries(copyList(isEmpty(override == null ? null : override.getQueries())
                ? base.getQueries()
                : override.getQueries()));
        merged.setIncludeDomains(copyList(isEmpty(override == null ? null : override.getIncludeDomains())
                ? base.getIncludeDomains()
                : override.getIncludeDomains()));
        merged.setExcludeDomains(copyList(isEmpty(override == null ? null : override.getExcludeDomains())
                ? base.getExcludeDomains()
                : override.getExcludeDomains()));
        merged.setCategoryKeywords(copyMap(isEmpty(override == null ? null : override.getCategoryKeywords())
                ? base.getCategoryKeywords()
                : override.getCategoryKeywords()));
        return merged;
    }

    private SearchLlmProperties.Prompts resolvePrompts(SearchLlmProperties local) {
        SearchLlmProperties.Prompts base = properties.getPrompts();
        SearchLlmProperties.Prompts override = local == null ? null : local.getPrompts();
        SearchLlmProperties.Prompts merged = new SearchLlmProperties.Prompts();
        merged.setMarketNewsQueryTemplate(firstNonBlank(
                override == null ? null : override.getMarketNewsQueryTemplate(),
                base == null ? null : base.getMarketNewsQueryTemplate()
        ));
        return merged;
    }

    private SearchLlmProperties.Provider resolveProvider(String providerName, SearchLlmProperties local) {
        Map<String, SearchLlmProperties.Provider> baseProviders = properties.getProviders();
        Map<String, SearchLlmProperties.Provider> overrideProviders = local == null ? Map.of() : local.getProviders();
        SearchLlmProperties.Provider base = baseProviders == null ? null : baseProviders.get(providerName);
        SearchLlmProperties.Provider override = overrideProviders == null ? null : overrideProviders.get(providerName);
        if (base == null && override == null) {
            return null;
        }
        SearchLlmProperties.Provider merged = new SearchLlmProperties.Provider();
        if (base != null) {
            merged.setBaseUrl(base.getBaseUrl());
            merged.setApiKey(base.getApiKey());
            merged.setSearchPath(base.getSearchPath());
            merged.setAuthHeader(base.getAuthHeader());
            merged.setAuthPrefix(base.getAuthPrefix());
            merged.setHeaders(copyMap(base.getHeaders()));
        }
        if (override != null) {
            if (hasText(override.getBaseUrl())) {
                merged.setBaseUrl(override.getBaseUrl());
            }
            if (hasText(override.getApiKey())) {
                merged.setApiKey(override.getApiKey());
            }
            if (hasText(override.getSearchPath())) {
                merged.setSearchPath(override.getSearchPath());
            }
            if (hasText(override.getAuthHeader())) {
                merged.setAuthHeader(override.getAuthHeader());
            }
            if (override.getAuthPrefix() != null) {
                merged.setAuthPrefix(override.getAuthPrefix());
            }
            if (override.getHeaders() != null && !override.getHeaders().isEmpty()) {
                Map<String, String> headers = merged.getHeaders();
                headers.putAll(override.getHeaders());
                merged.setHeaders(headers);
            }
        }
        return merged;
    }

    private String resolveProviderName(String provider, SearchLlmProperties local) {
        String resolved = firstNonBlank(
                provider,
                local == null ? null : local.getDefaultProvider(),
                properties.getDefaultProvider()
        );
        if (hasText(resolved)) {
            return resolved;
        }
        Map<String, SearchLlmProperties.Provider> providers = properties.getProviders();
        if (providers != null && !providers.isEmpty()) {
            return providers.keySet().iterator().next();
        }
        return "";
    }

    private String resolveQueryText(SearchLlmProperties.MarketNews marketNews, SearchLlmProperties.Prompts prompts) {
        if (prompts != null && hasText(prompts.getMarketNewsQueryTemplate()) && isEmpty(marketNews.getQueries())) {
            return prompts.getMarketNewsQueryTemplate().trim();
        }
        List<String> queries = marketNews.getQueries();
        if (queries == null || queries.isEmpty()) {
            throw new IllegalStateException("market news query is empty");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String q : queries) {
            if (hasText(q)) {
                unique.add(q.trim());
            }
        }
        if (unique.isEmpty()) {
            throw new IllegalStateException("market news query configuration is empty");
        }
        return String.join(" OR ", unique);
    }

    private List<String> resolveDomainFilter(SearchLlmProperties.MarketNews marketNews) {
        List<String> domains = new ArrayList<>();
        if (marketNews == null) {
            return domains;
        }
        if (marketNews.getIncludeDomains() != null) {
            for (String domain : marketNews.getIncludeDomains()) {
                if (hasText(domain)) {
                    domains.add(domain.trim());
                }
            }
        }
        if (marketNews.getExcludeDomains() != null) {
            for (String domain : marketNews.getExcludeDomains()) {
                if (hasText(domain)) {
                    domains.add("-" + domain.trim());
                }
            }
        }
        return domains;
    }

    private List<String> resolveLanguages(List<String> override, SearchLlmProperties.MarketNews marketNews) {
        List<String> resolved = new ArrayList<>();
        List<String> source = isEmpty(override) ? marketNews.getLanguages() : override;
        if (source != null) {
            for (String lang : source) {
                if (hasText(lang)) {
                    resolved.add(lang.trim());
                }
            }
        }
        return resolved;
    }

    private String resolveUpdatedAt(String serverTime, List<MarketNewsItem> items) {
        OffsetDateTime serverTs = parseOffsetDateTime(serverTime);
        if (serverTs != null) {
            return serverTs.toString();
        }
        OffsetDateTime max = null;
        if (items != null) {
            for (MarketNewsItem item : items) {
                OffsetDateTime ts = parseOffsetDateTime(item.timestamp());
                if (ts != null && (max == null || ts.isAfter(max))) {
                    max = ts;
                }
            }
        }
        if (max != null) {
            return max.toString();
        }
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    private String resolveRecencyFilter(OffsetDateTime startTime, OffsetDateTime endTime) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = startTime == null ? now.minusDays(1) : startTime;
        OffsetDateTime end = endTime == null ? now : endTime;
        long days = Math.abs(Duration.between(start, end).toDays());
        if (days <= 1) {
            return "day";
        }
        if (days <= 7) {
            return "week";
        }
        if (days <= 31) {
            return "month";
        }
        return "year";
    }

    private boolean matchesLanguage(String title, List<String> languages) {
        if (isEmpty(languages)) {
            return true;
        }
        if (title == null || title.isBlank()) {
            return false;
        }
        boolean hasChinese = title.codePoints().anyMatch(ch ->
                (ch >= 0x3400 && ch <= 0x4dbf) || (ch >= 0x4e00 && ch <= 0x9fff)
        );
        boolean hasLatin = title.codePoints().anyMatch(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'));
        for (String lang : languages) {
            if (!hasText(lang)) {
                continue;
            }
            String normalized = lang.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("zh")) {
                if (hasChinese) {
                    return true;
                }
            } else if (normalized.startsWith("en")) {
                if (hasLatin && !hasChinese) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTimeRange(OffsetDateTime timestamp, OffsetDateTime start, OffsetDateTime end) {
        if (timestamp == null) {
            return start == null && end == null;
        }
        if (start != null && timestamp.isBefore(start)) {
            return false;
        }
        if (end != null && timestamp.isAfter(end)) {
            return false;
        }
        return true;
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        String raw = value.trim();
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception ignore) {
            // ignore
        }
        try {
            LocalDateTime local = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return local.atOffset(ZoneOffset.UTC);
        } catch (Exception ignore) {
            // ignore
        }
        try {
            LocalDate date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String resolveCategory(String title, SearchLlmProperties.MarketNews marketNews) {
        if (marketNews != null && marketNews.getCategoryKeywords() != null && !marketNews.getCategoryKeywords().isEmpty()) {
            String lowered = title == null ? "" : title.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> entry : marketNews.getCategoryKeywords().entrySet()) {
                String category = entry.getKey();
                if (!hasText(category)) {
                    continue;
                }
                List<String> keywords = entry.getValue();
                if (keywords == null || keywords.isEmpty()) {
                    continue;
                }
                for (String keyword : keywords) {
                    if (keyword == null || keyword.isBlank()) {
                        continue;
                    }
                    String needle = keyword.toLowerCase(Locale.ROOT);
                    if (lowered.contains(needle)) {
                        return category;
                    }
                }
            }
        }
        return "market";
    }

    private String deriveSource(String url, String author) {
        if (hasText(author)) {
            return author.trim();
        }
        if (!hasText(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private String generateId(String url, String title, int index) {
        String seed = firstNonBlank(url, title, "news-" + index);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String postJson(SearchLlmProperties.Provider provider, Map<String, Object> body) {
        String baseUrl = provider.getBaseUrl();
        String path = provider.getSearchPath();
        String url = resolveUrl(baseUrl, path);
        if (!hasText(url)) {
            throw new IllegalArgumentException("search provider url missing");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            applyAuthHeader(provider, requestBuilder);
            if (provider.getHeaders() != null) {
                for (Map.Entry<String, String> entry : provider.getHeaders().entrySet()) {
                    if (hasText(entry.getKey()) && entry.getValue() != null) {
                        requestBuilder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("Search provider {} returned status {} body {}", url, response.statusCode(), response.body());
                throw new IllegalStateException("search provider error: " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("search provider request failed", e);
        }
    }

    private void applyAuthHeader(SearchLlmProperties.Provider provider, HttpRequest.Builder builder) {
        if (provider == null) {
            return;
        }
        String header = provider.getAuthHeader();
        String apiKey = provider.getApiKey();
        if (!hasText(header) || !hasText(apiKey)) {
            return;
        }
        String prefix = provider.getAuthPrefix();
        builder.header(header, (prefix == null ? "" : prefix) + apiKey);
    }

    private String resolveUrl(String baseUrl, String path) {
        if (!hasText(baseUrl)) {
            return "";
        }
        if (hasText(path) && path.startsWith("http")) {
            return path;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = hasText(path) ? path.trim() : "";
        if (!normalizedPath.isEmpty() && !normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedBase + normalizedPath;
    }

    private int defaultLimit(SearchLlmProperties.MarketNews marketNews) {
        if (marketNews == null || marketNews.getDefaultLimit() == null || marketNews.getDefaultLimit() <= 0) {
            return 8;
        }
        return marketNews.getDefaultLimit();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isBlank(String value) {
        return !hasText(value);
    }

    private boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    private List<String> copyList(List<String> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private <T> Map<String, T> copyMap(Map<String, T> values) {
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(values);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    public record MarketNewsQuery(String provider,
                                  List<String> languages,
                                  int limit,
                                  String startPublishedDate,
                                  String endPublishedDate) {
    }

    public record MarketNewsResult(List<MarketNewsItem> items, String updatedAt, String provider) {
    }

    public record MarketNewsItem(String id,
                                 String timestamp,
                                 String title,
                                 String source,
                                 String category,
                                 String url) {
    }

    public record MarketNewsResponse(List<MarketNewsItem> items, String updatedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PerplexitySearchResponse(@JsonProperty("results") List<PerplexitySearchResult> results,
                                           @JsonProperty("server_time") String serverTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PerplexitySearchResult(String title,
                                         String url,
                                         String snippet,
                                         String date,
                                         @JsonProperty("last_updated") String lastUpdated) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExaSearchResponse(@JsonProperty("results") List<ExaSearchResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExaSearchResult(String title,
                                  String url,
                                  String summary,
                                  String author,
                                  String id,
                                  @JsonProperty("publishedDate") String publishedDate) {
    }
}
