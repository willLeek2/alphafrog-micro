package world.willfrog.agent.tools.market;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizeItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticEtfShareSizesByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavsByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundNavsByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorRequest;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetAdjFactorResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：getOffExchangeAssetDaily / getEtfAdj / getListedAssetShareSize 的十五例钉住测试（task #109）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序。
 */
class MarketDataToolsFundEtfToolsTest {

    private static final long MS_20240101 = 1704038400000L; // 2024-01-01T00:00:00+08:00
    private static final long MS_20240331 = 1711814400000L; // 2024-03-31T00:00:00+08:00
    private static final long MS_20240102 = 1704124800000L; // 2024-01-02T00:00:00+08:00

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticFundService fundService;
    private DomesticListedAssetService listedAssetService;
    private AgentLlmLocalConfigLoader localConfigLoader;
    private AgentLlmProperties llmProperties;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        fundService = mock(DomesticFundService.class);
        listedAssetService = mock(DomesticListedAssetService.class);
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry datasetRegistry = mock(DatasetRegistry.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        llmProperties = new AgentLlmProperties();
        tools = configure(new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                llmProperties, objectMapper));
        ReflectionTestUtils.setField(tools, "domesticFundService", fundService);
        ReflectionTestUtils.setField(tools, "domesticListedAssetService", listedAssetService);
    }

    private Map<String, Object> invokeOffExchange(String tsCode, String start, String end) throws Exception {
        return objectMapper.readValue(tools.getOffExchangeAssetDaily(tsCode, start, end, null),
                new TypeReference<>() {});
    }

    private Map<String, Object> invokeEtfAdj(String tsCode, String start, String end) throws Exception {
        return objectMapper.readValue(tools.getEtfAdj(tsCode, start, end), new TypeReference<>() {});
    }

    private Map<String, Object> invokeShareSize(String tsCode, String start, String end, String exchange)
            throws Exception {
        return objectMapper.readValue(tools.getListedAssetShareSize(tsCode, start, end, exchange),
                new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("error");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> previewRowsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("preview_rows");
    }

    private void enableAdjFactor() {
        llmProperties.getRuntime().getExecution().setAdjFactorEnabled(true);
    }

    // ---------- getOffExchangeAssetDaily ----------





    // ---------- getEtfAdj ----------






    // ---------- getListedAssetShareSize ----------






}
