package world.willfrog.agent.tools.market.advanced;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByConCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByConCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchResponse;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

@RequiredArgsConstructor
@Slf4j
public class AdvancedSearchEngine {

    private static final int SEARCH_LIMIT = 200;
    private static final long MIN_DATE_MS;
    private static final long MAX_DATE_MS;

    static {
        MIN_DATE_MS = DateConvertUtils.convertDateStrToLong(String.valueOf(AdvancedSearchCondition.MIN_DATE), "yyyyMMdd");
        MAX_DATE_MS = DateConvertUtils.convertDateStrToLong(String.valueOf(AdvancedSearchCondition.MAX_DATE), "yyyyMMdd");
    }

    private final DomesticIndexService domesticIndexService;
    private final DomesticListedAssetService domesticListedAssetService;
    private final IndexWeightDao indexWeightDao;

    private List<String> upstreamErrors;

    public Map<String, Object> execute(AdvancedSearchRequest request, int maxCodes) {
        this.upstreamErrors = new ArrayList<>();
        Map<String, Object> dataset;
        try {
            if ("searchIndex".equals(request.getToolName())) {
                dataset = executeSearchIndex(request, maxCodes);
            } else {
                dataset = executeSearchAssetInfo(request, maxCodes);
            }
        } finally {
            if (upstreamErrors == null) {
                upstreamErrors = new ArrayList<>();
            }
        }
        if (!upstreamErrors.isEmpty()) {
            dataset.put("upstream_error", String.join("; ", upstreamErrors));
        }
        int rowCount = dataset.get("row_count") instanceof Number n ? n.intValue() : 0;
        if (rowCount == 0 && upstreamErrors.isEmpty()) {
            dataset.put("empty_reason", "no_matching_index_weights");
        }
        return dataset;
    }

    private Map<String, Object> executeSearchIndex(AdvancedSearchRequest request, int maxCodes) {
        Map<String, AdvancedSearchResult> candidates = request.getName().isBlank()
                ? null : searchIndexByName(request.getName(), request.getConditions().size());
        for (AdvancedSearchCondition condition : request.getConditions()) {
            if (!"has_stock".equals(condition.getType())) {
                throw new AdvancedSearchException("INVALID_ARGUMENT", "searchIndex advanced only supports has_stock.");
            }
            Map<String, AdvancedSearchResult> matches = executeHasStockForIndices(condition, maxCodes,
                    request.getConditions().size());
            candidates = intersect(candidates, matches);
        }
        return dataset("searchIndex", "", request, candidates == null ? Map.of() : candidates);
    }

