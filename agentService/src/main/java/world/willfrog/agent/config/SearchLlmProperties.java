package world.willfrog.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.search-llm")
public class SearchLlmProperties {

    private String defaultProvider = "exa";
    private Integer maxDefaultResults = 10;
    private Map<String, Provider> providers = new LinkedHashMap<>();
    private Prompts prompts = new Prompts();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Integer getMaxDefaultResults() {
        return maxDefaultResults;
    }

    public void setMaxDefaultResults(Integer maxDefaultResults) {
        this.maxDefaultResults = maxDefaultResults;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : providers;
    }

    public Prompts getPrompts() {
        return prompts;
    }

    public void setPrompts(Prompts prompts) {
        this.prompts = prompts == null ? new Prompts() : prompts;
    }

    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private String country;
        private String recencyFilter;
        private String type;
        private String category;
        private List<String> includeDomains = new ArrayList<>();

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getRecencyFilter() {
            return recencyFilter;
        }

        public void setRecencyFilter(String recencyFilter) {
            this.recencyFilter = recencyFilter;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public List<String> getIncludeDomains() {
            return includeDomains;
        }

        public void setIncludeDomains(List<String> includeDomains) {
            this.includeDomains = includeDomains == null ? new ArrayList<>() : includeDomains;
        }
    }

    public static class Prompts {
        private String marketNewsQueryTemplate = "今日A股市场实时动态、政策/央行公告、美股/全球市场要闻、行业板块热点";

        public String getMarketNewsQueryTemplate() {
            return marketNewsQueryTemplate;
        }

        public void setMarketNewsQueryTemplate(String marketNewsQueryTemplate) {
            this.marketNewsQueryTemplate = marketNewsQueryTemplate;
        }
    }
}
