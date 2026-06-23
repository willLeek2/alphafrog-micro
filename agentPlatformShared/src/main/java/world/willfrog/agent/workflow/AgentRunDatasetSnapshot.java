package world.willfrog.agent.workflow;

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
}
