package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

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
@Slf4j
public class DatasetWriter {

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.tools.market-data.dataset.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return resolveEnabled();
    }

    public String getDatasetPath() {
        return datasetPath;
    }

    public <T> String writeDataset(String prefix, String tsCode, String start, String end, 
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

        String scopeHash = DatasetPathStrategy.scopeHash(prefix, tsCode, start, end);
        Path datasetDirPath = DatasetPathStrategy.resolvePath(Path.of(datasetPath), prefix, scopeHash, datasetId);
        File datasetDir = datasetDirPath.toFile();
        if (!datasetDir.exists()) {
            datasetDir.mkdirs();
        }

        // Compatibility symlink: old flat path → new hierarchical path
        DatasetPathStrategy.validateDatasetId(datasetId);
        Path flatLinkPath = Path.of(datasetPath, datasetId);
        try {
            Files.createSymbolicLink(flatLinkPath, datasetDirPath);
        } catch (IOException e) {
            log.warn("Failed to create compat symlink {} → {}: {}", flatLinkPath, datasetDirPath, e.getMessage());
        }

        File csvFile = new File(datasetDir, datasetId + ".csv");
        File metaFile = new File(datasetDir, datasetId + ".meta.json");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            // Write Header
            writer.write(String.join(",", headers));
            writer.newLine();

            // Write Data
            for (T item : data) {
                List<Object> row = rowMapper.apply(item);
                String line = row.stream()
                        .map(String::valueOf)
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
            DatasetMetadata meta = DatasetMetadata.builder()
                    .datasetId(datasetId)
                    .tsCode(tsCode)
                    .startDate(start)
                    .endDate(end)
                    .rowCount(data.size())
                    .columns(headers)
                    .format("csv")
                    .build();
            objectMapper.writeValue(metaFile, meta);
        } catch (IOException e) {
             log.error("Failed to write dataset Meta: " + datasetId, e);
        }

        return datasetId;
    }

    private void ensureDirectory() {
        File dir = new File(datasetPath);
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
        private List<String> columns;
        private String format;
    }
}
