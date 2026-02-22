package world.willfrog.agent.config;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class SearchLlmProperties {

    private boolean enabled = true;
    private String defaultProvider = "exa";
    private Integer defaultLimit = 10;
    private String defaultLanguage = "zh";
    private Integer connectTimeoutMillis = 10000;
    private Integer requestTimeoutMillis = 30000;
    private Prompts prompts = new Prompts();
    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Data
    public static class Prompts {
        private String marketNewsUserTemplate = "请搜索{{market}}今日市场新闻，返回{{limit}}条，检索结果使用{{language}}，请优先选择发布时间在{{startPublishedAt}}至{{endPublishedAt}}之间的新闻。";
    }

    @Data
    public static class Provider {
        private String type;
        private String endpoint;
        private String apiKey;
        private String model;
        private String searchMode;
        private String searchRecencyFilter;
        private String category;
        private String country;
        private Integer maxResults;
    }
}
