package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private boolean queryIsHs300(DomesticIndexSearchRequest request) {
        return request != null && "沪深300".equals(request.getQuery());
    }

    private boolean queryIsIndexWithMissingFields(DomesticIndexDailyByTsCodeAndDateRangeRequest request) {
        return request != null && "931998.CSI".equals(request.getTsCode());
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
