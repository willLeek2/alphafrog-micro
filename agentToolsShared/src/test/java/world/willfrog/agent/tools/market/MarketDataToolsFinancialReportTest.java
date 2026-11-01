package world.willfrog.agent.tools.market;

import static world.willfrog.agent.tools.support.ToolPromptTestFixture.configure;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockBalancesheetQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockCashflowQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockExpressQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockFinancialQueryRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockIncomeQueryResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.StockBalancesheetItem;
import world.willfrog.alphafrogmicro.domestic.idl.StockCashflowItem;
import world.willfrog.alphafrogmicro.domestic.idl.StockExpressItem;
import world.willfrog.alphafrogmicro.domestic.idl.StockIncomeItem;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：getFinancialReport 的九例钉住测试（task #109）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序。
 */
class MarketDataToolsFinancialReportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticStockService stockService;
    private DatasetWriter datasetWriter;
    private DatasetRegistry datasetRegistry;
    private AgentLlmLocalConfigLoader localConfigLoader;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        stockService = mock(DomesticStockService.class);
        datasetWriter = mock(DatasetWriter.class);
        datasetRegistry = mock(DatasetRegistry.class);
        localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        tools = configure(new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                new AgentLlmProperties(), objectMapper));
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
    }

    private Map<String, Object> invoke(String tsCode, String reportType, String start, String end) throws Exception {
        return objectMapper.readValue(tools.getFinancialReport(tsCode, reportType, start, end),
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
    private static List<Map<String, Object>> itemsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }









}
