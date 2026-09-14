package world.willfrog.agent.workflow;

import org.springframework.context.ApplicationEvent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Contract event published by 01 (storage layer) after dataset or manifest persistence.
 * Consumed by 02 (run-level ID translation layer).
 *
 * <p>This is the frozen contract between sub-tasks. Field additions require owner coordination.
 */
public class DatasetPersistedEvent extends ApplicationEvent {

    public enum PersistedArtifactType { DATASET, MANIFEST }

    private final String runId;
    private final PersistedArtifactType artifactType;
    private final String datasetId;       // non-null iff artifactType == DATASET
    private final String manifestId;      // non-null iff artifactType == MANIFEST
    private final String persistedPath;   // 01 storage path (not sandbox path — 02 derives sandbox path)
    private final String fromTsCode;      // "000300.SH" / "UNCERTAIN"
    private final List<String> relatedDatasetIds;  // empty for DATASET; dataset IDs for MANIFEST
    private final String sortKey;         // filename lexicographic key for stable ordering

    // ---- dataset constructor ----
    public DatasetPersistedEvent(Object source, String runId, String datasetId,
                                 String persistedPath, String fromTsCode, String sortKey) {
        super(source);
        this.runId = Objects.requireNonNull(runId, "runId");
        this.artifactType = PersistedArtifactType.DATASET;
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId");
        this.manifestId = null;
        this.persistedPath = Objects.requireNonNull(persistedPath, "persistedPath");
        this.fromTsCode = (fromTsCode == null || fromTsCode.isBlank()) ? "UNCERTAIN" : fromTsCode;
        this.relatedDatasetIds = Collections.emptyList();
        this.sortKey = Objects.requireNonNull(sortKey, "sortKey");
    }

    // ---- manifest constructor ----
    public DatasetPersistedEvent(Object source, String runId, String manifestId,
                                 String persistedPath, String fromTsCode,
                                 List<String> relatedDatasetIds, String sortKey) {
        super(source);
        this.runId = Objects.requireNonNull(runId, "runId");
        this.artifactType = PersistedArtifactType.MANIFEST;
        this.datasetId = null;
        this.manifestId = Objects.requireNonNull(manifestId, "manifestId");
        this.persistedPath = Objects.requireNonNull(persistedPath, "persistedPath");
        this.fromTsCode = (fromTsCode == null || fromTsCode.isBlank()) ? "UNCERTAIN" : fromTsCode;
        this.relatedDatasetIds = relatedDatasetIds != null ? List.copyOf(relatedDatasetIds) : Collections.emptyList();
        this.sortKey = Objects.requireNonNull(sortKey, "sortKey");
    }

    // ---- accessors (stable contract — do not change signatures without 01/02 coordination) ----

    public String getRunId() { return runId; }
    public PersistedArtifactType getArtifactType() { return artifactType; }
    /** Non-null iff {@link #getArtifactType()} == DATASET. */
    public String getDatasetId() { return datasetId; }
    /** Non-null iff {@link #getArtifactType()} == MANIFEST. */
    public String getManifestId() { return manifestId; }
    /** 01 storage path. 02 derives sandbox path from this. */
    public String getPersistedPath() { return persistedPath; }
    public String getFromTsCode() { return fromTsCode; }
    /** Empty for DATASET; dataset IDs for MANIFEST. */
    public List<String> getRelatedDatasetIds() { return relatedDatasetIds; }
    /** Filename lexicographic key for stable ordering within the same tool call. */
    public String getSortKey() { return sortKey; }

    @Override
    public String toString() {
        return "DatasetPersistedEvent{" +
                "runId='" + runId + '\'' +
                ", type=" + artifactType +
                ", id=" + (artifactType == PersistedArtifactType.DATASET ? datasetId : manifestId) +
                ", fromTsCode='" + fromTsCode + '\'' +
                ", sortKey='" + sortKey + '\'' +
                ", relatedCount=" + relatedDatasetIds.size() +
                '}';
    }
}
