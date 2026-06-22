package world.willfrog.agent.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.workflow.DatasetPersistedEvent.PersistedArtifactType;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 给定 agent run 期间，订阅 {@link DatasetPersistedEvent}，分配「run 级别编号」并维护映射。
 *
 * <p>编号规则（拍板 §6）：
 * <ul>
 *   <li>Q3 整个 agent run 期间稳定（同一 datasetId / manifestId 多次出现，编号不变）</li>
 *   <li>Q4 dataset 与 manifest 各自独立递增</li>
 *   <li>Q5 编号按事件到达顺序分配（与 sortKey 字典序等价：单批次内 sortKey 排序 == 到达顺序）</li>
 *   <li>失败幂等：同一 (runId, artifactType, originalId) 多次事件只分配一次编号</li>
 * </ul>
 *
 * <p>线程安全：所有状态在 {@code ConcurrentMap} 内，{@link AtomicInteger} 保证 counter 单调。
 */
@Component
@Slf4j
public class AgentRunDatasetRegistry {

    private final ConcurrentMap<String, RunState> runStates = new ConcurrentHashMap<>();

    /**
     * 订阅 01 contract V2 事件。同一 datasetId / manifestId 重复事件会复用首次分配的编号。
     */
    @EventListener
    public void onDatasetPersisted(DatasetPersistedEvent event) {
        if (event == null || event.getRunId() == null || event.getRunId().isBlank()) {
            log.warn("DatasetPersistedEvent missing runId, ignored: {}", event);
            return;
        }
        RunState state = runStates.computeIfAbsent(event.getRunId(), k -> new RunState());
        if (event.getArtifactType() == PersistedArtifactType.DATASET) {
            assignDataset(state, event);
        } else {
            assignManifest(state, event);
        }
    }

    private void assignDataset(RunState state, DatasetPersistedEvent event) {
        String datasetId = event.getDatasetId();
        if (datasetId == null || datasetId.isBlank()) {
            log.warn("DATASET event missing datasetId, ignored: {}", event);
            return;
        }
        Integer existing = state.datasetIdToNumber.putIfAbsent(datasetId, Integer.MIN_VALUE);
        if (existing != null) {
            log.debug("Dataset already registered, skip re-assign: runId={} datasetId={} number={}",
                    event.getRunId(), datasetId, existing);
            return;
        }
        int number = state.datasetCounter.incrementAndGet();
        state.datasetIdToNumber.put(datasetId, number);
        AgentRunDatasetEntry entry = AgentRunDatasetEntry.forDataset(
                number, datasetId, event.getPersistedPath(),
                event.getFromTsCode(), event.getSortKey());
        AgentRunDatasetEntry prior = state.datasetsByNumber.putIfAbsent(number, entry);
        if (prior != null) {
            log.warn("Dataset number collision: runId={} number={} existing={} new={}",
                    event.getRunId(), number, prior.originalId(), datasetId);
        }
        log.info("Assigned dataset run-level number: runId={} number={} datasetId={} sortKey={}",
                event.getRunId(), number, datasetId, event.getSortKey());
    }

    private void assignManifest(RunState state, DatasetPersistedEvent event) {
        String manifestId = event.getManifestId();
        if (manifestId == null || manifestId.isBlank()) {
            log.warn("MANIFEST event missing manifestId, ignored: {}", event);
            return;
        }
        Integer existing = state.manifestIdToNumber.putIfAbsent(manifestId, Integer.MIN_VALUE);
        if (existing != null) {
            log.debug("Manifest already registered, skip re-assign: runId={} manifestId={} number={}",
                    event.getRunId(), manifestId, existing);
            return;
        }
        int number = state.manifestCounter.incrementAndGet();
        state.manifestIdToNumber.put(manifestId, number);
        AgentRunDatasetEntry entry = AgentRunDatasetEntry.forManifest(
                number, manifestId, event.getPersistedPath(),
                event.getFromTsCode(), event.getSortKey(),
                List.copyOf(event.getRelatedDatasetIds()));
        AgentRunDatasetEntry prior = state.manifestsByNumber.putIfAbsent(number, entry);
        if (prior != null) {
            log.warn("Manifest number collision: runId={} number={} existing={} new={}",
                    event.getRunId(), number, prior.originalId(), manifestId);
        }
        log.info("Assigned manifest run-level number: runId={} number={} manifestId={} sortKey={} relatedCount={}",
                event.getRunId(), number, manifestId, event.getSortKey(),
                event.getRelatedDatasetIds().size());
    }

    /**
     * 当前 agent run 状态下所有 dataset / manifest 的稳定快照（按 number 升序）。
     * 每次返回一个新的 immutable snapshot 对象，不暴露内部 mutable 状态。
     */
    public AgentRunDatasetSnapshot snapshot(String runId) {
        if (runId == null || runId.isBlank()) {
            return AgentRunDatasetSnapshot.empty();
        }
        RunState state = runStates.get(runId);
        if (state == null) {
            return AgentRunDatasetSnapshot.empty();
        }
        List<AgentRunDatasetEntry> datasets = state.datasetsByNumber.values().stream()
                .sorted(Comparator.comparingInt(AgentRunDatasetEntry::number))
                .toList();
        List<AgentRunDatasetEntry> manifests = state.manifestsByNumber.values().stream()
                .sorted(Comparator.comparingInt(AgentRunDatasetEntry::number))
                .toList();
        return new AgentRunDatasetSnapshot(datasets, manifests);
    }

    public Optional<AgentRunDatasetEntry> findDatasetByNumber(String runId, int number) {
        RunState state = runStates.get(runId);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.datasetsByNumber.get(number));
    }

    public Optional<AgentRunDatasetEntry> findManifestByNumber(String runId, int number) {
        RunState state = runStates.get(runId);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.manifestsByNumber.get(number));
    }

    public Optional<Integer> resolveDatasetNumber(String runId, String datasetId) {
        RunState state = runStates.get(runId);
        if (state == null) {
            return Optional.empty();
        }
        Integer n = state.datasetIdToNumber.get(datasetId);
        return n == null || n == Integer.MIN_VALUE ? Optional.empty() : Optional.of(n);
    }

    public Optional<Integer> resolveManifestNumber(String runId, String manifestId) {
        RunState state = runStates.get(runId);
        if (state == null) {
            return Optional.empty();
        }
        Integer n = state.manifestIdToNumber.get(manifestId);
        return n == null || n == Integer.MIN_VALUE ? Optional.empty() : Optional.of(n);
    }

    /**
     * 返回当前合法 dataset 编号列表（升序）。用于 Q12 错误反馈：「合法 dataset 编号 = [1,2,3]」。
     */
    public List<Integer> listDatasetNumbers(String runId) {
        RunState state = runStates.get(runId);
        if (state == null) {
            return List.of();
        }
        return state.datasetsByNumber.keySet().stream().sorted().toList();
    }

    public List<Integer> listManifestNumbers(String runId) {
        RunState state = runStates.get(runId);
        if (state == null) {
            return List.of();
        }
        return state.manifestsByNumber.keySet().stream().sorted().toList();
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
        final AtomicInteger datasetCounter = new AtomicInteger(0);
        final AtomicInteger manifestCounter = new AtomicInteger(0);
        final ConcurrentMap<Integer, AgentRunDatasetEntry> datasetsByNumber = new ConcurrentHashMap<>();
        final ConcurrentMap<Integer, AgentRunDatasetEntry> manifestsByNumber = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Integer> datasetIdToNumber = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Integer> manifestIdToNumber = new ConcurrentHashMap<>();
    }
}
