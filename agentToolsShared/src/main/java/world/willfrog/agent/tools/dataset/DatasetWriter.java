package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatasetWriter {

    // D04：dataset 根目录改经统一存储门面解析（新键 agent.storage.dataset-root →
    // 旧键别名 agent.tools.market-data.dataset.path → 默认 /data/agent_datasets），
    // 本类不再直接持有根键 @Value
    private final AgentStoragePaths storagePaths;

    @Value("${agent.tools.market-data.dataset.database-fetched-path:/data/database_fetched}")
    private String databaseFetchedPath;

    @Value("${agent.tools.market-data.dataset.manifests-path:/data/manifests}")
    private String manifestsPath;

    @Value("${agent.tools.market-data.dataset.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return resolveEnabled();
    }

    public String getDatasetPath() {
        return storagePaths.datasetRoot().toString();
    }

    public String getDatabaseFetchedPath() {
        return databaseFetchedPath;
    }

    public <T> String writeDataset(String type, String prefix, String tsCode, String start, String end,
                                   List<T> data,
                                   List<String> headers,
                                   Function<T, List<Object>> rowMapper) {
        if (!resolveEnabled()) {
            return null;
        }

        ensureDirectory();

        String uuid = UUID.randomUUID().toString().substring(0, 8);
        // datasetId format: <prefix>-<tsCode>-<start>-<end>-<uuid>
        // Sanitize components to ensure valid filename
        String safeTsCode = tsCode.replaceAll("[^a-zA-Z0-9.]", "_");
        String datasetId = String.format("%s-%s-%s-%s-%s", prefix, safeTsCode, start, end, uuid);

        // New 4-layer path: database_fetched/<topic>/<tsCode>/<encodedString>/
        // type is the clean data type (e.g. "stock_daily") — NOT the runId-prefixed prefix
        // This ensures writer and registry compute the same path.
        String topic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String encodedStr = DatabaseFetchedPathStrategy.encodedString(type, safeTsCode, start, end, headers);
        Path datasetDirPath = DatabaseFetchedPathStrategy.resolveDataPath(
                Path.of(databaseFetchedPath), topic, safeTsCode, encodedStr);
        File datasetDir = datasetDirPath.toFile();
        if (!datasetDir.exists()) {
            datasetDir.mkdirs();
        }

        // NO compat symlink — old flat-path symlink deleted in this version
        // (see task #39 V4: compat symlink was a source of broken dataset references)

        String csvFileName = safeTsCode + ".csv";
        File csvFile = new File(datasetDir, csvFileName);
        File metaFile = new File(datasetDir, safeTsCode + ".meta.json");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            // Write Header
            writer.write(String.join(",", headers));
            writer.newLine();

            // Write Data
            for (T item : data) {
                List<Object> row = rowMapper.apply(item);
                String line = row.stream()
                        .map(value -> value == null ? "" : String.valueOf(value))
                        .collect(Collectors.joining(","));
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            log.error("Failed to write dataset CSV: " + datasetId, e);
            throw new RuntimeException("Failed to write dataset", e);
        }

        // Write Metadata
        try {
            DatasetSchemaHintResolver.SchemaHints hints = DatasetSchemaHintResolver.resolve(headers);
            DatasetMetadata meta = DatasetMetadata.builder()
                    .datasetId(datasetId)
                    .tsCode(tsCode)
                    .startDate(start)
                    .endDate(end)
                    .rowCount(data.size())
                    .bytes(Files.size(csvFile.toPath()))
                    .columns(headers)
                    .recommendedUsecols(hints.recommendedUsecols())
                    .recommendedDtype(hints.recommendedDtype())
                    .readProfiles(hints.readProfiles())
                    .metadataStatus("complete")
                    .format("csv")
                    .build();
            objectMapper.writeValue(metaFile, meta);
        } catch (IOException e) {
             log.error("Failed to write dataset Meta: " + datasetId, e);
        }

        return datasetId;
    }

    private void ensureDirectory() {
        File dir = storagePaths.datasetRoot().toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private boolean resolveEnabled() {
        if (localConfigLoader == null) {
            return enabled;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getMarketData)
                .map(AgentLlmProperties.MarketData::getDataset)
                .map(AgentLlmProperties.MarketDataDataset::getEnabled)
                .orElse(enabled);
    }

    @Data
    @Builder
    public static class DatasetMetadata {
        private String datasetId;
        private String tsCode;
        private String startDate;
        private String endDate;
        private int rowCount;
        private long bytes;
        private List<String> columns;
        private List<String> recommendedUsecols;
        private java.util.Map<String, String> recommendedDtype;
        private java.util.Map<String, List<String>> readProfiles;
        private String metadataStatus;
        private String format;
    }
}
