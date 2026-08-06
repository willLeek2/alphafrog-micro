package world.willfrog.agent.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 一次 agent run 在某时刻的稳定快照：datasets + manifests 两个有序列表。
 *
 * <p>Q13 拍板：「executePython 启动时 csv 是 (a) 启动那一刻的 snapshot，sandbox 跑起来后 csv 内容固定」。
 * 该 record 即 snapshot 的 Java 形态；转译层调用方（PythonSandboxTools / ListMyDataTool）按需
 * 渲染成 paths_dataset.csv / path_manifest.csv。
 */
public record AgentRunDatasetSnapshot(
        List<AgentRunDatasetEntry> datasets,
        List<AgentRunDatasetEntry> manifests
) {
    public AgentRunDatasetSnapshot {
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        manifests = manifests == null ? List.of() : List.copyOf(manifests);
    }

    public static AgentRunDatasetSnapshot empty() {
        return new AgentRunDatasetSnapshot(List.of(), List.of());
    }

    public boolean isEmpty() {
        return datasets.isEmpty() && manifests.isEmpty();
    }

    /**
     * Returns a deterministic digest for the immutable dataset identity exposed to Sandbox creation.
     *
     * <p>The digest intentionally excludes {@link AgentRunDatasetEntry#persistedPath()} because host and
     * task-local paths are deployment details. It includes the stable run-level number, internal artifact
     * identity, sort/source semantics and manifest membership. Entries are sorted by number so a JSON/list
     * serialization detail cannot change the digest.
     */
    public String immutableDigest() {
        StringBuilder canonical = new StringBuilder(256);
        appendEntries(canonical, "datasets", datasets);
        appendEntries(canonical, "manifests", manifests);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendEntries(StringBuilder output, String label, List<AgentRunDatasetEntry> entries) {
        append(output, label, Integer.toString(entries.size()));
        entries.stream()
                .sorted(Comparator.comparingInt(AgentRunDatasetEntry::number))
                .forEach(entry -> {
                    append(output, "number", Integer.toString(entry.number()));
                    append(output, "originalId", entry.originalId());
                    append(output, "fromTsCode", entry.fromTsCode());
                    append(output, "sortKey", entry.sortKey());
                    append(output, "artifactType", entry.artifactType().name());
                    append(output, "relatedCount", Integer.toString(entry.relatedDatasetIds().size()));
                    entry.relatedDatasetIds().forEach(value -> append(output, "related", value));
                });
    }

    private static void append(StringBuilder output, String field, String value) {
        output.append(field.length()).append(':').append(field)
                .append(value.length()).append(':').append(value);
    }
}
