package world.willfrog.agent.platform.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "agent.llm")
public class AgentLlmProperties {

    private String defaultEndpoint;
    private String defaultModel;
    private Map<String, Endpoint> endpoints = new HashMap<>();
    private List<String> models = new ArrayList<>();
    private Runtime runtime = new Runtime();
    private Observability observability = new Observability();
    private Prompts prompts = new Prompts();
    private Debug debug = new Debug();
    private OpenRouterConfig openrouter = new OpenRouterConfig();
    private ExecutorConfig executor = new ExecutorConfig();
    private EventStoreConfig eventStore = new EventStoreConfig();
    private DataFreshness dataFreshness = new DataFreshness();
    private Tools tools = new Tools();
    private Agent agent = new Agent();
    private FinanceMethodResolver financeMethodResolver = new FinanceMethodResolver();

    public String getDefaultEndpoint() {
        return defaultEndpoint;
    }

    public void setDefaultEndpoint(String defaultEndpoint) {
        this.defaultEndpoint = defaultEndpoint;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, Endpoint> endpoints) {
        this.endpoints = endpoints == null ? new HashMap<>() : endpoints;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models == null ? new ArrayList<>() : models;
    }

    public Prompts getPrompts() {
        return prompts;
    }

    public void setPrompts(Prompts prompts) {
        this.prompts = prompts == null ? new Prompts() : prompts;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public void setRuntime(Runtime runtime) {
        this.runtime = runtime == null ? new Runtime() : runtime;
    }

    public Observability getObservability() {
        return observability;
    }

    public void setObservability(Observability observability) {
        this.observability = observability == null ? new Observability() : observability;
    }

    public Debug getDebug() {
        return debug;
    }

    public void setDebug(Debug debug) {
        this.debug = debug == null ? new Debug() : debug;
    }

    public OpenRouterConfig getOpenrouter() {
        return openrouter;
    }

    public void setOpenrouter(OpenRouterConfig openrouter) {
        this.openrouter = openrouter == null ? new OpenRouterConfig() : openrouter;
    }

    public ExecutorConfig getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorConfig executor) {
        this.executor = executor == null ? new ExecutorConfig() : executor;
    }

    public EventStoreConfig getEventStore() {
        return eventStore;
    }

    public void setEventStore(EventStoreConfig eventStore) {
        this.eventStore = eventStore == null ? new EventStoreConfig() : eventStore;
    }

    public DataFreshness getDataFreshness() {
        return dataFreshness;
    }

    public void setDataFreshness(DataFreshness dataFreshness) {
        this.dataFreshness = dataFreshness == null ? new DataFreshness() : dataFreshness;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools == null ? new Tools() : tools;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent == null ? new Agent() : agent;
    }

    public FinanceMethodResolver getFinanceMethodResolver() {
        return financeMethodResolver;
    }

    public void setFinanceMethodResolver(FinanceMethodResolver financeMethodResolver) {
        this.financeMethodResolver = financeMethodResolver == null ? new FinanceMethodResolver() : financeMethodResolver;
    }

    /**
     * Agent-level feature toggles loaded from agent-llm.json. This intentionally mirrors
     * production-facing {@code agent.*} config names so Nacos pushes can hot-reload them.
     */
    public static class Agent {
        @JsonAlias({"call-raw-content", "call_raw_content"})
        private CallRawContent callRawContent = new CallRawContent();
        private Workspace workspace = new Workspace();
        private Dataset dataset = new Dataset();

        public CallRawContent getCallRawContent() {
            return callRawContent;
        }

        public void setCallRawContent(CallRawContent callRawContent) {
            this.callRawContent = callRawContent == null ? new CallRawContent() : callRawContent;
        }

        public Workspace getWorkspace() {
            return workspace;
        }

        public void setWorkspace(Workspace workspace) {
            this.workspace = workspace == null ? new Workspace() : workspace;
        }

        public Dataset getDataset() {
            return dataset;
        }

        public void setDataset(Dataset dataset) {
            this.dataset = dataset == null ? new Dataset() : dataset;
        }
    }

    public static class CallRawContent {
        @JsonAlias({"ttl-seconds", "ttl_seconds"})
        private Long ttlSeconds;

