package world.willfrog.agent.tools.market.advanced;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvancedSearchDatasetWriterTest {

    @Mock
    private DatasetWriter datasetWriter;
    @Mock
    private DatasetRegistry datasetRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AdvancedSearchDatasetWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        writer = new AdvancedSearchDatasetWriter(datasetWriter, datasetRegistry, objectMapper);
        AgentContext.setRunId("test-run-001");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Nested
    @DisplayName("缓存未命中：写入新文件并注册到 registry")
    class CacheMissCreated {

        @Test
        @DisplayName("datasetWriter 与 registry 均开启时，cache miss 返回 created 并落盘")
        void shouldCreateDatasetWhenCacheMisses() throws Exception {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(eq(AdvancedSearchDatasetWriter.DATASET_TYPE), anyString(),
                    eq("NONE"), eq("NONE"), eq(AdvancedSearchDatasetWriter.COLUMNS)))
                    .thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of(
                    "results", List.of(Map.of("ts_code", "000300.SH")),
                    "row_count", 1
            );
            Map<String, Object> canonicalQuery = Map.of("name", "沪深300");

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", canonicalQuery, dataset, 10);

            assertEquals("created", result.getDatasetStatus());
            assertFalse(result.isReused());
            assertNotNull(result.getDatasetId());
            assertTrue(result.getDatasetId().contains("test-run-001"));
            assertTrue(result.getDatasetId().contains("searchIndex"));
            assertTrue(result.getDatasetId().contains("index"));
            assertEquals(1, result.getPreviewRows().size());
            assertEquals("000300.SH", result.getPreviewRows().get(0).get("ts_code"));

            verify(datasetWriter).getDatasetPath();
            verify(datasetRegistry).registerDataset(
                    eq(AdvancedSearchDatasetWriter.DATASET_TYPE),
                    anyString(),
                    eq("NONE"),
                    eq("NONE"),
                    eq(AdvancedSearchDatasetWriter.COLUMNS),
                    eq(result.getDatasetId()),
                    eq(1),
                    eq("json"),
                    eq(result.getDatasetId() + ".json"));

            Path jsonFile = tempDir.resolve(result.getDatasetId()).resolve(result.getDatasetId() + ".json");
            assertTrue(Files.exists(jsonFile), "json file should be written under tempDir");
            String written = Files.readString(jsonFile);
            assertTrue(written.contains("000300.SH"), "written json should contain dataset content");
        }
    }

    @Nested
    @DisplayName("缓存命中：复用已有 DatasetMeta")
    class CacheHitReused {

        @Test
        @DisplayName("findReusable 命中时返回 reused，不写入新文件，不注册")
        void shouldReuseWhenCacheHits() throws Exception {
            String existingId = "existing-ds-001";
            Path existingDir = tempDir.resolve(existingId);
            Files.createDirectories(existingDir);
            Files.writeString(existingDir.resolve(existingId + ".json"),
                    "{\"results\":[{\"ts_code\":\"000001.SZ\"}]}");

            DatasetRegistry.DatasetMeta meta = DatasetRegistry.DatasetMeta.builder()
                    .datasetId(existingId)
                    .dataFileName(existingId + ".json")
                    .path(existingDir.toAbsolutePath().toString())
                    .build();

            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(eq(AdvancedSearchDatasetWriter.DATASET_TYPE), anyString(),
                    eq("NONE"), eq("NONE"), eq(AdvancedSearchDatasetWriter.COLUMNS)))
                    .thenReturn(Optional.of(meta));

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "999999.SZ")));
            Map<String, Object> canonicalQuery = Map.of("name", "any");

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", canonicalQuery, dataset, 5);

            assertEquals("reused", result.getDatasetStatus());
            assertTrue(result.isReused());
            assertEquals(existingId, result.getDatasetId());
            assertEquals(1, result.getPreviewRows().size());
            assertEquals("000001.SZ", result.getPreviewRows().get(0).get("ts_code"));

            try (var stream = Files.list(tempDir)) {
                List<Path> children = stream.toList();
                assertEquals(1, children.size(), "no new directory should be created");
                assertEquals(existingId, children.get(0).getFileName().toString());
            }

            verify(datasetRegistry, never()).registerDataset(anyString(), anyString(), anyString(),
                    anyString(), anyList(), anyString(), anyInt(), anyString(), anyString());
            verify(datasetWriter, never()).getDatasetPath();
        }
    }

    @Nested
    @DisplayName("datasetWriter 关闭：内联返回，不写盘不查 registry")
    class DatasetWriterDisabledInline {

        @Test
        @DisplayName("datasetWriter.isEnabled()=false 时返回 inline，且不调用 findReusable")
        void shouldReturnInlineWhenDatasetWriterDisabled() {
            when(datasetWriter.isEnabled()).thenReturn(false);
            lenient().when(datasetRegistry.isEnabled()).thenReturn(true);

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "000001.SZ")));

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 10);

            assertEquals("inline", result.getDatasetStatus());
            assertEquals("", result.getDatasetId());
            assertFalse(result.isReused());
            assertNotNull(result.getPreviewRows());
            assertEquals(1, result.getPreviewRows().size());

            verify(datasetRegistry, never()).findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList());
            verify(datasetRegistry, never()).registerDataset(anyString(), anyString(), anyString(),
                    anyString(), anyList(), anyString(), anyInt(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("previewRows 按 limit 截取")
    class PreviewRowLimit {

        @Test
        @DisplayName("results 列表 30 行，limit=5 时仅返回前 5 行")
        void shouldCapPreviewToLimit() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                rows.add(Map.of("ts_code", String.format("%06d.SZ", i)));
            }
            Map<String, Object> dataset = Map.of("results", rows);

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 5);

            assertEquals(5, result.getPreviewRows().size());
            assertEquals("000000.SZ", result.getPreviewRows().get(0).get("ts_code"));
            assertEquals("000004.SZ", result.getPreviewRows().get(4).get("ts_code"));
        }

        @Test
        @DisplayName("limit=0 时返回空列表")
        void shouldReturnEmptyWhenLimitIsZero() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            List<Map<String, Object>> rows = List.of(Map.of("ts_code", "000001.SZ"));
            Map<String, Object> dataset = Map.of("results", rows);

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 0);

            assertTrue(result.getPreviewRows().isEmpty());
        }
    }

    @Nested
    @DisplayName("缓存命中但预览文件读取失败")
    class CacheHitReadFailure {

        @Test
        @DisplayName("dataFileName 指向不存在的文件时，返回 reused 但 previewRows 为空")
        void shouldReturnEmptyPreviewWhenReadFails() {
            DatasetRegistry.DatasetMeta meta = DatasetRegistry.DatasetMeta.builder()
                    .datasetId("ghost-ds")
                    .dataFileName("ghost.json")
                    .path(tempDir.toAbsolutePath().toString())
                    .build();

            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(eq(AdvancedSearchDatasetWriter.DATASET_TYPE), anyString(),
                    eq("NONE"), eq("NONE"), eq(AdvancedSearchDatasetWriter.COLUMNS)))
                    .thenReturn(Optional.of(meta));

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "000001.SZ")));

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 5);

            assertEquals("reused", result.getDatasetStatus());
            assertEquals("ghost-ds", result.getDatasetId());
            assertTrue(result.getPreviewRows().isEmpty());
        }
    }

    @Nested
    @DisplayName("buildDatasetId 命名规则")
    class DatasetIdComposition {

        @Test
        @DisplayName("runId + toolName + assetType 三段都会出现在 datasetId 中")
        void shouldEmbedRunIdToolNameAssetType() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            AgentContext.setRunId("my-run");

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchAssetInfo", "etf", Map.of("name", "X"),
                    Map.of("results", List.of(Map.of("ts_code", "510300.SH"))), 10);

            assertEquals("created", result.getDatasetStatus());
            assertTrue(result.getDatasetId().contains("my-run"));
            assertTrue(result.getDatasetId().contains("searchAssetInfo"));
            assertTrue(result.getDatasetId().contains("etf"));
            assertTrue(result.getDatasetId().startsWith("my-run-advanced-searchAssetInfo-etf-"));
        }

        @Test
        @DisplayName("runId 为 null/blank 时使用 unknown 前缀")
        void shouldUseUnknownPrefixWhenRunIdBlank() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            AgentContext.setRunId("");

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "stock", Map.of("name", "X"),
                    Map.of("results", List.of(Map.of("ts_code", "000001.SZ"))), 10);

            assertTrue(result.getDatasetId().startsWith("unknown-advanced-searchIndex-stock-"));
        }

        @Test
        @DisplayName("assetType 为 null 时默认为 index")
        void shouldDefaultAssetTypeToIndex() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            AgentContext.setRunId("r1");

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", null, Map.of("name", "X"),
                    Map.of("results", List.of(Map.of("ts_code", "000001.SZ"))), 10);

            assertTrue(result.getDatasetId().contains("-index-"));
            assertFalse(result.getDatasetId().contains("-null-"));
            assertTrue(result.getDatasetId().startsWith("r1-advanced-searchIndex-index-"));
        }
    }

    @Nested
    @DisplayName("querySignature 稳定性")
    class QuerySignatureStability {

        @Test
        @DisplayName("相同 canonicalQuery 产生相同签名，不同 query 签名不同")
        void shouldBeDeterministicAndDistinct() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "000001.SZ")));
            Map<String, Object> queryX = Map.of("name", "X");
            Map<String, Object> queryY = Map.of("name", "Y");

            writer.writeOrReuse("searchIndex", "index", queryX, dataset, 10);
            writer.writeOrReuse("searchIndex", "index", queryX, dataset, 10);
            writer.writeOrReuse("searchIndex", "index", queryY, dataset, 10);

            ArgumentCaptor<String> signatureCaptor = ArgumentCaptor.forClass(String.class);
            verify(datasetRegistry, times(3)).registerDataset(
                    eq(AdvancedSearchDatasetWriter.DATASET_TYPE),
                    signatureCaptor.capture(),
                    eq("NONE"),
                    eq("NONE"),
                    eq(AdvancedSearchDatasetWriter.COLUMNS),
                    anyString(),
                    anyInt(),
                    eq("json"),
                    anyString());

            List<String> signatures = signatureCaptor.getAllValues();
            assertEquals(3, signatures.size());
            assertEquals(signatures.get(0), signatures.get(1),
                    "same canonical query must yield same signature");
            assertFalse(signatures.get(0).equals(signatures.get(2)),
                    "different canonical query must yield different signature");
            assertEquals(64, signatures.get(0).length(), "signature should be sha-256 hex (64 chars)");
        }
    }

    @Nested
    @DisplayName("previewRows 数据形态边界")
    class PreviewRowsShapeBoundary {

        @Test
        @DisplayName("dataset[\"results\"] 不是 List 时 previewRows 为空")
        void shouldReturnEmptyWhenResultsNotList() {
            lenient().when(datasetWriter.isEnabled()).thenReturn(true);
            lenient().when(datasetRegistry.isEnabled()).thenReturn(true);
            lenient().when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            lenient().when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of("results", "not-a-list");

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 10);

            assertTrue(result.getPreviewRows().isEmpty());
        }

        @Test
        @DisplayName("dataset 缺少 results 字段时 previewRows 为空")
        void shouldReturnEmptyWhenResultsMissing() {
            lenient().when(datasetWriter.isEnabled()).thenReturn(true);
            lenient().when(datasetRegistry.isEnabled()).thenReturn(true);
            lenient().when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            lenient().when(datasetWriter.getDatasetPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of();

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 10);

            assertTrue(result.getPreviewRows().isEmpty());
        }
    }
}