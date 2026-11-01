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
import world.willfrog.agent.tools.market.advanced.AdvancedSearchException;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticFundService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticIndexWeightItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticListedAssetService;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyByTsCodeAndDateRangeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoByTsCodeResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockInfoSimpleItem;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchRequest;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockSearchResponse;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockService;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoItem;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetInfoResponse;
import world.willfrog.alphafrogmicro.domestic.idl.ListedAssetSearchResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D24 拆分前行为基线：searchStock / searchFund / getStockDaily simple / searchAssetInfo 公开入口（task #111）。
 * 只断言字段集合/字段值/null 与缺失区别，不锁 JSON 键顺序；与 task #109 四个测试文件不交叉。
 */
class MarketDataToolsSearchStockFundDailyTest {

    private static final long MS_20240101 = 1704038400000L; // 2024-01-01T00:00:00+08:00
    private static final long MS_20240331 = 1711814400000L; // 2024-03-31T00:00:00+08:00
    private static final List<String> DAILY_HEADERS = List.of(
            "ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DomesticStockService stockService;
    private DomesticFundService fundService;
    private DomesticIndexService indexService;
    private DomesticListedAssetService listedAssetService;
    private DatasetWriter datasetWriter;
    private DatasetRegistry datasetRegistry;
    private MarketDataTools tools;

    @BeforeEach
    void setUp() {
        stockService = mock(DomesticStockService.class);
        fundService = mock(DomesticFundService.class);
        indexService = mock(DomesticIndexService.class);
        listedAssetService = mock(DomesticListedAssetService.class);
        datasetWriter = mock(DatasetWriter.class);
        datasetRegistry = mock(DatasetRegistry.class);
        AgentLlmLocalConfigLoader localConfigLoader = mock(AgentLlmLocalConfigLoader.class);
        when(localConfigLoader.current()).thenReturn(Optional.empty());
        when(datasetWriter.isEnabled()).thenReturn(false);
        when(datasetRegistry.isEnabled()).thenReturn(false);
        tools = configure(new MarketDataTools(datasetWriter, datasetRegistry, null, localConfigLoader,
                new AgentLlmProperties(), objectMapper));
        ReflectionTestUtils.setField(tools, "domesticStockService", stockService);
        ReflectionTestUtils.setField(tools, "domesticFundService", fundService);
        ReflectionTestUtils.setField(tools, "domesticIndexService", indexService);
        ReflectionTestUtils.setField(tools, "domesticListedAssetService", listedAssetService);
        ReflectionTestUtils.setField(tools, "swIndustryMemberDao", mock(SwIndustryMemberDao.class));
    }

    private Map<String, Object> parse(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
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
    private static Map<String, Object> detailsOf(Map<String, Object> error) {
        return (Map<String, Object>) error.get("details");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private void enableDataset() {
        when(datasetWriter.isEnabled()).thenReturn(true);
        when(datasetRegistry.isEnabled()).thenReturn(true);
    }

    // ---------- searchStock ----------






    // ---------- searchFund ----------





    // ---------- getStockDaily simple ----------










    // ---------- searchAssetInfo 公开入口 ----------







    private static DomesticStockDailyItem dailyItem(String tsCode, long tradeDate, double close) {
        return DomesticStockDailyItem.newBuilder()
                .setTsCode(tsCode).setTradeDate(tradeDate)
                .setOpen(10.0).setHigh(10.5).setLow(9.8).setClose(close)
                .setPreClose(10.0).setChange(0.2).setPctChg(2.0).setVol(1000.0).setAmount(10000.0)
                .build();
    }

    private static DomesticIndexWeightItem indexWeightItem(String indexCode, String conCode, String tradeDate, double weight) {
        return DomesticIndexWeightItem.newBuilder()
                .setIndexCode(indexCode).setConCode(conCode)
                .setTradeDate(DateConvertUtils.convertDateStrToLong(tradeDate, "yyyyMMdd"))
                .setWeight(weight).build();
    }
}
