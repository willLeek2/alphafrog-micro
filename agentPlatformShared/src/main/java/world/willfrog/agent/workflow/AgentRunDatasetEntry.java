package world.willfrog.agent.workflow;

import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.List;
import java.util.Objects;

/**
 * 单条 dataset 或 manifest 在「run 级别编号」抽象层的记录。
 *
 * <p>编号语义（Q1-Q5）：同 agent run 内唯一；dataset 与 manifest 各自独立递增；sortKey 字段
 * 携带原始文件名用于稳定排序。
 */
public record AgentRunDatasetEntry(
        int number,
        String originalId,
        String persistedPath,
        String fromTsCode,
        String sortKey,
        List<String> relatedDatasetIds,
        PersistedArtifactType artifactType
) {
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
    }

    public static AgentRunDatasetEntry forDataset(int number, String datasetId, String persistedPath,
                                                  String fromTsCode, String sortKey) {
        return new AgentRunDatasetEntry(number, datasetId, persistedPath, fromTsCode, sortKey,
                List.of(), PersistedArtifactType.DATASET);
    }

    public static AgentRunDatasetEntry forManifest(int number, String manifestId, String persistedPath,
                                                   String fromTsCode, String sortKey,
                                                   List<String> relatedDatasetIds) {
        return new AgentRunDatasetEntry(number, manifestId, persistedPath, fromTsCode, sortKey,
                relatedDatasetIds == null ? List.of() : List.copyOf(relatedDatasetIds),
                PersistedArtifactType.MANIFEST);
    }

    public boolean isDataset() {
        return artifactType == PersistedArtifactType.DATASET;
    }

    public boolean isManifest() {
        return artifactType == PersistedArtifactType.MANIFEST;
    }
}
