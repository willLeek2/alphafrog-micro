package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads public, path-free metadata for a persisted dataset or manifest. */
@Slf4j
public class DatasetEntryMetadataReader {

    private final ObjectMapper objectMapper;

    public DatasetEntryMetadataReader() {
        this(new ObjectMapper());
    }

    public DatasetEntryMetadataReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EntryMetadata read(AgentRunDatasetEntry entry) {
        Path persisted = safePath(entry.persistedPath());
        Long bytes = regularFileSize(persisted);
        JsonNode document = readMetadataDocument(entry, persisted);
        Long rowCount = firstLong(document, "rowCount", "totalRowCount");
        if (bytes == null) {
            bytes = firstLong(document, "bytes", "totalBytes");
        }
        List<String> columns = stringList(document, "columns");
        if (columns.isEmpty() && entry.isDataset()) {
            columns = readCsvHeader(persisted);
        }
        DatasetSchemaHintResolver.SchemaHints fallback = DatasetSchemaHintResolver.resolve(columns);
        List<String> usecols = stringList(document, "recommendedUsecols");
        if (usecols.isEmpty()) {
            usecols = fallback.recommendedUsecols();
        }
        Map<String, String> dtypes = stringMap(document, "recommendedDtype");
        if (dtypes.isEmpty()) {
            dtypes = fallback.recommendedDtype();
        }
        Map<String, List<String>> profiles = listMap(document, "readProfiles");
        if (profiles.isEmpty()) {
            profiles = fallback.readProfiles();
        }
        String status = rowCount != null && bytes != null && !columns.isEmpty() ? "complete" : "partial";
        return new EntryMetadata(rowCount, bytes, columns, usecols, dtypes, profiles, status);
    }

    private JsonNode readMetadataDocument(AgentRunDatasetEntry entry, Path persisted) {
        if (persisted == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        Path parent = persisted.getParent();
        if (parent != null) {
            if (entry.isManifest()) {
                candidates.add(parent.resolve("meta.json"));
            } else {
                String fileName = persisted.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
                candidates.add(parent.resolve(stem + ".meta.json"));
                candidates.add(parent.resolve("meta.json"));
            }
        }
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                return objectMapper.readTree(candidate.toFile());
            } catch (IOException error) {
                log.warn("Failed to read dataset metadata: {}", candidate, error);
            }
        }
        if (entry.isManifest() && Files.isRegularFile(persisted)) {
            try {
                return objectMapper.readTree(persisted.toFile());
            } catch (IOException error) {
                log.warn("Failed to read manifest metadata: {}", persisted, error);
            }
        }
        return null;
    }

    private Path safePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Long regularFileSize(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.size(path);
        } catch (IOException error) {
            return null;
        }
    }

    private List<String> readCsvHeader(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return List.of();
        }
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || header.isBlank()) {
                return List.of();
            }
            return List.of(header.split(",", -1));
        } catch (IOException error) {
            return List.of();
        }
    }

    private Long firstLong(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.longValue();
            }
        }
        return null;
    }

    private List<String> stringList(JsonNode node, String name) {
        if (node == null || !node.path(name).isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.path(name).forEach(value -> {
            if (value.isTextual()) {
                result.add(value.textValue());
            }
        });
        return List.copyOf(result);
    }

    private Map<String, String> stringMap(JsonNode node, String name) {
        if (node == null || !node.path(name).isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.path(name).fields().forEachRemaining(entry -> {
            if (entry.getValue().isTextual()) {
                result.put(entry.getKey(), entry.getValue().textValue());
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, List<String>> listMap(JsonNode node, String name) {
        if (node == null || !node.path(name).isObject()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        node.path(name).fields().forEachRemaining(entry -> {
            if (entry.getValue().isArray()) {
                List<String> values = new ArrayList<>();
                entry.getValue().forEach(value -> {
                    if (value.isTextual()) {
                        values.add(value.textValue());
                    }
                });
                result.put(entry.getKey(), List.copyOf(values));
            }
        });
        return Map.copyOf(result);
    }

    public record EntryMetadata(
            Long rowCount,
            Long bytes,
            List<String> columns,
            List<String> recommendedUsecols,
            Map<String, String> recommendedDtype,
            Map<String, List<String>> readProfiles,
            String metadataStatus
    ) {
    }
}
