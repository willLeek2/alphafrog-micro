package world.willfrog.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.search-llm")
public class SearchLlmProperties {

    private String defaultProvider;
    private Integer defaultLimit;
    private String defaultLanguage;
    private String defaultStartPublishedDate;
    private String defaultEndPublishedDate;
    private Map<String, Provider> providers = new HashMap<>();
    private Prompts prompts = new Prompts();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Integer getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(Integer defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }

    public String getDefaultStartPublishedDate() {
        return defaultStartPublishedDate;
    }

    public void setDefaultStartPublishedDate(String defaultStartPublishedDate) {
        this.defaultStartPublishedDate = defaultStartPublishedDate;
    }

    public String getDefaultEndPublishedDate() {
        return defaultEndPublishedDate;
    }

    public void setDefaultEndPublishedDate(String defaultEndPublishedDate) {
        this.defaultEndPublishedDate = defaultEndPublishedDate;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new HashMap<>() : providers;
    }

    public Prompts getPrompts() {
        return prompts;
    }

    public void setPrompts(Prompts prompts) {
        this.prompts = prompts == null ? new Prompts() : prompts;
    }

    public static class Provider {
        private String type;
        private String baseUrl;
        private String apiKey;
        private String searchPath;
        private String chatPath;
        private Integer defaultLimit;
        private List<String> defaultLanguages = new ArrayList<>();
        private List<String> defaultDomains = new ArrayList<>();
        private String defaultRecency;
        private String country;
        private String defaultCategory;
        private String defaultType;
        private String userLocation;
        private String searchMode;
        private String model;
        private Integer maxResults;
        private Integer maxTokensPerPage;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

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

        public String getSearchPath() {
            return searchPath;
        }

        public void setSearchPath(String searchPath) {
            this.searchPath = searchPath;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public Integer getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(Integer defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public List<String> getDefaultLanguages() {
            return defaultLanguages;
        }

        public void setDefaultLanguages(List<String> defaultLanguages) {
            this.defaultLanguages = defaultLanguages == null ? new ArrayList<>() : defaultLanguages;
        }

        public List<String> getDefaultDomains() {
            return defaultDomains;
        }

        public void setDefaultDomains(List<String> defaultDomains) {
            this.defaultDomains = defaultDomains == null ? new ArrayList<>() : defaultDomains;
        }

        public String getDefaultRecency() {
            return defaultRecency;
        }

        public void setDefaultRecency(String defaultRecency) {
            this.defaultRecency = defaultRecency;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getDefaultCategory() {
            return defaultCategory;
        }

        public void setDefaultCategory(String defaultCategory) {
            this.defaultCategory = defaultCategory;
        }

        public String getDefaultType() {
            return defaultType;
        }

        public void setDefaultType(String defaultType) {
            this.defaultType = defaultType;
        }

        public String getUserLocation() {
            return userLocation;
        }

        public void setUserLocation(String userLocation) {
            this.userLocation = userLocation;
        }

        public String getSearchMode() {
            return searchMode;
        }

        public void setSearchMode(String searchMode) {
            this.searchMode = searchMode;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(Integer maxResults) {
            this.maxResults = maxResults;
        }

        public Integer getMaxTokensPerPage() {
            return maxTokensPerPage;
        }

        public void setMaxTokensPerPage(Integer maxTokensPerPage) {
            this.maxTokensPerPage = maxTokensPerPage;
        }
    }

    public static class Prompts {
        private String marketNewsQuery;
        private String marketNewsFallback;

        public String getMarketNewsQuery() {
            return marketNewsQuery;
        }

        public void setMarketNewsQuery(String marketNewsQuery) {
            this.marketNewsQuery = marketNewsQuery;
        }

        public String getMarketNewsFallback() {
            return marketNewsFallback;
        }

        public void setMarketNewsFallback(String marketNewsFallback) {
            this.marketNewsFallback = marketNewsFallback;
        }
    }
}
