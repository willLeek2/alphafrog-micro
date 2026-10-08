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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.DatasetEntryMetadataReader;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.DatasetPersistedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

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

    @Test
    void createdAndReusedJsonExposeAccurateCompleteMetadata() throws Exception {
        RealWriter fixture = new RealWriter();
        Map<String, Object> query = Map.of("conditions", List.of(
                Map.of("type", "index_component", "index_code", "000300.SH")));
        List<Map<String, Object>> results = List.of(resultRow("000001.SZ", 1.0), Map.of(
                "ts_code", "600519.SH", "name", "贵州茅台", "index_code", "000300.SH"));
        // 读取方的真实统计必须来自完整结果数组，不能信任错误的行数标签。
        Map<String, Object> dataset = Map.of("schema_version", 1, "row_count", 99, "results", results);
        var created = fixture.writer.writeOrReuse("searchAssetInfo", "stock", query, dataset, 10);
        AgentContext.setRunId("test-run-002");
        var reused = fixture.writer.writeOrReuse("searchAssetInfo", "stock", query, dataset, 10);
        assertFalse(created.isReused());
        assertTrue(reused.isReused());
        assertEquals(created.getDatasetId(), reused.getDatasetId());
        for (String runId : List.of("test-run-001", "test-run-002")) {
            var entry = fixture.runRegistry.snapshot(runId).datasets().get(0);
            Path file = Path.of(entry.persistedPath());
            var metadata = new DatasetEntryMetadataReader().read(entry);
            assertEquals("complete", metadata.metadataStatus());
            assertEquals(2L, metadata.rowCount());
            assertEquals(Files.size(file), metadata.bytes());
            assertEquals(List.of("index_code", "match_conditions", "name", "ts_code"), metadata.columns());
            assertEquals(objectMapper.valueToTree(results), objectMapper.readTree(file.toFile()).path("results"));
            var document = objectMapper.readTree(file.resolveSibling("data.meta.json").toFile());
            assertEquals("json", document.path("format").asText());
            assertEquals("results", document.path("recordsPath").asText());
            assertEquals(1, document.path("schema_version").asInt());
            assertTrue(Files.isRegularFile(file.resolveSibling(created.getDatasetId() + ".meta.json")));
        }
    }

    @Test
    void emptyResultsRetainJsonSchemaButInvalidShapeCannotBecomeComplete() throws Exception {
        RealWriter fixture = new RealWriter();
        var empty = fixture.writer.writeOrReuse("searchAssetInfo", "stock", Map.of(),
                Map.of("schema_version", 1, "row_count", 0, "results", List.of()), 10);
        var emptyEntry = fixture.runRegistry.snapshot("test-run-001").datasets().stream()
                .filter(entry -> entry.originalId().equals(empty.getDatasetId())).findFirst().orElseThrow();
        var emptyMetadata = new DatasetEntryMetadataReader().read(emptyEntry);
        assertEquals("complete", emptyMetadata.metadataStatus());
        assertEquals(0L, emptyMetadata.rowCount());
        assertEquals(Files.size(Path.of(emptyEntry.persistedPath())), emptyMetadata.bytes());
        assertEquals(List.of("ts_code", "name", "asset_type", "match_conditions"), emptyMetadata.columns());

        var invalid = fixture.writer.writeOrReuse("searchAssetInfo", "stock", Map.of(),
                Map.of("row_count", 10, "results", "not-an-array"), 10);
        var invalidEntry = fixture.runRegistry.snapshot("test-run-001").datasets().stream()
                .filter(entry -> entry.originalId().equals(invalid.getDatasetId())).findFirst().orElseThrow();
        var invalidMetadata = new DatasetEntryMetadataReader().read(invalidEntry);
        assertEquals("partial", invalidMetadata.metadataStatus());
        assertNull(invalidMetadata.rowCount());
    }

    @Test
    void changedContentsDoNotReuseEmptyMembersOrWeights() throws Exception {
        RealWriter fixture = new RealWriter();
        Map<String, Object> query = Map.of("conditions", List.of(
                Map.of("type", "index_component", "index_code", "000300.SH")));
        List<Map<String, Object>> datasets = List.of(
                Map.of("row_count", 0, "results", List.of()),
                Map.of("row_count", 1, "results", List.of(resultRow("000001.SZ", 1.0))),
                Map.of("row_count", 1, "results", List.of(resultRow("600519.SH", 1.0))),
                Map.of("row_count", 1, "results", List.of(resultRow("600519.SH", 2.0))));
        List<String> identities = new ArrayList<>();
        for (Map<String, Object> dataset : datasets) {
            var written = fixture.writer.writeOrReuse("searchAssetInfo", "stock", query, dataset, 10);
            assertFalse(written.isReused());
            identities.add(written.getDatasetId());
            var entry = fixture.runRegistry.snapshot("test-run-001").datasets().stream()
                    .filter(item -> item.originalId().equals(written.getDatasetId())).findFirst().orElseThrow();
            assertEquals(objectMapper.valueToTree(dataset), objectMapper.readTree(Path.of(entry.persistedPath()).toFile()));
        }
        assertEquals(4, identities.stream().distinct().count(), "行数相同也必须区分成员与权重变化");
        assertEquals(4, fixture.runRegistry.snapshot("test-run-001").datasets().size());
        var emptyEntry = fixture.runRegistry.snapshot("test-run-001").datasets().stream()
                .filter(item -> item.originalId().equals(identities.get(0))).findFirst().orElseThrow();
        assertEquals(0, objectMapper.readTree(Path.of(emptyEntry.persistedPath()).toFile()).path("row_count").asInt());
    }

    @Test
    void identicalContentWithReorderedObjectKeysReusesFileButArrayOrderRemainsSignificant() throws Exception {
        RealWriter fixture = new RealWriter();
        Map<String, Object> query = Map.of("asset_type", "stock", "conditions", List.of(
                Map.of("type", "index_component", "index_code", "000300.SH")));
        List<Map<String, Object>> results = List.of(resultRow("000001.SZ", 1.0), resultRow("600519.SH", 2.0));
        Map<String, Object> dataset = Map.of("query", query, "row_count", 2, "results", results);
        var first = fixture.writer.writeOrReuse("searchAssetInfo", "stock", query, dataset, 10);
        AgentContext.setRunId("test-run-002");
        var second = fixture.writer.writeOrReuse("searchAssetInfo", "stock",
                reverseObjectKeys(query), reverseObjectKeys(dataset), 10);
        assertTrue(second.isReused());
        assertEquals(first.getDatasetId(), second.getDatasetId());
        var firstEntry = fixture.runRegistry.snapshot("test-run-001").datasets().get(0);
        var secondEntry = fixture.runRegistry.snapshot("test-run-002").datasets().get(0);
        assertEquals(firstEntry.persistedPath(), secondEntry.persistedPath());
        assertEquals(objectMapper.valueToTree(dataset), objectMapper.readTree(Path.of(secondEntry.persistedPath()).toFile()));

        Map<String, Object> reorderedResults = Map.of("query", query, "row_count", 2,
                "results", List.of(results.get(1), results.get(0)));
        var third = fixture.writer.writeOrReuse("searchAssetInfo", "stock", query, reorderedResults, 10);
        assertFalse(third.isReused());
        assertNotEquals(first.getDatasetId(), third.getDatasetId());
    }

    private Map<String, Object> resultRow(String code, double weight) {
        return Map.of("ts_code", code, "match_conditions", Map.of(
                "index_component-000300.SH", List.of(1704124800000L, weight)));
    }

    private Map<String, Object> reverseObjectKeys(Map<String, Object> map) {
        Map<String, Object> reversed = new LinkedHashMap<>();
        map.entrySet().stream().sorted(Map.Entry.<String, Object>comparingByKey().reversed())
                .forEach(entry -> reversed.put(entry.getKey(), reverseNestedObjectKeys(entry.getValue())));
        return reversed;
    }

    @SuppressWarnings("unchecked")
    private Object reverseNestedObjectKeys(Object value) {
        if (value instanceof Map<?, ?> map) return reverseObjectKeys((Map<String, Object>) map);
        if (value instanceof List<?> list) return list.stream().map(this::reverseNestedObjectKeys).toList();
        return value;
    }

    private class RealWriter {
        private final AgentRunDatasetRegistry runRegistry = new AgentRunDatasetRegistry();
        private final AdvancedSearchDatasetWriter writer;

        @SuppressWarnings("unchecked")
        private RealWriter() {
            Map<String, String> storedMetadata = new HashMap<>();
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(call -> storedMetadata.get(call.getArgument(0)));
            doAnswer(call -> {
                storedMetadata.put(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(values).set(anyString(), anyString());
            when(redis.opsForSet()).thenReturn(mock(SetOperations.class));
            DatasetRegistry registry = new DatasetRegistry(redis, event -> {
                if (event instanceof DatasetPersistedEvent persisted) runRegistry.onDatasetPersisted(persisted);
            });
            ReflectionTestUtils.setField(registry, "enabled", true);
            ReflectionTestUtils.setField(registry, "databaseFetchedPath", tempDir.toString());
            ReflectionTestUtils.setField(registry, "ttlSeconds", 3600L);
            DatasetWriter actualWriter = new DatasetWriter(new AgentStoragePaths(
                    tempDir.resolve("workspaces").toString(), tempDir.resolve("artifacts").toString(),
                    tempDir.resolve("datasets").toString(), tempDir.resolve("obs.log").toString()));
            ReflectionTestUtils.setField(actualWriter, "enabled", true);
            ReflectionTestUtils.setField(actualWriter, "databaseFetchedPath", tempDir.toString());
            writer = new AdvancedSearchDatasetWriter(actualWriter, registry, objectMapper);
        }
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
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

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
            assertTrue(result.getDatasetId().startsWith("adv-"), "datasetId should use query-based prefix, not runId");
            assertEquals(16, result.getDatasetId().length(), "adv- prefix (4) + 12 hex chars");
            assertEquals(1, result.getPreviewRows().size());
            assertEquals("000300.SH", result.getPreviewRows().get(0).get("ts_code"));

            verify(datasetWriter).getDatabaseFetchedPath();
            verify(datasetRegistry).registerDataset(
                    eq(AdvancedSearchDatasetWriter.DATASET_TYPE),
                    anyString(),
                    eq("NONE"),
                    eq("NONE"),
                    eq(AdvancedSearchDatasetWriter.COLUMNS),
                    eq(result.getDatasetId()),
                    eq(1),
                    eq("json"),
                    eq("data.json"));

            // Verify 4-layer path: database_fetched/<topic>/<tsCode>/<encodedString>/data.json
            Path advancedSearchDir = tempDir.resolve("advanced_search");
            assertTrue(Files.exists(advancedSearchDir), "advanced_search topic dir should exist");
            // Walk to find the data.json
            List<Path> jsonFiles = Files.walk(tempDir)
                    .filter(p -> p.getFileName().toString().equals("data.json"))
                    .toList();
            assertEquals(1, jsonFiles.size(), "one data.json should be written under 4-layer path");
            String written = Files.readString(jsonFiles.get(0));
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

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "000001.SZ")));
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
            verify(datasetWriter, never()).getDatabaseFetchedPath();
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
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

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
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

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
    @DisplayName("datasetId 基于 querySignature，不含 runId")
    class DatasetIdComposition {

        @Test
        @DisplayName("相同 query 产生相同 datasetId，不含 runId")
        void shouldBeDeterministicAndRunIdFree() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "510300.SH")));
            Map<String, Object> query = Map.of("name", "沪深300");

            AgentContext.setRunId("run-A");
            AdvancedSearchDatasetWriter.WriteResult r1 = writer.writeOrReuse(
                    "searchIndex", "stock", query, dataset, 10);

            AgentContext.setRunId("run-B");
            AdvancedSearchDatasetWriter.WriteResult r2 = writer.writeOrReuse(
                    "searchIndex", "stock", query, dataset, 10);

            assertEquals(r1.getDatasetId(), r2.getDatasetId(),
                    "same query must yield same datasetId regardless of runId");
            assertTrue(r1.getDatasetId().startsWith("adv-"), "datasetId should start with adv-");
            assertFalse(r1.getDatasetId().contains("run-A"));
            assertFalse(r2.getDatasetId().contains("run-B"));
        }

        @Test
        @DisplayName("不同 toolName/assetType/query 产生不同 datasetId")
        void shouldDifferByQueryInputs() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of("results", List.of(Map.of("ts_code", "000001.SZ")));

            AdvancedSearchDatasetWriter.WriteResult r1 = writer.writeOrReuse(
                    "searchIndex", "stock", Map.of("name", "X"), dataset, 10);
            AdvancedSearchDatasetWriter.WriteResult r2 = writer.writeOrReuse(
                    "searchAssetInfo", "stock", Map.of("name", "X"), dataset, 10);

            assertFalse(r1.getDatasetId().equals(r2.getDatasetId()),
                    "different toolName must produce different datasetId");
        }

        @Test
        @DisplayName("assetType 为 null 时仍可生成稳定 datasetId")
        void shouldHandleNullAssetType() {
            when(datasetWriter.isEnabled()).thenReturn(true);
            when(datasetRegistry.isEnabled()).thenReturn(true);
            when(datasetRegistry.findReusable(anyString(), anyString(), anyString(),
                    anyString(), anyList())).thenReturn(Optional.empty());
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", null, Map.of("name", "X"),
                    Map.of("results", List.of(Map.of("ts_code", "000001.SZ"))), 10);

            assertTrue(result.getDatasetId().startsWith("adv-"));
            assertEquals(16, result.getDatasetId().length());
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
            when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

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
            lenient().when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

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
            lenient().when(datasetWriter.getDatabaseFetchedPath()).thenReturn(tempDir.toString());

            Map<String, Object> dataset = Map.of();

            AdvancedSearchDatasetWriter.WriteResult result = writer.writeOrReuse(
                    "searchIndex", "index", Map.of("name", "X"), dataset, 10);

            assertTrue(result.getPreviewRows().isEmpty());
        }
    }
}
