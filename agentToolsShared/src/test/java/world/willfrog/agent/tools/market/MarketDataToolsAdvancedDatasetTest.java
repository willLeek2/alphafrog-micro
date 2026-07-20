package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.IndexWeightDao;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task #70 review fix: writeAdvancedDailyDataset must use stable group identity
 * and registerDataset with identical parameters to writer.
 *
 * <p>Tests the private {@code writeAdvancedDailyDataset} via reflection, asserting:
 * <ul>
 *   <li>Writer and registry receive the same non-"multiple" stable tsCode</li>
 *   <li>Same datasetId, rowCount, dates, headers passed to both</li>
 *   <li>Same canonicalQuery + same sorted stockCodes → same stable identity</li>
 *   <li>Different stockCodes → different stable identity (no collision)</li>
 * </ul>
 */
class MarketDataToolsAdvancedDatasetTest {

    @Test
    @SuppressWarnings("unchecked")
    void writeAdvancedDailyDataset_shouldUseStableIdentityAndRegisterWithSameParams() throws Exception {
        DatasetWriter writer = mock(DatasetWriter.class);
        DatasetRegistry registry = mock(DatasetRegistry.class);
        when(writer.isEnabled()).thenReturn(true);
        when(registry.isEnabled()).thenReturn(true);

        when(writer.writeDataset(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any()
        )).thenReturn("test-dataset-id-123");

        MarketDataTools tools = new MarketDataTools(
                writer, registry, mock(ManifestWriter.class),
                null, new AgentLlmProperties(), new ObjectMapper(),
                mock(IndexWeightDao.class), mock(SwIndustryMemberDao.class)
        );

        Map<String, Object> canonicalQuery = new LinkedHashMap<>();
        canonicalQuery.put("asset_type", "stock");
        canonicalQuery.put("name", "test-query");
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "index_component", "index_code", "000300.SH",
                        "start_date", "20240101", "end_date", "20241231", "min_weight", 0.01)
        );
        canonicalQuery.put("conditions", conditions);

        List<String> stockCodes = Arrays.asList("600519.SH", "000001.SZ", "000002.SZ");
        String startDate = "20240101";
        String endDate = "20240131";
        List<String> headers = Arrays.asList("ts_code", "trade_date", "open", "high", "low", "close",
                "pre_close", "change", "pct_chg", "vol", "amount");

        DomesticStockDailyItem item = DomesticStockDailyItem.newBuilder()
                .setTsCode("600519.SH")
                .setTradeDate(20240115L)
                .setOpen(100.0)
                .setHigh(101.0)
                .setLow(99.0)
                .setClose(100.5)
                .setPreClose(100.0)
                .setChange(0.5)
                .setPctChg(0.5)
                .setVol(10000.0)
                .setAmount(1000000.0)
                .build();
        List<DomesticStockDailyItem> items = List.of(item);

        Method method = MarketDataTools.class.getDeclaredMethod(
                "writeAdvancedDailyDataset",
                String.class, Map.class, List.class, String.class, String.class, List.class, List.class
        );
        method.setAccessible(true);
        String datasetId = (String) method.invoke(tools,
                "stock_daily_advanced", canonicalQuery, stockCodes, startDate, endDate, headers, items);

        assertEquals("test-dataset-id-123", datasetId);

        // Capture writer tsCode
        ArgumentCaptor<String> writerTsCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(writer).writeDataset(
                eq("stock_daily_advanced"),
                anyString(),
                writerTsCodeCaptor.capture(),
                eq(startDate),
                eq(endDate),
                eq(items),
                eq(headers),
                any()
        );
        String writerTsCode = writerTsCodeCaptor.getValue();

        // Capture registry tsCode
        ArgumentCaptor<String> registryTsCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(registry).registerDataset(
                eq("stock_daily_advanced"),
                registryTsCodeCaptor.capture(),
                eq(startDate),
                eq(endDate),
                eq(headers),
                eq("test-dataset-id-123"),
                eq(1)
        );
        String registryTsCode = registryTsCodeCaptor.getValue();

        // Writer and registry must receive the SAME stable identity
        assertEquals(writerTsCode, registryTsCode, "Writer and registry must receive identical stable identity");
        assertTrue(writerTsCode.startsWith("group-"), "tsCode must start with 'group-' prefix");
        assertTrue(!writerTsCode.equals("multiple"), "tsCode must not be literal 'multiple'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeAdvancedDailyDataset_sameQueryAndCodesDifferentOrder_shouldProduceSameIdentity() throws Exception {
        Map<String, Object> canonicalQuery = new LinkedHashMap<>();
        canonicalQuery.put("asset_type", "stock");
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "index_component", "index_code", "000300.SH")
        );
        canonicalQuery.put("conditions", conditions);

        List<String> stockCodesA = Arrays.asList("600519.SH", "000001.SZ");
        List<String> stockCodesB = Arrays.asList("000001.SZ", "600519.SH"); // different order
        String startDate = "20240101";
        String endDate = "20240131";
        List<String> headers = List.of("ts_code", "trade_date");
        List<DomesticStockDailyItem> items = List.of();

        String tsCodeA = invokeAndCaptureTsCode(canonicalQuery, stockCodesA, startDate, endDate, headers, items);
        String tsCodeB = invokeAndCaptureTsCode(canonicalQuery, stockCodesB, startDate, endDate, headers, items);

        // Same set, different order → same identity
        assertEquals(tsCodeA, tsCodeB, "Same stock codes in different order should produce same stable identity");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeAdvancedDailyDataset_differentCodes_shouldProduceDifferentIdentity() throws Exception {
        Map<String, Object> canonicalQuery = new LinkedHashMap<>();
        canonicalQuery.put("asset_type", "stock");
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "index_component", "index_code", "000300.SH")
        );
        canonicalQuery.put("conditions", conditions);

        List<String> stockCodesA = List.of("600519.SH");
        List<String> stockCodesB = List.of("000001.SZ");
        String startDate = "20240101";
        String endDate = "20240131";
        List<String> headers = List.of("ts_code", "trade_date");
        List<DomesticStockDailyItem> items = List.of();

        String tsCodeA = invokeAndCaptureTsCode(canonicalQuery, stockCodesA, startDate, endDate, headers, items);
        String tsCodeB = invokeAndCaptureTsCode(canonicalQuery, stockCodesB, startDate, endDate, headers, items);

        // Different stock codes → different identity
        assertNotEquals(tsCodeA, tsCodeB, "Different stock codes should produce different stable identity");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeAdvancedDailyDataset_whenWriterDisabled_shouldReturnEmptyAndNotRegister() throws Exception {
        DatasetWriter writer = mock(DatasetWriter.class);
        DatasetRegistry registry = mock(DatasetRegistry.class);
        when(writer.isEnabled()).thenReturn(false);

        MarketDataTools tools = new MarketDataTools(
                writer, registry, mock(ManifestWriter.class),
                null, new AgentLlmProperties(), new ObjectMapper(),
                mock(IndexWeightDao.class), mock(SwIndustryMemberDao.class)
        );

        Map<String, Object> canonicalQuery = Map.of("asset_type", "stock");
        List<String> stockCodes = List.of("600519.SH");
        List<String> headers = List.of("ts_code");
        List<DomesticStockDailyItem> items = List.of();

        Method method = MarketDataTools.class.getDeclaredMethod(
                "writeAdvancedDailyDataset",
                String.class, Map.class, List.class, String.class, String.class, List.class, List.class
        );
        method.setAccessible(true);
        String datasetId = (String) method.invoke(tools,
                "stock_daily_advanced", canonicalQuery, stockCodes, "20240101", "20240131", headers, items);

        assertEquals("", datasetId);
        verify(writer, never()).writeDataset(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any());
        verify(registry, never()).registerDataset(anyString(), anyString(), anyString(), anyString(), anyList(), anyString(), anyInt());
    }

    @SuppressWarnings("unchecked")
    private String invokeAndCaptureTsCode(Map<String, Object> canonicalQuery,
                                          List<String> stockCodes,
                                          String startDate,
                                          String endDate,
                                          List<String> headers,
                                          List<DomesticStockDailyItem> items) throws Exception {
        DatasetWriter writer = mock(DatasetWriter.class);
        DatasetRegistry registry = mock(DatasetRegistry.class);
        when(writer.isEnabled()).thenReturn(true);
        when(registry.isEnabled()).thenReturn(true);
        when(writer.writeDataset(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn("ds-test");

        MarketDataTools tools = new MarketDataTools(
                writer, registry, mock(ManifestWriter.class),
                null, new AgentLlmProperties(), new ObjectMapper(),
                mock(IndexWeightDao.class), mock(SwIndustryMemberDao.class)
        );

        Method method = MarketDataTools.class.getDeclaredMethod(
                "writeAdvancedDailyDataset",
                String.class, Map.class, List.class, String.class, String.class, List.class, List.class
        );
        method.setAccessible(true);
        method.invoke(tools, "stock_daily_advanced", canonicalQuery, stockCodes, startDate, endDate, headers, items);

        ArgumentCaptor<String> tsCodeCaptor = ArgumentCaptor.forClass(String.class);
        verify(writer).writeDataset(anyString(), anyString(), tsCodeCaptor.capture(), anyString(), anyString(), anyList(), anyList(), any());
        return tsCodeCaptor.getValue();
    }
}