    private Map<String, Object> executeSearchAssetInfo(AdvancedSearchRequest request, int maxCodes) {
        String assetType = request.getAssetType();
        if (!"stock".equals(assetType) && !"etf".equals(assetType)) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", "searchAssetInfo advanced requires asset_type=stock|etf.");
        }
        Map<String, AdvancedSearchResult> candidates = request.getName().isBlank()
                ? null : searchListedAssetsByName(request.getName(), assetType, request.getConditions().size());
        for (AdvancedSearchCondition condition : request.getConditions()) {
            Map<String, AdvancedSearchResult> matches;
            if ("stock".equals(assetType) && "index_component".equals(condition.getType())) {
                matches = executeIndexComponentForStocks(condition, maxCodes, request.getConditions().size());
            } else if ("etf".equals(assetType) && "has_stock".equals(condition.getType())) {
                matches = executeHasStockForEtfs(condition, maxCodes, request.getConditions().size());
            } else {
                throw new AdvancedSearchException("INVALID_ARGUMENT",
                        "Unsupported condition type for asset_type=" + assetType + ": " + condition.getType());
            }
            candidates = intersect(candidates, matches);
        }
        enrichNames(candidates == null ? Map.of() : candidates, assetType);
        return dataset("searchAssetInfo", assetType, request, candidates == null ? Map.of() : candidates);
    }

    private Map<String, AdvancedSearchResult> executeIndexComponentForStocks(AdvancedSearchCondition condition,
                                                                            int maxCodes,
                                                                            int conditionCount) {
        List<String> indexCodes = splitCodes(condition.getIndexCode(), "index_code", maxCodes);
        Map<String, AdvancedSearchResult> out = new LinkedHashMap<>();
        for (String indexCode : indexCodes) {
            List<DomesticIndexWeightItem> items;
            try {
                items = queryIndexComponentWeights(indexCode, condition);
            } catch (Exception e) {
                recordUpstreamError("index_component query failed for " + indexCode, e);
                continue;
            }
            Map<String, DomesticIndexWeightItem> latestByStock = new LinkedHashMap<>();
            for (DomesticIndexWeightItem item : items) {
                if (!weightMatches(condition, item)) {
                    continue;
                }
                DomesticIndexWeightItem previous = latestByStock.get(item.getConCode());
                if (previous == null || item.getTradeDate() > previous.getTradeDate()) {
                    latestByStock.put(item.getConCode(), item);
                }
            }
            latestByStock.forEach((tsCode, item) -> {
                AdvancedSearchResult result = base(tsCode, "stock", conditionCount);
                result.setIndexCode(item.getIndexCode());
                putReason(result, condition.getIndex(), item);
                out.put(tsCode, result);
            });
        }
        return out;
    }

    private List<DomesticIndexWeightItem> queryIndexComponentWeights(String indexCode, AdvancedSearchCondition condition) {
        if (condition.getStartDateValue() == null && condition.getEndDateValue() == null) {
            Long maxTradeDate = indexWeightDao.getMaxTradeDateByTsCode(indexCode, MIN_DATE_MS, MAX_DATE_MS);
            if (maxTradeDate == null) {
                return List.of();
            }
            return indexWeightDao.getIndexWeightsByTsCodeAndTradeDate(indexCode, maxTradeDate)
                    .stream()
                    .map(this::toItem)
                    .toList();
        }
        long startMs = yyyymmddToMillis(condition.effectiveStartDate());
        long endMs = yyyymmddToMillis(condition.effectiveEndDate());
        return indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange(indexCode, startMs, endMs)
                .stream()
                .map(this::toItem)
                .toList();
    }

    private Map<String, AdvancedSearchResult> executeHasStockForIndices(AdvancedSearchCondition condition,
                                                                        int maxCodes,
                                                                        int conditionCount) {
        List<String> stockCodes = splitCodes(condition.getStockCode(), "stock_code", maxCodes);
        Map<String, DomesticIndexWeightItem> latestByIndex = findLatestIndicesForStocks(condition, stockCodes);
        Map<String, AdvancedSearchResult> out = new LinkedHashMap<>();
        latestByIndex.forEach((indexCode, item) -> {
            AdvancedSearchResult result = base(indexCode, "index", conditionCount);
            putReason(result, condition.getIndex(), item);
            out.put(indexCode, result);
        });
        enrichNames(out, "index");
        return out;
    }

    private Map<String, AdvancedSearchResult> executeHasStockForEtfs(AdvancedSearchCondition condition,
                                                                     int maxCodes,
                                                                     int conditionCount) {
        List<String> stockCodes = splitCodes(condition.getStockCode(), "stock_code", maxCodes);
        Map<String, DomesticIndexWeightItem> latestByIndex = findLatestIndicesForStocks(condition, stockCodes);
        Map<String, AdvancedSearchResult> out = new LinkedHashMap<>();
        for (Map.Entry<String, DomesticIndexWeightItem> entry : latestByIndex.entrySet()) {
            String indexCode = entry.getKey();
            ListedAssetSearchResponse response = domesticListedAssetService.searchListedAssets(
                    ListedAssetSearchRequest.newBuilder()
                            .setQuery(indexCode)
                            .addAssetTypes("etf")
                            .setMarketScope("domestic")
                            .setLimit(SEARCH_LIMIT)
                            .build());
            for (var item : response.getItemsList()) {
                AdvancedSearchResult result = out.computeIfAbsent(item.getTsCode(),
                        ignored -> base(item.getTsCode(), "etf", conditionCount));
                result.setName(emptyToNull(item.getName()));
                result.setIndexCode(indexCode);
                result.setIndexName(emptyToNull(item.getIndexName()));
                putReason(result, condition.getIndex(), entry.getValue());
            }
        }
        return out;
    }

    private Map<String, DomesticIndexWeightItem> findLatestIndicesForStocks(AdvancedSearchCondition condition,
                                                                            List<String> stockCodes) {
        Map<String, DomesticIndexWeightItem> latestByIndex = new LinkedHashMap<>();
        long startMs = condition.getStartDateValue() == null ? MIN_DATE_MS : yyyymmddToMillis(condition.effectiveStartDate());
        long endMs = condition.getEndDateValue() == null ? MAX_DATE_MS : yyyymmddToMillis(condition.effectiveEndDate());
        for (String stockCode : stockCodes) {
            List<DomesticIndexWeightItem> items;
            try {
                items = indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange(stockCode, startMs, endMs)
                        .stream()
                        .map(this::toItem)
                        .toList();
            } catch (Exception e) {
                recordUpstreamError("has_stock query failed for " + stockCode, e);
                continue;
            }
            for (DomesticIndexWeightItem item : items) {
                if (!weightMatches(condition, item)) {
                    continue;
                }
                DomesticIndexWeightItem previous = latestByIndex.get(item.getIndexCode());
                if (previous == null || item.getTradeDate() > previous.getTradeDate()) {
                    latestByIndex.put(item.getIndexCode(), item);
                }
            }
        }
        return latestByIndex;
    }

    private boolean weightMatches(AdvancedSearchCondition condition, DomesticIndexWeightItem item) {
        return !condition.hasWeightFilter() || condition.matchesWeight(item.getWeight());
    }

    private Map<String, AdvancedSearchResult> searchIndexByName(String name, int conditionCount) {
        DomesticIndexSearchResponse response = domesticIndexService.searchDomesticIndex(
                DomesticIndexSearchRequest.newBuilder().setQuery(name).build());
        Map<String, AdvancedSearchResult> out = new LinkedHashMap<>();
        for (var item : response.getItemsList()) {
            AdvancedSearchResult result = base(item.getTsCode(), "index", conditionCount);
            result.setName(emptyToNull(item.getName()));
            out.put(item.getTsCode(), result);
        }
        return out;
    }

    private Map<String, AdvancedSearchResult> searchListedAssetsByName(String name, String assetType, int conditionCount) {
        ListedAssetSearchResponse response = domesticListedAssetService.searchListedAssets(
                ListedAssetSearchRequest.newBuilder()
                        .setQuery(name)
                        .addAssetTypes(assetType)
                        .setMarketScope("domestic")
                        .setLimit(SEARCH_LIMIT)
                        .build());
        Map<String, AdvancedSearchResult> out = new LinkedHashMap<>();
        for (var item : response.getItemsList()) {
            AdvancedSearchResult result = base(item.getTsCode(), assetType, conditionCount);
            result.setName(emptyToNull(item.getName()));
            result.setIndexCode(emptyToNull(item.getIndexCode()));
            result.setIndexName(emptyToNull(item.getIndexName()));
            out.put(item.getTsCode(), result);
        }
        return out;
    }

    private void enrichNames(Map<String, AdvancedSearchResult> results, String assetType) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (AdvancedSearchResult result : results.values()) {
            if (result.getName() != null && !result.getName().isBlank()) {
                continue;
            }
            try {
                if ("index".equals(assetType)) {
                    DomesticIndexInfoByTsCodeResponse response = domesticIndexService.getDomesticIndexInfoByTsCode(
                            DomesticIndexInfoByTsCodeRequest.newBuilder().setTsCode(result.getTsCode()).build());
                    if (response != null && response.hasItem()) {
                        result.setName(emptyToNull(response.getItem().getName()));
                    }
                } else {
                    ListedAssetInfoResponse response = domesticListedAssetService.getListedAssetInfo(
                            ListedAssetInfoRequest.newBuilder()
                                    .setTsCode(result.getTsCode())
                                    .setAssetType(assetType)
                                    .build());
                    if (response != null && response.hasItem()) {
                        result.setName(emptyToNull(response.getItem().getName()));
                        result.setIndexCode(emptyToNull(response.getItem().getIndexCode()));
                        result.setIndexName(emptyToNull(response.getItem().getIndexName()));
                    }
                }
            } catch (Exception e) {
                log.warn("Advanced search name lookup failed: assetType={}, tsCode={}", assetType, result.getTsCode(), e);
            }
        }
    }

    private Map<String, AdvancedSearchResult> intersect(Map<String, AdvancedSearchResult> left,
                                                        Map<String, AdvancedSearchResult> right) {
        if (left == null) {
            return right;
        }
        Map<String, AdvancedSearchResult> out = new LinkedHashMap<>();
        for (Map.Entry<String, AdvancedSearchResult> entry : left.entrySet()) {
            AdvancedSearchResult match = right.get(entry.getKey());
            if (match == null) {
                continue;
            }
            AdvancedSearchResult merged = entry.getValue().copy();
            for (int i = 0; i < match.getMatchConditions().size(); i++) {
                if (match.getMatchConditions().get(i) != null) {
                    merged.getMatchConditions().set(i, match.getMatchConditions().get(i));
                }
            }
            if (merged.getName() == null || merged.getName().isBlank()) {
                merged.setName(match.getName());
            }
            if (merged.getIndexCode() == null || merged.getIndexCode().isBlank()) {
                merged.setIndexCode(match.getIndexCode());
            }
            if (merged.getIndexName() == null || merged.getIndexName().isBlank()) {
                merged.setIndexName(match.getIndexName());
            }
            out.put(entry.getKey(), merged);
        }
        return out;
    }

    private AdvancedSearchResult base(String tsCode, String assetType, int conditionCount) {
        AdvancedSearchResult result = new AdvancedSearchResult();
        result.setTsCode(tsCode);
        result.setAssetType(assetType);
        for (int i = 0; i < conditionCount; i++) {
            result.getMatchConditions().add(null);
        }
        return result;
    }

    private void putReason(AdvancedSearchResult result, int conditionIndex, DomesticIndexWeightItem item) {
        result.getMatchConditions().set(conditionIndex, new ArrayList<>(List.of(item.getTradeDate(), item.getWeight())));
    }

    private List<String> splitCodes(String raw, String field, int maxCodes) {
        if (raw == null || raw.isBlank()) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", field + " is required.");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : raw.split("\\|")) {
            String code = token.trim();
            if (!code.isBlank()) {
                out.add(code);
            }
        }
        if (out.isEmpty()) {
            throw new AdvancedSearchException("INVALID_ARGUMENT", field + " is required.");
        }
        if (out.size() > maxCodes) {
            throw new AdvancedSearchException("BATCH_LIMIT_EXCEEDED",
                    field + " count exceeds maxParallelQueriesInAdvancedMode.");
        }
        return new ArrayList<>(out);
    }

    private Map<String, Object> dataset(String toolName,
                                        String assetType,
                                        AdvancedSearchRequest request,
                                        Map<String, AdvancedSearchResult> results) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdvancedSearchResult result : results.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts_code", result.getTsCode());
            row.put("name", result.getName());
            row.put("asset_type", result.getAssetType());
            if (result.getIndexCode() != null) {
                row.put("index_code", result.getIndexCode());
            }
            if (result.getIndexName() != null) {
                row.put("index_name", result.getIndexName());
            }
            row.put("match_conditions", result.getMatchConditions());
            rows.add(row);
        }
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("schema_version", 1);
        dataset.put("mode", "advanced");
        dataset.put("tool", toolName);
        dataset.put("asset_type", assetType == null || assetType.isBlank() ? "index" : assetType);
        dataset.put("query", request.getCanonicalQuery());
        dataset.put("conditions_meta", conditionsMeta(request.getConditions()));
        dataset.put("row_count", rows.size());
        dataset.put("results", rows);
        return dataset;
    }

    private List<Map<String, Object>> conditionsMeta(List<AdvancedSearchCondition> conditions) {
        List<Map<String, Object>> meta = new ArrayList<>();
        for (AdvancedSearchCondition condition : conditions) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, Object> slotType = new LinkedHashMap<>();
            slotType.put("date_match_reason", "long");
            slotType.put("weight_match_reason", "float|null");
            row.put("condition_index", condition.getIndex());
            row.put("type", condition.getType());
            row.put("slot_type", slotType);
            row.put("start_date", condition.getStartDate());
            row.put("end_date", condition.getEndDate());
            row.put("min_weight", condition.getMinWeight());
            row.put("max_weight", condition.getMaxWeight());
            meta.add(row);
        }
        return meta;
    }

    private void recordUpstreamError(String message, Exception e) {
        String detail = message + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        log.warn(detail, e);
        if (upstreamErrors == null) {
            upstreamErrors = new ArrayList<>();
        }
        upstreamErrors.add(detail);
    }

    private DomesticIndexWeightItem toItem(IndexWeight pojo) {
        return DomesticIndexWeightItem.newBuilder()
                .setIndexCode(pojo.getIndexCode())
                .setConCode(pojo.getConCode())
                .setTradeDate(millisToYyyymmdd(pojo.getTradeDate()))
                .setWeight(pojo.getWeight())
                .build();
    }

    private static long yyyymmddToMillis(long yyyymmdd) {
        return DateConvertUtils.convertDateStrToLong(String.valueOf(yyyymmdd), "yyyyMMdd");
    }

    private static long millisToYyyymmdd(long millis) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return Long.parseLong(dateFormat.format(new Date(millis)));
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
