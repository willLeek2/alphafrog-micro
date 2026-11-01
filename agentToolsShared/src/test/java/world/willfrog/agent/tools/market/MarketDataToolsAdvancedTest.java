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
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.pojo.domestic.index.SwIndustryMember;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataToolsAdvancedTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MarketDataTools tools;
    private DomesticStockService stockService;
    private DomesticIndexService indexService;
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
        indexService = mock(DomesticIndexService.class);
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);
        swIndustryMemberDao = mock(SwIndustryMemberDao.class);
        ReflectionTestUtils.setField(tools, "swIndustryMemberDao", swIndustryMemberDao);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        ReflectionTestUtils.setField(tools, "localConfigLoader", localConfigLoader);
    }











    private long toTimestamp(String date) {
        return DateConvertUtils.convertDateStrToLong(date, "yyyyMMdd");
    }

    private boolean matchesIndexWeightRequest(DomesticIndexWeightByTsCodeAndDateRangeRequest request) {
        return request != null && "000300.SH".equals(request.getTsCode())
                && request.getStartDate() == toTimestamp("20240101")
                && request.getEndDate() == toTimestamp("20241231");
    }

    private DomesticIndexWeightItem indexWeightItem(String indexCode, String conCode, String tradeDate, double weight) {
        return DomesticIndexWeightItem.newBuilder()
                .setIndexCode(indexCode).setConCode(conCode)
                .setTradeDate(toTimestamp(tradeDate)).setWeight(weight).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        assertTrue(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }
}
