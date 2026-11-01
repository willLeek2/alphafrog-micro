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
