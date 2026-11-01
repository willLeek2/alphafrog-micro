package world.willfrog.agent.tools.market;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetDailyResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataToolsSearchIndexTest {

    private final ObjectMapper objectMapper = new ObjectMapper();







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

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        assertTrue(value instanceof List<?>);
        return (List<String>) value;
    }

    private static void assertThatAllStartWith(List<String> values, String prefix) {
        assertEquals(2, values.size());
        assertTrue(values.stream().allMatch(value -> value.startsWith(prefix)));
    }
}
