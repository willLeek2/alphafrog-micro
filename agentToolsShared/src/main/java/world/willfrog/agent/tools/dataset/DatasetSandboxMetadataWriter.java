package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders the versioned, path-free metadata documents consumed by sandbox_runner/af_dataset_loader. */
public final class DatasetSandboxMetadataWriter {

    public static final String DATASET_SCHEMA_VERSION = "agent_run_dataset_meta_v1";
    public static final String MANIFEST_SCHEMA_VERSION = "agent_run_manifest_meta_v1";

    private final ObjectMapper objectMapper;
    private final DatasetEntryMetadataReader metadataReader;

    public DatasetSandboxMetadataWriter() {
        this(new ObjectMapper(), new DatasetEntryMetadataReader());
    }

    DatasetSandboxMetadataWriter(ObjectMapper objectMapper, DatasetEntryMetadataReader metadataReader) {
        this.objectMapper = objectMapper;
        this.metadataReader = metadataReader;
    }

    public String writeDatasetMetadata(AgentRunDatasetSnapshot snapshot) {
        Map<String, Object> datasets = new LinkedHashMap<>();
        for (AgentRunDatasetEntry entry : snapshot.datasets()) {
            datasets.put(Integer.toString(entry.number()), publicMetadata(metadataReader.read(entry)));
        }
        return json(Map.of("schema_version", DATASET_SCHEMA_VERSION, "datasets", datasets));
    }

    public String writeManifestMetadata(AgentRunDatasetSnapshot snapshot) {
        Map<String, Object> manifests = new LinkedHashMap<>();
        for (AgentRunDatasetEntry entry : snapshot.manifests()) {
            DatasetEntryMetadataReader.EntryMetadata metadata = metadataReader.read(entry);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("totalRowCount", metadata.rowCount());
            view.put("totalBytes", metadata.bytes());
            view.put("columns", metadata.columns());
            view.put("recommendedUsecols", metadata.recommendedUsecols());
            view.put("recommendedDtype", metadata.recommendedDtype());
            view.put("readProfiles", metadata.readProfiles());
            view.put("memberNumbers", entry.relatedDatasetIds().stream()
                    .map(Integer::valueOf)
                    .toList());
            view.put("metadataStatus", metadata.metadataStatus());
            manifests.put(Integer.toString(entry.number()), view);
        }
        return json(Map.of("schema_version", MANIFEST_SCHEMA_VERSION, "manifests", manifests));
    }

    private Map<String, Object> publicMetadata(DatasetEntryMetadataReader.EntryMetadata metadata) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rowCount", metadata.rowCount());
        view.put("bytes", metadata.bytes());
        view.put("columns", metadata.columns());
        view.put("recommendedUsecols", metadata.recommendedUsecols());
        view.put("recommendedDtype", metadata.recommendedDtype());
        view.put("readProfiles", metadata.readProfiles());
        view.put("metadataStatus", metadata.metadataStatus());
        return view;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to render sandbox dataset metadata", error);
        }
    }
}
