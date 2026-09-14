package world.willfrog.agent.tools.market.advanced;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoFullItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightItem;
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
    private final IndexWeightDao indexWeightDao = mock(IndexWeightDao.class);
    private final AdvancedSearchEngine engine = new AdvancedSearchEngine(indexService, listedAssetService, indexWeightDao, null);

    @Nested
    @DisplayName("searchIndex + has_stock")
    class SearchIndexHasStock {

        @Test
        @DisplayName("根据股票代码反查包含该股票的指数")
        void findsIndicesContainingStock() {
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("000001.SZ", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "000001.SZ", "20240115", 5.23),
                            weightPojo("000905.SH", "000001.SZ", "20240110", 1.2)
                    ));
            when(indexService.getDomesticIndexInfoByTsCode(any()))
                    .thenReturn(DomesticIndexInfoByTsCodeResponse.newBuilder()
                            .setItem(DomesticIndexInfoFullItem.newBuilder().setTsCode("000300.SH").setName("沪深300").build())
                            .build());

            Map<String, Object> dataset = engine.execute(request("searchIndex", null, null,
                    condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES);

            assertEquals("searchIndex", dataset.get("tool"));
            assertEquals("index", dataset.get("asset_type"));
            List<Map<String, Object>> results = results(dataset);
            assertEquals(2, results.size());
            Map<String, Object> hs300 = findByCode(results, "000300.SH");
            assertNotNull(hs300);
            assertEquals("沪深300", hs300.get("name"));
            assertEquals(List.of(List.of(20240115L, 5.23)), hs300.get("match_conditions"));
            Map<String, Object> meta = conditionsMeta(dataset).get(0);
            assertEquals("has_stock", meta.get("type"));
            assertEquals(Map.of("date_match_reason", "long", "weight_match_reason", "float|null"),
                    meta.get("slot_type"));
        }

        @Test
        @DisplayName("多个 condition 对指数结果取 AND")
        void multipleConditionsIntersect() {
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("000001.SZ", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(weightPojo("000300.SH", "000001.SZ", "20240115", 5.0)));
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("600519.SH", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "600519.SH", "20240115", 3.0),
                            weightPojo("000905.SH", "600519.SH", "20240115", 2.0)
                    ));

            Map<String, Object> dataset = engine.execute(request("searchIndex", null, null,
                    condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null),
                    condition("has_stock", "", "600519.SH", "20240101", "20241231", null, null)), MAX_CODES);

            List<Map<String, Object>> results = results(dataset);
            assertEquals(1, results.size());
            assertEquals("000300.SH", results.get(0).get("ts_code"));
            List<List<Object>> reasons = (List<List<Object>>) results.get(0).get("match_conditions");
            assertEquals(List.of(20240115L, 5.0), reasons.get(0));
            assertEquals(List.of(20240115L, 3.0), reasons.get(1));
        }

        @Test
        @DisplayName("index_component 对 searchIndex 非法")
        void indexComponentRejectedForSearchIndex() {
            AdvancedSearchException ex = assertThrows(AdvancedSearchException.class, () ->
                    engine.execute(request("searchIndex", null, null,
                            condition("index_component", "000300.SH", "", "20240101", "20241231", null, null)), MAX_CODES));
            assertEquals("INVALID_ARGUMENT", ex.getCode());
        }

        @Test
        @DisplayName("股票代码数量超过 maxCodes 抛 BATCH_LIMIT_EXCEEDED")
        void tooManyStockCodesRejected() {
            AdvancedSearchException ex = assertThrows(AdvancedSearchException.class, () ->
                    engine.execute(request("searchIndex", null, null,
                            condition("has_stock", "", "000001.SZ|000002.SZ|000003.SZ|000004.SZ",
                                    "20240101", "20241231", null, null)), MAX_CODES));
            assertEquals("BATCH_LIMIT_EXCEEDED", ex.getCode());
        }

        @Test
        @DisplayName("同一指数多 trade_date 保留最新日期")
        void keepsLatestTradeDatePerIndex() {
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("000001.SZ", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "000001.SZ", "20240110", 5.0),
                            weightPojo("000300.SH", "000001.SZ", "20240115", 5.23),
                            weightPojo("000300.SH", "000001.SZ", "20240112", 5.1)
                    ));
            when(indexService.getDomesticIndexInfoByTsCode(any()))
                    .thenReturn(DomesticIndexInfoByTsCodeResponse.newBuilder()
                            .setItem(DomesticIndexInfoFullItem.newBuilder().setTsCode("000300.SH").setName("沪深300").build())
                            .build());

            Map<String, Object> dataset = engine.execute(request("searchIndex", null, null,
                    condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES);

            List<Map<String, Object>> results = results(dataset);
            assertEquals(1, results.size());
            assertEquals(List.of(List.of(20240115L, 5.23)), results.get(0).get("match_conditions"));
        }
    }

    @Nested
    @DisplayName("searchAssetInfo(stock) + index_component")
    class SearchAssetInfoStockIndexComponent {

        @Test
        @DisplayName("根据指数代码查询成分股")
        void findsConstituentStocks() {
            when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange("000300.SH", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "000001.SZ", "20240115", 5.23),
                            weightPojo("000300.SH", "600519.SH", "20240115", 3.1)
                    ));
            when(listedAssetService.getListedAssetInfo(any()))
                    .thenReturn(ListedAssetInfoResponse.newBuilder()
                            .setItem(ListedAssetInfoItem.newBuilder().setTsCode("000001.SZ").setName("平安银行").build())
                            .build());

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "stock", null,
                    condition("index_component", "000300.SH", "", "20240101", "20241231", null, null)), MAX_CODES);

            assertEquals("stock", dataset.get("asset_type"));
            List<Map<String, Object>> results = results(dataset);
            assertEquals(2, results.size());
            Map<String, Object> pingan = findByCode(results, "000001.SZ");
            assertNotNull(pingan);
            assertEquals("平安银行", pingan.get("name"));
            assertEquals(List.of(List.of(20240115L, 5.23)), pingan.get("match_conditions"));
        }

        @Test
        @DisplayName("权重过滤生效")
        void weightFilterExcludesOutOfRange() {
            when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange("000300.SH", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "000001.SZ", "20240115", 5.0),
                            weightPojo("000300.SH", "600519.SH", "20240115", 1.0)
                    ));
            when(listedAssetService.getListedAssetInfo(any()))
                    .thenReturn(ListedAssetInfoResponse.getDefaultInstance());

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "stock", null,
                    condition("index_component", "000300.SH", "", "20240101", "20241231", 2.0, null)), MAX_CODES);

            List<Map<String, Object>> results = results(dataset);
            assertEquals(1, results.size());
            assertEquals("000001.SZ", results.get(0).get("ts_code"));
        }
        @Test
        @DisplayName("name 查询返回 null 时不崩溃，结果保留 ts_code")
        void nullNameResponseDoesNotCrash() {
            when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange("000300.SH", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(weightPojo("000300.SH", "000001.SZ", "20240115", 5.23)));
            when(listedAssetService.getListedAssetInfo(any()))
                    .thenReturn(null);

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "stock", null,
                    condition("index_component", "000300.SH", "", "20240101", "20241231", null, null)), MAX_CODES);

            List<Map<String, Object>> results = results(dataset);
            assertEquals(1, results.size());
            assertEquals("000001.SZ", results.get(0).get("ts_code"));
            assertEquals(List.of(List.of(20240115L, 5.23)), results.get(0).get("match_conditions"));
        }
    }

    @Nested
    @DisplayName("searchAssetInfo(etf) + has_stock")
    class SearchAssetInfoEtfHasStock {

        @Test
        @DisplayName("根据股票代码找到跟踪对应指数的 ETF")
        void findsEtfsTrackingIndicesContainingStock() {
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("000001.SZ", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(weightPojo("000300.SH", "000001.SZ", "20240115", 5.23)));
            when(listedAssetService.searchListedAssets(any()))
                    .thenReturn(ListedAssetSearchResponse.newBuilder()
                            .addItems(ListedAssetInfoItem.newBuilder()
                                    .setAssetType("etf")
                                    .setTsCode("510300.SH")
                                    .setName("沪深300ETF")
                                    .setIndexCode("000300.SH")
                                    .setIndexName("沪深300")
                                    .build())
                            .build());

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "etf", null,
                    condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES);

            assertEquals("etf", dataset.get("asset_type"));
            List<Map<String, Object>> results = results(dataset);
            assertEquals(1, results.size());
            Map<String, Object> etf = results.get(0);
            assertEquals("510300.SH", etf.get("ts_code"));
            assertEquals("沪深300ETF", etf.get("name"));
            assertEquals("000300.SH", etf.get("index_code"));
        }

        @Test
        @DisplayName("单个股票命中多个指数时不按中间 index 数量触发 batch limit")
        void intermediateIndexCountDoesNotTriggerBatchLimit() {
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("000001.SZ", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "000001.SZ", "20240115", 5.23),
                            weightPojo("000905.SH", "000001.SZ", "20240115", 1.5),
                            weightPojo("000852.SH", "000001.SZ", "20240115", 0.9),
                            weightPojo("399006.SZ", "000001.SZ", "20240115", 0.4)
                    ));
            when(listedAssetService.searchListedAssets(argThat(queryIs("000300.SH"))))
                    .thenReturn(etfSearch("510300.SH", "000300.SH"));
            when(listedAssetService.searchListedAssets(argThat(queryIs("000905.SH"))))
                    .thenReturn(etfSearch("510500.SH", "000905.SH"));
            when(listedAssetService.searchListedAssets(argThat(queryIs("000852.SH"))))
                    .thenReturn(etfSearch("512100.SH", "000852.SH"));
            when(listedAssetService.searchListedAssets(argThat(queryIs("399006.SZ"))))
                    .thenReturn(etfSearch("159915.SZ", "399006.SZ"));

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "etf", null,
                    condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES);

            List<Map<String, Object>> results = results(dataset);
            assertEquals(4, results.size());
            assertNotNull(findByCode(results, "510300.SH"));
            assertNotNull(findByCode(results, "159915.SZ"));
        }

        @Test
        @DisplayName("index_component 对 ETF 非法")
        void indexComponentRejectedForEtf() {
            AdvancedSearchException ex = assertThrows(AdvancedSearchException.class, () ->
                    engine.execute(request("searchAssetInfo", "etf", null,
                            condition("index_component", "000300.SH", "", "20240101", "20241231", null, null)), MAX_CODES));
            assertEquals("INVALID_ARGUMENT", ex.getCode());
        }

        @Test
        @DisplayName("has_stock 对 stock 非法")
        void hasStockRejectedForStock() {
            AdvancedSearchException ex = assertThrows(AdvancedSearchException.class, () ->
                    engine.execute(request("searchAssetInfo", "stock", null,
                            condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES));
            assertEquals("INVALID_ARGUMENT", ex.getCode());
        }
    }

    @Nested
    @DisplayName("name 预过滤与条件交集")
    class NamePrefilter {

        @Test
        @DisplayName("name 非空时先按名称拿候选再应用 condition")
        void nameFiltersCandidatesBeforeCondition() {
            when(indexService.searchDomesticIndex(any()))
                    .thenReturn(DomesticIndexSearchResponse.newBuilder()
                            .addItems(simpleIndex("000300.SH", "沪深300"))
                            .addItems(simpleIndex("000905.SH", "中证500"))
                            .build());
            when(indexWeightDao.getLatestIndexWeightsByConCodeAndDateRange("000001.SZ", ms("20240101"), ms("20241231")))
                    .thenReturn(List.of(weightPojo("000300.SH", "000001.SZ", "20240115", 5.0)));

            Map<String, Object> dataset = engine.execute(request("searchIndex", null, "沪深",
                    condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES);

            List<Map<String, Object>> results = results(dataset);
            assertEquals(1, results.size());
            assertEquals("000300.SH", results.get(0).get("ts_code"));
        }
    }

    @Nested
    @DisplayName("NONE 日期边界")
    class NoneDateBoundaries {

        @Test
        @DisplayName("start_date=NONE 使用全局最小日期")
        void noneStartDateUsesMinBoundary() {
            when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange(
                    "000300.SH", ms(AdvancedSearchCondition.MIN_DATE), ms("20241231")))
                    .thenReturn(List.of(weightPojo("000300.SH", "000001.SZ", "20240115", 5.0)));
            when(listedAssetService.getListedAssetInfo(any()))
                    .thenReturn(ListedAssetInfoResponse.getDefaultInstance());

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "stock", null,
                    condition("index_component", "000300.SH", "", "NONE", "20241231", null, null)), MAX_CODES);

            assertEquals(1, results(dataset).size());
        }

        @Test
        @DisplayName("NONE/NONE 取最新公告期完整快照")
        void noneNoneUsesLatestSnapshot() {
            when(indexWeightDao.getMaxTradeDateByTsCode("000300.SH", ms(AdvancedSearchCondition.MIN_DATE), ms(AdvancedSearchCondition.MAX_DATE)))
                    .thenReturn(ms("20240601"));
            when(indexWeightDao.getIndexWeightsByTsCodeAndTradeDate("000300.SH", ms("20240601")))
                    .thenReturn(List.of(
                            weightPojo("000300.SH", "000001.SZ", "20240601", 5.0),
                            weightPojo("000300.SH", "600519.SH", "20240601", 3.0)
                    ));
            when(listedAssetService.getListedAssetInfo(any()))
                    .thenReturn(ListedAssetInfoResponse.getDefaultInstance());

            Map<String, Object> dataset = engine.execute(request("searchAssetInfo", "stock", null,
                    condition("index_component", "000300.SH", "", "NONE", "NONE", null, null)), MAX_CODES);

            assertEquals(2, results(dataset).size());
        }
    }

    @Nested
    @DisplayName("非法 asset_type")
    class InvalidAssetType {

        @Test
        @DisplayName("searchAssetInfo advanced 仅支持 stock/etf")
        void unsupportedAssetTypeRejected() {
            AdvancedSearchException ex = assertThrows(AdvancedSearchException.class, () ->
                    engine.execute(request("searchAssetInfo", "fund", null,
                            condition("has_stock", "", "000001.SZ", "20240101", "20241231", null, null)), MAX_CODES));
            assertEquals("INVALID_ARGUMENT", ex.getCode());
        }
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

    private static IndexWeight weightPojo(String indexCode, String conCode, String tradeDate, double weight) {
        IndexWeight pojo = new IndexWeight();
        pojo.setIndexCode(indexCode);
        pojo.setConCode(conCode);
        pojo.setTradeDate(ms(tradeDate));
        pojo.setWeight(weight);
        return pojo;
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
