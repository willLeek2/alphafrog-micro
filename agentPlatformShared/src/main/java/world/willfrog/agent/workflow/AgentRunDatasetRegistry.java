package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>线程安全（Cindy round 2 review MF-new-2 改造）：
 * <ul>
 *   <li>raw entry 入池（{@code rawDatasets} / {@code rawManifests}）走
 *       {@code ConcurrentMap.putIfAbsent}，并发安全；事件入池不需要加锁。</li>
 *   <li>编号分配（{@code datasetNumbering} / {@code manifestNumbering}）走
 *       {@code putIfAbsent}，但「size() + 1」分配时存在 TOCTOU 竞态：两个并发
 *       snapshot 可能同时看到同一个 {@code size()} 并对不同 originalId 分配相同 number，
 *       后续 {@code datasetByNumber.put(n, raw)} 会互相覆盖。</li>
 *   <li>本类修复：snapshot 内部用 per-RunState lock 包住「读 raw → 分配 missing
 *       number → build snapshot」这一段，保证并发 snapshot 出来的 number 仍然
 *       是 1..N 唯一序列，且 run 期间 raw event 入池不被阻塞。</li>
 * </ul>
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
     * <p>编号在 snapshot 时按 sortKey 字典序分配（MF4 改造），且一旦分配就 frozen
     * 不变（MF3 lock-after-snapshot / visible-after-freeze 改造）：
     * <ol>
     *   <li>把所有 raw dataset entry 按 sortKey 升序排 → 对不在 numbering map 里的 originalId
     *       用 {@code map.size() + 1} 分配；已在 map 里的保持原 number（frozen）</li>
     *   <li>把所有 raw manifest entry 按 sortKey 升序排 → 独立编号空间（Q4），同 MF3 语义</li>
     *   <li>对每个 manifest entry，把它的 {@code relatedDatasetIds}（存的是 originalId 列表）
     *       用 (1) 的映射翻译成 dataset run-level 编号（MF2 改造，spec §4.2.2）；
     *       翻译不到（dataset 还没注册）的 originalId 被丢弃并 log warning，
     *       manifest 注册本身不失败</li>
     *   <li>用 {@link AgentRunDatasetEntry#forDataset} / {@link AgentRunDatasetEntry#forManifest}
     *       构造 snapshot entry，relatedDatasetIds 存的是已翻译的编号字符串；
     *       entry 列表按 number 升序输出，existing #1, #2 顺序不变，late entries 追加到尾部</li>
     * </ol>
     *
     * <p>每次返回一个新的 immutable snapshot 对象，不暴露内部 mutable 状态。
     *
     * <p>线程安全（Cindy round 2 review MF-new-2 改造）：
     * 整个「读 raw → 分配 missing number → build snapshot」段都在 per-RunState
     * 锁内执行。raw event 入池（{@link #onDatasetPersisted}）走
     * {@code ConcurrentMap.putIfAbsent}，不进入此锁，因此并发事件入池不被阻塞。
     * 但两个 snapshot 之间的「读 rawDatasets.size() / 分配下一个 number」会序列化，
     * 保证并发 snapshot 也输出 1..N 唯一 number 序列。
     */
    public AgentRunDatasetSnapshot snapshot(String runId) {
        if (runId == null || runId.isBlank()) {
            return AgentRunDatasetSnapshot.empty();
        }
        RunState state = runStates.get(runId);
        if (state == null) {
            return AgentRunDatasetSnapshot.empty();
        }

        // MF-new-2: 在 per-RunState 锁内做「读 raw → 分配 missing number → build snapshot」，
        // 防止两个并发 snapshot 看到同一个 size() 并对不同 originalId 分配相同 number。
        // raw event 入池仍走 ConcurrentMap.putIfAbsent，不被本锁阻塞。
        synchronized (state) {
            // MF3 lock-after-snapshot (visible-after-freeze)：
            // 已分配的 number 一旦暴露就 frozen 不变，迟到 raw event 拿下一个可用 number（追加到尾部）。
            // 先按 sortKey 字典序遍历，对不在 numbering map 里的 originalId 用「map.size() + 1」分配。
            List<RawEntry> sortedDatasets = state.rawDatasets.values().stream()
                    .sorted(Comparator.comparing(RawEntry::sortKey, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (RawEntry raw : sortedDatasets) {
                state.datasetNumbering.putIfAbsent(raw.originalId(), state.datasetNumbering.size() + 1);
            }

            List<RawEntry> sortedManifests = state.rawManifests.values().stream()
                    .sorted(Comparator.comparing(RawEntry::sortKey, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (RawEntry raw : sortedManifests) {
                state.manifestNumbering.putIfAbsent(raw.originalId(), state.manifestNumbering.size() + 1);
            }

            // 按 number 升序构建 entry 列表：existing #1, #2 顺序不变，late entries 追加到尾部
            java.util.Map<Integer, RawEntry> datasetByNumber = new java.util.HashMap<>(sortedDatasets.size() * 2);
            for (RawEntry raw : sortedDatasets) {
                Integer n = state.datasetNumbering.get(raw.originalId());
                datasetByNumber.put(n, raw);
            }
            List<Integer> sortedDatasetNumbers = new ArrayList<>(datasetByNumber.keySet());
            java.util.Collections.sort(sortedDatasetNumbers);
            List<AgentRunDatasetEntry> datasetEntries = new ArrayList<>(sortedDatasets.size());
            for (Integer n : sortedDatasetNumbers) {
                RawEntry raw = datasetByNumber.get(n);
                datasetEntries.add(AgentRunDatasetEntry.forDataset(
                        n, raw.originalId(), raw.persistedPath(),
                        raw.fromTsCode(), raw.sortKey()));
            }

            // MF2: manifest.relatedDatasetIds 用同 snapshot 的 dataset run-level 编号翻译。
            // 由于 dataset numbering 是 frozen 的，跨 snapshot 翻译是稳定的。
            java.util.Map<String, Integer> datasetIdToNumber = new java.util.HashMap<>(state.datasetNumbering.size() * 2);
            for (java.util.Map.Entry<String, Integer> e : state.datasetNumbering.entrySet()) {
                datasetIdToNumber.put(e.getKey(), e.getValue());
            }

            java.util.Map<Integer, RawEntry> manifestByNumber = new java.util.HashMap<>(sortedManifests.size() * 2);
            for (RawEntry raw : sortedManifests) {
                Integer n = state.manifestNumbering.get(raw.originalId());
                manifestByNumber.put(n, raw);
            }
            List<Integer> sortedManifestNumbers = new ArrayList<>(manifestByNumber.keySet());
            java.util.Collections.sort(sortedManifestNumbers);
            List<AgentRunDatasetEntry> manifestEntries = new ArrayList<>(sortedManifests.size());
            for (Integer n : sortedManifestNumbers) {
                RawEntry raw = manifestByNumber.get(n);
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
                        n, raw.originalId(), raw.persistedPath(),
                        raw.fromTsCode(), raw.sortKey(), translated);
                manifestEntries.add(mf);
            }

            return new AgentRunDatasetSnapshot(datasetEntries, manifestEntries);
        }
    }

    /**
     * Restores the durable run-level dataset mapping before a suspended tool call resumes.
     *
     * <p>The operation is atomic per run and idempotent for an identical snapshot. A reused number,
     * original id, or artifact payload with different content is rejected instead of silently changing the
     * mapping that was previously exposed to the Agent/Sandbox. Manifest references in the public snapshot
     * are run-level dataset numbers; they are translated back to internal original ids while rebuilding the
     * registry so subsequent snapshots preserve the same public mapping.
     */
    public void restore(String runId, AgentRunDatasetSnapshot snapshot) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(snapshot, "snapshot");

        RestoredState restored = validateRestoreSnapshot(snapshot);
        RunState state = runStates.computeIfAbsent(runId, ignored -> new RunState());
        synchronized (state) {
            verifyCompatible("dataset", state.rawDatasets, state.datasetNumbering,
                    restored.datasets(), restored.datasetNumbering());
            verifyCompatible("manifest", state.rawManifests, state.manifestNumbering,
                    restored.manifests(), restored.manifestNumbering());

            state.rawDatasets.putAll(restored.datasets());
            state.rawManifests.putAll(restored.manifests());
            state.datasetNumbering.putAll(restored.datasetNumbering());
            state.manifestNumbering.putAll(restored.manifestNumbering());
        }
        log.info("Restored dataset registry: runId={} datasets={} manifests={} digest={}",
                runId, snapshot.datasets().size(), snapshot.manifests().size(), snapshot.immutableDigest());
    }

    private RestoredState validateRestoreSnapshot(AgentRunDatasetSnapshot snapshot) {
        Map<String, RawEntry> datasets = new HashMap<>();
        Map<String, Integer> datasetNumbering = new HashMap<>();
        Map<Integer, String> datasetIdsByNumber = new HashMap<>();
        for (AgentRunDatasetEntry entry : snapshot.datasets()) {
            requireArtifactType(entry, PersistedArtifactType.DATASET, "datasets");
            putUnique(datasetIdsByNumber, entry.number(), entry.originalId(), "dataset number");
            putUnique(datasetNumbering, entry.originalId(), entry.number(), "dataset originalId");
            putUnique(datasets, entry.originalId(), new RawEntry(
                    entry.originalId(), entry.persistedPath(), entry.fromTsCode(), entry.sortKey(),
                    List.of(), PersistedArtifactType.DATASET), "dataset originalId");
        }

        Map<String, RawEntry> manifests = new HashMap<>();
        Map<String, Integer> manifestNumbering = new HashMap<>();
        Map<Integer, String> manifestIdsByNumber = new HashMap<>();
        for (AgentRunDatasetEntry entry : snapshot.manifests()) {
            requireArtifactType(entry, PersistedArtifactType.MANIFEST, "manifests");
            putUnique(manifestIdsByNumber, entry.number(), entry.originalId(), "manifest number");
            putUnique(manifestNumbering, entry.originalId(), entry.number(), "manifest originalId");

            List<String> relatedOriginalIds = new ArrayList<>(entry.relatedDatasetIds().size());
            for (String value : entry.relatedDatasetIds()) {
                int datasetNumber;
                try {
                    datasetNumber = Integer.parseInt(value);
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("manifest " + entry.originalId()
                            + " has non-numeric related dataset number: " + value, invalid);
                }
                String relatedOriginalId = datasetIdsByNumber.get(datasetNumber);
                if (relatedOriginalId == null) {
                    throw new IllegalArgumentException("manifest " + entry.originalId()
                            + " references unknown dataset number: " + datasetNumber);
                }
                relatedOriginalIds.add(relatedOriginalId);
            }
            putUnique(manifests, entry.originalId(), new RawEntry(
                    entry.originalId(), entry.persistedPath(), entry.fromTsCode(), entry.sortKey(),
                    List.copyOf(relatedOriginalIds), PersistedArtifactType.MANIFEST), "manifest originalId");
        }
        return new RestoredState(datasets, manifests, datasetNumbering, manifestNumbering);
    }

    private void requireArtifactType(AgentRunDatasetEntry entry, PersistedArtifactType expected, String listName) {
        if (entry.artifactType() != expected) {
            throw new IllegalArgumentException(listName + " contains " + entry.artifactType()
                    + " entry: " + entry.originalId());
        }
    }

    private <K, V> void putUnique(Map<K, V> target, K key, V value, String label) {
        V prior = target.putIfAbsent(key, value);
        if (prior != null && !prior.equals(value)) {
            throw new IllegalArgumentException("conflicting " + label + ": " + key);
        }
        if (prior != null) {
            throw new IllegalArgumentException("duplicate " + label + ": " + key);
        }
    }

    private void verifyCompatible(String label,
                                  Map<String, RawEntry> currentRaw,
                                  Map<String, Integer> currentNumbering,
                                  Map<String, RawEntry> restoredRaw,
                                  Map<String, Integer> restoredNumbering) {
        Map<Integer, String> currentIdsByNumber = invert(currentNumbering, label);
        for (Map.Entry<String, Integer> entry : restoredNumbering.entrySet()) {
            Integer currentNumber = currentNumbering.get(entry.getKey());
            if (currentNumber != null && !currentNumber.equals(entry.getValue())) {
                throw new IllegalStateException("conflicting " + label + " number for " + entry.getKey()
                        + ": current=" + currentNumber + " restored=" + entry.getValue());
            }
            String currentId = currentIdsByNumber.get(entry.getValue());
            if (currentId != null && !currentId.equals(entry.getKey())) {
                throw new IllegalStateException("conflicting " + label + " number " + entry.getValue()
                        + ": current=" + currentId + " restored=" + entry.getKey());
            }
        }
        for (Map.Entry<String, RawEntry> entry : restoredRaw.entrySet()) {
            RawEntry current = currentRaw.get(entry.getKey());
            if (current != null && !current.equals(entry.getValue())) {
                throw new IllegalStateException("conflicting " + label + " payload for " + entry.getKey());
            }
        }
    }

    private Map<Integer, String> invert(Map<String, Integer> numbering, String label) {
        Map<Integer, String> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : numbering.entrySet()) {
            String prior = result.putIfAbsent(entry.getValue(), entry.getKey());
            if (prior != null && !prior.equals(entry.getKey())) {
                throw new IllegalStateException("current " + label + " state reuses number " + entry.getValue());
            }
        }
        return result;
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
        // MF3 lock-after-snapshot: 已分配的 dataset / manifest 编号冻结，迟到 raw event 拿下一个可用编号。
        final ConcurrentMap<String, Integer> datasetNumbering = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Integer> manifestNumbering = new ConcurrentHashMap<>();
    }

    private record RestoredState(
            Map<String, RawEntry> datasets,
            Map<String, RawEntry> manifests,
            Map<String, Integer> datasetNumbering,
            Map<String, Integer> manifestNumbering
    ) {
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
