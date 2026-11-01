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
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDayStatusResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticTradingDaysCountResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：getTradingDaysSummary / isTradingDay 的十二例钉住测试（task #109）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序。
 */
class MarketDataToolsCalendarToolsTest {

    private static final long MS_20240101 = 1704038400000L; // 2024-01-01T00:00:00+08:00
    private static final long MS_20240331 = 1711814400000L; // 2024-03-31T00:00:00+08:00
    private static final long MS_20240102 = 1704124800000L; // 2024-01-02T00:00:00+08:00
    private static final long MS_20240105 = 1704384000000L; // 2024-01-05T00:00:00+08:00

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticIndexService indexService;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        indexService = mock(DomesticIndexService.class);
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry datasetRegistry = mock(DatasetRegistry.class);
        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        tools = configure(new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                new AgentLlmProperties(), objectMapper));
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);
    }

    private Map<String, Object> invokeSummary(String start, String end, String exchange) throws Exception {
        return objectMapper.readValue(tools.getTradingDaysSummary(start, end, exchange),
                new TypeReference<>() {});
    }

    private Map<String, Object> invokeIsTradingDay(String date, String exchange) throws Exception {
        return objectMapper.readValue(tools.isTradingDay(date, exchange), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("error");
    }

    // ---------- getTradingDaysSummary ----------






    // ---------- isTradingDay ----------







}
