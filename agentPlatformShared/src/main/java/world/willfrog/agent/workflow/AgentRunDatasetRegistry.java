package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 给定 agent run 期间，订阅 {@link DatasetPersistedEvent}，分配「run 级别编号」并维护映射。
 *
 * <p>编号规则（拍板 §6）：
 * <ul>
 *   <li>Q3 整个 agent run 期间稳定（同一 datasetId / manifestId 多次出现，编号不变）</li>
 *   <li>Q4 dataset 与 manifest 各自独立递增</li>
 *   <li>Q5 编号按 sortKey 字典序作为稳定全局序（不是事件到达顺序；
 *       DAG 并行节点可能乱序到达，sortKey = 落盘文件名 = 字典序全局唯一）</li>
 *   <li>失败幂等：同一 (runId, artifactType, originalId) 多次事件只入一次</li>
 * </ul>
 *
 * <p>实现要点（260623 Cindy review MF4 改造）：
 * 内部存储只保留 raw entry（无 number），不立即按到达顺序分配编号。
 * 编号分配推迟到 {@link #snapshot(String)} 调用时刻：sort by sortKey 后
 * 按出现顺序递增分配，确保乱序到达的事件也能得到字典序的稳定编号。
 *
 * <p>实现要点（260623 Cindy review MF2 改造）：
 * snapshot 时把 manifest.relatedDatasetIds（原始 originalId 列表）翻译成
 * 同 snapshot 的 dataset run-level 编号，保证 CSV 里 related_dataset_ids 是
 * 「run 级别编号」而不是 originalId（spec §4.2.2）。
 *
 * <p>线程安全：所有状态在 {@code ConcurrentMap} 内；putIfAbsent 保证 raw entry 入池幂等。
 */
@Component
@Slf4j
public class AgentRunDatasetRegistry {

    private final ConcurrentMap<String, RunState> runStates = new ConcurrentHashMap<>();

    /**
     * 订阅 01 contract V2 事件。同一 datasetId / manifestId 重复事件只入一次（保留 sortKey 首次）。
     * 编号分配推迟到 snapshot 时（按 sortKey 字典序）。
     */
    @EventListener
    public void onDatasetPersisted(DatasetPersistedEvent event) {
        if (event == null || event.getRunId() == null || event.getRunId().isBlank()) {
            log.warn("DatasetPersistedEvent missing runId, ignored: {}", event);
            return;
        }
        RunState state = runStates.computeIfAbsent(event.getRunId(), k -> new RunState());
        if (event.getArtifactType() == PersistedArtifactType.DATASET) {
            ingestDataset(state, event);
        } else {
            ingestManifest(state, event);
        }
    }

    private void ingestDataset(RunState state, DatasetPersistedEvent event) {
        String datasetId = event.getDatasetId();
        if (datasetId == null || datasetId.isBlank()) {
            log.warn("DATASET event missing datasetId, ignored: {}", event);
            return;
        }
        RawEntry raw = new RawEntry(
                datasetId,
                event.getPersistedPath(),
                event.getFromTsCode(),
                event.getSortKey(),
                List.of(),
                PersistedArtifactType.DATASET);
        RawEntry prior = state.rawDatasets.putIfAbsent(datasetId, raw);
        if (prior != null) {
            log.debug("Dataset already ingested (raw), skip re-ingest: runId={} datasetId={} sortKey={}",
                    event.getRunId(), datasetId, prior.sortKey());
        } else {
            log.info("Ingested dataset raw entry: runId={} datasetId={} sortKey={}",
                    event.getRunId(), datasetId, event.getSortKey());
        }
    }

    private void ingestManifest(RunState state, DatasetPersistedEvent event) {
        String manifestId = event.getManifestId();
        if (manifestId == null || manifestId.isBlank()) {
            log.warn("MANIFEST event missing manifestId, ignored: {}", event);
            return;
        }
        List<String> related = event.getRelatedDatasetIds() == null
                ? List.of()
                : List.copyOf(event.getRelatedDatasetIds());
        RawEntry raw = new RawEntry(
                manifestId,
                event.getPersistedPath(),
                event.getFromTsCode(),
                event.getSortKey(),
                related,
                PersistedArtifactType.MANIFEST);
        RawEntry prior = state.rawManifests.putIfAbsent(manifestId, raw);
        if (prior != null) {
            log.debug("Manifest already ingested (raw), skip re-ingest: runId={} manifestId={} sortKey={}",
                    event.getRunId(), manifestId, prior.sortKey());
        } else {
            log.info("Ingested manifest raw entry: runId={} manifestId={} sortKey={} relatedCount={}",
                    event.getRunId(), manifestId, event.getSortKey(), related.size());
        }
    }

    /**
     * 当前 agent run 状态下所有 dataset / manifest 的稳定快照。
     *
     * <p>编号在 snapshot 时按 sortKey 字典序分配（MF4 改造）：
     * <ol>
     *   <li>把所有 raw dataset entry 按 sortKey 升序排 → 编号 1..N</li>
     *   <li>把所有 raw manifest entry 按 sortKey 升序排 → 编号 1..M（独立编号空间，Q4）</li>
     *   <li>对每个 manifest entry，把它的 {@code relatedDatasetIds}（存的是 originalId 列表）
     *       用 (1) 的映射翻译成 dataset run-level 编号（MF2 改造，spec §4.2.2）；
     *       翻译不到（dataset 还没注册）的 originalId 被丢弃并 log warning，
     *       manifest 注册本身不失败</li>
     *   <li>用 {@link AgentRunDatasetEntry#forDataset} / {@link AgentRunDatasetEntry#forManifest}
     *       构造 snapshot entry，relatedDatasetIds 存的是已翻译的编号字符串</li>
     * </ol>
     *
     * <p>每次返回一个新的 immutable snapshot 对象，不暴露内部 mutable 状态。
     */
    public AgentRunDatasetSnapshot snapshot(String runId) {
        if (runId == null || runId.isBlank()) {
            return AgentRunDatasetSnapshot.empty();
        }
        RunState state = runStates.get(runId);
        if (state == null) {
            return AgentRunDatasetSnapshot.empty();
        }

        List<RawEntry> sortedDatasets = state.rawDatasets.values().stream()
                .sorted(Comparator.comparing(RawEntry::sortKey, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        java.util.Map<String, Integer> datasetIdToNumber = new java.util.HashMap<>(sortedDatasets.size() * 2);
        List<AgentRunDatasetEntry> datasetEntries = new ArrayList<>(sortedDatasets.size());
        for (int i = 0; i < sortedDatasets.size(); i++) {
            int number = i + 1;
            RawEntry raw = sortedDatasets.get(i);
            datasetIdToNumber.put(raw.originalId(), number);
            datasetEntries.add(AgentRunDatasetEntry.forDataset(
                    number, raw.originalId(), raw.persistedPath(),
                    raw.fromTsCode(), raw.sortKey()));
        }

        List<RawEntry> sortedManifests = state.rawManifests.values().stream()
                .sorted(Comparator.comparing(RawEntry::sortKey, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<AgentRunDatasetEntry> manifestEntries = new ArrayList<>(sortedManifests.size());
        for (int i = 0; i < sortedManifests.size(); i++) {
            int number = i + 1;
            RawEntry raw = sortedManifests.get(i);
            // MF2: 把 originalId 形式的 relatedDatasetIds 翻译成同 snapshot 的 run-level 编号。
            // 翻译不到的 originalId 丢弃（dataset 还没注册），不阻断 manifest。
            List<String> translated = new ArrayList<>(raw.relatedDatasetIds().size());
            for (String relatedOriginalId : raw.relatedDatasetIds()) {
                Integer relatedNumber = datasetIdToNumber.get(relatedOriginalId);
                if (relatedNumber == null) {
                    log.warn("Manifest references unknown dataset, drop ref: runId={} manifestId={} missingDatasetId={}",
                            runId, raw.originalId(), relatedOriginalId);
                    continue;
                }
                translated.add(Integer.toString(relatedNumber));
            }
            AgentRunDatasetEntry mf = AgentRunDatasetEntry.forManifest(
                    number, raw.originalId(), raw.persistedPath(),
                    raw.fromTsCode(), raw.sortKey(), translated);
            manifestEntries.add(mf);
        }

        return new AgentRunDatasetSnapshot(datasetEntries, manifestEntries);
    }

    public Optional<AgentRunDatasetEntry> findDatasetByNumber(String runId, int number) {
        return snapshot(runId).datasets().stream()
                .filter(e -> e.number() == number)
                .findFirst();
    }

    public Optional<AgentRunDatasetEntry> findManifestByNumber(String runId, int number) {
        return snapshot(runId).manifests().stream()
                .filter(e -> e.number() == number)
                .findFirst();
    }

    /**
     * 把 raw dataset originalId 翻译成 snapshot 时的 run-level 编号。
     * O(N) 实现（遍历当前 snapshot）；调用方应优先用 {@link #findDatasetByNumber} 直接查 number。
     */
    public Optional<Integer> resolveDatasetNumber(String runId, String datasetId) {
        if (runId == null || runId.isBlank() || datasetId == null) {
            return Optional.empty();
        }
        return snapshot(runId).datasets().stream()
                .filter(e -> datasetId.equals(e.originalId()))
                .map(AgentRunDatasetEntry::number)
                .findFirst();
    }

    /**
     * 把 raw manifest originalId 翻译成 snapshot 时的 run-level 编号。
     * O(N) 实现（遍历当前 snapshot）；调用方应优先用 {@link #findManifestByNumber} 直接查 number。
     */
    public Optional<Integer> resolveManifestNumber(String runId, String manifestId) {
        if (runId == null || runId.isBlank() || manifestId == null) {
            return Optional.empty();
        }
        return snapshot(runId).manifests().stream()
                .filter(e -> manifestId.equals(e.originalId()))
                .map(AgentRunDatasetEntry::number)
                .findFirst();
    }

    /**
     * 返回当前合法 dataset 编号列表（升序）。用于 Q12 错误反馈：「合法 dataset 编号 = [1,2,3]」。
     */
    public List<Integer> listDatasetNumbers(String runId) {
        return snapshot(runId).datasets().stream()
                .map(AgentRunDatasetEntry::number)
                .sorted()
                .toList();
    }

    public List<Integer> listManifestNumbers(String runId) {
        return snapshot(runId).manifests().stream()
                .map(AgentRunDatasetEntry::number)
                .sorted()
                .toList();
    }

    public boolean hasRunState(String runId) {
        return runId != null && runStates.containsKey(runId);
    }

    /**
     * 清理指定 run 的 per-run 状态。在 agent run finally 块调用。
     */
    public void reset(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        runStates.remove(runId);
        log.debug("Reset AgentRunDatasetRegistry state for runId={}", runId);
    }

    private static final class RunState {
        final ConcurrentMap<String, RawEntry> rawDatasets = new ConcurrentHashMap<>();
        final ConcurrentMap<String, RawEntry> rawManifests = new ConcurrentHashMap<>();
    }

    /**
     * 内部 raw entry：事件入池后未分配 number 的形态。
     * number 推迟到 snapshot 时按 sortKey 字典序分配。
     */
    private record RawEntry(
            String originalId,
            String persistedPath,
            String fromTsCode,
            String sortKey,
            List<String> relatedDatasetIds,
            PersistedArtifactType artifactType
    ) {
    }
}
