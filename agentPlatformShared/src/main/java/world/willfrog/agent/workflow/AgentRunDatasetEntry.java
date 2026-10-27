package world.willfrog.agent.workflow;

import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.List;
import java.util.Objects;

/**
 * 单条 dataset 或 manifest 在「run 级别编号」抽象层的记录。
 *
 * <p>编号语义（Q1-Q5）：同 agent run 内唯一；dataset 与 manifest 各自独立递增；sortKey 字段
 * 携带原始文件名用于稳定排序。
 *
 * <p>014-data-04 起新增 {@code source} 字段（登记来源，带默认值，不破坏既有构造与用法）：
 * 取数工具产出的 dataset / manifest 恒为 {@link #SOURCE_TOOL_OUTPUT}；DataFrog 平台数据对象
 * 挂载进 run 的 CSV 副本为 {@link #SOURCE_PLATFORM_OBJECT}，listMyData 据此在 run 数据集区
 * 跳过这类条目（平台条目只在平台对象区以 ds_ 标识展示）。旧持久化快照（无该字段的 JSON）
 * 反序列化得到 null 时在紧凑构造器里归一为默认值。
 */
public record AgentRunDatasetEntry(
        int number,
        String originalId,
        String persistedPath,
        String fromTsCode,
        String sortKey,
        List<String> relatedDatasetIds,
        PersistedArtifactType artifactType,
        String source
) {
    public static final String SOURCE_TOOL_OUTPUT = DatasetPersistedEvent.SOURCE_TOOL_OUTPUT;
    public static final String SOURCE_PLATFORM_OBJECT = DatasetPersistedEvent.SOURCE_PLATFORM_OBJECT;

    public AgentRunDatasetEntry {
        Objects.requireNonNull(originalId, "originalId");
        Objects.requireNonNull(persistedPath, "persistedPath");
        Objects.requireNonNull(fromTsCode, "fromTsCode");
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(relatedDatasetIds, "relatedDatasetIds");
        Objects.requireNonNull(artifactType, "artifactType");
        if (number <= 0) {
            throw new IllegalArgumentException("number must be positive, got " + number);
        }
        source = (source == null || source.isBlank()) ? SOURCE_TOOL_OUTPUT : source;
    }

    public static AgentRunDatasetEntry forDataset(int number, String datasetId, String persistedPath,
                                                  String fromTsCode, String sortKey) {
        return new AgentRunDatasetEntry(number, datasetId, persistedPath, fromTsCode, sortKey,
                List.of(), PersistedArtifactType.DATASET, SOURCE_TOOL_OUTPUT);
    }

    public static AgentRunDatasetEntry forManifest(int number, String manifestId, String persistedPath,
                                                   String fromTsCode, String sortKey,
                                                   List<String> relatedDatasetIds) {
        return new AgentRunDatasetEntry(number, manifestId, persistedPath, fromTsCode, sortKey,
                relatedDatasetIds == null ? List.of() : List.copyOf(relatedDatasetIds),
                PersistedArtifactType.MANIFEST, SOURCE_TOOL_OUTPUT);
    }

    /**
     * 平台数据对象挂载副本（014-data-04）：originalId 即 ds_ 对象标识，sortKey 即对象名.csv
     * （沙箱内文件名），来源标记 platform_object。
     */
    public static AgentRunDatasetEntry forPlatformObject(int number, String objectId, String persistedPath,
                                                         String fromTsCode, String sortKey) {
        return new AgentRunDatasetEntry(number, objectId, persistedPath, fromTsCode, sortKey,
                List.of(), PersistedArtifactType.DATASET, SOURCE_PLATFORM_OBJECT);
    }

    public boolean isDataset() {
        return artifactType == PersistedArtifactType.DATASET;
    }

    public boolean isManifest() {
        return artifactType == PersistedArtifactType.MANIFEST;
    }

    /** 是否为平台数据对象挂载副本（014-data-04）。 */
    public boolean isPlatformObject() {
        return SOURCE_PLATFORM_OBJECT.equals(source);
    }
}
