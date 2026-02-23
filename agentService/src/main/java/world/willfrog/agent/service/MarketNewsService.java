package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.SearchLlmProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketNewsService {

    private static final int MAX_LIMIT = 20;
    private static final int MIN_LIMIT = 1;

    private final SearchLlmProperties properties;
    private final SearchLlmLocalConfigLoader localConfigLoader;
    private final ObjectMapper objectMapper;

    public TodayMarketNewsResult getTodayMarketNews(String provider,
                                                    String language,
                                                    int limit,
                                                    String startPublishedDate,
                                                    String endPublishedDate) {
        SearchLlmProperties cfg = localConfigLoader.current().orElse(properties);
        String providerKey = normalize(provider);
        if (providerKey == null) {
            providerKey = normalize(cfg.getDefaultProvider());
        }
        if (providerKey == null) {
            providerKey = "exa";
        }
        int resolvedLimit = resolveLimit(limit, cfg.getMaxDefaultResults());
        String query = buildQuery(cfg, language);
        String resolvedLanguage = normalize(language);
        String updatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        try {
            List<MarketNewsItem> items = "perplexity".equalsIgnoreCase(providerKey)
                    ? fetchPerplexityNews(cfg, query, resolvedLanguage, resolvedLimit, startPublishedDate, endPublishedDate)
                    : fetchExaNews(cfg, query, resolvedLanguage, resolvedLimit, startPublishedDate, endPublishedDate);
            if (!items.isEmpty()) {
                updatedAt = items.get(0).timestamp();
            }
            return new TodayMarketNewsResult(items, updatedAt, providerKey.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            log.error("Failed to fetch market news: provider={}", providerKey, e);
            return new TodayMarketNewsResult(List.of(), updatedAt, providerKey.toLowerCase(Locale.ROOT));
        }
    }

    private List<MarketNewsItem> fetchExaNews(SearchLlmProperties cfg,
                                              String query,
                                              String language,
                                              int limit,
                                              String startPublishedDate,
                                              String endPublishedDate) throws Exception {
        SearchLlmProperties.Provider provider = provider(cfg, "exa");
        if (provider == null || isBlank(provider.getBaseUrl()) || isBlank(provider.getApiKey())) {
            return List.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("type", fallback(provider.getType(), "auto"));
        payload.put("category", fallback(provider.getCategory(), "news"));
        payload.put("numResults", limit);
        payload.put("startPublishedDate", resolveStartPublishedDate(startPublishedDate));
        if (!isBlank(endPublishedDate)) {
            payload.put("endPublishedDate", endPublishedDate);
        }
        if (!isBlank(provider.getCountry())) {
            payload.put("userLocation", provider.getCountry());
        }
        if (provider.getIncludeDomains() != null && !provider.getIncludeDomains().isEmpty()) {
            payload.put("includeDomains", provider.getIncludeDomains());
        }
        if (!isBlank(language)) {
            payload.put("context", Map.of("targetLanguage", language));
        }
        String body = postJson(
                URI.create(provider.getBaseUrl() + "/search"),
                "x-api-key",
                provider.getApiKey(),
                objectMapper.writeValueAsString(payload)
        );
        JsonNode root = objectMapper.readTree(body);
        List<MarketNewsItem> items = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String title = safeText(item.path("title"));
            if (isBlank(title)) {
                continue;
            }
            String timestamp = safeText(item.path("publishedDate"));
            String url = safeText(item.path("url"));
            String source = safeText(item.path("author"));
            String id = safeText(item.path("id"));
            if (isBlank(id)) {
                id = buildId(url, title);
            }
            items.add(new MarketNewsItem(
                    id,
                    fallback(timestamp, OffsetDateTime.now(ZoneOffset.UTC).toString()),
                    title,
                    fallback(source, sourceFromUrl(url)),
                    "news",
                    url
            ));
        }
        return filterAndSort(items, startPublishedDate, endPublishedDate, limit);
    }

    private List<MarketNewsItem> fetchPerplexityNews(SearchLlmProperties cfg,
                                                     String query,
                                                     String language,
                                                     int limit,
                                                     String startPublishedDate,
                                                     String endPublishedDate) throws Exception {
        SearchLlmProperties.Provider provider = provider(cfg, "perplexity");
        if (provider == null || isBlank(provider.getBaseUrl()) || isBlank(provider.getApiKey())) {
            return List.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("max_results", limit);
        payload.put("search_recency_filter", fallback(provider.getRecencyFilter(), "day"));
        if (provider.getIncludeDomains() != null && !provider.getIncludeDomains().isEmpty()) {
            payload.put("search_domain_filter", provider.getIncludeDomains());
        }
        if (!isBlank(language)) {
            payload.put("search_language_filter", List.of(language));
        }
        if (!isBlank(provider.getCountry())) {
            payload.put("country", provider.getCountry());
        }
        String body = postJson(
                URI.create(provider.getBaseUrl() + "/search"),
                "Authorization",
                "Bearer " + provider.getApiKey(),
                objectMapper.writeValueAsString(payload)
        );
        JsonNode root = objectMapper.readTree(body);
        List<MarketNewsItem> items = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String title = safeText(item.path("title"));
            if (isBlank(title)) {
                continue;
            }
            String url = safeText(item.path("url"));
            String timestamp = safeText(item.path("last_updated"));
            if (isBlank(timestamp)) {
                timestamp = safeText(item.path("date"));
            }
            items.add(new MarketNewsItem(
                    buildId(url, title),
                    fallback(timestamp, OffsetDateTime.now(ZoneOffset.UTC).toString()),
                    title,
                    sourceFromUrl(url),
                    "market",
                    url
            ));
        }
        return filterAndSort(items, startPublishedDate, endPublishedDate, limit);
    }

    private String postJson(URI uri, String authHeader, String authValue, String payload) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header(authHeader, authValue)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("market news provider request failed, status=" + response.statusCode());
        }
        return response.body();
    }

    private List<MarketNewsItem> filterAndSort(List<MarketNewsItem> items,
                                               String startPublishedDate,
                                               String endPublishedDate,
                                               int limit) {
        Instant start = parseDateTime(startPublishedDate, false);
        Instant end = parseDateTime(endPublishedDate, true);
        List<MarketNewsItem> filtered = new ArrayList<>();
        for (MarketNewsItem item : items) {
            Instant ts = parseDateTime(item.timestamp(), false);
            if (start != null && ts != null && ts.isBefore(start)) {
                continue;
            }
            if (end != null && ts != null && ts.isAfter(end)) {
                continue;
            }
            filtered.add(item);
        }
        filtered.sort(Comparator.comparing(
                (MarketNewsItem item) -> parseDateTime(item.timestamp(), false),
                Comparator.nullsLast(Comparator.reverseOrder())
        ));
        if (filtered.size() <= limit) {
            return filtered;
        }
        return filtered.subList(0, limit);
    }

    private Instant parseDateTime(String text, boolean endOfDayWhenDateOnly) {
        if (isBlank(text)) {
            return null;
        }
        String v = text.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }
        try {
            if (v.length() == 10) {
                String suffix = endOfDayWhenDateOnly ? "T23:59:59Z" : "T00:00:00Z";
                return Instant.parse(v + suffix);
            }
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private String resolveStartPublishedDate(String startPublishedDate) {
        if (!isBlank(startPublishedDate)) {
            return startPublishedDate;
        }
        return OffsetDateTime.now(ZoneOffset.UTC).toLocalDate() + "T00:00:00Z";
    }

    private SearchLlmProperties.Provider provider(SearchLlmProperties cfg, String key) {
        if (cfg == null || cfg.getProviders() == null) {
            return null;
        }
        for (Map.Entry<String, SearchLlmProperties.Provider> entry : cfg.getProviders().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private int resolveLimit(int requestLimit, Integer configLimit) {
        int candidate = requestLimit > 0 ? requestLimit : (configLimit == null ? 10 : configLimit);
        return Math.min(MAX_LIMIT, Math.max(MIN_LIMIT, candidate));
    }

    private String buildQuery(SearchLlmProperties cfg, String language) {
        String template = cfg == null || cfg.getPrompts() == null ? null : cfg.getPrompts().getMarketNewsQueryTemplate();
        String query = fallback(template, "今日A股市场实时动态、政策、全球市场与行业热点");
        if (isBlank(language)) {
            return query;
        }
        return query + "，请优先返回" + language + "内容";
    }

    private String sourceFromUrl(String url) {
        if (isBlank(url)) {
            return "";
        }
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildId(String url, String title) {
        String key = fallback(url, "") + "|" + fallback(title, "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("news_");
            for (int i = 0; i < 8 && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "news_" + Math.abs(Objects.hash(key));
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    private String safeText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private String fallback(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    public record MarketNewsItem(String id, String timestamp, String title, String source, String category, String url) {}

    public record TodayMarketNewsResult(List<MarketNewsItem> data, String updatedAt, String provider) {}
}
