package world.willfrog.agent.tools.market.advanced;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoFullItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByConCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AdvancedSearchEngine 指数/成分股条件执行")
class AdvancedSearchEngineTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_CODES = 3;

    private final DomesticIndexService indexService = mock(DomesticIndexService.class);
    private final DomesticListedAssetService listedAssetService = mock(DomesticListedAssetService.class);
    private final AdvancedSearchEngine engine = new AdvancedSearchEngine(indexService, listedAssetService, null);

    @Nested
    @DisplayName("searchIndex + has_stock")
    class SearchIndexHasStock {





    }

    @Nested
    @DisplayName("searchAssetInfo(stock) + index_component")
    class SearchAssetInfoStockIndexComponent {


    }

    @Nested
    @DisplayName("searchAssetInfo(etf) + has_stock")
    class SearchAssetInfoEtfHasStock {




    }

    @Nested
    @DisplayName("name 预过滤与条件交集")
    class NamePrefilter {

    }

    @Nested
    @DisplayName("NONE 日期边界")
    class NoneDateBoundaries {


    }

    @Nested
    @DisplayName("历史 RPC 响应的选择与失败语义")
    class HistoricalRpcResults {







    }

    @Nested
    @DisplayName("非法 asset_type")
    class InvalidAssetType {

    }

    private static AdvancedSearchRequest request(String toolName, String assetType, String name,
                                                  AdvancedSearchCondition... conditions) {
        AdvancedSearchRequest request = new AdvancedSearchRequest();
        request.setToolName(toolName);
        request.setAssetType(assetType == null ? "" : assetType);
        request.setName(name == null ? "" : name);
        request.setConditions(List.of(conditions));
        for (int i = 0; i < request.getConditions().size(); i++) {
            request.getConditions().get(i).setIndex(i);
        }
        request.setCanonicalQuery(Map.of("name", name == null ? "" : name, "conditions", List.of()));
        return request;
    }

    private static AdvancedSearchCondition condition(String type, String indexCode, String stockCode,
                                                      String startDate, String endDate,
                                                      Double minWeight, Double maxWeight) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("type", type);
        raw.put("index_code", indexCode);
        raw.put("stock_code", stockCode);
        raw.put("start_date", startDate);
        raw.put("end_date", endDate);
        if (minWeight != null) raw.put("min_weight", minWeight);
        if (maxWeight != null) raw.put("max_weight", maxWeight);
        return AdvancedSearchCondition.from(0, raw);
    }

    private static long ms(String yyyymmdd) {
        return DateConvertUtils.convertDateStrToLong(yyyymmdd, "yyyyMMdd");
    }

    private static long ms(long yyyymmdd) {
        return DateConvertUtils.convertDateStrToLong(String.valueOf(yyyymmdd), "yyyyMMdd");
    }

    /** RPC 替身按真实协议返回毫秒日期，不把 Agent 展示用的 YYYYMMDD 提前填入响应。 */
    private static DomesticIndexWeightItem rpcWeight(String indexCode, String conCode, String tradeDate, double weight) {
        return weightItem(indexCode, conCode, ms(tradeDate), weight);
    }

    private void stubIndexWeights(String indexCode, long start, long end, List<DomesticIndexWeightItem> items) {
        when(indexService.getDomesticIndexWeightByTsCodeAndDateRange(argThat(request ->
                request != null && indexCode.equals(request.getTsCode())
                        && request.getStartDate() == start && request.getEndDate() == end)))
                .thenReturn(DomesticIndexWeightByTsCodeAndDateRangeResponse.newBuilder().addAllItems(items).build());
    }

    private void stubStockWeights(String stockCode, long start, long end, List<DomesticIndexWeightItem> items) {
        when(indexService.getDomesticIndexWeightByConCodeAndDateRange(argThat(request ->
                request != null && stockCode.equals(request.getConCode())
                        && request.getStartDate() == start && request.getEndDate() == end)))
                .thenReturn(DomesticIndexWeightByConCodeAndDateRangeResponse.newBuilder().addAllItems(items).build());
    }

    private static DomesticIndexWeightItem weightItem(String indexCode, String conCode, long tradeDate, double weight) {
        return DomesticIndexWeightItem.newBuilder()
                .setIndexCode(indexCode)
                .setConCode(conCode)
                .setTradeDate(tradeDate)
                .setWeight(weight)
                .build();
    }

    private static DomesticIndexInfoSimpleItem simpleIndex(String tsCode, String name) {
        return DomesticIndexInfoSimpleItem.newBuilder().setTsCode(tsCode).setName(name).build();
    }

    private static ListedAssetSearchResponse etfSearch(String tsCode, String indexCode) {
        return ListedAssetSearchResponse.newBuilder()
                .addItems(ListedAssetInfoItem.newBuilder()
                        .setAssetType("etf")
                        .setTsCode(tsCode)
                        .setName(tsCode + " ETF")
                        .setIndexCode(indexCode)
                        .setIndexName(indexCode + " index")
                        .build())
                .build();
    }

    private static ArgumentMatcher<ListedAssetSearchRequest> queryIs(String expected) {
        return req -> req != null && expected.equals(req.getQuery());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> dataset) {
        return (List<Map<String, Object>>) dataset.get("results");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> conditionsMeta(Map<String, Object> dataset) {
        return (List<Map<String, Object>>) dataset.get("conditions_meta");
    }

    private static Map<String, Object> findByCode(List<Map<String, Object>> results, String tsCode) {
        return results.stream()
                .filter(r -> tsCode.equals(r.get("ts_code")))
                .findFirst()
                .orElse(null);
    }
}
