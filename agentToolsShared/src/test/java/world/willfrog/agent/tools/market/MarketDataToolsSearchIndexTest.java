package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.dataset.DatasetManifest;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoFullItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoByTsCodeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoFullItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataToolsSearchIndexTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchIndexIncludesHasDaily() throws Exception {
        DomesticIndexService indexService = mock(DomesticIndexService.class);
        when(indexService.searchDomesticIndex(argThat(this::queryIsHs300)))
                .thenReturn(DomesticIndexSearchResponse.newBuilder()
                        .addItems(DomesticIndexInfoSimpleItem.newBuilder()
                                .setTsCode("000300.SH")
                                .setName("沪深300")
                                .setFullname("沪深300指数")
                                .setMarket("SSE")
                                .setHasDaily(1)
                                .build())
                        .build());

        MarketDataTools tools = new MarketDataTools(
                null,
                null,
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);

        Map<String, Object> response = objectMapper.readValue(
                tools.searchIndex("沪深300"),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = castMap(response.get("data"));
        List<Map<String, Object>> items = castList(data.get("items"));
        assertEquals("000300.SH", items.get(0).get("ts_code"));
        assertEquals(1, ((Number) items.get(0).get("has_daily")).intValue());
    }

    @Test
    void getIndexInfoReturnsReadableChineseFields() throws Exception {
        DomesticIndexService indexService = mock(DomesticIndexService.class);
        when(indexService.getDomesticIndexInfoByTsCode(argThat(this::infoQueryIsNewInfrastructure)))
                .thenReturn(DomesticIndexInfoByTsCodeResponse.newBuilder()
                        .setItem(DomesticIndexInfoFullItem.newBuilder()
                                .setTsCode("000943.CSI")
                                .setName("新基建50")
                                .setFullname("中证新型基础设施建设50指数")
                                .setMarket("CSI")
                                .setPublisher("中证指数有限公司")
                                .build())
                        .build());

        MarketDataTools tools = new MarketDataTools(
                null,
                null,
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);

        Map<String, Object> response = objectMapper.readValue(
                tools.getIndexInfo("000943.CSI"),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = castMap(response.get("data"));
        Map<String, Object> item = castMap(data.get("item"));
        assertEquals("新基建50", item.get("name"));
        assertEquals("中证新型基础设施建设50指数", item.get("fullname"));
        String itemText = String.valueOf(data.get("item_text"));
        assertTrue(itemText.contains("新基建50"));
        assertTrue(itemText.contains("中证新型基础设施建设50指数"));
        assertFalse(itemText.contains("\\346"));
    }

    @Test
    void getStockInfoReturnsReadableChineseFields() throws Exception {
        DomesticStockService stockService = mock(DomesticStockService.class);
        when(stockService.getStockInfoByTsCode(argThat(this::stockInfoQueryIsPingAn)))
                .thenReturn(DomesticStockInfoByTsCodeResponse.newBuilder()
                        .setItem(DomesticStockInfoFullItem.newBuilder()
                                .setTsCode("000001.SZ")
                                .setSymbol("000001")
                                .setName("平安银行")
                                .setFullName("平安银行股份有限公司")
                                .setIndustry("银行")
                                .build())
                        .build());

        MarketDataTools tools = new MarketDataTools(
                null,
                null,
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);

        Map<String, Object> response = objectMapper.readValue(
                tools.getStockInfo("000001.SZ"),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = castMap(response.get("data"));
        Map<String, Object> item = castMap(data.get("item"));
        assertEquals("平安银行", item.get("name"));
        assertEquals("平安银行股份有限公司", item.get("fullName"));
        String itemText = String.valueOf(data.get("item_text"));
        assertTrue(itemText.contains("平安银行"));
        assertTrue(itemText.contains("平安银行股份有限公司"));
        assertFalse(itemText.contains("\\345"));
    }

    @Test
    void getIndexDailyIncludesMissingFieldSummary() throws Exception {
        DomesticIndexService indexService = mock(DomesticIndexService.class);
        when(indexService.getDomesticIndexDailyByTsCodeAndDateRange(argThat(this::queryIsIndexWithMissingFields)))
                .thenReturn(DomesticIndexDailyByTsCodeAndDateRangeResponse.newBuilder()
                        .addItems(DomesticIndexDailyItem.newBuilder()
                                .setTsCode("931998.CSI")
                                .setTradeDate(20260602L)
                                .setClose(1000.0)
                                .setPreClose(990.0)
                                .setChange(10.0)
                                .setPctChg(1.01)
                                .addMissingFields("open")
                                .addMissingFields("high")
                                .addMissingFields("low")
                                .addMissingFields("vol")
                                .addMissingFields("amount")
                                .build())
                        .build());

        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        when(datasetWriter.isEnabled()).thenReturn(false);
        MarketDataTools tools = new MarketDataTools(
                datasetWriter,
                null,
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);

        Map<String, Object> response = objectMapper.readValue(
                tools.getIndexDaily("931998.CSI", "20260601", "20260602"),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        Map<String, Object> data = castMap(response.get("data"));
        assertEquals(Boolean.TRUE, data.get("has_missing_values"));
        Map<String, Object> summary = castMap(data.get("missing_fields_summary"));
        assertEquals(1, ((Number) summary.get("open")).intValue());
        assertEquals(1, ((Number) summary.get("amount")).intValue());
        List<Map<String, Object>> previewRows = castList(data.get("preview_rows"));
        assertEquals(1000.0, ((Number) previewRows.get(0).get("close")).doubleValue(), 0.0001);
    }

    @Test
    void manifestMemberUsesActualReusableDatasetRange() {
        List<String> headers = List.of("ts_code", "trade_date", "open", "high", "low", "close",
                "pre_close", "change", "pct_chg", "vol", "amount");
        DatasetRegistry.DatasetMeta meta = DatasetRegistry.DatasetMeta.builder()
                .datasetId("shared-stock-000001.SZ-20240101-20240131-abc")
                .type("stock_daily")
                .tsCode("000001.SZ")
                .startDate("20240101")
                .endDate("20240131")
                .columns(headers)
                .rowCount(20)
                .build();
        MarketDataTools tools = new MarketDataTools(
                null,
                null,
                null,
                null,
                new AgentLlmProperties(),
                objectMapper
        );

        Map<String, Object> data = ReflectionTestUtils.invokeMethod(
                tools, "datasetDataFromMeta", "000001.SZ", "20240110", "20240120", headers, meta);
        List<Map<String, Object>> results = List.of(Map.of(
                "ts_code", "000001.SZ",
                "ok", true,
                "data", data
        ));

        List<DatasetManifest.ManifestMember> members = ReflectionTestUtils.invokeMethod(
                tools, "buildManifestMembers", results, "20240110", "20240120", headers);

        assertEquals(1, members.size());
        DatasetManifest.ManifestMember member = members.get(0);
        assertEquals("shared-stock-000001.SZ-20240101-20240131-abc", member.getDatasetId());
        assertEquals("20240101", member.getStartDate());
        assertEquals("20240131", member.getEndDate());
    }

    private boolean queryIsHs300(DomesticIndexSearchRequest request) {
        return request != null && "沪深300".equals(request.getQuery());
    }

    private boolean queryIsIndexWithMissingFields(DomesticIndexDailyByTsCodeAndDateRangeRequest request) {
        return request != null && "931998.CSI".equals(request.getTsCode());
    }

    private boolean infoQueryIsNewInfrastructure(DomesticIndexInfoByTsCodeRequest request) {
        return request != null && "000943.CSI".equals(request.getTsCode());
    }

    private boolean stockInfoQueryIsPingAn(DomesticStockInfoByTsCodeRequest request) {
        return request != null && "000001.SZ".equals(request.getTsCode());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        assertTrue(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        assertTrue(value instanceof List<?>);
        return (List<Map<String, Object>>) value;
    }
}