        public Long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(Long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class Workspace {
        private Dump dump = new Dump();

        public Dump getDump() {
            return dump;
        }

        public void setDump(Dump dump) {
            this.dump = dump == null ? new Dump() : dump;
        }
    }

    public static class Dump {
        @JsonAlias({"ttl-hours", "ttl_hours"})
        private Integer ttlHours;

        public Integer getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(Integer ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    public static class Dataset {
        @JsonAlias({"ttl-hours", "ttl_hours"})
        private Integer ttlHours;

        public Integer getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(Integer ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    /**
     * Tool-level feature toggles that must hot-reload from agent-llm.json.
     */
    public static class Tools {
        @JsonAlias({"market-data", "market_data"})
        private MarketData marketData = new MarketData();
        private ToolResult result = new ToolResult();
        private ToolSummary summary = new ToolSummary();
        private ToolReread reread = new ToolReread();
        @JsonAlias({"raw-ref", "raw_ref"})
        private ToolRawRef rawRef = new ToolRawRef();
        private Rag rag = new Rag();

        public MarketData getMarketData() {
            return marketData;
        }

        public void setMarketData(MarketData marketData) {
            this.marketData = marketData == null ? new MarketData() : marketData;
        }

        public ToolResult getResult() {
            return result;
        }

        public void setResult(ToolResult result) {
            this.result = result == null ? new ToolResult() : result;
        }

        public ToolSummary getSummary() {
            return summary;
        }

        public void setSummary(ToolSummary summary) {
            this.summary = summary == null ? new ToolSummary() : summary;
        }

        public ToolReread getReread() {
            return reread;
        }

        public void setReread(ToolReread reread) {
            this.reread = reread == null ? new ToolReread() : reread;
        }

        public ToolRawRef getRawRef() {
            return rawRef;
        }

        public void setRawRef(ToolRawRef rawRef) {
            this.rawRef = rawRef == null ? new ToolRawRef() : rawRef;
        }

        public Rag getRag() {
            return rag;
        }

        public void setRag(Rag rag) {
            this.rag = rag == null ? new Rag() : rag;
        }
    }

    public static class Rag {
        @JsonAlias({"visible-chars", "visible_chars"})
        private Integer visibleChars;
        @JsonAlias({"preview-chars", "preview_chars"})
        private Integer previewChars;
        @JsonAlias({"snippet-cap-per-doc", "snippet_cap_per_doc"})
        private Integer snippetCapPerDoc;
        @JsonAlias({"short-doc-full-threshold", "short_doc_full_threshold"})
        private Integer shortDocFullThreshold;

        public Integer getVisibleChars() { return visibleChars; }
        public void setVisibleChars(Integer visibleChars) { this.visibleChars = visibleChars; }
        public Integer getPreviewChars() { return previewChars; }
        public void setPreviewChars(Integer previewChars) { this.previewChars = previewChars; }
        public Integer getSnippetCapPerDoc() { return snippetCapPerDoc; }
        public void setSnippetCapPerDoc(Integer snippetCapPerDoc) { this.snippetCapPerDoc = snippetCapPerDoc; }
        public Integer getShortDocFullThreshold() { return shortDocFullThreshold; }
        public void setShortDocFullThreshold(Integer shortDocFullThreshold) { this.shortDocFullThreshold = shortDocFullThreshold; }
    }

    public static class ToolResult {
        @JsonAlias({"max-string-length", "max_string_length"})
        private Integer maxStringLength;
        @JsonAlias({"summary-model", "summary_model"})
        private String summaryModel;
        @JsonAlias({"summary-endpoint", "summary_endpoint"})
        private String summaryEndpoint;

        public Integer getMaxStringLength() {
            return maxStringLength;
        }

        public void setMaxStringLength(Integer maxStringLength) {
            this.maxStringLength = maxStringLength;
        }

        public String getSummaryModel() {
            return summaryModel;
        }

        public void setSummaryModel(String summaryModel) {
            this.summaryModel = summaryModel;
        }

        public String getSummaryEndpoint() {
            return summaryEndpoint;
        }

        public void setSummaryEndpoint(String summaryEndpoint) {
            this.summaryEndpoint = summaryEndpoint;
        }
    }

    public static class ToolSummary {
        @JsonAlias({"max-retries", "max_retries"})
        private Integer maxRetries;

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public static class ToolReread {
        @JsonAlias({"max-limit", "max_limit"})
        private Integer maxLimit;
        @JsonAlias({"keyword-char-limit", "keyword_char_limit"})
        private Integer keywordCharLimit;
        @JsonAlias({"range-max-limit", "range_max_limit"})
        private Integer rangeMaxLimit;
        @JsonAlias({"range-min-limit-without-keyword", "range_min_limit_without_keyword"})
        private Integer rangeMinLimitWithoutKeyword;

        public Integer getMaxLimit() { return maxLimit; }
        public void setMaxLimit(Integer maxLimit) { this.maxLimit = maxLimit; }
        public Integer getKeywordCharLimit() { return keywordCharLimit; }
        public void setKeywordCharLimit(Integer keywordCharLimit) { this.keywordCharLimit = keywordCharLimit; }
        public Integer getRangeMaxLimit() { return rangeMaxLimit; }
        public void setRangeMaxLimit(Integer rangeMaxLimit) { this.rangeMaxLimit = rangeMaxLimit; }
        public Integer getRangeMinLimitWithoutKeyword() { return rangeMinLimitWithoutKeyword; }
        public void setRangeMinLimitWithoutKeyword(Integer rangeMinLimitWithoutKeyword) { this.rangeMinLimitWithoutKeyword = rangeMinLimitWithoutKeyword; }
    }

    public static class ToolRawRef {
        @JsonAlias({"ttl-hours", "ttl_hours"})
        private Integer ttlHours;
        @JsonAlias({"ttl-seconds", "ttl_seconds"})
        private Integer ttlSeconds;

        public Integer getTtlHours() { return ttlHours; }
        public void setTtlHours(Integer ttlHours) { this.ttlHours = ttlHours; }
        public Integer getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(Integer ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    public static class MarketData {
        private MarketDataDataset dataset = new MarketDataDataset();
        private MarketDataBatch batch = new MarketDataBatch();
        private MarketDataAdvanced advanced = new MarketDataAdvanced();

        public MarketDataDataset getDataset() {
            return dataset;
        }

        public void setDataset(MarketDataDataset dataset) {
            this.dataset = dataset == null ? new MarketDataDataset() : dataset;
        }

        public MarketDataBatch getBatch() {
            return batch;
        }

        public void setBatch(MarketDataBatch batch) {
            this.batch = batch == null ? new MarketDataBatch() : batch;
        }

        public MarketDataAdvanced getAdvanced() {
            return advanced;
        }

        public void setAdvanced(MarketDataAdvanced advanced) {
            this.advanced = advanced == null ? new MarketDataAdvanced() : advanced;
        }
    }

    public static class MarketDataDataset {
        private Boolean enabled;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class MarketDataBatch {
        @JsonAlias({"emit-manifest", "emit_manifest"})
        private Boolean emitManifest;

        public Boolean getEmitManifest() {
            return emitManifest;
        }

        public void setEmitManifest(Boolean emitManifest) {
            this.emitManifest = emitManifest;
        }
    }

    public static class MarketDataAdvanced {
        @JsonAlias({"preview-rows", "preview_rows"})
        private Integer previewRows;

        public Integer getPreviewRows() {
            return previewRows;
        }

        public void setPreviewRows(Integer previewRows) {
            this.previewRows = previewRows;
        }
    }

    public static class DataFreshness {
        /** 部署者声明的本地已爬取数据起始日期，格式 YYYY-MM-DD。 */
        private String startDate;
        /** 部署者声明的本地已爬取数据截止日期，格式 YYYY-MM-DD。 */
        private String endDate;
        /** 可选：部署者声明的单点 as-of 日期，格式 YYYY-MM-DD。 */
        private String asOfDate;
        /** 可选：数据范围说明，例如覆盖的资产类型或口径。 */
        private String description;

        public String getStartDate() {
            return startDate;
        }

        public void setStartDate(String startDate) {
            this.startDate = startDate;
        }

        public String getEndDate() {
            return endDate;
        }

        public void setEndDate(String endDate) {
            this.endDate = endDate;
        }

        public String getAsOfDate() {
            return asOfDate;
        }

        public void setAsOfDate(String asOfDate) {
            this.asOfDate = asOfDate;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class Endpoint {
        private String baseUrl;
        private String apiKey;
        private String region;
        /**
         * 新配置支持在 endpoint 下声明模型元信息：
         * endpoint -> models -> modelId -> metadata。
         */
        private Map<String, ModelMetadata> models = new HashMap<>();

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

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public Map<String, ModelMetadata> getModels() {
            return models;
        }

        public void setModels(Map<String, ModelMetadata> models) {
            this.models = models == null ? new HashMap<>() : models;
        }
    }

    public static class ModelMetadata {
        private String displayName;
        private Double baseRate;
        private List<String> features = new ArrayList<>();
        private List<String> validProviders = new ArrayList<>();
        /** 模型级 max_completion_tokens 覆盖；null 则使用 endpoint / 全局默认值 */
        private Integer maxTokens;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Double getBaseRate() {
            return baseRate;
        }

        public void setBaseRate(Double baseRate) {
            this.baseRate = baseRate;
        }

        public List<String> getFeatures() {
            return features;
        }

        public void setFeatures(List<String> features) {
            this.features = features == null ? new ArrayList<>() : features;
        }

        public List<String> getValidProviders() {
            return validProviders;
        }

        public void setValidProviders(List<String> validProviders) {
            this.validProviders = validProviders == null ? new ArrayList<>() : validProviders;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    public static class Runtime {
        private Resume resume = new Resume();
        private Cache cache = new Cache();
        private Execution execution = new Execution();
        private Planning planning = new Planning();
        private Parallel parallel = new Parallel();
        private SubAgent subAgent = new SubAgent();
        private Judge judge = new Judge();
        private MultiTurn multiTurn = new MultiTurn();
        private RunBudget runBudget = new RunBudget();
        private FinalAnswerStage finalAnswer = new FinalAnswerStage();
        private Request request = new Request();

        public Resume getResume() {
            return resume;
        }

        public void setResume(Resume resume) {
            this.resume = resume == null ? new Resume() : resume;
        }

        public Cache getCache() {
            return cache;
        }

        public void setCache(Cache cache) {
            this.cache = cache == null ? new Cache() : cache;
        }

        public Execution getExecution() {
            return execution;
        }

        public void setExecution(Execution execution) {
            this.execution = execution == null ? new Execution() : execution;
        }

        public Planning getPlanning() {
            return planning;
        }

        public void setPlanning(Planning planning) {
            this.planning = planning == null ? new Planning() : planning;
        }

        public Parallel getParallel() {
            return parallel;
        }

        public void setParallel(Parallel parallel) {
            this.parallel = parallel == null ? new Parallel() : parallel;
        }

        public SubAgent getSubAgent() {
            return subAgent;
        }

        public void setSubAgent(SubAgent subAgent) {
            this.subAgent = subAgent == null ? new SubAgent() : subAgent;
        }

        public Judge getJudge() {
            return judge;
        }

        public void setJudge(Judge judge) {
            this.judge = judge == null ? new Judge() : judge;
        }

        public MultiTurn getMultiTurn() {
            return multiTurn;
        }

        public void setMultiTurn(MultiTurn multiTurn) {
            this.multiTurn = multiTurn == null ? new MultiTurn() : multiTurn;
        }

        public RunBudget getRunBudget() {
            return runBudget;
        }

        public void setRunBudget(RunBudget runBudget) {
            this.runBudget = runBudget == null ? new RunBudget() : runBudget;
        }

        public FinalAnswerStage getFinalAnswer() {
            return finalAnswer;
        }

        public void setFinalAnswer(FinalAnswerStage finalAnswer) {
            this.finalAnswer = finalAnswer == null ? new FinalAnswerStage() : finalAnswer;
        }

        public Request getRequest() {
            return request;
        }

        public void setRequest(Request request) {
            this.request = request == null ? new Request() : request;
        }
    }

    public static class Request {
        @JsonAlias({"max-retries", "max_retries"})
        private Integer maxRetries;
        private Retry retry = new Retry();

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry == null ? new Retry() : retry;
        }
    }

    public static class Retry {
        @JsonAlias({"backoff-type", "backoff_type"})
        private String backoffType;
        @JsonAlias({"base-delay-ms", "base_delay_ms"})
        private Long baseDelayMs;
        @JsonAlias({"max-delay-ms", "max_delay_ms"})
        private Long maxDelayMs;
        @JsonAlias({"jitter-ms", "jitter_ms"})
        private Long jitterMs;

        public String getBackoffType() {
            return backoffType;
        }

        public void setBackoffType(String backoffType) {
            this.backoffType = backoffType;
        }

        public Long getBaseDelayMs() {
            return baseDelayMs;
        }

        public void setBaseDelayMs(Long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
        }

        public Long getMaxDelayMs() {
            return maxDelayMs;
        }

        public void setMaxDelayMs(Long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
        }

        public Long getJitterMs() {
            return jitterMs;
        }

        public void setJitterMs(Long jitterMs) {
            this.jitterMs = jitterMs;
        }
    }

    public static class Resume {
        private Integer interruptedTtlDays;

        public Integer getInterruptedTtlDays() {
            return interruptedTtlDays;
        }

        public void setInterruptedTtlDays(Integer interruptedTtlDays) {
            this.interruptedTtlDays = interruptedTtlDays;
        }
    }

    public static class Cache {
        private String version;
        private Integer searchTtlSeconds;
        private Integer infoTtlSeconds;
        private Integer datasetTtlSeconds;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public Integer getSearchTtlSeconds() {
            return searchTtlSeconds;
        }

        public void setSearchTtlSeconds(Integer searchTtlSeconds) {
            this.searchTtlSeconds = searchTtlSeconds;
        }

        public Integer getInfoTtlSeconds() {
            return infoTtlSeconds;
        }

        public void setInfoTtlSeconds(Integer infoTtlSeconds) {
            this.infoTtlSeconds = infoTtlSeconds;
        }

        public Integer getDatasetTtlSeconds() {
            return datasetTtlSeconds;
        }

        public void setDatasetTtlSeconds(Integer datasetTtlSeconds) {
            this.datasetTtlSeconds = datasetTtlSeconds;
        }
    }

    public static class Planning {
        private Integer maxTodos;
        /** 客户端可请求的 maxTodos 上限，超过则拒绝执行。null 表示不限制。 */
        private Integer maxTodosClientCap;
        /** Planning 阶段专用 endpoint，未配置则使用 execution 阶段模型 */
        private String endpointName;
        /** Planning 阶段专用 model，未配置则使用 execution 阶段模型 */
        private String modelName;
        /** 单次 LLM 输出 token 上限 */
        private Integer maxTokens;
        /** OpenRouter reasoning (thinking) 配置 */
        private Reasoning reasoning = new Reasoning();
        private StructuredOutput structuredOutput = new StructuredOutput();

        public Integer getMaxTodos() {
            return maxTodos;
        }

        public void setMaxTodos(Integer maxTodos) {
            this.maxTodos = maxTodos;
        }

        public Integer getMaxTodosClientCap() {
            return maxTodosClientCap;
        }

        public void setMaxTodosClientCap(Integer maxTodosClientCap) {
            this.maxTodosClientCap = maxTodosClientCap;
        }

        public String getEndpointName() {
            return endpointName;
        }

        public void setEndpointName(String endpointName) {
            this.endpointName = endpointName;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Reasoning getReasoning() {
            return reasoning;
        }

        public void setReasoning(Reasoning reasoning) {
            this.reasoning = reasoning == null ? new Reasoning() : reasoning;
        }

        public StructuredOutput getStructuredOutput() {
            return structuredOutput;
        }

        public void setStructuredOutput(StructuredOutput structuredOutput) {
            this.structuredOutput = structuredOutput == null ? new StructuredOutput() : structuredOutput;
        }
    }

    public static class FinalAnswerStage {
        private Integer maxTokens;
        private Reasoning reasoning = new Reasoning();

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Reasoning getReasoning() {
            return reasoning;
        }

        public void setReasoning(Reasoning reasoning) {
            this.reasoning = reasoning == null ? new Reasoning() : reasoning;
        }
    }

    public static class Execution {
        private String mode;
        private Integer maxToolCalls;
        private Integer maxToolCallsPerSubAgent;
        private Integer maxRetriesPerTodo;
        private Boolean staticPrecheckEnabled;
        private Integer maxStaticRecoveryRetries;
        private Integer maxRuntimeRecoveryRetries;
        private Integer maxSemanticRecoveryRetries;
        private Integer maxTotalRecoveryRetries;
        private String staticFixEndpoint;
        private String staticFixModel;
        private Double staticFixTemperature;
        private Boolean failFast;
        private String defaultExecutionMode;
        /** 为 true 时才允许抓取/查询 ETF 复权因子并暴露 getEtfAdj */
        private Boolean adjFactorEnabled = false;
        /** 单次 LLM 输出 token 上限（execution 阶段） */
        private Integer maxTokens;
        /** OpenRouter reasoning (thinking) 配置 */
        private Reasoning reasoning = new Reasoning();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Integer getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(Integer maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public Integer getMaxToolCallsPerSubAgent() {
            return maxToolCallsPerSubAgent;
        }

        public void setMaxToolCallsPerSubAgent(Integer maxToolCallsPerSubAgent) {
            this.maxToolCallsPerSubAgent = maxToolCallsPerSubAgent;
        }

        public Integer getMaxRetriesPerTodo() {
            return maxRetriesPerTodo;
        }

        public void setMaxRetriesPerTodo(Integer maxRetriesPerTodo) {
            this.maxRetriesPerTodo = maxRetriesPerTodo;
        }

        public Boolean getStaticPrecheckEnabled() {
            return staticPrecheckEnabled;
        }

        public void setStaticPrecheckEnabled(Boolean staticPrecheckEnabled) {
            this.staticPrecheckEnabled = staticPrecheckEnabled;
        }

        public Integer getMaxStaticRecoveryRetries() {
            return maxStaticRecoveryRetries;
        }

        public void setMaxStaticRecoveryRetries(Integer maxStaticRecoveryRetries) {
            this.maxStaticRecoveryRetries = maxStaticRecoveryRetries;
        }

        public Integer getMaxRuntimeRecoveryRetries() {
            return maxRuntimeRecoveryRetries;
        }

        public void setMaxRuntimeRecoveryRetries(Integer maxRuntimeRecoveryRetries) {
            this.maxRuntimeRecoveryRetries = maxRuntimeRecoveryRetries;
        }

        public Integer getMaxSemanticRecoveryRetries() {
            return maxSemanticRecoveryRetries;
        }

        public void setMaxSemanticRecoveryRetries(Integer maxSemanticRecoveryRetries) {
            this.maxSemanticRecoveryRetries = maxSemanticRecoveryRetries;
        }

        public Integer getMaxTotalRecoveryRetries() {
            return maxTotalRecoveryRetries;
        }

        public void setMaxTotalRecoveryRetries(Integer maxTotalRecoveryRetries) {
            this.maxTotalRecoveryRetries = maxTotalRecoveryRetries;
        }

        public String getStaticFixEndpoint() {
            return staticFixEndpoint;
        }

        public void setStaticFixEndpoint(String staticFixEndpoint) {
            this.staticFixEndpoint = staticFixEndpoint;
        }

        public String getStaticFixModel() {
            return staticFixModel;
        }

        public void setStaticFixModel(String staticFixModel) {
            this.staticFixModel = staticFixModel;
        }

        public Double getStaticFixTemperature() {
            return staticFixTemperature;
        }

        public void setStaticFixTemperature(Double staticFixTemperature) {
            this.staticFixTemperature = staticFixTemperature;
        }

        public Boolean getFailFast() {
            return failFast;
        }

        public void setFailFast(Boolean failFast) {
            this.failFast = failFast;
        }

        public String getDefaultExecutionMode() {
            return defaultExecutionMode;
        }

        public void setDefaultExecutionMode(String defaultExecutionMode) {
            this.defaultExecutionMode = defaultExecutionMode;
        }

        public Boolean getAdjFactorEnabled() {
            return adjFactorEnabled;
        }

        public void setAdjFactorEnabled(Boolean adjFactorEnabled) {
            this.adjFactorEnabled = adjFactorEnabled;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Reasoning getReasoning() {
            return reasoning;
        }

        public void setReasoning(Reasoning reasoning) {
            this.reasoning = reasoning == null ? new Reasoning() : reasoning;
        }
    }

    /**
     * Run 级资源预算（agent-llm.json runtime.runBudget，Nacos 推送后可热生效）。
     */
    public static class RunBudget {
        private Long maxWallClockMs;
        private Long maxLlmCalls;
        private Long maxToolCalls;
        private Long maxTokens;
        private Integer maxHttpAttemptsPerLogicalCall;

        public Long getMaxWallClockMs() {
            return maxWallClockMs;
        }

        public void setMaxWallClockMs(Long maxWallClockMs) {
            this.maxWallClockMs = maxWallClockMs;
        }

        public Long getMaxLlmCalls() {
            return maxLlmCalls;
        }

        public void setMaxLlmCalls(Long maxLlmCalls) {
            this.maxLlmCalls = maxLlmCalls;
        }

        public Long getMaxToolCalls() {
            return maxToolCalls;
        }

        public void setMaxToolCalls(Long maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
        }

        public Long getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Long maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Integer getMaxHttpAttemptsPerLogicalCall() {
            return maxHttpAttemptsPerLogicalCall;
        }

        public void setMaxHttpAttemptsPerLogicalCall(Integer maxHttpAttemptsPerLogicalCall) {
            this.maxHttpAttemptsPerLogicalCall = maxHttpAttemptsPerLogicalCall;
        }
    }

    public static class Parallel {
        private Integer maxParallelSearchQueries;
        private Integer maxParallelDailyQueries;
        private Integer maxParallelCalendarQueries;
        @JsonAlias({"max-parallel-queries-in-advanced-mode", "max_parallel_queries_in_advanced_mode"})
        private Integer maxParallelQueriesInAdvancedMode;
        @JsonAlias({"max-advanced-daily-constituent-stocks", "max_advanced_daily_constituent_stocks"})
        private Integer maxAdvancedDailyConstituentStocks;
        private Integer dagThreadPoolSize;
        private ExternalSearch externalSearch = new ExternalSearch();
        private ToolWeightedLimit toolWeightedLimit = new ToolWeightedLimit();

        public Integer getMaxParallelSearchQueries() {
            return maxParallelSearchQueries;
        }

        public void setMaxParallelSearchQueries(Integer maxParallelSearchQueries) {
            this.maxParallelSearchQueries = maxParallelSearchQueries;
        }

        public Integer getMaxParallelDailyQueries() {
            return maxParallelDailyQueries;
        }

        public void setMaxParallelDailyQueries(Integer maxParallelDailyQueries) {
            this.maxParallelDailyQueries = maxParallelDailyQueries;
        }

        public Integer getMaxParallelCalendarQueries() {
            return maxParallelCalendarQueries;
        }

        public void setMaxParallelCalendarQueries(Integer maxParallelCalendarQueries) {
            this.maxParallelCalendarQueries = maxParallelCalendarQueries;
        }

        public Integer getMaxParallelQueriesInAdvancedMode() {
            return maxParallelQueriesInAdvancedMode;
        }

        public void setMaxParallelQueriesInAdvancedMode(Object maxParallelQueriesInAdvancedMode) {
            this.maxParallelQueriesInAdvancedMode = parseIntegerOrOne(maxParallelQueriesInAdvancedMode);
        }

        public Integer getMaxAdvancedDailyConstituentStocks() {
            return maxAdvancedDailyConstituentStocks;
        }

        public void setMaxAdvancedDailyConstituentStocks(Integer maxAdvancedDailyConstituentStocks) {
            this.maxAdvancedDailyConstituentStocks = maxAdvancedDailyConstituentStocks;
        }

        public Integer getDagThreadPoolSize() {
            return dagThreadPoolSize;
        }

        public void setDagThreadPoolSize(Integer dagThreadPoolSize) {
            this.dagThreadPoolSize = dagThreadPoolSize;
        }

        public ExternalSearch getExternalSearch() {
            return externalSearch;
        }

        public void setExternalSearch(ExternalSearch externalSearch) {
            this.externalSearch = externalSearch == null ? new ExternalSearch() : externalSearch;
        }

        public ToolWeightedLimit getToolWeightedLimit() {
            return toolWeightedLimit;
        }

        public void setToolWeightedLimit(ToolWeightedLimit toolWeightedLimit) {
            this.toolWeightedLimit = toolWeightedLimit == null ? new ToolWeightedLimit() : toolWeightedLimit;
        }
    }

    private static Integer parseIntegerOrOne(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public static class ExternalSearch {
        private Integer maxConcurrent;
        private ProviderLimits providerLimits = new ProviderLimits();

        public Integer getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(Integer maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public ProviderLimits getProviderLimits() {
            return providerLimits;
        }

        public void setProviderLimits(ProviderLimits providerLimits) {
            this.providerLimits = providerLimits == null ? new ProviderLimits() : providerLimits;
        }
    }

    public static class ProviderLimits {
        private Integer perplexity;
        private Integer exa;
        private Integer tavily;

        public Integer getPerplexity() {
            return perplexity;
        }

        public void setPerplexity(Integer perplexity) {
            this.perplexity = perplexity;
        }

        public Integer getExa() {
            return exa;
        }

        public void setExa(Integer exa) {
            this.exa = exa;
        }

        public Integer getTavily() {
            return tavily;
        }

        public void setTavily(Integer tavily) {
            this.tavily = tavily;
        }
    }

    public static class ToolWeightedLimit {
        private Boolean enabled;
        private Integer maxWeight;
        private Integer defaultWeight;
        private java.util.Map<String, ToolWeightEntry> tools = new java.util.HashMap<>();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxWeight() {
            return maxWeight;
        }

        public void setMaxWeight(Integer maxWeight) {
            this.maxWeight = maxWeight;
        }

        public Integer getDefaultWeight() {
            return defaultWeight;
        }

        public void setDefaultWeight(Integer defaultWeight) {
            this.defaultWeight = defaultWeight;
        }

        public java.util.Map<String, ToolWeightEntry> getTools() {
            return tools;
        }

        public void setTools(java.util.Map<String, ToolWeightEntry> tools) {
            this.tools = tools == null ? new java.util.HashMap<>() : tools;
        }
    }

    public static class ToolWeightEntry {
        private Integer weight;
        private Integer maxBatchItems;
        private Boolean requiresAdjFactorEnabled;

        public Integer getWeight() {
            return weight;
        }

        public void setWeight(Integer weight) {
            this.weight = weight;
        }

        public Integer getMaxBatchItems() {
            return maxBatchItems;
        }

        public void setMaxBatchItems(Integer maxBatchItems) {
            this.maxBatchItems = maxBatchItems;
        }

        public Boolean getRequiresAdjFactorEnabled() {
            return requiresAdjFactorEnabled;
        }

        public void setRequiresAdjFactorEnabled(Boolean requiresAdjFactorEnabled) {
            this.requiresAdjFactorEnabled = requiresAdjFactorEnabled;
        }

        /** 配置缺省时视为 false，避免 Nacos/JSON 遗漏字段导致 NPE 或歧义。 */
        public boolean isRequiresAdjFactorEnabled() {
            return Boolean.TRUE.equals(requiresAdjFactorEnabled);
        }
    }

    public static class SubAgent {
        private Boolean enabled;
        private String complexityThreshold;
        private Integer maxSteps;
        /** 单个 Todo 内最多并行启动的子代理数量。*/
        private Integer maxCount;
        private String endpointName;
        private String modelName;
        private String lowComplexityModelName;
        private String mediumComplexityModelName;
        private String highComplexityModelName;
        /** OpenRouter reasoning (thinking) 配置 */
        private Reasoning reasoning = new Reasoning();
        private StructuredOutput structuredOutput = new StructuredOutput();
        private Placeholder placeholder = new Placeholder();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getComplexityThreshold() {
            return complexityThreshold;
        }

        public void setComplexityThreshold(String complexityThreshold) {
            this.complexityThreshold = complexityThreshold;
        }

        public Integer getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(Integer maxSteps) {
            this.maxSteps = maxSteps;
        }

        public Integer getMaxCount() {
            return maxCount;
        }

        public void setMaxCount(Integer maxCount) {
            this.maxCount = maxCount;
        }

        public String getEndpointName() {
            return endpointName;
        }

        public void setEndpointName(String endpointName) {
            this.endpointName = endpointName;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public String getLowComplexityModelName() {
            return lowComplexityModelName;
        }

        public void setLowComplexityModelName(String lowComplexityModelName) {
            this.lowComplexityModelName = lowComplexityModelName;
        }

        public String getMediumComplexityModelName() {
            return mediumComplexityModelName;
        }

        public void setMediumComplexityModelName(String mediumComplexityModelName) {
            this.mediumComplexityModelName = mediumComplexityModelName;
        }

        public String getHighComplexityModelName() {
            return highComplexityModelName;
        }

        public void setHighComplexityModelName(String highComplexityModelName) {
            this.highComplexityModelName = highComplexityModelName;
        }

        public StructuredOutput getStructuredOutput() {
            return structuredOutput;
        }

        public void setStructuredOutput(StructuredOutput structuredOutput) {
            this.structuredOutput = structuredOutput == null ? new StructuredOutput() : structuredOutput;
        }

        public Placeholder getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(Placeholder placeholder) {
            this.placeholder = placeholder == null ? new Placeholder() : placeholder;
        }

        public Reasoning getReasoning() {
            return reasoning;
        }

        public void setReasoning(Reasoning reasoning) {
            this.reasoning = reasoning == null ? new Reasoning() : reasoning;
        }
    }

    public static class StructuredOutput {
        private Boolean enabled;
        private Integer maxAttempts;
        private Boolean strict;
        private Boolean failOnExhaustedRetries;
        private Boolean requireProviderParameters;
        private Boolean allowProviderFallbacks;
        /** 第一阶段是否启用结构化输出（默认 true） */
        private Boolean strategyStageEnabled = true;
        /** 第一阶段 detail 最大长度（字符数），默认 500 */
        private Integer strategyMaxDetailLength = 500;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Boolean getStrict() {
            return strict;
        }

        public void setStrict(Boolean strict) {
            this.strict = strict;
        }

        public Boolean getFailOnExhaustedRetries() {
            return failOnExhaustedRetries;
        }

        public void setFailOnExhaustedRetries(Boolean failOnExhaustedRetries) {
            this.failOnExhaustedRetries = failOnExhaustedRetries;
        }

        public Boolean getRequireProviderParameters() {
            return requireProviderParameters;
        }

        public void setRequireProviderParameters(Boolean requireProviderParameters) {
            this.requireProviderParameters = requireProviderParameters;
        }

        public Boolean getAllowProviderFallbacks() {
            return allowProviderFallbacks;
        }

        public void setAllowProviderFallbacks(Boolean allowProviderFallbacks) {
            this.allowProviderFallbacks = allowProviderFallbacks;
        }

        public Boolean getStrategyStageEnabled() {
            return strategyStageEnabled;
        }

        public void setStrategyStageEnabled(Boolean strategyStageEnabled) {
            this.strategyStageEnabled = strategyStageEnabled;
        }

        public Integer getStrategyMaxDetailLength() {
            return strategyMaxDetailLength;
        }

        public void setStrategyMaxDetailLength(Integer strategyMaxDetailLength) {
            this.strategyMaxDetailLength = strategyMaxDetailLength;
        }
    }

    public static class Placeholder {
        private Boolean resolveStepAlias;
        private Boolean resolveTodoAlias;

        public Boolean getResolveStepAlias() {
            return resolveStepAlias;
        }

        public void setResolveStepAlias(Boolean resolveStepAlias) {
            this.resolveStepAlias = resolveStepAlias;
        }

        public Boolean getResolveTodoAlias() {
            return resolveTodoAlias;
        }

        public void setResolveTodoAlias(Boolean resolveTodoAlias) {
            this.resolveTodoAlias = resolveTodoAlias;
        }
    }

    /**
     * OpenRouter reasoning (thinking) 配置。
     * <p>用于控制 reasoning 模型的思考强度。</p>
     */
    public static class Reasoning {
        /** 是否启用 reasoning (thinking) */
        private Boolean enabled;
        /** reasoning effort 级别: xhigh, high, medium, low, minimal, none */
        private String effort;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getEffort() {
            return effort;
        }

        public void setEffort(String effort) {
            this.effort = effort;
        }

        /**
         * 解析并返回有效的 effort 值。
         * <p>如果 enabled 为 null（未配置），返回 null，表示不发送 reasoning 参数（使用模型默认行为）。</p>
         * <p>如果 enabled 为 false，返回 "none" 以显式关闭 reasoning。</p>
         * <p>如果 enabled 为 true 但 effort 未配置，返回 "medium" 作为默认值。</p>
         *
         * @return 有效的 effort 值，或 null 表示不发送 reasoning 参数
         */
        public String resolveEffort() {
            // enabled 未配置（null）：不发送 reasoning 参数，使用模型默认行为
            if (enabled == null) {
                return null;
            }
            // enabled 为 false：显式关闭 reasoning
            if (!enabled) {
                return "none";
            }
            // enabled 为 true：解析 effort 值
            String e = effort == null ? null : effort.trim().toLowerCase();
            if (e == null || e.isEmpty()) {
                return "medium"; // 默认值
            }
            // 验证有效值
            return switch (e) {
                case "xhigh", "high", "medium", "low", "minimal", "none" -> e;
                default -> "medium";
            };
        }
    }

    public static class Judge {
        private Boolean enabled;
        private Boolean semanticEnabled;
        private Double temperature;
        private Integer maxAttempts;
        private Boolean failOpen;
        private Boolean blockOnInsufficientEvidence;
        /**
         * @deprecated Prefer run {@code stage_config.search_judge} or inherit current phase model.
         * Ordered route list kept for backward compatibility only.
         */
        @Deprecated
        private List<JudgeRoute> routes = new ArrayList<>();

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getSemanticEnabled() {
            return semanticEnabled;
        }

        public void setSemanticEnabled(Boolean semanticEnabled) {
            this.semanticEnabled = semanticEnabled;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Boolean getFailOpen() {
            return failOpen;
        }

        public void setFailOpen(Boolean failOpen) {
            this.failOpen = failOpen;
        }

        public Boolean getBlockOnInsufficientEvidence() {
            return blockOnInsufficientEvidence;
        }

        public void setBlockOnInsufficientEvidence(Boolean blockOnInsufficientEvidence) {
            this.blockOnInsufficientEvidence = blockOnInsufficientEvidence;
        }

        public List<JudgeRoute> getRoutes() {
            return routes;
        }

        public void setRoutes(List<JudgeRoute> routes) {
            this.routes = routes == null ? new ArrayList<>() : routes;
        }
    }

    public static class JudgeRoute {
        private String endpointName;
        private List<String> models = new ArrayList<>();

        public String getEndpointName() {
            return endpointName;
        }

        public void setEndpointName(String endpointName) {
            this.endpointName = endpointName;
        }

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models == null ? new ArrayList<>() : models;
        }
    }

    public static class MultiTurn {
        private Compression compression = new Compression();

        public Compression getCompression() {
            return compression;
        }

        public void setCompression(Compression compression) {
            this.compression = compression == null ? new Compression() : compression;
        }
    }

    public static class Compression {
        private Boolean enabled;
        private String strategy;
        private String summaryEndpoint;
        private String summaryModel;
        private List<String> summaryProviderOrder = new ArrayList<>();
        private Integer summaryMaxChars;
        private Double summaryTemperature;
        private Integer minMessagesForCompression;
        private Integer minMessagesForSummary;
        private Integer summaryMaxMessages;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getSummaryEndpoint() {
            return summaryEndpoint;
        }

        public void setSummaryEndpoint(String summaryEndpoint) {
            this.summaryEndpoint = summaryEndpoint;
        }

        public String getSummaryModel() {
            return summaryModel;
        }

        public void setSummaryModel(String summaryModel) {
            this.summaryModel = summaryModel;
        }

        public List<String> getSummaryProviderOrder() {
            return summaryProviderOrder;
        }

        public void setSummaryProviderOrder(List<String> summaryProviderOrder) {
            this.summaryProviderOrder = summaryProviderOrder == null ? new ArrayList<>() : summaryProviderOrder;
        }

        public Integer getSummaryMaxChars() {
            return summaryMaxChars;
        }

        public void setSummaryMaxChars(Integer summaryMaxChars) {
            this.summaryMaxChars = summaryMaxChars;
        }

        public Double getSummaryTemperature() {
            return summaryTemperature;
        }

        public void setSummaryTemperature(Double summaryTemperature) {
            this.summaryTemperature = summaryTemperature;
        }

        public Integer getMinMessagesForCompression() {
            return minMessagesForCompression;
        }

        public void setMinMessagesForCompression(Integer minMessagesForCompression) {
            this.minMessagesForCompression = minMessagesForCompression;
        }

        public Integer getMinMessagesForSummary() {
            return minMessagesForSummary;
        }

        public void setMinMessagesForSummary(Integer minMessagesForSummary) {
            this.minMessagesForSummary = minMessagesForSummary;
        }

        public Integer getSummaryMaxMessages() {
            return summaryMaxMessages;
        }

        public void setSummaryMaxMessages(Integer summaryMaxMessages) {
            this.summaryMaxMessages = summaryMaxMessages;
        }
    }

    public static class Observability {
        private OpenRouter openrouter = new OpenRouter();
        private StreamingProgress streamingProgress = new StreamingProgress();

        public OpenRouter getOpenrouter() {
            return openrouter;
        }

        public void setOpenrouter(OpenRouter openrouter) {
            this.openrouter = openrouter == null ? new OpenRouter() : openrouter;
        }

        public StreamingProgress getStreamingProgress() {
            return streamingProgress;
        }

        public void setStreamingProgress(StreamingProgress streamingProgress) {
            this.streamingProgress = streamingProgress == null ? new StreamingProgress() : streamingProgress;
        }
    }

    public static class OpenRouter {
        private CostEnrichment costEnrichment = new CostEnrichment();

        public CostEnrichment getCostEnrichment() {
            return costEnrichment;
        }

        public void setCostEnrichment(CostEnrichment costEnrichment) {
            this.costEnrichment = costEnrichment == null ? new CostEnrichment() : costEnrichment;
        }
    }

    public static class CostEnrichment {
        private Boolean enabled = false;
        private Integer timeoutMs = 5000;
        private Integer maxAttempts = 3;
        private Integer retryDelayMs = 1000;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getTimeoutMs() {
            return timeoutMs;
        }
        
        public void setTimeoutMs(Integer timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Integer getRetryDelayMs() {
            return retryDelayMs;
        }

        public void setRetryDelayMs(Integer retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
        }
    }

    public static class StreamingProgress {
        /** 是否把流式接收进度定期写入客户端可见的 observability */
        private Boolean enabled = true;
        /** 写入 observability 的最小间隔，避免压测时过度写 Redis */
        private Integer updateIntervalMs = 3000;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getUpdateIntervalMs() {
            return updateIntervalMs;
        }

        public void setUpdateIntervalMs(Integer updateIntervalMs) {
            this.updateIntervalMs = updateIntervalMs;
        }
    }

    /**
     * Debug 配置（热加载）。
     * 用于控制运行时调试日志输出，默认全部关闭。
     */
    public static class Debug {
        /** 是否打印 LLM 请求的 curl 命令 */
        private Boolean logLlmCurl = false;
        /** 是否打印阶段级 LLM 配置解析日志 */
        private Boolean logStageConfig = false;
        /** 是否打印 SSE 流式接收进度日志 */
        private Boolean logSseProgress = false;

        public Boolean getLogLlmCurl() {
            return logLlmCurl;
        }

        public void setLogLlmCurl(Boolean logLlmCurl) {
            this.logLlmCurl = logLlmCurl;
        }

        public Boolean getLogStageConfig() {
            return logStageConfig;
        }

        public void setLogStageConfig(Boolean logStageConfig) {
            this.logStageConfig = logStageConfig;
        }

        public Boolean getLogSseProgress() {
            return logSseProgress;
        }

        public void setLogSseProgress(Boolean logSseProgress) {
            this.logSseProgress = logSseProgress;
        }
    }

    /**
     * OpenRouter 专属配置。
     * <p>用于设置 HTTP Referer 和 X-Title，使 OpenRouter Dashboard 正确显示 App 名称。</p>
     *
     * @see <a href="https://openrouter.ai/docs/app-attribution">OpenRouter App Attribution</a>
     */
    public static class OpenRouterConfig {
        /** HTTP Referer，OpenRouter 用于统计和展示调用来源（必需） */
        @com.fasterxml.jackson.annotation.JsonProperty("http-referer")
        private String httpReferer;
        /** App 标题，显示在 OpenRouter Dashboard 中（可选，建议使用 X-OpenRouter-Title） */
        @com.fasterxml.jackson.annotation.JsonProperty("title")
        private String title;
        /** 可选的 App 分类，逗号分隔，如 "cloud-agent,programming-app" */
        @com.fasterxml.jackson.annotation.JsonProperty("categories")
        private String categories;

        public String getHttpReferer() {
            return httpReferer;
        }

        public void setHttpReferer(String httpReferer) {
            this.httpReferer = httpReferer;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getCategories() {
            return categories;
        }

        public void setCategories(String categories) {
            this.categories = categories;
        }
    }

    public static class Prompts {
        private String agentRunSystemPrompt;
        /** 多轮 follow-up 历史摘要的稳定 System Prompt；正文来自 shared classpath 权威资源。 */
        private String followUpSummarySystemPrompt;
        private String todoPlannerSystemPromptTemplate;
        private String workflowFinalSystemPrompt;
        private String workflowTodoRecoverySystemPrompt;
        private String parallelPlannerSystemPromptTemplate;
        private String parallelFinalSystemPrompt;
        private String parallelPatchPlannerSystemPromptTemplate;
        private String planJudgeSystemPromptTemplate;
        private String planJudgeRuntimeSystemPromptTemplate;
        private String semanticJudgeSystemPromptTemplate;
        private String subAgentPlannerSystemPromptTemplate;
        private String subAgentSummarySystemPrompt;
        private String pythonRefineSystemPrompt;
        private String pythonRefineRequirementsFile;
        private List<String> pythonRefineRequirements = new ArrayList<>();
        private String pythonRefineOutputInstruction;
        private List<DatasetFieldSpec> datasetFieldSpecs = new ArrayList<>();
        private String datasetFieldSpecsFile;
        private String financeMethodResolverSystemPrompt;
        private String financeMethodResolverSystemPromptFile;
        private String orchestratorPlanningSystemPrompt;
        private String orchestratorSummarySystemPrompt;
        private String dagReactSystemPrompt;
        private String dagReactSystemPromptFile;
        private String dagModeGuidancePrompt;
        private String dagModeGuidancePromptFile;
        /** 第一阶段统筹规划 prompt 投影文件路径（相对于 config 目录）；缺省时由 classpath 权威正文提供。 */
        private String planningStrategyStageFile;
        /** 第一阶段统筹规划 prompt 内容（由 loader 从文件读取） */
        private String planningStrategyStage;
        /** 第二阶段任务拆解 prompt 投影文件路径（相对于 config 目录）；缺省时由 classpath 权威正文提供。 */
        private String planningTodosStageFile;
        /** 第二阶段任务拆解 prompt 内容（由 loader 从文件读取） */
        private String planningTodosStage;
        /** D02：规划分析兼容阶段说明。 */
        private String planningAnalysisStage;
        /** D02：规划结构化输出阶段说明。 */
        private String planningStructuredStage;
        /** D02：LINEAR 模式说明。 */
        private String planningLinearModeGuidance;
        /** D02：DAG 模式说明。 */
        private String planningDagModeGuidance;
        /** D02：强制 LINEAR 时追加到 planning User 的约束。 */
        private String planningLinearConstraint;
        /** D02：executePython 修复轮次 User 阶段说明。 */
        private String pythonRepairStageInstruction;
        /** D02：空输出恢复 User 阶段说明。 */
        private String emptyOutputRecoveryStageInstruction;
        /** D02：预算 last-mile User 阶段说明模板。 */
        private String budgetLastMileStageInstruction;
        /** Todo 语义重试时追加到用户消息的上下文模板。 */
        private String todoRetryContextInstruction;
        /** 代码执行失败后修复轮次追加到用户消息的诊断上下文模板。 */
        private String pythonRepairContextInstruction;
        /** 工具结果摘要的系统说明。 */
        private String toolSummarySystemPrompt;
        /** 工具结果摘要的用户消息模板。 */
        private String toolSummaryUserPromptTemplate;
        /** D02：按工具名登记的能力说明 JSON。 */
        private String toolCapabilityCatalog;
        /** DAG recovery judge prompt 投影文件路径；loader 保留路径，并把校验后的正文写入 template 字段。 */
        private String dagRecoveryJudgeSystemPromptFile;
        /** DAG recovery judge prompt 内容（只能是 classpath 权威正文或其逐字一致投影）。 */
        private String dagRecoveryJudgeSystemPromptTemplate;

        public String getAgentRunSystemPrompt() {
            return agentRunSystemPrompt;
        }

        public void setAgentRunSystemPrompt(String agentRunSystemPrompt) {
            this.agentRunSystemPrompt = agentRunSystemPrompt;
        }

        public String getFollowUpSummarySystemPrompt() {
            return followUpSummarySystemPrompt;
        }

        public void setFollowUpSummarySystemPrompt(String followUpSummarySystemPrompt) {
            this.followUpSummarySystemPrompt = followUpSummarySystemPrompt;
        }

        public String getTodoPlannerSystemPromptTemplate() {
            return todoPlannerSystemPromptTemplate;
        }

        public void setTodoPlannerSystemPromptTemplate(String todoPlannerSystemPromptTemplate) {
            this.todoPlannerSystemPromptTemplate = todoPlannerSystemPromptTemplate;
        }

        public String getWorkflowFinalSystemPrompt() {
            return workflowFinalSystemPrompt;
        }

        public void setWorkflowFinalSystemPrompt(String workflowFinalSystemPrompt) {
            this.workflowFinalSystemPrompt = workflowFinalSystemPrompt;
        }

        public String getWorkflowTodoRecoverySystemPrompt() {
            return workflowTodoRecoverySystemPrompt;
        }

        public void setWorkflowTodoRecoverySystemPrompt(String workflowTodoRecoverySystemPrompt) {
            this.workflowTodoRecoverySystemPrompt = workflowTodoRecoverySystemPrompt;
        }

        public String getParallelPlannerSystemPromptTemplate() {
            return parallelPlannerSystemPromptTemplate;
        }

        public void setParallelPlannerSystemPromptTemplate(String parallelPlannerSystemPromptTemplate) {
            this.parallelPlannerSystemPromptTemplate = parallelPlannerSystemPromptTemplate;
        }

        public String getParallelFinalSystemPrompt() {
            return parallelFinalSystemPrompt;
        }

        public void setParallelFinalSystemPrompt(String parallelFinalSystemPrompt) {
            this.parallelFinalSystemPrompt = parallelFinalSystemPrompt;
        }

        public String getParallelPatchPlannerSystemPromptTemplate() {
            return parallelPatchPlannerSystemPromptTemplate;
        }

        public void setParallelPatchPlannerSystemPromptTemplate(String parallelPatchPlannerSystemPromptTemplate) {
            this.parallelPatchPlannerSystemPromptTemplate = parallelPatchPlannerSystemPromptTemplate;
        }

        public String getPlanJudgeSystemPromptTemplate() {
            return planJudgeSystemPromptTemplate;
        }

        public void setPlanJudgeSystemPromptTemplate(String planJudgeSystemPromptTemplate) {
            this.planJudgeSystemPromptTemplate = planJudgeSystemPromptTemplate;
        }

        public String getPlanJudgeRuntimeSystemPromptTemplate() {
            return planJudgeRuntimeSystemPromptTemplate;
        }

        public void setPlanJudgeRuntimeSystemPromptTemplate(String planJudgeRuntimeSystemPromptTemplate) {
            this.planJudgeRuntimeSystemPromptTemplate = planJudgeRuntimeSystemPromptTemplate;
        }

        public String getSemanticJudgeSystemPromptTemplate() {
            return semanticJudgeSystemPromptTemplate;
        }

        public void setSemanticJudgeSystemPromptTemplate(String semanticJudgeSystemPromptTemplate) {
            this.semanticJudgeSystemPromptTemplate = semanticJudgeSystemPromptTemplate;
        }

        public String getSubAgentPlannerSystemPromptTemplate() {
            return subAgentPlannerSystemPromptTemplate;
        }

        public void setSubAgentPlannerSystemPromptTemplate(String subAgentPlannerSystemPromptTemplate) {
            this.subAgentPlannerSystemPromptTemplate = subAgentPlannerSystemPromptTemplate;
        }

        public String getSubAgentSummarySystemPrompt() {
            return subAgentSummarySystemPrompt;
        }

        public void setSubAgentSummarySystemPrompt(String subAgentSummarySystemPrompt) {
            this.subAgentSummarySystemPrompt = subAgentSummarySystemPrompt;
        }

        public String getPythonRefineSystemPrompt() {
            return pythonRefineSystemPrompt;
        }

        public void setPythonRefineSystemPrompt(String pythonRefineSystemPrompt) {
            this.pythonRefineSystemPrompt = pythonRefineSystemPrompt;
        }

        public String getPythonRefineRequirementsFile() {
            return pythonRefineRequirementsFile;
        }

        public void setPythonRefineRequirementsFile(String pythonRefineRequirementsFile) {
            this.pythonRefineRequirementsFile = pythonRefineRequirementsFile;
        }

        public List<String> getPythonRefineRequirements() {
            return pythonRefineRequirements;
        }

        public void setPythonRefineRequirements(List<String> pythonRefineRequirements) {
            this.pythonRefineRequirements = pythonRefineRequirements == null ? new ArrayList<>() : pythonRefineRequirements;
        }

        public String getPythonRefineOutputInstruction() {
            return pythonRefineOutputInstruction;
        }

        public void setPythonRefineOutputInstruction(String pythonRefineOutputInstruction) {
            this.pythonRefineOutputInstruction = pythonRefineOutputInstruction;
        }

        public List<DatasetFieldSpec> getDatasetFieldSpecs() {
            return datasetFieldSpecs;
        }

        public void setDatasetFieldSpecs(List<DatasetFieldSpec> datasetFieldSpecs) {
            this.datasetFieldSpecs = datasetFieldSpecs == null ? new ArrayList<>() : datasetFieldSpecs;
        }

        public String getDatasetFieldSpecsFile() {
            return datasetFieldSpecsFile;
        }

        public void setDatasetFieldSpecsFile(String datasetFieldSpecsFile) {
            this.datasetFieldSpecsFile = datasetFieldSpecsFile;
        }

        public String getFinanceMethodResolverSystemPrompt() {
            return financeMethodResolverSystemPrompt;
        }

        public void setFinanceMethodResolverSystemPrompt(String financeMethodResolverSystemPrompt) {
            this.financeMethodResolverSystemPrompt = financeMethodResolverSystemPrompt;
        }

        public String getFinanceMethodResolverSystemPromptFile() {
            return financeMethodResolverSystemPromptFile;
        }

        public void setFinanceMethodResolverSystemPromptFile(String financeMethodResolverSystemPromptFile) {
            this.financeMethodResolverSystemPromptFile = financeMethodResolverSystemPromptFile;
        }

        public String getOrchestratorPlanningSystemPrompt() {
            return orchestratorPlanningSystemPrompt;
        }

        public void setOrchestratorPlanningSystemPrompt(String orchestratorPlanningSystemPrompt) {
            this.orchestratorPlanningSystemPrompt = orchestratorPlanningSystemPrompt;
        }

        public String getOrchestratorSummarySystemPrompt() {
            return orchestratorSummarySystemPrompt;
        }

        public void setOrchestratorSummarySystemPrompt(String orchestratorSummarySystemPrompt) {
            this.orchestratorSummarySystemPrompt = orchestratorSummarySystemPrompt;
        }

        public String getDagReactSystemPrompt() {
            return dagReactSystemPrompt;
        }

        public void setDagReactSystemPrompt(String dagReactSystemPrompt) {
            this.dagReactSystemPrompt = dagReactSystemPrompt;
        }

        public String getDagReactSystemPromptFile() {
            return dagReactSystemPromptFile;
        }

        public void setDagReactSystemPromptFile(String dagReactSystemPromptFile) {
            this.dagReactSystemPromptFile = dagReactSystemPromptFile;
        }

        public String getDagModeGuidancePrompt() {
            return dagModeGuidancePrompt;
        }

        public void setDagModeGuidancePrompt(String dagModeGuidancePrompt) {
            this.dagModeGuidancePrompt = dagModeGuidancePrompt;
        }

        public String getDagModeGuidancePromptFile() {
            return dagModeGuidancePromptFile;
        }

        public void setDagModeGuidancePromptFile(String dagModeGuidancePromptFile) {
            this.dagModeGuidancePromptFile = dagModeGuidancePromptFile;
        }

        public String getPlanningStrategyStageFile() {
            return planningStrategyStageFile;
        }

        public void setPlanningStrategyStageFile(String planningStrategyStageFile) {
            this.planningStrategyStageFile = planningStrategyStageFile;
        }

        public String getPlanningStrategyStage() {
            return planningStrategyStage;
        }

        public void setPlanningStrategyStage(String planningStrategyStage) {
            this.planningStrategyStage = planningStrategyStage;
        }

        public String getPlanningTodosStageFile() {
            return planningTodosStageFile;
        }

        public void setPlanningTodosStageFile(String planningTodosStageFile) {
            this.planningTodosStageFile = planningTodosStageFile;
        }

        public String getPlanningTodosStage() {
            return planningTodosStage;
        }

        public void setPlanningTodosStage(String planningTodosStage) {
            this.planningTodosStage = planningTodosStage;
        }

        public String getPlanningAnalysisStage() {
            return planningAnalysisStage;
        }

        public void setPlanningAnalysisStage(String planningAnalysisStage) {
            this.planningAnalysisStage = planningAnalysisStage;
        }

        public String getPlanningStructuredStage() {
            return planningStructuredStage;
        }

        public void setPlanningStructuredStage(String planningStructuredStage) {
            this.planningStructuredStage = planningStructuredStage;
        }

        public String getPlanningLinearModeGuidance() {
            return planningLinearModeGuidance;
        }

        public void setPlanningLinearModeGuidance(String planningLinearModeGuidance) {
            this.planningLinearModeGuidance = planningLinearModeGuidance;
        }

        public String getPlanningDagModeGuidance() {
            return planningDagModeGuidance;
        }

        public void setPlanningDagModeGuidance(String planningDagModeGuidance) {
            this.planningDagModeGuidance = planningDagModeGuidance;
        }

        public String getPlanningLinearConstraint() {
            return planningLinearConstraint;
        }

        public void setPlanningLinearConstraint(String planningLinearConstraint) {
            this.planningLinearConstraint = planningLinearConstraint;
        }

        public String getPythonRepairStageInstruction() {
            return pythonRepairStageInstruction;
        }

        public void setPythonRepairStageInstruction(String pythonRepairStageInstruction) {
            this.pythonRepairStageInstruction = pythonRepairStageInstruction;
        }

        public String getEmptyOutputRecoveryStageInstruction() {
            return emptyOutputRecoveryStageInstruction;
        }

        public void setEmptyOutputRecoveryStageInstruction(String emptyOutputRecoveryStageInstruction) {
            this.emptyOutputRecoveryStageInstruction = emptyOutputRecoveryStageInstruction;
        }

        public String getBudgetLastMileStageInstruction() {
            return budgetLastMileStageInstruction;
        }

        public void setBudgetLastMileStageInstruction(String budgetLastMileStageInstruction) {
            this.budgetLastMileStageInstruction = budgetLastMileStageInstruction;
        }

        public String getTodoRetryContextInstruction() {
            return todoRetryContextInstruction;
        }

        public void setTodoRetryContextInstruction(String todoRetryContextInstruction) {
            this.todoRetryContextInstruction = todoRetryContextInstruction;
        }

        public String getPythonRepairContextInstruction() {
            return pythonRepairContextInstruction;
        }

        public void setPythonRepairContextInstruction(String pythonRepairContextInstruction) {
            this.pythonRepairContextInstruction = pythonRepairContextInstruction;
        }

        public String getToolSummarySystemPrompt() {
            return toolSummarySystemPrompt;
        }

        public void setToolSummarySystemPrompt(String toolSummarySystemPrompt) {
            this.toolSummarySystemPrompt = toolSummarySystemPrompt;
        }

        public String getToolSummaryUserPromptTemplate() {
            return toolSummaryUserPromptTemplate;
        }

        public void setToolSummaryUserPromptTemplate(String toolSummaryUserPromptTemplate) {
            this.toolSummaryUserPromptTemplate = toolSummaryUserPromptTemplate;
        }

        public String getToolCapabilityCatalog() {
            return toolCapabilityCatalog;
        }

        public void setToolCapabilityCatalog(String toolCapabilityCatalog) {
            this.toolCapabilityCatalog = toolCapabilityCatalog;
        }

        public String getDagRecoveryJudgeSystemPromptFile() {
            return dagRecoveryJudgeSystemPromptFile;
        }

        public void setDagRecoveryJudgeSystemPromptFile(String dagRecoveryJudgeSystemPromptFile) {
            this.dagRecoveryJudgeSystemPromptFile = dagRecoveryJudgeSystemPromptFile;
        }

        public String getDagRecoveryJudgeSystemPromptTemplate() {
            return dagRecoveryJudgeSystemPromptTemplate;
        }

        public void setDagRecoveryJudgeSystemPromptTemplate(String dagRecoveryJudgeSystemPromptTemplate) {
            this.dagRecoveryJudgeSystemPromptTemplate = dagRecoveryJudgeSystemPromptTemplate;
        }
    }

    public static class DatasetFieldSpec {
        private String name;
        private String meaning;
        private String dataType;
        private String dataFormat;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMeaning() {
            return meaning;
        }

        public void setMeaning(String meaning) {
            this.meaning = meaning;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public String getDataFormat() {
            return dataFormat;
        }

        public void setDataFormat(String dataFormat) {
            this.dataFormat = dataFormat;
        }
    }

    /**
     * Agent run event 持久化相关配置（Nacos → agent-llm.local.json 热更新）。
     */
    public static class EventStoreConfig {
        /**
         * Redis ZSET 批量刷写条数 K：每累积 K 条 event 才 pipeline 写一次 Redis。
         * 设为 1 则与逐条写等价。读路径会先 flush 该 run 的 pending，避免漏读。
         */
        private Integer redisFlushBatchSize;
        /** Pending buffer 超过该毫秒数未刷写时，由定时任务兜底 flush（默认 3s）。 */
        private Integer redisFlushStaleMs;

        public Integer getRedisFlushBatchSize() {
            return redisFlushBatchSize;
        }

        public void setRedisFlushBatchSize(Integer redisFlushBatchSize) {
            this.redisFlushBatchSize = redisFlushBatchSize;
        }

        public Integer getRedisFlushStaleMs() {
            return redisFlushStaleMs;
        }

        public void setRedisFlushStaleMs(Integer redisFlushStaleMs) {
            this.redisFlushStaleMs = redisFlushStaleMs;
        }
    }

    public static class ExecutorConfig {
        private Integer corePoolSize;
        private Integer maxPoolSize;
        private Integer queueCapacity;
        private String threadNamePrefix;
        private ExecutorParallelConfig parallel;

        public Integer getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(Integer corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public Integer getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(Integer maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(Integer queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public ExecutorParallelConfig getParallel() {
            return parallel;
        }

        public void setParallel(ExecutorParallelConfig parallel) {
            this.parallel = parallel;
        }
    }

    public static class ExecutorParallelConfig {
        private ExecutorConfig hard;
        private ExecutorConfig current;

        public ExecutorConfig getHard() {
            return hard;
        }

        public void setHard(ExecutorConfig hard) {
            this.hard = hard;
        }

        public ExecutorConfig getCurrent() {
            return current;
        }

        public void setCurrent(ExecutorConfig current) {
            this.current = current;
        }
    }

    /**
     * Finance MethodSpec resolver configuration: dedicated lightweight default route
     * and catalog budget limits. Never silently inherit the execution model.
     */
    public static class FinanceMethodResolver {
        @JsonAlias({"default_route", "defaultRoute"})
        private DefaultRoute defaultRoute = new DefaultRoute();
        @JsonAlias({"catalog_prompt_max_bytes", "catalogPromptMaxBytes"})
        private Integer catalogPromptMaxBytes = 8192;
        @JsonAlias({"catalog_prompt_max_tokens", "catalogPromptMaxTokens"})
        private Integer catalogPromptMaxTokens = 2048;
        @JsonAlias({"request_max_bytes", "requestMaxBytes"})
        private Integer requestMaxBytes = 8192;
        @JsonAlias({"response_max_bytes", "responseMaxBytes"})
        private Integer responseMaxBytes = 16384;
        @JsonAlias({"max_candidates", "maxCandidates"})
        private Integer maxCandidates = 8;

        public DefaultRoute getDefaultRoute() {
            return defaultRoute;
        }

        public void setDefaultRoute(DefaultRoute defaultRoute) {
            this.defaultRoute = defaultRoute == null ? new DefaultRoute() : defaultRoute;
        }

        public Integer getCatalogPromptMaxBytes() {
            return catalogPromptMaxBytes;
        }

        public void setCatalogPromptMaxBytes(Integer catalogPromptMaxBytes) {
            this.catalogPromptMaxBytes = catalogPromptMaxBytes;
        }

        public Integer getCatalogPromptMaxTokens() {
            return catalogPromptMaxTokens;
        }

        public void setCatalogPromptMaxTokens(Integer catalogPromptMaxTokens) {
            this.catalogPromptMaxTokens = catalogPromptMaxTokens;
        }

        public Integer getRequestMaxBytes() {
            return requestMaxBytes;
        }

        public void setRequestMaxBytes(Integer requestMaxBytes) {
            this.requestMaxBytes = requestMaxBytes;
        }

        public Integer getResponseMaxBytes() {
            return responseMaxBytes;
        }

        public void setResponseMaxBytes(Integer responseMaxBytes) {
            this.responseMaxBytes = responseMaxBytes;
        }

        public Integer getMaxCandidates() {
            return maxCandidates;
        }

        public void setMaxCandidates(Integer maxCandidates) {
            this.maxCandidates = maxCandidates;
        }

        public static class DefaultRoute {
            private Boolean enabled = false;
            private String endpointName;
            private String modelName;
            @JsonAlias({"provider_order", "providers"})
            private List<String> providerOrder;
            private Double temperature = 0.0D;
            private Integer maxTokens = 2048;
            private Integer maxAttempts = 2;
            @JsonAlias({"structured_output", "structuredOutput"})
            private Boolean structuredOutput = true;

            public Boolean getEnabled() {
                return enabled;
            }

            public void setEnabled(Boolean enabled) {
                this.enabled = enabled;
            }

            public String getEndpointName() {
                return endpointName;
            }

            public void setEndpointName(String endpointName) {
                this.endpointName = endpointName;
            }

            public String getModelName() {
                return modelName;
            }

            public void setModelName(String modelName) {
                this.modelName = modelName;
            }

            public List<String> getProviderOrder() {
                return providerOrder;
            }

            public void setProviderOrder(List<String> providerOrder) {
                this.providerOrder = providerOrder;
            }

            public Double getTemperature() {
                return temperature;
            }

            public void setTemperature(Double temperature) {
                this.temperature = temperature;
            }

            public Integer getMaxTokens() {
                return maxTokens;
            }

            public void setMaxTokens(Integer maxTokens) {
                this.maxTokens = maxTokens;
            }

            public Integer getMaxAttempts() {
                return maxAttempts;
            }

            public void setMaxAttempts(Integer maxAttempts) {
                this.maxAttempts = maxAttempts;
            }

            public Boolean getStructuredOutput() {
                return structuredOutput;
            }

            public void setStructuredOutput(Boolean structuredOutput) {
                this.structuredOutput = structuredOutput;
            }
        }
    }
}
