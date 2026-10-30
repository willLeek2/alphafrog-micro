package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.compaction.ToolOutputCompactionService;
import world.willfrog.agent.tools.market.advanced.AdvancedSearchRequest;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.input.PromptMessageCodec;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolResultCacheService {

    private static final String CACHE_PREFIX = "agent:tool-cache:";
    private static final String SOURCE_REDIS = "redis_tool_cache";
    private static final String SOURCE_DATASET_REGISTRY = "dataset_registry";
    private static final String SOURCE_NONE = "none";
    // 以下集合由 AgentToolRegistry 的 cacheFamily 元数据派生，与注册表单一真相源对齐
    private static final Set<String> SEARCH_TOOLS = AgentToolRegistry.namesInCacheFamily(
            AgentToolRegistry.CacheFamily.SEARCH);
    private static final Set<String> INFO_TOOLS = AgentToolRegistry.namesInCacheFamily(
            AgentToolRegistry.CacheFamily.INFO);
    private static final Set<String> DATASET_TOOLS = AgentToolRegistry.namesInCacheFamily(
            AgentToolRegistry.CacheFamily.DATASET);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final ToolOutputCompactionService toolOutputCompactionService;
    private final MeterRegistry meterRegistry;

    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Timer cacheLookupTimer;

    @Value("${agent.tool-cache.version:v1}")
    private String defaultVersion;

    @Value("${agent.tool-cache.search-ttl-seconds:3600}")
    private int defaultSearchTtlSeconds;

    @Value("${agent.tool-cache.info-ttl-seconds:21600}")
    private int defaultInfoTtlSeconds;

    @Value("${agent.tool-cache.dataset-ttl-seconds:604800}")
    private int defaultDatasetTtlSeconds;

    @PostConstruct
    public void init() {
        this.cacheHitCounter = Counter.builder("cache.hit")
                .description("Cache hit count")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("cache.miss")
                .description("Cache miss count")
                .register(meterRegistry);
        this.cacheLookupTimer = Timer.builder("cache.lookup")
                .description("Cache lookup duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public CachedToolCallResult executeWithCache(String toolName,
                                                 Map<String, Object> params,
                                                 String scope,
                                                 Supplier<ToolExecutionOutcome> loader) {
        return execute(toolName, params, scope, loader, false);
    }

    public CachedToolCallResult executeWithPromptCache(String toolName, Map<String, Object> params,
                                                       String scope, Supplier<ToolExecutionOutcome> loader) {
        return execute(toolName, params, scope, loader, true);
    }

    private CachedToolCallResult execute(String toolName, Map<String, Object> params, String scope,
                                          Supplier<ToolExecutionOutcome> loader, boolean classified) {
        CachePlan plan = buildPlan(toolName, params, scope);
        String rootSelectionDigest = classified ? currentRootSelectionDigest() : null;
        String rootKey = blank(plan.getKey()) || rootSelectionDigest == null ? null
                : plan.getKey() + ":prompt-root:" + rootSelectionDigest;
        debugLog("cache plan resolved: runId={}, tool={}, mode={}, key={}, ttlSeconds={}, scope={}",
                AgentContext.getRunId(), nvl(toolName), plan.getMode(), plan.getKey(), plan.getTtlSeconds(), nvl(scope));
        long lookupStartedAt = System.currentTimeMillis();

        if (plan.getMode() == CacheMode.REDIS && plan.getTtlSeconds() > 0) {
            List<String> keys = new ArrayList<>();
            if (classified && rootKey != null) keys.add(rootKey);
            keys.add(plan.getKey());
            for (String key : keys) {
                CachePayload cached = readCache(key);
                if (cached == null || cached.getResult() == null) continue;
                PromptText cachedPrompt = classified ? cachedPrompt(cached, key.equals(rootKey), rootSelectionDigest) : null;
                if (!isStructuredToolResult(cached.getResult()) || classified && cachedPrompt == null) {
                    redisTemplate.delete(key);
                    log.info("Ignore tool cache without a usable structured result and its provenance, key={}", key);
                    continue;
                }
                long durationMs = Math.max(0L, System.currentTimeMillis() - lookupStartedAt);
                long ttlRemainingMs = ttlRemainingMs(key);
                CacheMeta meta = CacheMeta.builder().eligible(true).hit(true).key(key)
                        .ttlRemainingMs(ttlRemainingMs).source(SOURCE_REDIS)
                        .estimatedSavedDurationMs(Math.max(0L, cached.getOriginalDurationMs() - durationMs)).build();
                cacheHitCounter.increment();
                cacheLookupTimer.record(durationMs, TimeUnit.MILLISECONDS);
                String result = cached.getResult();
                if (cached.isCompactionApplied() && cached.getRawLocator() != null) {
                    try {
                        if (classified) {
                            cachedPrompt = toolOutputCompactionService.rebindPromptForCacheHit(cachedPrompt, cached.getRawLocator());
                            result = cachedPrompt.text();
                        } else result = toolOutputCompactionService.rebindForCacheHit(result, cached.getRawLocator());
                    } catch (IllegalArgumentException e) {
                        redisTemplate.delete(key);
                        log.info("Tool cache rawRef no longer owned by current run, invalidating cache, key={}", key);
                        result = null;
                    }
                }
                if (result != null) {
                    if (classified) requirePrompt(result, cachedPrompt);
                    return CachedToolCallResult.builder().result(result).resultPrompt(cachedPrompt)
                            .observabilityResult(result).observabilityPrompt(cachedPrompt)
                            .durationMs(durationMs).success(true).cacheMeta(meta).build();
                }
            }
        }

        cacheMissCounter.increment();
        ToolExecutionOutcome loaded = loader.get();
        cacheLookupTimer.record(System.currentTimeMillis() - lookupStartedAt, TimeUnit.MILLISECONDS);
        if (loaded == null) {
            String error = fallbackToolErrorJson(toolName, "EMPTY_LOADER_RESULT", "Tool invocation error: empty loader result");
            loaded = ToolExecutionOutcome.builder().result(error)
                    .resultPrompt(classified ? PromptText.runtimeData(error) : null)
                    .durationMs(0L).success(false).build();
        }
        PromptText rawPrompt = classified ? requirePrompt(loaded.getResult(), loaded.getResultPrompt()) : null;
        if (classified && loaded.getObservabilityPrompt() != null
                && !rawPrompt.equals(loaded.getObservabilityPrompt())) {
            throw missingSource("工具原始结果与观测来源不一致");
        }
        ToolOutputCompactionService.CompactionResult compaction = classified
                ? toolOutputCompactionService.compactPrompt(toolName, rawPrompt, resolveTodoGoal())
                : toolOutputCompactionService.compact(toolName, loaded.getResult(), resolveTodoGoal());
        String modelOutput = compaction.getModelOutput();
        String cacheValue = compaction.isCompactionApplied() ? compaction.getCacheTemplate() : modelOutput;
        RawPayloadLocator rawLocator = compaction.isCompactionApplied() ? compaction.getRawLocator() : null;
        PromptText modelPrompt = classified ? requirePrompt(modelOutput, compaction.getModelPrompt()) : null;
        PromptText cachePrompt = classified ? requirePrompt(cacheValue, compaction.isCompactionApplied()
                ? compaction.getCacheTemplatePrompt() : compaction.getModelPrompt()) : null;
        PromptText observabilityPrompt = classified
                ? requirePrompt(compaction.getObservabilityOutput(), compaction.getObservabilityPrompt()) : null;
        boolean applicationSources = classified && (!rawPrompt.sources().isEmpty()
                || !modelPrompt.sources().isEmpty() || !cachePrompt.sources().isEmpty()
                || !observabilityPrompt.sources().isEmpty());
        String cacheKey = plan.getKey();
        if (applicationSources && rootKey != null) cacheKey = rootKey;
        CacheMeta meta;
        if (plan.getMode() == CacheMode.NONE) {
            meta = CacheMeta.builder().eligible(false).hit(false).key("").ttlRemainingMs(-1L)
                    .source(SOURCE_NONE).estimatedSavedDurationMs(0L).build();
        } else if (plan.getMode() == CacheMode.REDIS) {
            if (loaded.isSuccess() && plan.getTtlSeconds() > 0 && isStructuredToolResult(cacheValue)) {
                if (applicationSources && rootSelectionDigest == null) {
                    throw missingSource("应用指令缓存缺少完整原根选择");
                }
                writeCache(cacheKey, cacheValue, loaded.getDurationMs(), plan.getTtlSeconds(), rawLocator,
                        compaction.isCompactionApplied(), cachePrompt, observabilityPrompt,
                        rawLocator == null ? null : rawPrompt, applicationSources ? rootSelectionDigest : null);
            }
            meta = CacheMeta.builder().eligible(true).hit(false).key(cacheKey)
                    .ttlRemainingMs(plan.getTtlSeconds() > 0 ? plan.getTtlSeconds() * 1000L : -1L)
                    .source(SOURCE_REDIS).estimatedSavedDurationMs(0L).build();
        } else {
            meta = CacheMeta.builder().eligible(true).hit(isDatasetReuseResult(loaded.getResult())).key(plan.getKey())
                    .ttlRemainingMs(plan.getTtlSeconds() > 0 ? plan.getTtlSeconds() * 1000L : -1L)
                    .source(SOURCE_DATASET_REGISTRY).estimatedSavedDurationMs(0L).build();
        }
        return CachedToolCallResult.builder().result(modelOutput).resultPrompt(modelPrompt)
                .observabilityResult(compaction.getObservabilityOutput()).observabilityPrompt(observabilityPrompt)
                .durationMs(Math.max(0L, loaded.getDurationMs())).success(loaded.isSuccess()).cacheMeta(meta).build();
    }

    private PromptText cachedPrompt(CachePayload payload, boolean rootBoundKey, String rootSelectionDigest) {
        try {
            PromptMessage result = decodeSource(payload.getResult(), payload.getResultSourceJson());
            PromptMessage observed = decodeSource("cached-observability-source", payload.getObservabilitySourceJson());
            PromptMessage raw = payload.getRawLocator() == null ? null
                    : decodeSource("cached-artifact-source", payload.getRawSourceJson());
            if (result.origin() == PromptMessage.Origin.UNKNOWN || observed.origin() == PromptMessage.Origin.UNKNOWN
                    || raw != null && raw.origin() == PromptMessage.Origin.UNKNOWN) return null;
            boolean application = !result.sources().isEmpty() || !observed.sources().isEmpty()
                    || raw != null && !raw.sources().isEmpty();
            if (application) {
                if (!rootBoundKey || rootSelectionDigest == null
                        || !rootSelectionDigest.equals(payload.getRootSelectionDigest())) return null;
            } else if (rootBoundKey || !blank(payload.getRootSelectionDigest())) return null;
            return new PromptText(payload.getResult(), result.sources());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** 来源属性只解码版本与分类，不把辅助值或来源元数据写进用户结果JSON。 */
    private PromptMessage decodeSource(String body, String sourceJson) {
        if (sourceJson == null) throw missingSource("缓存缺少结果来源");
        return PromptMessageCodec.fromSourceAttributes(dev.langchain4j.data.message.UserMessage.from(body),
                Map.of(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY, sourceJson));
    }

    private PromptText requirePrompt(String body, PromptText prompt) {
        if (prompt == null || !Objects.equals(body, prompt.text())) throw missingSource("工具结果与已分类正文不一致");
        return prompt;
    }

    private String currentRootSelectionDigest() {
        PromptRunSelection selection = AgentContext.getPromptRunSelection();
        if (selection == null || selection.schemaVersion() != PromptRunSelection.RACING_SCHEMA_VERSION) return null;
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(selection.racingSelection().toByteArray()));
        } catch (Exception failure) {
            throw missingSource("缓存无法确认完整原根选择");
        }
    }

    private static ModelSendBlockedException missingSource(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }

    private String resolveTodoGoal() {
        String excerpt = AgentContext.getDecisionExcerpt();
        return excerpt == null ? "" : excerpt.trim();
    }

    public Map<String, Object> toPayload(CacheMeta meta) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eligible", meta != null && meta.isEligible());
        payload.put("hit", meta != null && meta.isHit());
        payload.put("key", meta == null ? "" : nvl(meta.getKey()));
        payload.put("ttlRemainingMs", meta == null ? -1L : meta.getTtlRemainingMs());
        payload.put("source", meta == null ? SOURCE_NONE : nvl(meta.getSource()));
        payload.put("estimatedSavedDurationMs", meta == null ? 0L : Math.max(0L, meta.getEstimatedSavedDurationMs()));
        return payload;
    }

    private CachePlan buildPlan(String toolName, Map<String, Object> params, String scope) {
        CacheMode mode = resolveMode(toolName, params);
        /*
         * D07 fail-closed（Risks 3.3.2）：blank scope（无 userId 且无 runId）不得
         * 落 global 共享键；REDIS 共享缓存此时跳过读写、强制回源。DATASET_REGISTRY
         * 为内容寻址的系统级市场数据复用，不经 Redis scope 键，不受此限。
         */
        if (mode == CacheMode.NONE || (mode == CacheMode.REDIS && blank(scope))) {
            return CachePlan.builder()
                    .mode(CacheMode.NONE)
                    .key("")
                    .ttlSeconds(0)
                    .build();
        }
        String key = buildCacheKey(toolName, params, scope);
        int ttlSeconds = resolveTtlSeconds(mode, toolName);
        return CachePlan.builder()
                .mode(mode)
                .key(key)
                .ttlSeconds(Math.max(0, ttlSeconds))
                .build();
    }

    private CacheMode resolveMode(String toolName, Map<String, Object> params) {
        if (("searchIndex".equals(toolName) || "searchAssetInfo".equals(toolName)
                || "getExchangeAssetDaily".equals(toolName)) && AdvancedSearchRequest.isAdvancedMap(params)) {
            // 高级工具返回数据集身份，必须执行工具以写盘或复用，并登记到当前 Run。
            return CacheMode.DATASET_REGISTRY;
        }
        return resolveMode(toolName);
    }

    private CacheMode resolveMode(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return CacheMode.NONE;
        }
        if (SEARCH_TOOLS.contains(toolName) || INFO_TOOLS.contains(toolName)) {
            return CacheMode.REDIS;
        }
        if (DATASET_TOOLS.contains(toolName)) {
            return CacheMode.DATASET_REGISTRY;
        }
        return CacheMode.NONE;
    }

    private int resolveTtlSeconds(CacheMode mode, String toolName) {
        AgentLlmProperties.Cache localCache = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getCache)
                .orElse(null);
        if (mode == CacheMode.REDIS) {
            Integer searchLocal = localCache == null ? null : localCache.getSearchTtlSeconds();
            Integer infoLocal = localCache == null ? null : localCache.getInfoTtlSeconds();
            int search = searchLocal != null && searchLocal > 0 ? searchLocal : defaultSearchTtlSeconds;
            int info = infoLocal != null && infoLocal > 0 ? infoLocal : defaultInfoTtlSeconds;
            if (SEARCH_TOOLS.contains(toolName)) {
                return Math.max(0, search);
            }
            return Math.max(0, info);
        }
        if (mode == CacheMode.DATASET_REGISTRY) {
            Integer local = localCache == null ? null : localCache.getDatasetTtlSeconds();
            if (local != null && local > 0) {
                return local;
            }
            return Math.max(0, defaultDatasetTtlSeconds);
        }
        return 0;
    }

    private String buildCacheKey(String toolName, Map<String, Object> params, String scope) {
        Map<String, String> normalizedArgs = normalizeArgs(toolName, params);
        String argsJson = safeWrite(normalizedArgs);
        String argsHash = sha256(argsJson);
        /*
         * D07：不再把 blank scope 默认写成 global。REDIS 模式的 blank scope 已在
         * buildPlan 被拒（跳过共享缓存）；这里仅 DATASET_REGISTRY 的观测性键可能
         * 带 blank scope，用不可与真实 scope（user:/run: 前缀）碰撞的明示标签。
         */
        String resolvedScope = safeToken(blank(scope) ? "no-shared-scope" : scope);
        String version = safeToken(resolveVersion());
        return CACHE_PREFIX + safeToken(toolName) + ":" + argsHash + ":" + resolvedScope + ":" + version;
    }

    private String resolveVersion() {
        String local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getCache)
                .map(AgentLlmProperties.Cache::getVersion)
                .orElse("");
        if (!blank(local)) {
            return local.trim();
        }
        if (!blank(defaultVersion)) {
            return defaultVersion.trim();
        }
        return "v1";
    }

    private Map<String, String> normalizeArgs(String toolName, Map<String, Object> params) {
        Map<String, Object> source = params == null ? Map.of() : params;
        TreeMap<String, String> normalized = new TreeMap<>();
        switch (nvl(toolName)) {
            case "searchStock":
            case "searchFund":
            case "searchIndex":
            case "searchAssetInfo":
                putIfPresent(normalized, "keyword", normalizeKeyword(firstNonBlank(
                        source.get("keyword"),
                        source.get("query"),
                        source.get("arg0")
                )));
                if ("searchAssetInfo".equals(toolName)) {
                    putIfPresent(normalized, "assetTypes", normalizeGeneric(firstNonBlank(
                            source.get("assetTypes"),
                            source.get("asset_types"),
                            source.get("arg1")
                    )).toLowerCase(Locale.ROOT));
                    putIfPresent(normalized, "marketScope", normalizeGeneric(firstNonBlank(
                            source.get("marketScope"),
                            source.get("market_scope"),
                            source.get("arg2"),
                            "domestic"
                    )).toLowerCase(Locale.ROOT));
                }
                break;
            case "getStockInfo":
            case "getIndexInfo":
                putIfPresent(normalized, "tsCode", normalizeCode(firstNonBlank(
                        source.get("tsCode"),
                        source.get("ts_code"),
                        source.get("code"),
                        source.get("stock_code"),
                        source.get("index_code"),
                        source.get("arg0")
                )));
                break;
            case "getStockDaily":
            case "getIndexDaily":
            case "getExchangeAssetDaily":
            case "getOffExchangeAssetDaily":
            case "getListedAssetShareSize":
            case "getEtfAdj":
                putIfPresent(normalized, "tsCode", normalizeCode(firstNonBlank(
                        source.get("tsCode"),
                        source.get("ts_code"),
                        source.get("code"),
                        source.get("stock_code"),
                        source.get("index_code"),
                        source.get("arg0")
                )));
                putIfPresent(normalized, "startDateStr", normalizeDate(firstNonBlank(
                        source.get("startDateStr"),
                        source.get("startDate"),
                        source.get("start_date"),
                        "getExchangeAssetDaily".equals(toolName) || "getOffExchangeAssetDaily".equals(toolName)
                                || "getListedAssetShareSize".equals(toolName) || "getEtfAdj".equals(toolName)
                                ? source.get("arg2") : source.get("arg1")
                )));
                putIfPresent(normalized, "endDateStr", normalizeDate(firstNonBlank(
                        source.get("endDateStr"),
                        source.get("endDate"),
                        source.get("end_date"),
                        "getExchangeAssetDaily".equals(toolName) || "getOffExchangeAssetDaily".equals(toolName)
                                || "getListedAssetShareSize".equals(toolName) || "getEtfAdj".equals(toolName)
                                ? source.get("arg3") : source.get("arg2")
                )));
                if ("getExchangeAssetDaily".equals(toolName)) {
                    putIfPresent(normalized, "assetType", normalizeGeneric(firstNonBlank(
                            source.get("assetType"),
                            source.get("asset_type"),
                            source.get("arg1")
                    )).toLowerCase(Locale.ROOT));
                    putIfPresent(normalized, "priceMode", normalizeGeneric(firstNonBlank(
                            source.get("priceMode"),
                            source.get("price_mode"),
                            source.get("arg4"),
                            "raw_ohlc"
                    )).toLowerCase(Locale.ROOT));
                }
                if ("getListedAssetShareSize".equals(toolName)) {
                    putIfPresent(normalized, "exchange", normalizeCode(firstNonBlank(
                            source.get("exchange"),
                            source.get("arg3")
                    )));
                }
                break;
            default:
                for (Map.Entry<String, Object> entry : source.entrySet()) {
                    putIfPresent(normalized, safeToken(entry.getKey()), normalizeGeneric(entry.getValue()));
                }
        }
        if (Set.of("searchIndex", "searchAssetInfo", "getExchangeAssetDaily").contains(nvl(toolName))
                && AdvancedSearchRequest.isAdvancedMap(source)) {
            // 高级条件决定股票范围；不能复用简单搜索或另一个期间的缓存结果。
            normalized.put("mode", "advanced");
            normalized.put("advancedQuery", normalizeAdvancedQuery(source.get("advancedQuery")));
            // 查询正文没有名称时，高级查询会回退读取顶层名称参数。
            putIfPresent(normalized, "name", normalizeGeneric(source.get("name")));
            putIfPresent(normalized, "keyword", normalizeGeneric(source.get("keyword")));
        }
        return normalized;
    }

    private String normalizeAdvancedQuery(Object value) {
        try {
            Object query = value instanceof String raw ? objectMapper.readValue(raw, Object.class) : value;
            // 只统一对象字段顺序，保留条件数组顺序及所有查询字段。
            return objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(query);
        } catch (Exception e) {
            // 无效输入仍保留原文，交给工具按原合同返回参数错误。
            return normalizeGeneric(value);
        }
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (blank(key) || blank(value)) {
            return;
        }
        map.put(key, value);
    }

    private String normalizeKeyword(Object value) {
        String text = normalizeGeneric(value);
        if (text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String normalizeCode(Object value) {
        String text = normalizeGeneric(value);
        if (text.isBlank()) {
            return "";
        }
        return text.toUpperCase(Locale.ROOT);
    }

    private String normalizeDate(Object value) {
        String text = normalizeGeneric(value);
        if (text.isBlank()) {
            return "";
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() == 13) {
            try {
                long ts = Long.parseLong(digits);
                return Instant.ofEpochMilli(ts)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toLocalDate()
                        .format(DATE_FORMATTER);
            } catch (Exception e) {
                return digits;
            }
        }
        if (digits.length() >= 8) {
            return digits.substring(0, 8);
        }
        return text;
    }

    private String normalizeGeneric(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return "";
        }
        return text;
    }

    private String firstNonBlank(Object... candidates) {
        if (candidates == null) {
            return "";
        }
        for (Object candidate : candidates) {
            String text = normalizeGeneric(candidate);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private CachePayload readCache(String key) {
        if (blank(key)) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(key);
        if (blank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CachePayload.class);
        } catch (Exception e) {
            redisTemplate.delete(key);
            log.warn("Parse tool cache failed, key={}", key, e);
            return null;
        }
    }

    private void writeCache(String key, String result, long originalDurationMs, int ttlSeconds,
                            RawPayloadLocator rawLocator, boolean compactionApplied, PromptText resultPrompt,
                            PromptText observabilityPrompt, PromptText rawPrompt, String rootSelectionDigest) {
        if (blank(key) || blank(result) || ttlSeconds <= 0) {
            return;
        }
        try {
            CachePayload payload = new CachePayload();
            payload.setResult(result);
            payload.setOriginalDurationMs(Math.max(0L, originalDurationMs));
            payload.setCachedAtMillis(System.currentTimeMillis());
            payload.setRawLocator(rawLocator);
            payload.setCompactionApplied(compactionApplied);
            if (resultPrompt != null) {
                payload.setResultSourceJson(sourceJson(resultPrompt));
                payload.setObservabilitySourceJson(sourceJson(observabilityPrompt));
                payload.setRawSourceJson(rawPrompt == null ? null : sourceJson(rawPrompt));
                payload.setRootSelectionDigest(rootSelectionDigest);
            }
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(payload), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            ModelSendBlocks.rethrowIfBlocked(e);
            log.warn("Write tool cache failed, key={}", key, e);
        }
    }

    private String sourceJson(PromptText prompt) {
        if (prompt == null) throw missingSource("缓存缺少正文来源");
        return (String) PromptMessageCodec.sourceAttributes(prompt.sources().isEmpty()
                ? PromptMessage.Origin.RUNTIME_DATA : PromptMessage.Origin.APPLICATION_INSTRUCTION,
                prompt.sources()).get(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY);
    }

    private boolean isDatasetReuseResult(String result) {
        if (blank(result)) {
            return false;
        }
        try {
            Map<?, ?> root = objectMapper.readValue(result, Map.class);
            Object ok = root.get("ok");
            if (!(ok instanceof Boolean) || !((Boolean) ok)) {
                return false;
            }
            Object dataObj = root.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) {
                return false;
            }
            Object cacheHit = data.get("cache_hit");
            if (cacheHit instanceof Boolean b) {
                return b;
            }
            Object source = data.get("source");
            return source != null && "reused".equalsIgnoreCase(String.valueOf(source));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isStructuredToolResult(String result) {
        if (blank(result)) {
            return false;
        }
        try {
            // 统一工具输出协议：必须含 ok + (data|error) 结构。
            Map<?, ?> root = objectMapper.readValue(result, Map.class);
            Object ok = root.get("ok");
            if (!(ok instanceof Boolean)) {
                return false;
            }
            Object data = root.get("data");
            Object error = root.get("error");
            return data instanceof Map<?, ?> || error instanceof Map<?, ?>;
        } catch (Exception e) {
            return false;
        }
    }

    private long ttlRemainingMs(String key) {
        if (blank(key)) {
            return -1L;
        }
        try {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            return ttl == null ? -1L : ttl;
        } catch (Exception e) {
            return -1L;
        }
    }

    private String safeWrite(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nvl(input).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(nvl(input).hashCode());
        }
    }

    private String safeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9:_-]", "_");
    }

    private boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private String fallbackToolErrorJson(String toolName, String code, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", false);
        payload.put("tool", nvl(toolName));
        payload.put("data", Map.of());
        payload.put("error", Map.of(
                "code", nvl(code),
                "message", nvl(message),
                "details", Map.of()
        ));
        return safeWrite(payload);
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private void debugLog(String pattern, Object... args) {
        if (!AgentContext.isDebugMode()) {
            return;
        }
        log.info("[agent-debug] " + pattern, args);
    }

    private enum CacheMode {
        NONE,
        REDIS,
        DATASET_REGISTRY
    }

    @Data
    @Builder
    private static class CachePlan {
        private CacheMode mode;
        private String key;
        private int ttlSeconds;
    }

    @Data
    private static class CachePayload {
        private String result;
        private long originalDurationMs;
        private long cachedAtMillis;
        private RawPayloadLocator rawLocator;
        private boolean compactionApplied;
        private String resultSourceJson;
        private String observabilitySourceJson;
        private String rawSourceJson;
        private String rootSelectionDigest;
    }

    @Data
    @Builder
    public static class ToolExecutionOutcome {
        private String result;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText resultPrompt;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText observabilityPrompt;
        private long durationMs;
        private boolean success;
    }

    @Data
    @Builder
    public static class CacheMeta {
        private boolean eligible;
        private boolean hit;
        private String key;
        private long ttlRemainingMs;
        private String source;
        private long estimatedSavedDurationMs;
    }

    @Data
    @Builder
    public static class CachedToolCallResult {
        private String result;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText resultPrompt;
        private String observabilityResult;
        @com.fasterxml.jackson.annotation.JsonIgnore
        private PromptText observabilityPrompt;
        private long durationMs;
        private boolean success;
        private CacheMeta cacheMeta;
    }
}
