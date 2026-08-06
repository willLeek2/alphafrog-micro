package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityQuery;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityReadMode;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySnapshot;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilitySummary;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data-analysis terminal usage 的幂等写入与读取实现。
 * PostgreSQL snapshot 子树是持久化依据，Redis 只保存运行中读取缓存。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataAnalysisObservabilityService
        implements DataAnalysisTerminalRecorder, DataAnalysisObservabilityQuery {

    private static final int MAX_CAS_ATTEMPTS = 8;

    private final AgentRunMapper runMapper;
    private final AgentRunStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public DataAnalysisUpsertOutcome upsert(DataAnalysisTerminalEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        Object lock = locks.computeIfAbsent(envelope.runId(), ignored -> new Object());
        synchronized (lock) {
            try {
                return upsertLocked(envelope);
            } finally {
                locks.remove(envelope.runId(), lock);
            }
        }
    }

    private DataAnalysisUpsertOutcome upsertLocked(DataAnalysisTerminalEnvelope envelope) {
        DataAnalysisObservabilityCall nextCall = DataAnalysisObservabilityCall.fromEnvelope(envelope);
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            AgentRun run = runMapper.findById(envelope.runId());
            if (run == null) {
                log.warn("Data-analysis usage run 不存在: runId={}", envelope.runId());
                return DataAnalysisUpsertOutcome.CONFLICT;
            }
            ParsedSnapshot current;
            try {
                current = parseSnapshot(run.getId(), run.getSnapshotJson());
            } catch (Exception e) {
                log.warn("Data-analysis snapshot 无法解析: runId={}, error={}", envelope.runId(), e.getMessage());
                return DataAnalysisUpsertOutcome.CONFLICT;
            }

            Optional<DataAnalysisObservabilityCall> existing = current.snapshot().calls().stream()
                    .filter(call -> call.toolCallId().equals(envelope.toolCallId())
                            && call.attempt() == envelope.attempt())
                    .findFirst();
            if (existing.isPresent()) {
                if (!existing.get().equals(nextCall)) {
                    log.warn("Data-analysis usage identity 冲突: runId={}, toolCallId={}, attempt={}",
                            envelope.runId(), envelope.toolCallId(), envelope.attempt());
                    return DataAnalysisUpsertOutcome.CONFLICT;
                }
                cache(current.snapshot());
                return DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME;
            }

            List<DataAnalysisObservabilityCall> calls = new ArrayList<>(current.snapshot().calls());
            calls.add(nextCall);
            calls.sort(java.util.Comparator.comparing(DataAnalysisObservabilityCall::toolCallId)
                    .thenComparingInt(DataAnalysisObservabilityCall::attempt));
            DataAnalysisObservabilitySnapshot next = DataAnalysisObservabilitySnapshot.of(envelope.runId(), calls);
            String nextJson = write(next);
            int rows = runMapper.casUpdateDataAnalysisObservability(
                    envelope.runId(), current.persistedJson(), nextJson);
            if (rows == 1) {
                cache(next);
                return DataAnalysisUpsertOutcome.INSERTED;
            }
        }
        log.warn("Data-analysis usage CAS 重试耗尽: runId={}, toolCallId={}, attempt={}",
                envelope.runId(), envelope.toolCallId(), envelope.attempt());
        return DataAnalysisUpsertOutcome.CONFLICT;
    }

    @Override
    public Optional<DataAnalysisObservabilitySummary> findSummaryByRunId(
            String runId,
            DataAnalysisObservabilityReadMode mode) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        DataAnalysisObservabilityReadMode effectiveMode = requireMode(mode);
        if (effectiveMode == DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST) {
            Optional<String> cached = stateStore.loadDataAnalysisObservabilitySummary(runId);
            if (cached.isPresent()) {
                try {
                    return Optional.of(objectMapper.readValue(
                            cached.get(), DataAnalysisObservabilitySummary.class));
                } catch (Exception e) {
                    log.warn("Redis data-analysis summary 无法解析，回退 DB: runId={}", runId);
                }
            }
        }
        String json = runMapper.findDataAnalysisObservabilitySummaryJsonById(runId);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            DataAnalysisObservabilitySummary summary = objectMapper.readValue(
                    json, DataAnalysisObservabilitySummary.class);
            if (effectiveMode == DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST) {
                try {
                    stateStore.saveDataAnalysisObservabilitySummary(runId, write(summary));
                } catch (Exception e) {
                    log.warn("Data-analysis summary Redis cache 写入失败，继续返回 DB 数据: runId={}", runId);
                }
            }
            return Optional.of(summary);
        } catch (Exception e) {
            log.warn("DB data-analysis summary 无法解析: runId={}, error={}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<DataAnalysisObservabilitySnapshot> findByRunId(
            String runId,
            DataAnalysisObservabilityReadMode mode) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        DataAnalysisObservabilityReadMode effectiveMode = requireMode(mode);
        if (effectiveMode == DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST) {
            Optional<String> cached = stateStore.loadDataAnalysisObservability(runId);
            if (cached.isPresent()) {
                try {
                    return Optional.of(readSnapshot(runId, cached.get()));
                } catch (Exception e) {
                    log.warn("Redis data-analysis snapshot 无法解析，回退 DB: runId={}", runId);
                }
            }
        }
        String json = runMapper.findDataAnalysisObservabilityJsonById(runId);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            DataAnalysisObservabilitySnapshot snapshot = readSnapshot(runId, json);
            if (effectiveMode == DataAnalysisObservabilityReadMode.RUNNING_CACHE_FIRST) {
                cache(snapshot);
            }
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.warn("DB data-analysis snapshot 无法解析: runId={}, error={}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    private ParsedSnapshot parseSnapshot(String runId, String snapshotJson) throws Exception {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return new ParsedSnapshot(DataAnalysisObservabilitySnapshot.of(runId, List.of()), null);
        }
        JsonNode root = objectMapper.readTree(snapshotJson);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("snapshot root must be object");
        }
        JsonNode node = root.get(DataAnalysisObservabilitySnapshot.ROOT_FIELD);
        if (node == null || node.isNull()) {
            return new ParsedSnapshot(DataAnalysisObservabilitySnapshot.of(runId, List.of()), null);
        }
        String persisted = objectMapper.writeValueAsString(node);
        return new ParsedSnapshot(readSnapshot(runId, persisted), persisted);
    }

    private DataAnalysisObservabilitySnapshot readSnapshot(String runId, String json) throws Exception {
        DataAnalysisObservabilitySnapshot snapshot = objectMapper.readValue(
                json, DataAnalysisObservabilitySnapshot.class);
        if (!runId.equals(snapshot.runId())) {
            throw new IllegalArgumentException("snapshot runId mismatch");
        }
        return snapshot;
    }

    private DataAnalysisObservabilityReadMode requireMode(DataAnalysisObservabilityReadMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("read mode must not be null");
        }
        return mode;
    }

    private void cache(DataAnalysisObservabilitySnapshot snapshot) {
        try {
            stateStore.saveDataAnalysisObservability(
                    snapshot.runId(), write(snapshot), write(snapshot.summary()));
        } catch (Exception e) {
            log.warn("Data-analysis Redis cache 写入失败，DB 已持久化: runId={}, error={}",
                    snapshot.runId(), e.getMessage());
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("data_analysis_observability_serialize_failed", e);
        }
    }

    private record ParsedSnapshot(DataAnalysisObservabilitySnapshot snapshot, String persistedJson) {
    }
}
