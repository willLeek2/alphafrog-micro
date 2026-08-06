package world.willfrog.agent.tools.market.advanced;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.tools.dataset.DatabaseFetchedPathStrategy;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class AdvancedSearchDatasetWriter {

    public static final String DATASET_TYPE = "market_data_advanced_search";
    public static final List<String> COLUMNS = List.of("advanced_search_json_v1");

    private final DatasetWriter datasetWriter;
    private final DatasetRegistry datasetRegistry;
    private final ObjectMapper objectMapper;

    public AdvancedSearchDatasetWriter(DatasetWriter datasetWriter,
                                       DatasetRegistry datasetRegistry,
                                       ObjectMapper objectMapper) {
        this.datasetWriter = datasetWriter;
        this.datasetRegistry = datasetRegistry;
        this.objectMapper = objectMapper;
    }

    public WriteResult writeOrReuse(String toolName,
                                    String assetType,
                                    Map<String, Object> canonicalQuery,
                                    Map<String, Object> dataset,
                                    int previewLimit) {
        String querySignature = querySignature(toolName, assetType, canonicalQuery);
        if (datasetWriter.isEnabled() && datasetRegistry.isEnabled()) {
            Optional<DatasetRegistry.DatasetMeta> existing = datasetRegistry.findReusable(
                    DATASET_TYPE, querySignature, "NONE", "NONE", COLUMNS);
            if (existing.isPresent()) {
                List<Map<String, Object>> previewRows = readPreviewRows(existing.get(), previewLimit);
                return WriteResult.builder()
                        .datasetId(existing.get().getDatasetId())
                        .datasetStatus("reused")
                        .reused(true)
                        .previewRows(previewRows)
                        .build();
            }
        }
        if (!datasetWriter.isEnabled()) {
            return WriteResult.builder()
                    .datasetId("")
                    .datasetStatus("inline")
                    .reused(false)
                    .previewRows(previewRows(dataset, previewLimit))
                    .build();
        }

        String datasetId = "adv-" + querySignature.substring(0, 12);
        String dataFileName = "data.json";
        String topic = DatabaseFetchedPathStrategy.resolveTopic(DATASET_TYPE);
        String encodedStr = DatabaseFetchedPathStrategy.encodedString(DATASET_TYPE, querySignature,
                "NONE", "NONE", COLUMNS);
        Path dir = DatabaseFetchedPathStrategy.resolveDataPath(
                Paths.get(datasetWriter.getDatabaseFetchedPath()), topic, querySignature, encodedStr);
        try {
            Files.createDirectories(dir);
            Path jsonFile = dir.resolve(dataFileName);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), dataset);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("dataset_id", datasetId);
            meta.put("kind", DATASET_TYPE);
            meta.put("format", "json");
            meta.put("schema_version", dataset.get("schema_version"));
            meta.put("tool", toolName);
            meta.put("asset_type", assetType);
            meta.put("row_count", dataset.get("row_count"));
            meta.put("data_file", dataFileName);
            meta.put("created_at", Instant.now().toEpochMilli());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(datasetId + ".meta.json").toFile(), meta);

            if (datasetRegistry.isEnabled()) {
                int rowCount = ((Number) dataset.getOrDefault("row_count", 0)).intValue();
                datasetRegistry.registerDataset(DATASET_TYPE, querySignature, "NONE", "NONE",
                        COLUMNS, datasetId, rowCount, "json", dataFileName);
            }
            return WriteResult.builder()
                    .datasetId(datasetId)
                    .datasetStatus("created")
                    .reused(false)
                    .previewRows(previewRows(dataset, previewLimit))
                    .build();
        } catch (IOException e) {
            throw new AdvancedSearchException("TOOL_ERROR", "Failed to write advanced search dataset: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> readPreviewRows(DatasetRegistry.DatasetMeta meta, int previewLimit) {
        try {
            String fileName = meta.getDataFileName();
            if (fileName == null || fileName.isBlank()) {
                fileName = meta.getDatasetId() + ".json";
            }
            File jsonFile = Paths.get(meta.getPath(), fileName).toFile();
            Map<String, Object> dataset = objectMapper.readValue(jsonFile, new TypeReference<>() {});
            return previewRows(dataset, previewLimit);
        } catch (Exception e) {
            log.warn("Failed to read advanced search preview rows for dataset={}", meta.getDatasetId(), e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> previewRows(Map<String, Object> dataset, int previewLimit) {
        Object raw = dataset.get("results");
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        int limit = Math.max(0, previewLimit);
        List<Map<String, Object>> preview = new ArrayList<>();
        for (Object row : rows) {
            if (preview.size() >= limit) {
                break;
            }
            if (row instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                preview.add(copy);
            }
        }
        return preview;
    }

    private String querySignature(String toolName, String assetType, Map<String, Object> canonicalQuery) {
        try {
            String raw = objectMapper.writeValueAsString(Map.of(
                    "schema_version", 1,
                    "tool", toolName == null ? "" : toolName,
                    "asset_type", assetType == null ? "" : assetType,
                    "query", canonicalQuery == null ? Map.of() : canonicalQuery
            ));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AdvancedSearchException("TOOL_ERROR", "Failed to build advanced search query signature.");
        }
    }

    @Data
    @Builder
    public static class WriteResult {
        private String datasetId;
        private String datasetStatus;
        private boolean reused;
        private List<Map<String, Object>> previewRows;
    }
}
