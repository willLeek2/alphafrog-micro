package world.willfrog.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.config.SearchLlmProperties;
import world.willfrog.agent.model.MarketNewsItem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketNewsService {

    private final SearchLlmLocalConfigLoader configLoader;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public NewsResult getTodayNews(String provider,
                                   String market,
                                   String language,
                                   int limit,
                                   String startPublishedAt,
                                   String endPublishedAt) {
        SearchLlmProperties cfg = configLoader.current().orElseThrow(() -> new IllegalStateException("search config not loaded"));
        String providerKey = provider == null || provider.isBlank() ? cfg.getDefaultProvider() : provider.trim();
        SearchLlmProperties.Provider providerConfig = cfg.getProviders().get(providerKey);
        if (providerConfig == null) {
            throw new IllegalArgumentException("unsupported provider: " + providerKey);
        }
        int finalLimit = limit > 0 ? limit : (cfg.getDefaultLimit() == null ? 10 : cfg.getDefaultLimit());
        String finalLanguage = language == null || language.isBlank() ? cfg.getDefaultLanguage() : language;
        String finalStart = startPublishedAt == null || startPublishedAt.isBlank() ? OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0).toString() : startPublishedAt;
        String finalEnd = endPublishedAt == null || endPublishedAt.isBlank() ? OffsetDateTime.now().toString() : endPublishedAt;

        String type = providerConfig.getType() == null ? "exa-search" : providerConfig.getType();
        List<MarketNewsItem> items;
        if ("exa-search".equalsIgnoreCase(type)) {
            items = callExa(providerKey, providerConfig, finalLimit, market, finalLanguage, finalStart, finalEnd);
        } else if ("perplexity-search".equalsIgnoreCase(type)) {
            items = callPerplexitySearch(providerKey, providerConfig, finalLimit, market, finalLanguage);
        } else if ("perplexity-sonar".equalsIgnoreCase(type)) {
            items = callPerplexitySonar(providerKey, providerConfig, finalLimit, market, finalLanguage, finalStart, finalEnd, cfg);
        } else {
            throw new IllegalArgumentException("unsupported provider type: " + type);
        }
        items.sort(Comparator.comparing(MarketNewsItem::timestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        return new NewsResult(items.stream().limit(finalLimit).toList(), OffsetDateTime.now().toString(), providerKey);
    }

    private List<MarketNewsItem> callExa(String provider,
                                         SearchLlmProperties.Provider config,
                                         int limit,
                                         String market,
                                         String language,
                                         String start,
                                         String end) {
        try {
            Map<String, Object> body = Map.of(
                    "query", queryText(market, language),
                    "type", config.getSearchMode() == null ? "auto" : config.getSearchMode(),
                    "category", config.getCategory() == null ? "news" : config.getCategory(),
                    "numResults", Math.min(Math.max(limit, 1), 100),
                    "startPublishedDate", start,
                    "endPublishedDate", end,
                    "contents", Map.of("summary", true)
            );
            JsonNode root = postJson(config.getEndpoint(), Map.of("x-api-key", config.getApiKey()), body);
            List<MarketNewsItem> out = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                out.add(new MarketNewsItem(
                        item.path("id").asText(item.path("url").asText()),
                        item.path("publishedDate").asText(""),
                        item.path("title").asText(""),
                        item.path("author").asText(""),
                        "market",
                        item.path("url").asText(""),
                        item.path("summary").asText(""),
                        provider
                ));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("exa search failed", e);
        }
    }

    private List<MarketNewsItem> callPerplexitySearch(String provider,
                                                       SearchLlmProperties.Provider config,
                                                       int limit,
                                                       String market,
                                                       String language) {
        try {
            Map<String, Object> body = Map.of(
                    "query", queryText(market, language),
                    "max_results", Math.min(Math.max(limit, 1), 20),
                    "search_recency_filter", config.getSearchRecencyFilter() == null ? "day" : config.getSearchRecencyFilter(),
                    "search_language_filter", List.of(language)
            );
            JsonNode root = postJson(config.getEndpoint(), Map.of("Authorization", "Bearer " + config.getApiKey()), body);
            List<MarketNewsItem> out = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                out.add(new MarketNewsItem(
                        item.path("url").asText(""),
                        item.path("last_updated").asText(item.path("date").asText("")),
                        item.path("title").asText(""),
                        "",
                        "market",
                        item.path("url").asText(""),
                        item.path("snippet").asText(""),
                        provider
                ));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("perplexity search failed", e);
        }
    }

    private List<MarketNewsItem> callPerplexitySonar(String provider,
                                                      SearchLlmProperties.Provider config,
                                                      int limit,
                                                      String market,
                                                      String language,
                                                      String start,
                                                      String end,
                                                      SearchLlmProperties properties) {
        try {
            String prompt = buildPrompt(properties.getPrompts().getMarketNewsUserTemplate(), market, language, limit, start, end);
            Map<String, Object> body = Map.of(
                    "model", config.getModel() == null ? "sonar-pro" : config.getModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "search_recency_filter", config.getSearchRecencyFilter() == null ? "day" : config.getSearchRecencyFilter(),
                    "search_mode", config.getSearchMode() == null ? "web" : config.getSearchMode()
            );
            JsonNode root = postJson(config.getEndpoint(), Map.of("Authorization", "Bearer " + config.getApiKey()), body);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            List<MarketNewsItem> out = new ArrayList<>();
            int idx = 1;
            for (JsonNode citation : root.path("citations")) {
                out.add(new MarketNewsItem(
                        "citation_" + idx,
                        "",
                        citation.path("title").asText(""),
                        citation.path("title").asText(""),
                        "market",
                        citation.path("url").asText(""),
                        content,
                        provider
                ));
                idx++;
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("perplexity sonar failed", e);
        }
    }

    private String buildPrompt(String template, String market, String language, int limit, String start, String end) {
        String raw = template == null || template.isBlank()
                ? "请整理今日市场新闻"
                : template;
        return raw.replace("{{market}}", market == null || market.isBlank() ? "全球市场" : market)
                .replace("{{language}}", language)
                .replace("{{limit}}", String.valueOf(limit))
                .replace("{{startPublishedAt}}", start)
                .replace("{{endPublishedAt}}", end);
    }

    private String queryText(String market, String language) {
        String marketPart = market == null || market.isBlank() ? "全球市场" : market;
        return marketPart + " 今日财经新闻 " + language;
    }

    private JsonNode postJson(String endpoint, Map<String, String> headers, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            log.error("search provider request failed: status={} body={}", response.statusCode(), response.body());
            throw new IllegalStateException("search provider request failed: " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    public record NewsResult(List<MarketNewsItem> items, String updatedAt, String provider) {
    }
}
