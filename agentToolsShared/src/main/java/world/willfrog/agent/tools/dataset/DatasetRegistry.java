package world.willfrog.agent.tools.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistry;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.workflow.DatasetPersistedEvent;
import world.willfrog.alphafrogmicro.common.utils.DateConvertUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
public class DatasetRegistry {

    private static final String META_PREFIX = "dataset:meta:";
    private static final String INDEX_PREFIX = "dataset:index:";
    private static final String MANIFEST_META_PREFIX = "manifest:meta:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agent.tools.market-data.dataset.path:/data/agent_datasets}")
    private String datasetPath;

    @Value("${agent.tools.market-data.dataset.database-fetched-path:/data/database_fetched}")
    private String databaseFetchedPath;

    @Value("${agent.tools.market-data.dataset.manifests-path:/data/manifests}")
    private String manifestsPath;

    @Value("${agent.tools.market-data.dataset.enabled:true}")
    private boolean enabled;

    @Value("${agent.tools.market-data.dataset.cache-ttl-seconds:604800}")
    private long ttlSeconds;

    @Value("${agent.tools.market-data.dataset.allow-range-reuse:true}")
    private boolean allowRangeReuse;

    @Value("${agent.tools.market-data.dataset.cleanup-scan-count:500}")
    private int scanCount;

    @Autowired(required = false)
    private AgentLlmLocalConfigLoader localConfigLoader;

    @Autowired(required = false)
    private PersistentArtifactRegistry artifactRegistry;

    private final ApplicationEventPublisher eventPublisher;

    public DatasetRegistry(StringRedisTemplate redisTemplate, ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    public boolean isEnabled() {
        return resolveEnabled();
    }

    public Optional<DatasetMeta> findReusable(String type, String tsCode, String startDate, String endDate, List<String> columns) {
        if (!resolveEnabled()) {
            return Optional.empty();
        }
        String queryKey = buildQueryKey(type, tsCode, startDate, endDate, columns);
        Optional<DatasetMeta> exact = loadMeta(queryKey);
        if (exact.isPresent()) {
            DatasetMeta meta = exact.get();
            if (isExpired(meta) || !datasetFilesExist(meta)) {
                cleanupMeta(meta);
            } else {
                touchMeta(meta);
                publishDatasetPersistedEvent(meta);
                return Optional.of(meta);
            }
        }

        if (!allowRangeReuse) {
            return Optional.empty();
        }

        String indexKey = indexKey(type, tsCode);
        Set<String> queryKeys = redisTemplate.opsForSet().members(indexKey);
        if (queryKeys == null || queryKeys.isEmpty()) {
            return Optional.empty();
        }

        Long targetStart = parseDateToLong(startDate);
        Long targetEnd = parseDateToLong(endDate);
        if (targetStart == null || targetEnd == null) {
            return Optional.empty();
        }

        String columnSignature = String.join(",", columns);
        List<DatasetMeta> candidates = new ArrayList<>();
        for (String candidateKey : queryKeys) {
            if (candidateKey.equals(queryKey)) {
                continue;
            }
            Optional<DatasetMeta> candidate = loadMeta(candidateKey);
            if (candidate.isEmpty()) {
                continue;
            }
            DatasetMeta meta = candidate.get();
            if (!columnSignature.equals(meta.getColumnsSignature())) {
                continue;
            }
            Long metaStart = parseDateToLong(meta.getStartDate());
            Long metaEnd = parseDateToLong(meta.getEndDate());
            if (metaStart == null || metaEnd == null) {
                continue;
            }
            if (metaStart <= targetStart && metaEnd >= targetEnd && !isExpired(meta) && datasetFilesExist(meta)) {
                candidates.add(meta);
            }
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(Comparator.comparingLong(meta -> rangeLength(meta)));
        DatasetMeta selected = candidates.get(0);
        touchMeta(selected);
        publishDatasetPersistedEvent(selected);
        return Optional.of(selected);
    }

    public void registerDataset(String type, String tsCode, String startDate, String endDate,
                                List<String> columns, String datasetId, int rowCount) {
        String safeTsCode = tsCode != null ? tsCode.replaceAll("[^a-zA-Z0-9.]", "_") : "data";
        registerDataset(type, tsCode, startDate, endDate, columns, datasetId, rowCount, "csv",
                safeTsCode + ".csv");
    }

    public void registerDataset(String type, String tsCode, String startDate, String endDate,
                                List<String> columns, String datasetId, int rowCount,
                                String format, String dataFileName) {
        if (!resolveEnabled() || datasetId == null || datasetId.isEmpty()) {
            return;
        }
        String queryKey = buildQueryKey(type, tsCode, startDate, endDate, columns);
        long now = Instant.now().toEpochMilli();
        long expireAt = ttlSeconds > 0 ? now + ttlSeconds * 1000L : Long.MAX_VALUE;
        // 使用四层路径：database_fetched/<topic>/<tsCode>/<encodedString>/<tsCode>.csv
        Path datasetDirPath = resolveDatasetDir(databaseFetchedPath, type, tsCode, startDate, endDate, columns);
        // meta.path = directory (for cleanup walking); event.getPersistedPath() = file (for 02 sandbox)
        String datasetDir = datasetDirPath.toAbsolutePath().toString();

        DatasetMeta meta = DatasetMeta.builder()
                .datasetId(datasetId)
                .queryKey(queryKey)
                .type(type)
                .tsCode(tsCode)
                .startDate(startDate)
                .endDate(endDate)
                .columns(columns)
                .columnsSignature(String.join(",", columns))
                .rowCount(rowCount)
                .path(datasetDir)
                .format(format == null || format.isBlank() ? "csv" : format)
                .dataFileName(dataFileName == null || dataFileName.isBlank() ? datasetId + ".csv" : dataFileName)
                .createdAt(now)
                .lastAccessAt(now)
                .hitCount(1)
                .ttlSeconds(ttlSeconds)
                .expireAt(expireAt)
                .build();

        String metaKey = metaKey(queryKey);
        try {
            redisTemplate.opsForValue().set(metaKey, objectMapper.writeValueAsString(meta));
            redisTemplate.opsForSet().add(indexKey(type, tsCode), queryKey);
            registerPersistentArtifacts(meta);

            // 01 → 02 contract: publish dataset persisted event
            publishDatasetPersistedEvent(meta);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize dataset meta: {}", datasetId, e);
        }
    }

    /**
     * 按精确参数查找已注册的 manifest。
     * 调用方传入 tsCodes 顺序任意，registry 内部排序后构造与 writer 一致的 queryKey。
     * Phase 1 不做 range reuse（见 dataset batching markdown §四.2）。
     */
    public Optional<ManifestMeta> findReusableManifest(String dataType, String startDate, String endDate,
                                                       List<String> tsCodes, List<String> columns) {
        if (!resolveEnabled()) {
            return Optional.empty();
        }
        if (dataType == null || dataType.isEmpty()
                || startDate == null || startDate.isEmpty()
                || endDate == null || endDate.isEmpty()) {
            return Optional.empty();
        }
        String queryKey = buildManifestQueryKey(dataType, startDate, endDate, tsCodes, columns);
        Optional<ManifestMeta> loaded = loadManifestMeta(queryKey);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        ManifestMeta meta = loaded.get();
        if (isManifestExpired(meta) || !manifestFilesExist(meta)) {
            cleanupManifestMeta(meta);
            return Optional.empty();
        }
        touchManifestMeta(meta);
        publishManifestMemberDatasetEvents(meta);
        publishManifestPersistedEvent(meta);
        return Optional.of(meta);
    }

    /**
     * 注册一份 manifest。需在 {@link ManifestWriter#writeManifest} 写盘成功后调用。
     * query key 构造规则与 writer 保持一致，registry 内部对 tsCodes 排序，避免不同输入顺序产生不同 key。
     */
    public void registerManifest(String dataType, String startDate, String endDate,
                                 List<String> tsCodes, List<String> columns,
                                 String manifestId, int memberCount, int readyCount,
                                 int failedCount, int totalRowCount) {
        if (!resolveEnabled() || manifestId == null || manifestId.isEmpty()) {
            return;
        }
        String queryKey = buildManifestQueryKey(dataType, startDate, endDate, tsCodes, columns);
        long now = Instant.now().toEpochMilli();
        long expireAt = ttlSeconds > 0 ? now + ttlSeconds * 1000L : Long.MAX_VALUE;
        Path manifestDirPath = DatabaseFetchedPathStrategy.resolveManifestPath(
                Paths.get(manifestsPath), manifestId);
        String manifestDir = manifestDirPath.toAbsolutePath().toString();

        ManifestMeta meta = ManifestMeta.builder()
                .manifestId(manifestId)
                .queryKey(queryKey)
                .dataType(dataType)
                .startDate(startDate)
                .endDate(endDate)
                .columns(columns)
                .columnsSignature(columns == null ? "" : String.join(",", columns))
                .memberCount(memberCount)
                .readyCount(readyCount)
                .failedCount(failedCount)
                .totalRowCount(totalRowCount)
                .path(manifestDir)
                .createdAt(now)
                .lastAccessAt(now)
                .hitCount(1)
                .ttlSeconds(ttlSeconds)
                .expireAt(expireAt)
                .build();

        String metaKey = manifestMetaKey(queryKey);
        try {
            redisTemplate.opsForValue().set(metaKey, objectMapper.writeValueAsString(meta));

            publishManifestMemberDatasetEvents(meta);
            publishManifestPersistedEvent(meta);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize manifest meta: {}", manifestId, e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.tools.market-data.dataset.cleanup-interval-ms:600000}")
    public void cleanupExpiredDatasets() {
        if (!resolveEnabled()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        Cursor<byte[]> cursor = null;
        try {
            cursor = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .scan(ScanOptions.scanOptions().match(META_PREFIX + "*").count(scanCount).build());
            while (cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                String json = redisTemplate.opsForValue().get(key);
                if (json == null || json.isEmpty()) {
                    continue;
                }
                DatasetMeta meta;
                try {
                    meta = objectMapper.readValue(json, DatasetMeta.class);
                } catch (JsonProcessingException e) {
                    log.warn("Skip invalid dataset meta, key={}", key, e);
                    continue;
                }
                if (now >= meta.getExpireAt()) {
                    deleteDatasetFiles(meta);
                    redisTemplate.delete(key);
                    redisTemplate.opsForSet().remove(indexKey(meta.getType(), meta.getTsCode()), meta.getQueryKey());
                }
            }
        } catch (Exception e) {
            log.warn("Dataset cleanup scan failed", e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                    log.debug("Failed to close redis scan cursor", e);
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${agent.tools.market-data.dataset.cleanup-interval-ms:600000}")
    public void cleanupExpiredManifests() {
        if (!resolveEnabled()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        Cursor<byte[]> cursor = null;
        try {
            cursor = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .scan(ScanOptions.scanOptions().match(MANIFEST_META_PREFIX + "*").count(scanCount).build());
            while (cursor.hasNext()) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                String json = redisTemplate.opsForValue().get(key);
                if (json == null || json.isEmpty()) {
                    continue;
                }
                ManifestMeta meta;
                try {
                    meta = objectMapper.readValue(json, ManifestMeta.class);
                } catch (JsonProcessingException e) {
                    log.warn("Skip invalid manifest meta, key={}", key, e);
                    continue;
                }
                if (now >= meta.getExpireAt()) {
                    deleteManifestFiles(meta);
                    redisTemplate.delete(key);
                }
            }
        } catch (Exception e) {
            log.warn("Manifest cleanup scan failed", e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                    log.debug("Failed to close redis scan cursor", e);
                }
            }
        }
    }

    private Optional<DatasetMeta> loadMeta(String queryKey) {
        String json = redisTemplate.opsForValue().get(metaKey(queryKey));
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, DatasetMeta.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dataset meta for key {}", queryKey, e);
            return Optional.empty();
        }
    }

    private Optional<ManifestMeta> loadManifestMeta(String queryKey) {
        String json = redisTemplate.opsForValue().get(manifestMetaKey(queryKey));
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, ManifestMeta.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse manifest meta for key {}", queryKey, e);
            return Optional.empty();
        }
    }

    private void touchMeta(DatasetMeta meta) {
        long now = Instant.now().toEpochMilli();
        meta.setLastAccessAt(now);
        meta.setHitCount(meta.getHitCount() + 1);
        if (ttlSeconds > 0) {
            meta.setExpireAt(now + ttlSeconds * 1000L);
            meta.setTtlSeconds(ttlSeconds);
        }
        try {
            redisTemplate.opsForValue().set(metaKey(meta.getQueryKey()), objectMapper.writeValueAsString(meta));
        } catch (JsonProcessingException e) {
            log.warn("Failed to update dataset meta for key {}", meta.getQueryKey(), e);
        }
    }

    private void publishDatasetPersistedEvent(DatasetMeta meta) {
        if (eventPublisher == null || meta == null || meta.getDatasetId() == null || meta.getDatasetId().isBlank()) {
            return;
        }
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        String dataFileName = meta.getDataFileName();
        if (dataFileName == null || dataFileName.isBlank()) {
            dataFileName = meta.getDatasetId() + ".csv";
        }
        String persistedPath = Paths.get(meta.getPath()).resolve(dataFileName).toAbsolutePath().toString();
        eventPublisher.publishEvent(new DatasetPersistedEvent(
                this, runId, meta.getDatasetId(), persistedPath, meta.getTsCode(), dataFileName));
    }

    private void publishManifestPersistedEvent(ManifestMeta meta) {
        if (eventPublisher == null || meta == null || meta.getManifestId() == null || meta.getManifestId().isBlank()) {
            return;
        }
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        Path persistedJsonPath = Paths.get(meta.getPath()).resolve("manifest.json");
        String persistedPath = persistedJsonPath.toAbsolutePath().toString();
        List<String> relatedDatasetIds = readManifestDatasetIds(persistedJsonPath);
        String fromTsCode = readManifestTsCodes(persistedJsonPath);
        String sortKey = "manifest-" + meta.getManifestId() + ".json";
        eventPublisher.publishEvent(new DatasetPersistedEvent(
                this, runId, meta.getManifestId(), persistedPath, fromTsCode,
                relatedDatasetIds, sortKey));
    }

    private void publishManifestMemberDatasetEvents(ManifestMeta meta) {
        if (eventPublisher == null || meta == null || meta.getPath() == null || meta.getPath().isBlank()) {
            return;
        }
        String runId = AgentContext.getRunId();
        if (runId == null || runId.isBlank()) {
            return;
        }
        Path manifestJsonPath = Paths.get(meta.getPath()).resolve("manifest.json");
        Optional<DatasetManifest> manifestOpt = readManifestDocument(manifestJsonPath);
        if (manifestOpt.isEmpty()) {
            return;
        }
        DatasetManifest manifest = manifestOpt.get();
        List<DatasetManifest.ManifestMember> members = manifest.getMembers();
        if (members == null || members.isEmpty()) {
            return;
        }
        String dataType = firstNonBlank(manifest.getDataType(), meta.getDataType());
        if (dataType.isBlank()) {
            return;
        }
        for (DatasetManifest.ManifestMember member : members) {
            if (member == null || !DatasetManifest.ManifestMember.STATUS_READY.equals(member.getStatus())) {
                continue;
            }
            String datasetId = member.getDatasetId();
            String tsCode = member.getTsCode();
            if (datasetId == null || datasetId.isBlank() || tsCode == null || tsCode.isBlank()) {
                continue;
            }
            String startDate = firstNonBlank(member.getStartDate(), manifest.getStartDate(), meta.getStartDate());
            String endDate = firstNonBlank(member.getEndDate(), manifest.getEndDate(), meta.getEndDate());
            List<String> columns = member.getColumns() != null ? member.getColumns() : manifest.getColumns();
            if (startDate.isBlank() || endDate.isBlank() || columns == null) {
                continue;
            }
            String queryKey = buildQueryKey(dataType, tsCode, startDate, endDate, columns);
            Optional<DatasetMeta> datasetMeta = loadMeta(queryKey);
            if (datasetMeta.isEmpty()) {
                log.warn("Manifest member dataset meta not found: manifestId={} datasetId={} tsCode={}",
                        meta.getManifestId(), datasetId, tsCode);
                continue;
            }
            DatasetMeta dsMeta = datasetMeta.get();
            if (isExpired(dsMeta) || !datasetFilesExist(dsMeta)) {
                log.warn("Manifest member dataset meta expired or missing file: manifestId={} datasetId={} tsCode={}",
                        meta.getManifestId(), dsMeta.getDatasetId(), tsCode);
                continue;
            }
            publishDatasetPersistedEvent(dsMeta);
        }
    }

    private void touchManifestMeta(ManifestMeta meta) {
        long now = Instant.now().toEpochMilli();
        meta.setLastAccessAt(now);
        meta.setHitCount(meta.getHitCount() + 1);
        if (ttlSeconds > 0) {
            meta.setExpireAt(now + ttlSeconds * 1000L);
            meta.setTtlSeconds(ttlSeconds);
        }
        try {
            redisTemplate.opsForValue().set(manifestMetaKey(meta.getQueryKey()), objectMapper.writeValueAsString(meta));
        } catch (JsonProcessingException e) {
            log.warn("Failed to update manifest meta for key {}", meta.getQueryKey(), e);
        }
    }

    private boolean isExpired(DatasetMeta meta) {
        return ttlSeconds > 0 && Instant.now().toEpochMilli() >= meta.getExpireAt();
    }

    private boolean isManifestExpired(ManifestMeta meta) {
        return ttlSeconds > 0 && Instant.now().toEpochMilli() >= meta.getExpireAt();
    }

    private boolean datasetFilesExist(DatasetMeta meta) {
        File dir = new File(meta.getPath());
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        String dataFileName = meta.getDataFileName();
        if (dataFileName == null || dataFileName.isBlank()) {
            dataFileName = meta.getDatasetId() + ".csv";
        }
        File dataFile = new File(dir, dataFileName);
        return dataFile.exists();
    }

    private boolean manifestFilesExist(ManifestMeta meta) {
        File dir = new File(meta.getPath());
        if (!dir.exists() || !dir.isDirectory()) {
            return false;
        }
        // Matches ManifestWriter: manifest.json / meta.json (spec §A.5, no manifestId prefix)
        File manifestJson = new File(dir, "manifest.json");
        File metaJson = new File(dir, "meta.json");
        return manifestJson.exists() && metaJson.exists();
    }

    private void cleanupMeta(DatasetMeta meta) {
        try {
            redisTemplate.delete(metaKey(meta.getQueryKey()));
            redisTemplate.opsForSet().remove(indexKey(meta.getType(), meta.getTsCode()), meta.getQueryKey());
        } catch (Exception e) {
            log.warn("Failed to cleanup meta for {}", meta.getDatasetId(), e);
        }
    }

    private void cleanupManifestMeta(ManifestMeta meta) {
        try {
            redisTemplate.delete(manifestMetaKey(meta.getQueryKey()));
        } catch (Exception e) {
            log.warn("Failed to cleanup manifest meta for {}", meta.getManifestId(), e);
        }
    }

    private void deleteManifestFiles(ManifestMeta meta) {
        Path dir = Paths.get(meta.getPath());
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete manifest file {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk manifest dir {}", dir, e);
        }
    }

    private void deleteDatasetFiles(DatasetMeta meta) {
        Path dir = Paths.get(meta.getPath());
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete dataset file {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to walk dataset dir {}", dir, e);
        }
    }

    private void registerPersistentArtifacts(DatasetMeta meta) {
        if (artifactRegistry == null || meta == null || meta.getDatasetId() == null || meta.getDatasetId().isBlank()) {
            return;
        }
        long ttlHours = resolveDatasetTtlHours(meta.getTtlSeconds());
        try {
            artifactRegistry.registerExternal(
                    "dataset",
                    meta.getDatasetId(),
                    meta.getType(),
                    Paths.get(meta.getPath()).toAbsolutePath().normalize(),
                    ttlHours,
                    false
            );
            // dataset-symlink artifact removed — no compat symlinks in new 4-layer structure
        } catch (Exception e) {
            log.warn("Failed to register dataset persistent artifacts for {}: {}",
                    meta.getDatasetId(), e.getMessage());
        }
    }

    private long resolveDatasetTtlHours(long metaTtlSeconds) {
        Integer configured = localConfigLoader == null ? null : localConfigLoader.current()
                .map(AgentLlmProperties::getAgent)
                .map(AgentLlmProperties.Agent::getDataset)
                .map(AgentLlmProperties.Dataset::getTtlHours)
                .orElse(null);
        if (configured != null && configured > 0) {
            return configured;
        }
        long seconds = metaTtlSeconds > 0 ? metaTtlSeconds : ttlSeconds;
        if (seconds <= 0) {
            return 12L;
        }
        return Math.max(1L, (seconds + 3599L) / 3600L);
    }

    // cleanupCompatSymlink removed — compat symlinks deleted entirely.
    // New 4-layer database_fetched path structure eliminates flat symlinks.

    private String metaKey(String queryKey) {
        return META_PREFIX + queryKey;
    }

    private String indexKey(String type, String tsCode) {
        // Migrated from <type>:<tsCode> to <topic>:<tsCode> per spec §A.4
        String topic = DatabaseFetchedPathStrategy.resolveTopic(type);
        return INDEX_PREFIX + topic + ":" + tsCode;
    }

    private String manifestMetaKey(String queryKey) {
        return MANIFEST_META_PREFIX + queryKey;
    }

    /**
     * Read dataset IDs from a just-written manifest.json.
     * Returns empty list on any error (file missing, malformed JSON, etc.) —
     * 02 consumers handle empty relatedDatasetIds gracefully.
     */
    private List<String> readManifestDatasetIds(Path manifestJsonPath) {
        Optional<DatasetManifest> manifestOpt = readManifestDocument(manifestJsonPath);
        if (manifestOpt.isEmpty() || manifestOpt.get().getMembers() == null) {
            return List.of();
        }
        return manifestOpt.get().getMembers().stream()
                .map(DatasetManifest.ManifestMember::getDatasetId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private String readManifestTsCodes(Path manifestJsonPath) {
        Optional<DatasetManifest> manifestOpt = readManifestDocument(manifestJsonPath);
        if (manifestOpt.isEmpty() || manifestOpt.get().getMembers() == null) {
            return "UNCERTAIN";
        }
        String joined = manifestOpt.get().getMembers().stream()
                .map(DatasetManifest.ManifestMember::getTsCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .reduce((left, right) -> left + "#" + right)
                .orElse("");
        return joined.isBlank() ? "UNCERTAIN" : joined;
    }

    private Optional<DatasetManifest> readManifestDocument(Path manifestJsonPath) {
        try {
            if (!Files.exists(manifestJsonPath)) {
                return Optional.empty();
            }
            DatasetManifest manifest = objectMapper.readValue(
                    manifestJsonPath.toFile(), DatasetManifest.class);
            return Optional.ofNullable(manifest);
        } catch (Exception e) {
            log.debug("Failed to read manifest document from {}: {}",
                    manifestJsonPath, e.getMessage());
            return Optional.empty();
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Shared helper: compute the 4-layer dataset directory path from clean type.
     * Package-private so tests can call the exact same method registry uses when
     * constructing {@code persistedPath} for {@link DatasetPersistedEvent}.
     * Both {@link #registerDataset} and the event publish code path go through this.
     */
    static Path resolveDatasetDir(String databaseFetchedPath, String type, String tsCode,
                                   String startDate, String endDate, List<String> columns) {
        String topic = DatabaseFetchedPathStrategy.resolveTopic(type);
        String encodedStr = DatabaseFetchedPathStrategy.encodedString(type, tsCode, startDate, endDate, columns);
        return DatabaseFetchedPathStrategy.resolveDataPath(
                Paths.get(databaseFetchedPath), topic, tsCode, encodedStr);
    }

    private String buildQueryKey(String type, String tsCode, String startDate, String endDate, List<String> columns) {
        String raw = type + "|" + tsCode + "|" + startDate + "|" + endDate + "|" + String.join(",", columns);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 与 {@link ManifestWriter} 保持一致：tsCodes 内部排序，columns 按调用方顺序 join。
     */
    private String buildManifestQueryKey(String dataType, String startDate, String endDate,
                                         List<String> tsCodes, List<String> columns) {
        List<String> sortedCodes = tsCodes == null ? new ArrayList<>() : new ArrayList<>(tsCodes);
        sortedCodes.sort(Comparator.naturalOrder());
        String tsCodesSignature = String.join(",", sortedCodes);
        String columnsSignature = columns == null ? "" : String.join(",", columns);
        String raw = "manifest|" + dataType + "|" + startDate + "|" + endDate
                + "|" + tsCodesSignature + "|" + columnsSignature;
        return sha256Hex(raw);
    }

    private String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashed) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Long parseDateToLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String raw = value.trim();
        if (raw.matches("\\d{13}")) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Long converted = DateConvertUtils.convertDateStrToLong(raw, "yyyyMMdd");
        if (converted == null || converted <= 0) {
            return null;
        }
        return converted;
    }

    private long rangeLength(DatasetMeta meta) {
        Long start = parseDateToLong(meta.getStartDate());
        Long end = parseDateToLong(meta.getEndDate());
        if (start == null || end == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(end - start);
    }

    private boolean resolveEnabled() {
        if (localConfigLoader == null) {
            return enabled;
        }
        return localConfigLoader.current()
                .map(AgentLlmProperties::getTools)
                .map(AgentLlmProperties.Tools::getMarketData)
                .map(AgentLlmProperties.MarketData::getDataset)
                .map(AgentLlmProperties.MarketDataDataset::getEnabled)
                .orElse(enabled);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatasetMeta {
        private String datasetId;
        private String queryKey;
        private String type;
        private String tsCode;
        private String startDate;
        private String endDate;
        private List<String> columns;
        private String columnsSignature;
        private int rowCount;
        private String path;
        private String format;
        private String dataFileName;
        private long createdAt;
        private long lastAccessAt;
        private int hitCount;
        private long ttlSeconds;
        private long expireAt;
    }

    /**
     * Manifest 注册元数据（Redis manifest:meta:* 序列化形态）。
     * 与 {@link DatasetMeta} 区分：{@code manifestId} 替代 {@code datasetId}；
     * 新增 {@code dataType} / {@code memberCount} / {@code readyCount} / {@code failedCount} / {@code totalRowCount}。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManifestMeta {
        private String manifestId;
        private String queryKey;
        private String dataType;
        private String startDate;
        private String endDate;
        private List<String> columns;
        private String columnsSignature;
        private int memberCount;
        private int readyCount;
        private int failedCount;
        private int totalRowCount;
        private String path;
        private long createdAt;
        private long lastAccessAt;
        private int hitCount;
        private long ttlSeconds;
        private long expireAt;
    }
}
