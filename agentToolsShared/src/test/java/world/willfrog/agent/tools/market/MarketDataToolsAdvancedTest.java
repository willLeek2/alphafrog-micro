package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.IndexWeight;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.SwIndustryMember;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataToolsAdvancedTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MarketDataTools tools;
    private DomesticStockService stockService;
    private IndexWeightDao indexWeightDao;
    private SwIndustryMemberDao swIndustryMemberDao;
    private AgentLlmLocalConfigLoader localConfigLoader;

    @BeforeEach
    void setUp() {
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        when(datasetWriter.isEnabled()).thenReturn(false);
        tools = new MarketDataTools(
                datasetWriter,
                mock(DatasetRegistry.class),
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        stockService = mock(DomesticStockService.class);
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
        indexWeightDao = mock(IndexWeightDao.class);
        ReflectionTestUtils.setField(tools, "indexWeightDao", indexWeightDao);
        swIndustryMemberDao = mock(SwIndustryMemberDao.class);
        ReflectionTestUtils.setField(tools, "swIndustryMemberDao", swIndustryMemberDao);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        ReflectionTestUtils.setField(tools, "localConfigLoader", localConfigLoader);
    }

    @Test
    void getStockSwIndustryInfo_shouldReturnL1L2L3ForSingleTsCode() throws Exception {
        SwIndustryMember member = new SwIndustryMember();
        member.setL1Code("430000");
        member.setL1Name("金融");
        member.setL2Code("430100");
        member.setL2Name("银行");
        member.setL3Code("430101");
        member.setL3Name("国有大型银行");
        member.setTsCode("000001.SZ");
        member.setName("平安银行");
        member.setInDate(20240101L);
        member.setIsNew("Y");
        when(swIndustryMemberDao.getByTsCode("000001.SZ")).thenReturn(List.of(member));

        String response = tools.getStockSwIndustryInfo("000001.SZ");
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});
        Map<String, Object> data = castMap(root.get("data"));

        assertEquals(Boolean.TRUE, root.get("ok"));
        assertEquals("000001.SZ", data.get("ts_code"));
        assertEquals(1, ((Number) data.get("count")).intValue());
        List<?> items = (List<?>) data.get("items");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("430000", item.get("l1_code"));
        assertEquals("国有大型银行", item.get("l3_name"));
    }

    @Test
    void getStockSwIndustryInfo_shouldSupportBatch() throws Exception {
        when(swIndustryMemberDao.getByTsCode("000001.SZ")).thenReturn(List.of());
        when(swIndustryMemberDao.getByTsCode("600519.SH")).thenReturn(List.of());

        String response = tools.getStockSwIndustryInfo("000001.SZ|600519.SH");
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});
        Map<String, Object> data = castMap(root.get("data"));

        assertEquals("batch", data.get("mode"));
        assertEquals(2, ((List<?>) data.get("results")).size());
    }

    @Test
    void getExchangeAssetDaily_advanced_indexComponent_shouldFetchConstituentDailies() throws Exception {
        when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange(
                eq("000300.SH"), eq(toTimestamp("20240101")), eq(toTimestamp("20241231"))))
                .thenReturn(List.of(
                        indexWeightPojo("000300.SH", "000001.SZ", "20240115", 5.0),
                        indexWeightPojo("000300.SH", "600519.SH", "20240115", 3.0)
                ));
        DomesticStockDailyItem item1 = DomesticStockDailyItem.newBuilder()
                .setTsCode("000001.SZ").setTradeDate(20240102L)
                .setOpen(10.0).setHigh(10.5).setLow(9.8).setClose(10.2)
                .setPreClose(10.0).setChange(0.2).setPctChg(2.0).setVol(1000.0).setAmount(10000.0)
                .build();
        DomesticStockDailyItem item2 = DomesticStockDailyItem.newBuilder()
                .setTsCode("600519.SH").setTradeDate(20240102L)
                .setOpen(100.0).setHigh(101.0).setLow(99.0).setClose(100.5)
                .setPreClose(100.0).setChange(0.5).setPctChg(0.5).setVol(500.0).setAmount(50000.0)
                .build();
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().addItems(item1).build())
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().addItems(item2).build());

        String advancedQuery = """
                {"asset_type":"stock","conditions":[{"type":"index_component","index_code":"000300.SH","start_date":"20240101","end_date":"20241231"}]}
                """;
        String response = tools.getExchangeAssetDaily(null, "stock", "20240101", "20240131", "raw_ohlc", "advanced", advancedQuery);
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});
        Map<String, Object> data = castMap(root.get("data"));

        assertEquals(Boolean.TRUE, root.get("ok"));
        assertEquals("advanced", data.get("mode"));
        assertEquals(2, ((Number) data.get("matched_stock_count")).intValue());
        assertEquals(2, ((Number) data.get("row_count")).intValue());
        List<?> matched = (List<?>) data.get("matched_stocks");
        assertTrue(matched.contains("000001.SZ"));
        assertTrue(matched.contains("600519.SH"));
    }

    @Test
    void getExchangeAssetDaily_advanced_swIndustryL3_shouldFetchConstituentDailies() throws Exception {
        SwIndustryMember member = new SwIndustryMember();
        member.setL1Code("430000");
        member.setL1Name("金融");
        member.setL2Code("430100");
        member.setL2Name("银行");
        member.setL3Code("430101");
        member.setL3Name("国有大型银行");
        member.setTsCode("000001.SZ");
        member.setName("平安银行");
        member.setIsNew("Y");
        when(swIndustryMemberDao.getByL3Code("430101")).thenReturn(List.of(member));

        DomesticStockDailyItem item = DomesticStockDailyItem.newBuilder()
                .setTsCode("000001.SZ").setTradeDate(20240102L)
                .setOpen(10.0).setHigh(10.5).setLow(9.8).setClose(10.2)
                .setPreClose(10.0).setChange(0.2).setPctChg(2.0).setVol(1000.0).setAmount(10000.0)
                .build();
        when(stockService.getStockDailyByTsCodeAndDateRange(any()))
                .thenReturn(DomesticStockDailyByTsCodeAndDateRangeResponse.newBuilder().addItems(item).build());

        String advancedQuery = """
                {"asset_type":"stock","conditions":[{"type":"sw_industry_l3_component","industry_code":"430101"}]}
                """;
        String response = tools.getExchangeAssetDaily(null, "stock", "20240101", "20240131", "raw_ohlc", "advanced", advancedQuery);
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});
        Map<String, Object> data = castMap(root.get("data"));

        assertEquals(Boolean.TRUE, root.get("ok"));
        assertEquals("advanced", data.get("mode"));
        assertEquals(1, ((Number) data.get("matched_stock_count")).intValue());
        assertEquals(1, ((Number) data.get("row_count")).intValue());
    }

    @Test
    void getExchangeAssetDaily_advanced_invalidAssetType_shouldReject() throws Exception {
        String advancedQuery = """
                {"asset_type":"etf","conditions":[{"type":"index_component","index_code":"000300.SH"}]}
                """;
        String response = tools.getExchangeAssetDaily(null, "etf", "20240101", "20240131", "raw_ohlc", "advanced", advancedQuery);
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});

        assertEquals(Boolean.FALSE, root.get("ok"));
        Map<String, Object> error = castMap(root.get("error"));
        assertEquals("INVALID_ARGUMENT", error.get("code"));
    }

    @Test
    void getExchangeAssetDaily_advanced_shouldRejectWhenMatchedStocksExceedLimit() throws Exception {
        List<IndexWeight> weights = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            weights.add(indexWeightPojo("000300.SH", String.format("%06d.SZ", i + 1), "20240115", 1.0));
        }
        when(indexWeightDao.getLatestIndexWeightsByTsCodeAndDateRange(
                eq("000300.SH"), eq(toTimestamp("20240101")), eq(toTimestamp("20241231"))))
                .thenReturn(weights);

        AgentLlmProperties limited = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Parallel parallel = new AgentLlmProperties.Parallel();
        parallel.setMaxAdvancedDailyConstituentStocks(5);
        runtime.setParallel(parallel);
        limited.setRuntime(runtime);
        when(localConfigLoader.current()).thenReturn(Optional.of(limited));

        String advancedQuery = """
                {"asset_type":"stock","conditions":[{"type":"index_component","index_code":"000300.SH","start_date":"20240101","end_date":"20241231"}]}
                """;
        String response = tools.getExchangeAssetDaily(null, "stock", "20240101", "20240131", "raw_ohlc", "advanced", advancedQuery);
        Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {});

        assertEquals(Boolean.FALSE, root.get("ok"));
        Map<String, Object> error = castMap(root.get("error"));
        assertEquals("BATCH_LIMIT_EXCEEDED", error.get("code"));
        verify(stockService, never()).getStockDailyByTsCodeAndDateRange(any());
    }

    private long toTimestamp(String date) {
        return DateConvertUtils.convertDateStrToLong(date, "yyyyMMdd");
    }

    private IndexWeight indexWeightPojo(String indexCode, String conCode, String tradeDate, double weight) {
        IndexWeight w = new IndexWeight();
        w.setIndexCode(indexCode);
        w.setConCode(conCode);
        w.setTradeDate(Long.parseLong(tradeDate));
        w.setWeight(weight);
        return w;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        assertTrue(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }
}
