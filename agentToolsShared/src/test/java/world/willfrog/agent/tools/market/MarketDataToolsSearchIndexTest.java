package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
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

    private boolean queryIsHs300(DomesticIndexSearchRequest request) {
        return request != null && "沪深300".equals(request.getQuery());
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
