package world.willfrog.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.search-llm")
public class SearchLlmProperties {

    private String defaultProvider;
    private Map<String, Provider> providers = new HashMap<>();
    private MarketNews marketNews = new MarketNews();
    private Prompts prompts = new Prompts();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new HashMap<>() : providers;
    }

    public MarketNews getMarketNews() {
        return marketNews;
    }

    public void setMarketNews(MarketNews marketNews) {
        this.marketNews = marketNews == null ? new MarketNews() : marketNews;
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
        private String searchPath;
        private String authHeader;
        private String authPrefix;
        private Integer connectTimeoutSeconds;
        private Integer requestTimeoutSeconds;
        private Map<String, String> headers = new HashMap<>();

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

        public String getAuthHeader() {
            return authHeader;
        }

        public void setAuthHeader(String authHeader) {
            this.authHeader = authHeader;
        }

        public String getAuthPrefix() {
            return authPrefix;
        }

        public void setAuthPrefix(String authPrefix) {
            this.authPrefix = authPrefix;
        }

        public Integer getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(Integer connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public Integer getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(Integer requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new HashMap<>() : headers;
        }
    }

    public static class MarketNews {
        private Integer defaultLimit;
        private Integer maxResults;
        private Integer maxTokensPerPage;
        private String country;
        private String userLocation;
        private String exaSearchType;
        private String exaCategory;
        private List<String> languages = new ArrayList<>();
        private List<String> queries = new ArrayList<>();
        private List<String> includeDomains = new ArrayList<>();
        private List<String> excludeDomains = new ArrayList<>();
        private Map<String, List<String>> categoryKeywords = new HashMap<>();

        public Integer getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(Integer defaultLimit) {
            this.defaultLimit = defaultLimit;
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

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getUserLocation() {
            return userLocation;
        }

        public void setUserLocation(String userLocation) {
            this.userLocation = userLocation;
        }

        public String getExaSearchType() {
            return exaSearchType;
        }

        public void setExaSearchType(String exaSearchType) {
            this.exaSearchType = exaSearchType;
        }

        public String getExaCategory() {
            return exaCategory;
        }

        public void setExaCategory(String exaCategory) {
            this.exaCategory = exaCategory;
        }

        public List<String> getLanguages() {
            return languages;
        }

        public void setLanguages(List<String> languages) {
            this.languages = languages == null ? new ArrayList<>() : languages;
        }

        public List<String> getQueries() {
            return queries;
        }

        public void setQueries(List<String> queries) {
            this.queries = queries == null ? new ArrayList<>() : queries;
        }

        public List<String> getIncludeDomains() {
            return includeDomains;
        }

        public void setIncludeDomains(List<String> includeDomains) {
            this.includeDomains = includeDomains == null ? new ArrayList<>() : includeDomains;
        }

        public List<String> getExcludeDomains() {
            return excludeDomains;
        }

        public void setExcludeDomains(List<String> excludeDomains) {
            this.excludeDomains = excludeDomains == null ? new ArrayList<>() : excludeDomains;
        }

        public Map<String, List<String>> getCategoryKeywords() {
            return categoryKeywords;
        }

        public void setCategoryKeywords(Map<String, List<String>> categoryKeywords) {
            this.categoryKeywords = categoryKeywords == null ? new HashMap<>() : categoryKeywords;
        }
    }

    public static class Prompts {
        private String marketNewsQueryTemplate;

        public String getMarketNewsQueryTemplate() {
            return marketNewsQueryTemplate;
        }

        public void setMarketNewsQueryTemplate(String marketNewsQueryTemplate) {
            this.marketNewsQueryTemplate = marketNewsQueryTemplate;
        }
    }
}
