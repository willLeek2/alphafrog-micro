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

    }

    @Nested
    @DisplayName("缓存命中：复用已有 DatasetMeta")
    class CacheHitReused {

    }

    @Nested
    @DisplayName("datasetWriter 关闭：内联返回，不写盘不查 registry")
    class DatasetWriterDisabledInline {

    }

    @Nested
    @DisplayName("previewRows 按 limit 截取")
    class PreviewRowLimit {


    }

    @Nested
    @DisplayName("缓存命中但预览文件读取失败")
    class CacheHitReadFailure {

    }

    @Nested
    @DisplayName("datasetId 基于 querySignature，不含 runId")
    class DatasetIdComposition {



    }

    @Nested
    @DisplayName("querySignature 稳定性")
    class QuerySignatureStability {

    }

    @Nested
    @DisplayName("previewRows 数据形态边界")
    class PreviewRowsShapeBoundary {


    }
}
