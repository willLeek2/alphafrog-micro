package world.willfrog.agent.tools.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.agent.tools.dataset.DatasetEntryMetadataReader;
import world.willfrog.agent.tools.dataset.DatasetManifest;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.DatasetPersistedEvent;
import world.willfrog.alphafrogmicro.common.dao.domestic.index.SwIndustryMemberDao;
import world.willfrog.alphafrogmicro.domestic.idl.DomesticStockDailyItem;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 高级日线的稳定标识必须让写盘与登记指向同一文件，并让读取方获得真实统计。
 */
class MarketDataToolsAdvancedDatasetTest {

    @TempDir
    Path tempDir;

    @Test
    void advancedDatasetRegistersActualFileAndCompleteStatistics() throws Exception {
        assertAdvancedDatasetStatistics(false);
    }

    @Test
    void fallbackIdentityRegistersActualFileWithoutNegativeHashSeparator() throws Exception {
        assertAdvancedDatasetStatistics(true);
    }

    @SuppressWarnings("unchecked")
    private void assertAdvancedDatasetStatistics(boolean failIdentitySerialization) throws Exception {
        Path databaseRoot = tempDir.resolve("database_fetched");
        DatasetWriter writer = new DatasetWriter(new AgentStoragePaths(
                tempDir.resolve("workspaces").toString(), tempDir.resolve("artifacts").toString(),
                tempDir.resolve("agent_datasets").toString(), tempDir.resolve("obs.log").toString()));
        ReflectionTestUtils.setField(writer, "enabled", true);
        ReflectionTestUtils.setField(writer, "databaseFetchedPath", databaseRoot.toString());

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
        AgentRunDatasetRegistry runRegistry = new AgentRunDatasetRegistry();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof DatasetPersistedEvent persisted) {
                runRegistry.onDatasetPersisted(persisted);
            }
        };
        DatasetRegistry registry = new DatasetRegistry(redis, publisher);
        ReflectionTestUtils.setField(registry, "enabled", true);
        ReflectionTestUtils.setField(registry, "databaseFetchedPath", databaseRoot.toString());

        ObjectMapper identityMapper = new ObjectMapper();
        if (failIdentitySerialization) {
            identityMapper = mock(ObjectMapper.class);
            when(identityMapper.writeValueAsBytes(any())).thenThrow(
                    new JsonProcessingException("条件摘要序列化不可用") {});
        }
        MarketDataTools tools = new MarketDataTools(writer, registry, mock(ManifestWriter.class),
                null, new AgentLlmProperties(), identityMapper, mock(SwIndustryMemberDao.class));
        // 该条件摘要的哈希为负数，验证降级标识也不会再产生连字符。
        Map<String, Object> query = Map.of("asset_type", "stock", "conditions", List.of(
                Map.of("type", "index_component", "index_code", "000905.SH")));
        List<String> stockCodes = List.of("600519.SH", "000001.SZ");
        List<String> headers = List.of("ts_code", "trade_date", "open", "high", "low", "close",
                "pre_close", "change", "pct_chg", "vol", "amount");
        List<DomesticStockDailyItem> rows = stockCodes.stream().map(code ->
                DomesticStockDailyItem.newBuilder().setTsCode(code).setTradeDate(1704124800000L)
                        .setOpen(10).setHigh(12).setLow(9).setClose(11).setPreClose(10)
                        .setChange(1).setPctChg(10).setVol(100).setAmount(1100).build()).toList();
        String runId = "run-advanced-statistics";
        AgentContext.setRunId(runId);
        String datasetId;
        try {
            datasetId = ReflectionTestUtils.invokeMethod(tools, "writeAdvancedDailyDataset",
                    "stock_daily_advanced", query, stockCodes, "20240102", "20240105", headers, rows);
        } finally {
            AgentContext.clear();
        }

        var snapshot = runRegistry.snapshot(runId);
        assertEquals(1, snapshot.datasets().size());
        var entry = snapshot.datasets().get(0);
        assertEquals(datasetId, entry.originalId());
        assertTrue(entry.fromTsCode().matches("group_[0-9a-f]+"));
        if (failIdentitySerialization) {
            int fallbackHash = "index_component-000905.SH-2".hashCode();
            assertTrue(fallbackHash < 0);
            assertEquals("group_" + Integer.toUnsignedString(fallbackHash, 16), entry.fromTsCode());
        }
        Path actualCsv;
        try (var files = Files.walk(databaseRoot)) {
            actualCsv = files.filter(path -> path.toString().endsWith(".csv")).findFirst().orElseThrow();
        }
        assertEquals(actualCsv.toAbsolutePath().toString(), entry.persistedPath());
        assertEquals(rows.size(), Files.readAllLines(actualCsv).size() - 1);
        var metadata = new DatasetEntryMetadataReader().read(entry);
        assertEquals("complete", metadata.metadataStatus());
        assertEquals((long) rows.size(), metadata.rowCount());
        assertEquals(Files.size(actualCsv), metadata.bytes());
        assertEquals(headers, metadata.columns());
        assertEquals(headers, metadata.recommendedUsecols());
    }

    @Test
    void directReadyCountMismatchMissRewritesManifestFromCurrentBatch() {
        DatasetWriter datasetWriter = mock(DatasetWriter.class);
        DatasetRegistry registry = mock(DatasetRegistry.class);
        ManifestWriter manifestWriter = mock(ManifestWriter.class);
        when(registry.isEnabled()).thenReturn(true);
        when(manifestWriter.isEnabled()).thenReturn(true);
        when(registry.findReusableManifest(
                eq("stock_daily"), eq("20240101"), eq("20240131"),
                eq(List.of("000001.SZ", "600519.SH")), eq(List.of("trade_date", "close")),
                eq(List.of("ds-current-1", "ds-current-2"))))
                .thenReturn(Optional.empty());
        when(manifestWriter.writeManifest(
                eq("stock_daily"), eq("20240101"), eq("20240131"),
                anyList(), eq(3), eq(List.of("trade_date", "close"))))
                .thenReturn("manifest-rewritten");
        MarketDataTools tools = new MarketDataTools(
                datasetWriter, registry, manifestWriter,
                null, new AgentLlmProperties(), new ObjectMapper(),
                mock(SwIndustryMemberDao.class));
        ReflectionTestUtils.setField(tools, "emitManifest", true);
        List<Map<String, Object>> results = List.of(
                Map.of("ts_code", "000001.SZ", "ok", true,
                        "data", Map.of("dataset_id", "ds-current-1", "rows", 1),
                        "error", Map.of()),
                Map.of("ts_code", "600519.SH", "ok", true,
                        "data", Map.of("dataset_id", "ds-current-2", "rows", 2),
                        "error", Map.of()));
        Map<String, Object> data = new LinkedHashMap<>();

        ReflectionTestUtils.invokeMethod(tools, "attachManifestIfEnabled",
                "stock_daily", "20240101", "20240131",
                List.of("000001.SZ", "600519.SH"), List.of("trade_date", "close"),
                results, data);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DatasetManifest.ManifestMember>> membersCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(manifestWriter).writeManifest(
                eq("stock_daily"), eq("20240101"), eq("20240131"),
                membersCaptor.capture(), eq(3), eq(List.of("trade_date", "close")));
        assertEquals(List.of("ds-current-1", "ds-current-2"),
                membersCaptor.getValue().stream()
                        .map(DatasetManifest.ManifestMember::getDatasetId)
                        .toList());
        verify(registry).registerManifest(
                eq("stock_daily"), eq("20240101"), eq("20240131"),
                eq(List.of("000001.SZ", "600519.SH")), eq(List.of("trade_date", "close")),
                eq("manifest-rewritten"), eq(2), eq(2), eq(0), eq(3));
        assertEquals(List.of("ds-current-1", "ds-current-2"), data.get("dataset_ids"));
        assertEquals("manifest-rewritten", data.get("manifest_id"));
    }

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
                mock(SwIndustryMemberDao.class)
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
        assertTrue(writerTsCode.startsWith("group_"), "tsCode must start with 'group_' prefix");
        assertTrue(!writerTsCode.equals("multiple"), "tsCode must not be literal 'multiple'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeAdvancedDailyDataset_differentConditions_shouldProduceDifferentIdentity() throws Exception {
        List<String> stockCodes = List.of("600519.SH");
        String startDate = "20240101";
        String endDate = "20240131";
        List<String> headers = List.of("ts_code", "trade_date");
        List<DomesticStockDailyItem> items = List.of();

        // Condition A: min_weight = 0.01
        Map<String, Object> queryA = new LinkedHashMap<>();
        queryA.put("asset_type", "stock");
        queryA.put("conditions", List.of(
                Map.of("type", "index_component", "index_code", "000300.SH",
                        "start_date", "20240101", "end_date", "20241231", "min_weight", 0.01)
        ));

        // Condition B: same index but different min_weight = 0.05
        Map<String, Object> queryB = new LinkedHashMap<>();
        queryB.put("asset_type", "stock");
        queryB.put("conditions", List.of(
                Map.of("type", "index_component", "index_code", "000300.SH",
                        "start_date", "20240101", "end_date", "20241231", "min_weight", 0.05)
        ));

        String tsCodeA = invokeAndCaptureTsCode(queryA, stockCodes, startDate, endDate, headers, items);
        String tsCodeB = invokeAndCaptureTsCode(queryB, stockCodes, startDate, endDate, headers, items);

        // Different canonical conditions → different identity
        assertNotEquals(tsCodeA, tsCodeB, "Different conditions should produce different stable identity");
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
                mock(SwIndustryMemberDao.class)
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
                mock(SwIndustryMemberDao.class)
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
