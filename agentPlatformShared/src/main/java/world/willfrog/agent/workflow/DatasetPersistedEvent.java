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

    /**
     * 登记来源（014-data-04）：默认取数工具产出；platform_object 表示这条 dataset 是
     * DataFrog 平台数据对象在本 run 的挂载副本。registry 把该标记透传到
     * {@link AgentRunDatasetEntry}，listMyData 据此在 run 数据集区跳过平台副本条目。
     */
    public static final String SOURCE_TOOL_OUTPUT = "tool_output";
    public static final String SOURCE_PLATFORM_OBJECT = "platform_object";

    private final String runId;
    private final PersistedArtifactType artifactType;
    private final String datasetId;       // non-null iff artifactType == DATASET
    private final String manifestId;      // non-null iff artifactType == MANIFEST
    private final String persistedPath;   // 01 storage path (not sandbox path — 02 derives sandbox path)
    private final String fromTsCode;      // "000300.SH" / "UNCERTAIN"
    private final List<String> relatedDatasetIds;  // empty for DATASET; dataset IDs for MANIFEST
    private final String sortKey;         // filename lexicographic key for stable ordering
    private final String datasetSource;   // 登记来源，见 SOURCE_* 常量；manifest 恒为默认值

    // ---- dataset constructor ----
    public DatasetPersistedEvent(Object source, String runId, String datasetId,
                                 String persistedPath, String fromTsCode, String sortKey) {
        this(source, runId, datasetId, persistedPath, fromTsCode, sortKey, SOURCE_TOOL_OUTPUT);
    }

    // ---- dataset constructor（带登记来源，014-data-04）----
    public DatasetPersistedEvent(Object source, String runId, String datasetId,
                                 String persistedPath, String fromTsCode, String sortKey,
                                 String datasetSource) {
        super(source);
        this.runId = Objects.requireNonNull(runId, "runId");
        this.artifactType = PersistedArtifactType.DATASET;
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId");
        this.manifestId = null;
        this.persistedPath = Objects.requireNonNull(persistedPath, "persistedPath");
        this.fromTsCode = (fromTsCode == null || fromTsCode.isBlank()) ? "UNCERTAIN" : fromTsCode;
        this.relatedDatasetIds = Collections.emptyList();
        this.sortKey = Objects.requireNonNull(sortKey, "sortKey");
        this.datasetSource = (datasetSource == null || datasetSource.isBlank())
                ? SOURCE_TOOL_OUTPUT : datasetSource;
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
        this.datasetSource = SOURCE_TOOL_OUTPUT;
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
    /** 登记来源（014-data-04），取值见 SOURCE_* 常量；空值已归一为默认来源。 */
    public String getDatasetSource() { return datasetSource; }

    @Override
    public String toString() {
        return "DatasetPersistedEvent{" +
                "runId='" + runId + '\'' +
                ", type=" + artifactType +
                ", id=" + (artifactType == PersistedArtifactType.DATASET ? datasetId : manifestId) +
                ", fromTsCode='" + fromTsCode + '\'' +
                ", sortKey='" + sortKey + '\'' +
                ", relatedCount=" + relatedDatasetIds.size() +
                ", datasetSource='" + datasetSource + '\'' +
                '}';
    }
}
