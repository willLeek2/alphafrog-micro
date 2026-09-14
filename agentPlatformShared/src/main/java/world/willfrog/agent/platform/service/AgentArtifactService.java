package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.artifact.PersistentArtifactMeta;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistry;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunEventMapper;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 用户侧制品 API 门面 + legacy 适配器（D22-5.1.3 起，codex 裁决 f0ee72cb）。
 *
 * <p>本服务不再是第二套自管存储实现：注册、索引、归属、清理的唯一权威是
 * {@link PersistentArtifactRegistry}；存储根一律经 D04 门面 {@link AgentStoragePaths}
 * 解析（原直连键 K3 {@code agent.artifact.storage.path} 与 K4
 * {@code agent.tools.market-data.dataset.path} 已摘除，双 legacy alias 收敛与
 * fail-closed 在门面内实现）。</p>
 *
 * <h3>list 面（Registry-first）</h3>
 * <ol>
 *   <li>重放 run 事件得出事件派生候选（python script / dataset 文件），沿用
 *       success-only 过滤与 retention 两档语义；</li>
 *   <li>对事件派生但尚未注册的制品做<b>惰性幂等注册</b>：脚本走
 *       {@link PersistentArtifactRegistry#registerIdempotent}（内容制品），
 *       dataset 文件走 {@link PersistentArtifactRegistry#registerExternalIdempotent}
 *       （原地引用，不复制文件、不双树写入）；重复 list 复用同一 artifactId；</li>
 *   <li>最终以 {@link PersistentArtifactRegistry#listByRunId} 为权威清单映射 DTO；
 *       事件派生项的 createdAt/expiresAt/metaJson 按事件侧重放值重建（零漂移）。</li>
 * </ol>
 *
 * <h3>load/download 面（Registry-first）</h3>
 * <p>先 {@link PersistentArtifactRegistry#find} + {@link PersistentArtifactRegistry#matchesOwnerStrict}
 * 归属校验；事件派生类型额外要求事件侧重放命中（retention/success-only 语义保持）。
 * 仅当 registry miss 时才允许对历史 Base64 {@code type|runId|ref} 格式 ID 做
 * <b>只读回退</b>：按旧快照位置读取内容，回退路径不写文件、不注册新制品。</p>
 *
 * <p>历史兼容：旧 Base64 ID 与旧快照树（{artifactRoot}/{runId}/scripts|datasets/…）
 * 保持只读可用；历史制品不搬迁、不删除。</p>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentArtifactService {

    private static final Pattern DATASET_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final String TYPE_PYTHON_SCRIPT = "python_script";

    /**
     * 事件派生制品类型：list 的 scope/retention 过滤与 load 的可见性判定都依赖事件重放，
     * 非事件派生制品（rawRef / tool-output / DatasetRegistry 注册项等）由 registry 自管生命周期。
     */
    private static final Set<String> EVENT_DERIVED_TYPES = Set.of(
            TYPE_PYTHON_SCRIPT, "dataset_csv", "dataset_json", "dataset_meta", "dataset_file");

    /** retention 配置为 <=0（永不过期）时的注册 TTL 兜底：meta TTL 无法表达无限，取一年上界。 */
    private static final long UNLIMITED_TTL_HOURS = 24L * 365L;

    private final AgentRunEventService eventService;
    /** DB event mapper；workspace v0 走 DB，不依赖 Redis 事件流。 */
    private final AgentRunEventMapper agentRunEventMapper;
    private final ObjectMapper objectMapper;
    /** D22-5.1.3：唯一权威制品注册表（注册 / 索引 / 归属 / 读取 / 清理均经此）。 */
    private final PersistentArtifactRegistry artifactRegistry;
    /** D04 统一存储门面：artifactRoot / datasetRoot（K3/K4 legacy alias 收敛在门面内）。 */
    private final AgentStoragePaths storagePaths;

    @Value("${agent.artifact.retention-days.normal:7}")
    private int normalRetentionDays;

    @Value("${agent.artifact.retention-days.admin:30}")
    private int adminRetentionDays;

    @Value("${agent.artifact.download.max-bytes:10485760}")
    private long downloadMaxBytes;

    public String extractRunId(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifact_id is required");
        }
        // Registry-first：注册制品直接取 meta.runId；仅历史 Base64 ID 走解码回退。
        try {
            Optional<PersistentArtifactMeta> meta = artifactRegistry.find(artifactId);
            if (meta.isPresent()) {
                String runId = meta.get().getRunId();
                if (runId != null && !runId.isBlank()) {
                    return runId;
                }
                throw new IllegalArgumentException("invalid artifact id");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            // registry 不可用时退回纯解码路径：legacy ID 不依赖 Redis；registry ID 会在解码处显式失败。
            log.warn("Artifact registry lookup failed during extractRunId, fallback to legacy decode: {}",
                    e.getMessage());
        }
        return decodeArtifactId(artifactId).runId();
    }

    public List<AgentArtifactMessage> listArtifacts(AgentRun run, boolean isAdmin) {
        return listArtifacts(run, isAdmin, true);
    }

    /**
     * 列出制品；管理员诊断采集可关闭事件派生制品的惰性注册，保证 GET 不写注册表。
     * 已经存在于注册表中的制品仍正常返回。
     */
    public List<AgentArtifactMessage> listArtifacts(AgentRun run,
                                                    boolean isAdmin,
                                                    boolean allowLazyRegistration) {
        String runId = run == null || run.getId() == null ? "" : run.getId();
        if (runId.isBlank()) {
            return List.of();
        }
        String userId = run.getUserId();

        List<EventDerivedCandidate> candidates = buildEventCandidates(
                run, isAdmin, !allowLazyRegistration);
        // 诊断读取关闭惰性注册时也必须关闭幽灵索引 ZREM，保证 Redis 严格只读。
        List<PersistentArtifactMeta> listed = artifactRegistry.listByRunId(
                runId, allowLazyRegistration);
        if (allowLazyRegistration && registerMissingEventDerived(runId, userId, candidates, listed)) {
            // 惰性注册后以 registry 为权威重读
            listed = artifactRegistry.listByRunId(runId);
        }

        Map<String, EventDerivedCandidate> candidateByIdentity = new HashMap<>();
        for (EventDerivedCandidate candidate : candidates) {
            candidateByIdentity.put(
                    PersistentArtifactRegistry.identityField(candidate.type(), candidate.logicalId(), null),
                    candidate);
        }

        List<RankedArtifact> ranked = new ArrayList<>();
        for (PersistentArtifactMeta meta : listed) {
            if (!PersistentArtifactRegistry.matchesOwnerStrict(meta, runId, userId)) {
                continue;
            }
            if (EVENT_DERIVED_TYPES.contains(meta.getArtifactType())) {
                // success-only 过滤：当前 scope 未重放出来的事件派生项不可见（normal 下的失败脚本、
                // 事件源缺失时的全部事件派生项——与降级前 resolveArtifacts 语义一致）。
                EventDerivedCandidate candidate = candidateByIdentity.get(
                        PersistentArtifactRegistry.identityField(
                                meta.getArtifactType(), meta.getLogicalId(), null));
                if (candidate == null) {
                    continue;
                }
                long createdAtMillis = candidate.createdAtMillis();
                long expiresAtMillis = calcExpiresAtMillis(createdAtMillis, isAdmin);
                if (isExpired(expiresAtMillis)) {
                    continue;
                }
                ranked.add(new RankedArtifact(toMessage(runId,
                        meta.getArtifactId(), meta.getArtifactType(), candidate.displayName(),
                        writeJson(buildEventDerivedMetaJson(candidate, isAdmin)),
                        createdAtMillis, expiresAtMillis), createdAtMillis));
            } else {
                long createdAtMillis = meta.getCreatedAtMillis() == null ? 0L : meta.getCreatedAtMillis();
                long expiresAtMillis = meta.getExpiresAtMillis() == null ? 0L : meta.getExpiresAtMillis();
                ranked.add(new RankedArtifact(toMessage(runId,
                        meta.getArtifactId(), meta.getArtifactType(), displayNameOf(meta),
                        writeJson(buildRegistryMetaJson(meta, isAdmin)),
                        createdAtMillis, expiresAtMillis), createdAtMillis));
            }
        }
        ranked.sort((a, b) -> Long.compare(b.createdAtMillis(), a.createdAtMillis()));
        List<AgentArtifactMessage> result = new ArrayList<>(ranked.size());
        for (RankedArtifact item : ranked) {
            result.add(item.message());
        }
        return result;
    }

    /**
     * 公开入口：收集指定 run 的 parsed events（python scripts + dataset ids）。
     *
     * <p>给 workspace dump pipeline 用。内部走 {@link #parseEvents} 走完整事件解析，
     * 然后映射成对外的 {@link ParsedEventsView}（避免把 internal record 暴露给跨模块 caller）。</p>
     *
     * <p>v0 走 {@link AgentRunEventMapper#listByRunId}（DB），不依赖 Redis 事件流
     * （Redis ZSET TTL 7 天，旧 run 解析会丢事件，dump 稳定性不可接受）。</p>
     *
     * @param run 目标 run
     * @return parsed events 公开视图
     */
    public ParsedEventsView collectParsedEvents(AgentRun run) {
        if (run == null || run.getId() == null || run.getId().isBlank()) {
            throw new IllegalArgumentException("run / runId 不能为空");
        }
        List<AgentRunEvent> events = agentRunEventMapper.listByRunId(run.getId());
        ParsedEvents parsed = parseEvents(events);
        List<PythonScript> scripts = new ArrayList<>();
        if (parsed.invocations() != null) {
            for (PythonInvocation invocation : parsed.invocations()) {
                scripts.add(new PythonScript(
                        invocation.ref(),
                        invocation.seq(),
                        invocation.createdAt(),
                        invocation.code(),
                        invocation.datasetIds() == null ? List.of() : new ArrayList<>(invocation.datasetIds()),
                        invocation.success(),
                        invocation.source()
                ));
            }
        }
        List<String> fallbackIds = parsed.fallbackDatasetIds() == null
                ? List.of() : new ArrayList<>(parsed.fallbackDatasetIds());
        return new ParsedEventsView(scripts, fallbackIds);
    }

    public ArtifactContent loadArtifact(AgentRun run, boolean isAdmin, String artifactId) {
        return loadArtifact(run, isAdmin, artifactId, true, false);
    }

    public ArtifactContent loadArtifactForParts(AgentRun run, boolean isAdmin, String artifactId) {
        // 管理员诊断分片必须严格只读：不刷新 registry retention，也不冲刷 pending 事件。
        return loadArtifact(run, isAdmin, artifactId, false, isAdmin);
    }

    private ArtifactContent loadArtifact(AgentRun run,
                                         boolean isAdmin,
                                         String artifactId,
                                         boolean enforceDownloadMaxBytes,
                                         boolean diagnosticNoTouch) {
        String runId = run == null || run.getId() == null ? "" : run.getId();
        String userId = run == null ? null : run.getUserId();

        // Registry-first：命中即走权威路径；事件派生类型额外要求事件侧重放命中
        // （retention 两档 + success-only 过滤语义保持，与降级前 resolveArtifacts 一致）。
        Optional<PersistentArtifactMeta> found = artifactRegistry.find(artifactId);
        if (found.isPresent()) {
            PersistentArtifactMeta meta = found.get();
            if (!PersistentArtifactRegistry.matchesOwnerStrict(meta, runId, userId)) {
                throw new IllegalArgumentException("artifact not found");
            }
            String filename;
            if (EVENT_DERIVED_TYPES.contains(meta.getArtifactType())) {
                EventDerivedCandidate candidate =
                        findCandidate(run, isAdmin, meta.getArtifactType(), meta.getLogicalId(),
                                isAdmin || diagnosticNoTouch);
                if (candidate == null) {
                    throw new IllegalArgumentException("artifact not found");
                }
                filename = candidate.displayName();
            } else {
                Long expiresAtMillis = meta.getExpiresAtMillis();
                if (expiresAtMillis != null && System.currentTimeMillis() > expiresAtMillis) {
                    throw new IllegalArgumentException("artifact not found");
                }
                filename = displayNameOf(meta);
            }
            byte[] bytes = readRegistryArtifact(meta, enforceDownloadMaxBytes, diagnosticNoTouch);
            return new ArtifactContent(meta.getArtifactId(), filename,
                    contentTypeFor(meta.getArtifactType()), bytes);
        }

        // Registry miss：仅历史 Base64 type|runId|ref ID 允许只读回退（不写文件、不注册）。
        return loadLegacyArtifact(run, isAdmin, artifactId, enforceDownloadMaxBytes,
                isAdmin || diagnosticNoTouch);
    }

    // ===== list 侧：事件派生候选 + 惰性幂等注册 =====

    /**
     * 事件派生候选：事件重放 + scope 过滤 + retention 过滤后的可注册项。
     *
     * <p>脚本候选携带代码内容（内容制品）；dataset 文件候选携带 dataset 根内的原位路径
     * （external 引用制品，注册不复制文件）。</p>
     */
    private List<EventDerivedCandidate> buildEventCandidates(AgentRun run,
                                                             boolean isAdmin,
                                                             boolean diagnosticNoTouch) {
        // 严格诊断读取必须绕过 Redis 事件投影，否则普通 listByRunId 会先 flush
        // 进程内 pending 并刷新 Redis TTL。
        List<AgentRunEvent> events = diagnosticNoTouch
                ? eventService.listByRunIdFromDatabase(run.getId())
                : eventService.listByRunId(run.getId());
        ParsedEvents parsed = parseEvents(events);

        List<PythonInvocation> selectedInvocations = selectInvocations(parsed.invocations(), isAdmin);
        LinkedHashSet<String> selectedDatasetIds = new LinkedHashSet<>();
        for (PythonInvocation invocation : selectedInvocations) {
            selectedDatasetIds.addAll(invocation.datasetIds());
        }
        selectedDatasetIds.addAll(parsed.fallbackDatasetIds());

        List<EventDerivedCandidate> candidates = new ArrayList<>();
        for (PythonInvocation invocation : selectedInvocations) {
            if (invocation.code() == null || invocation.code().isBlank()) {
                continue;
            }
            long createdAtMillis = toMillis(invocation.createdAt(), run);
            if (isExpired(calcExpiresAtMillis(createdAtMillis, isAdmin))) {
                continue;
            }
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("source", invocation.source());
            extras.put("seq", invocation.seq());
            extras.put("success", invocation.success());
            candidates.add(new EventDerivedCandidate(
                    TYPE_PYTHON_SCRIPT,
                    invocation.ref(),
                    sanitizeFileName(invocation.ref()) + ".py",
                    createdAtMillis,
                    invocation.code(),
                    null,
                    extras));
        }

        Path datasetRoot = storagePaths.datasetRoot();
        for (String datasetId : selectedDatasetIds) {
            if (datasetId == null || datasetId.isBlank()) {
                continue;
            }
            Path datasetDir = datasetRoot.resolve(datasetId).normalize();
            if (!datasetDir.startsWith(datasetRoot)) {
                continue;
            }
            addDatasetFileCandidates(candidates, run.getId(), datasetId, datasetDir, isAdmin);
        }
        return candidates;
    }

    private void addDatasetFileCandidates(List<EventDerivedCandidate> candidates,
                                          String runId,
                                          String datasetId,
                                          Path datasetDir,
                                          boolean isAdmin) {
        try {
            if (!Files.exists(datasetDir) || !Files.isDirectory(datasetDir)) {
                return;
            }
            try (Stream<Path> stream = Files.list(datasetDir)) {
                stream
                        .filter(Files::isRegularFile)
                        .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                        .forEach(file -> {
                            try {
                                if (!Files.exists(file) || !Files.isRegularFile(file)) {
                                    return;
                                }
                                long createdAtMillis = Files.getLastModifiedTime(file).toMillis();
                                if (isExpired(calcExpiresAtMillis(createdAtMillis, isAdmin))) {
                                    return;
                                }
                                String fileName = file.getFileName().toString();
                                DatasetFileKind kind = resolveDatasetFileKind(datasetId, fileName);
                                Map<String, Object> extras = new LinkedHashMap<>();
                                extras.put("dataset_id", datasetId);
                                extras.put("file_name", fileName);
                                extras.put("format", datasetFormat(fileName, kind.type()));
                                candidates.add(new EventDerivedCandidate(
                                        kind.type(),
                                        canonicalDatasetArtifactRef(datasetId, fileName, kind.type()),
                                        fileName,
                                        createdAtMillis,
                                        null,
                                        file,
                                        extras));
                            } catch (Exception e) {
                                log.warn("Resolve dataset artifact candidate failed: runId={}, datasetId={}, file={}",
                                        runId, datasetId, file, e);
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("Resolve dataset artifact candidates failed: runId={}, datasetId={}, dir={}",
                    runId, datasetId, datasetDir, e);
        }
    }

    /**
     * 惰性幂等注册：只注册 run 索引中尚不存在的候选。
     *
     * <p>幂等身份 (runId|type|logicalId[|path]) 由 registry 的 HSETNX 原子抢占兜底，
     * 重复 list / 并发 list / 重启后 list 均不产生新 artifactId、不重写文件。
     * 注册失败（如 Redis 不可达）warn-degrade：该候选本次 list 缺席，下次 list 重试。</p>
     *
     * @return 是否发生过注册尝试（调用方据此重读权威清单）
     */
    private boolean registerMissingEventDerived(String runId,
                                                String userId,
                                                List<EventDerivedCandidate> candidates,
                                                List<PersistentArtifactMeta> alreadyListed) {
        if (candidates.isEmpty()) {
            return false;
        }
        Set<String> registeredIdentities = new HashSet<>();
        for (PersistentArtifactMeta meta : alreadyListed) {
            registeredIdentities.add(registryIdentityOf(meta));
        }
        long ttlHours = registrationTtlHours();
        boolean registered = false;
        for (EventDerivedCandidate candidate : candidates) {
            String identity = candidateIdentityOf(candidate);
            if (registeredIdentities.contains(identity)) {
                continue;
            }
            registered = true;
            try {
                if (candidate.externalPath() == null) {
                    artifactRegistry.registerIdempotent(runId, userId, candidate.type(),
                            candidate.logicalId(), candidate.displayName(),
                            candidate.scriptContent(), ttlHours);
                } else {
                    artifactRegistry.registerExternalIdempotent(runId, userId, candidate.type(),
                            candidate.logicalId(), candidate.displayName(),
                            candidate.externalPath(), ttlHours);
                }
            } catch (Exception e) {
                log.warn("Lazy artifact registration failed: runId={}, type={}, logicalId={}, err={}",
                        runId, candidate.type(), candidate.logicalId(), e.getMessage());
            }
        }
        return registered;
    }

    /** 幂等身份与 registry 共用唯一 collision-free 编码（{@link PersistentArtifactRegistry#identityField}）。 */
    private static String candidateIdentityOf(EventDerivedCandidate candidate) {
        return PersistentArtifactRegistry.identityField(candidate.type(), candidate.logicalId(),
                candidate.externalPath() == null ? null
                        : candidate.externalPath().toAbsolutePath().normalize().toString());
    }

    private static String registryIdentityOf(PersistentArtifactMeta meta) {
        return PersistentArtifactRegistry.identityField(meta.getArtifactType(), meta.getLogicalId(),
                Boolean.TRUE.equals(meta.getExternal()) ? meta.getPath() : null);
    }

    /** 注册 TTL 取两档 retention 的长档；配置 <=0（永不过期）时取一年上界。 */
    private long registrationTtlHours() {
        long days = Math.max(normalRetentionDays, adminRetentionDays);
        if (days <= 0) {
            return UNLIMITED_TTL_HOURS;
        }
        return days * 24L;
    }

    private EventDerivedCandidate findCandidate(AgentRun run,
                                                boolean isAdmin,
                                                String type,
                                                String logicalId,
                                                boolean diagnosticNoTouch) {
        if (run == null || run.getId() == null || type == null || logicalId == null) {
            return null;
        }
        for (EventDerivedCandidate candidate : buildEventCandidates(run, isAdmin, diagnosticNoTouch)) {
            if (type.equals(candidate.type()) && logicalId.equals(candidate.logicalId())) {
                return candidate;
            }
        }
        return null;
    }

    // ===== load 侧：registry 读取 + legacy 只读回退 =====

    private byte[] readRegistryArtifact(PersistentArtifactMeta meta,
                                        boolean enforceDownloadMaxBytes,
                                        boolean diagnosticNoTouch) {
        if (meta.getPath() == null || meta.getPath().isBlank()) {
            throw new IllegalArgumentException("artifact source not found");
        }
        // D22 MUST-FIX ④：内容/external 一律经 registry 权威读取——读前 realpath containment
        // 复检 + no-follow 打开 + 哈希校验（TOCTOU 强化），门面不再直读 meta.path。
        long maxBytes = enforceDownloadMaxBytes ? downloadMaxBytes : -1L;
        try {
            return artifactRegistry.readArtifactBytes(
                    meta.getArtifactId(), maxBytes, !diagnosticNoTouch);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("read artifact failed", e);
        }
    }

    /**
     * 历史 Base64 {@code type|runId|ref} ID 的只读回退：按旧快照位置
     * （{artifactRoot}/{runId}/scripts|datasets/…）读取内容。
     * 回退路径绝不写文件、绝不注册新制品；retention/success-only 语义经事件候选重放保持。
     */
    private ArtifactContent loadLegacyArtifact(AgentRun run, boolean isAdmin, String artifactId,
                                               boolean enforceDownloadMaxBytes,
                                               boolean diagnosticNoTouch) {
        ArtifactRef ref = decodeArtifactId(artifactId);
        String runId = run == null || run.getId() == null ? "" : run.getId();
        if (!Objects.equals(ref.runId(), runId)) {
            throw new IllegalArgumentException("artifact not found");
        }
        boolean script = "script".equals(ref.type());
        String type = script ? TYPE_PYTHON_SCRIPT : ref.type();
        if (!EVENT_DERIVED_TYPES.contains(type)) {
            throw new IllegalArgumentException("artifact not found");
        }
        // retention/success-only 保持：当前 scope 重放不出该候选即视为 not found（与降级前一致）
        EventDerivedCandidate candidate = findCandidate(
                run, isAdmin, type, ref.ref(), diagnosticNoTouch);
        if (candidate == null) {
            throw new IllegalArgumentException("artifact not found");
        }
        Path file = script
                ? legacyScriptPath(runId, ref.ref())
                : legacyDatasetPath(runId, ref.type(), ref.ref());
        byte[] bytes = readLegacyFile(file, enforceDownloadMaxBytes);
        return new ArtifactContent(artifactId, file.getFileName().toString(), contentTypeFor(type), bytes);
    }

    /** 旧脚本快照位置（只读）：{artifactRoot}/{runId}/scripts/{sanitizedRef}.py。 */
    private Path legacyScriptPath(String runId, String ref) {
        Path runDir = legacyRunDir(runId);
        return runDir.resolve("scripts").resolve(sanitizeFileName(ref) + ".py").normalize();
    }

    /** 旧 dataset 快照副本位置（只读）：{artifactRoot}/{runId}/datasets/{datasetId}/{fileName}。 */
    private Path legacyDatasetPath(String runId, String type, String ref) {
        int slash = ref.indexOf('/');
        String datasetId = slash < 0 ? ref : ref.substring(0, slash);
        String fileName;
        if (slash >= 0) {
            fileName = ref.substring(slash + 1);
        } else if ("dataset_csv".equals(type)) {
            fileName = datasetId + ".csv";
        } else if ("dataset_meta".equals(type)) {
            fileName = datasetId + ".meta.json";
        } else if ("dataset_json".equals(type)) {
            fileName = datasetId + ".json";
        } else {
            throw new IllegalArgumentException("invalid artifact id");
        }
        Path runDir = legacyRunDir(runId);
        Path file = runDir.resolve("datasets").resolve(datasetId).resolve(fileName).normalize();
        if (!file.startsWith(runDir)) {
            throw new IllegalArgumentException("invalid artifact id");
        }
        return file;
    }

    private Path legacyRunDir(String runId) {
        Path baseDir = storagePaths.artifactRoot().toAbsolutePath().normalize();
        Path runDir = baseDir.resolve(runId).normalize();
        if (!runDir.startsWith(baseDir)) {
            throw new IllegalArgumentException("invalid run id path");
        }
        return runDir;
    }

    private byte[] readLegacyFile(Path file, boolean enforceDownloadMaxBytes) {
        // D22 MUST-FIX ④：legacy 快照读取收回 registry 权威 seam；门面侧先做 no-follow
        // 常规文件预检（symlink/目录 fail-closed），再经 registry 复检读取。
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("artifact not found");
        }
        long maxBytes = enforceDownloadMaxBytes ? downloadMaxBytes : -1L;
        try {
            return artifactRegistry.readWithinArtifactRoot(file, maxBytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("artifact not found");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("read artifact failed", e);
        }
    }

    // ===== DTO 映射（字段零漂移） =====

    private AgentArtifactMessage toMessage(String runId,
                                           String artifactId,
                                           String type,
                                           String name,
                                           String metaJson,
                                           long createdAtMillis,
                                           long expiresAtMillis) {
        return AgentArtifactMessage.newBuilder()
                .setArtifactId(artifactId)
                .setType(type)
                .setName(name)
                .setContentType(contentTypeFor(type))
                .setUrl("/api/agent/runs/" + runId + "/artifacts/" + artifactId + "/download")
                .setMetaJson(metaJson)
                .setCreatedAt(formatTime(toOffsetDateTime(createdAtMillis)))
                .setExpiresAtMillis(expiresAtMillis)
                .build();
    }

    private static String contentTypeFor(String artifactType) {
        String type = artifactType == null ? "" : artifactType;
        switch (type) {
            case TYPE_PYTHON_SCRIPT:
                return "text/x-python";
            case "dataset_csv":
                return "text/csv";
            case "dataset_json":
            case "dataset_meta":
                return "application/json";
            default:
                return "application/octet-stream";
        }
    }

    private static String displayNameOf(PersistentArtifactMeta meta) {
        if (meta.getDisplayName() != null && !meta.getDisplayName().isBlank()) {
            return meta.getDisplayName();
        }
        if (meta.getPath() != null && !meta.getPath().isBlank()) {
            try {
                Path fileName = Path.of(meta.getPath()).getFileName();
                if (fileName != null) {
                    return fileName.toString();
                }
            } catch (Exception ignored) {
                // 非法路径回退 artifactId
            }
        }
        return meta.getArtifactId();
    }

    private Map<String, Object> buildEventDerivedMetaJson(EventDerivedCandidate candidate, boolean isAdmin) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", candidate.type());
        meta.put("scope", isAdmin ? "admin" : "normal");
        meta.putAll(candidate.metaExtras());
        return meta;
    }

    private Map<String, Object> buildRegistryMetaJson(PersistentArtifactMeta meta, boolean isAdmin) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", meta.getArtifactType());
        result.put("scope", isAdmin ? "admin" : "normal");
        return result;
    }

    // ===== 事件解析（与降级前一致，未改动） =====

    private ParsedEvents parseEvents(List<AgentRunEvent> events) {
        List<MutableInvocation> invocations = new ArrayList<>();
        Map<String, MutableInvocation> invocationByRef = new HashMap<>();
        LinkedHashSet<String> fallbackDatasetIds = new LinkedHashSet<>();
        List<String> pendingToolRefs = new ArrayList<>();
        Map<String, String> pendingParallelRefsByTask = new HashMap<>();
        Map<String, Map<String, Object>> parallelExecuteArgsByTask = new HashMap<>();
        Map<String, Map<String, Object>> todoExecuteArgsByTodo = new HashMap<>();
        Map<String, String> pendingTodoRefsByTodoId = new HashMap<>();
        for (AgentRunEvent event : events) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            Map<String, Object> payload = readJsonMap(event.getPayloadJson());
            String eventType = event.getEventType();
            collectDatasetIds(fallbackDatasetIds, readAsString(payload.get("result_preview")));
            collectDatasetIds(fallbackDatasetIds, readAsString(payload.get("output_preview")));

            if ("PLAN_CREATED".equals(eventType)) {
                collectParallelExecutePythonArgs(payload, parallelExecuteArgsByTask);
                continue;
            }

            if ("TODO_LIST_CREATED".equals(eventType)) {
                collectTodoExecutePythonArgs(payload, todoExecuteArgsByTodo);
                continue;
            }

            if ("TOOL_CALL_STARTED".equals(eventType)) {
                String toolName = readAsString(payload.get("tool_name"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                Map<String, Object> params = readNestedMap(payload.get("parameters"));
                MutableInvocation invocation = createInvocation(
                        "tool-" + safeSeq(event),
                        safeSeq(event),
                        event.getCreatedAt(),
                        "TOOL_CALL",
                        extractCode(params),
                        extractDatasetIds(params),
                        null
                );
                invocations.add(invocation);
                invocationByRef.put(invocation.ref, invocation);
                pendingToolRefs.add(invocation.ref);
                continue;
            }

            if ("TOOL_CALL_FINISHED".equals(eventType)) {
                String toolName = readAsString(payload.get("tool_name"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                String preview = readAsString(payload.get("result_preview"));
                JsonNode outputNode = parseToolOutput(preview);
                // FIFO pairing of STARTED/FINISHED by event order.
                // Parallel executePython may interleave; precise match would need tool_execution_id in events.
                if (!pendingToolRefs.isEmpty()) {
                    String ref = pendingToolRefs.remove(0);
                    MutableInvocation invocation = invocationByRef.get(ref);
                    if (invocation != null) {
                        invocation.success = toNullableBoolean(payload.get("success"));
                        if (invocation.success == null) {
                            invocation.success = isToolOutputSuccess(outputNode);
                        }
                        invocation.datasetIds.addAll(extractDatasetIdsFromToolOutput(outputNode));
                    }
                }
                continue;
            }

            if ("PARALLEL_TASK_STARTED".equals(eventType)) {
                String toolName = readAsString(payload.get("tool"));
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                String taskId = readAsString(payload.get("task_id"));
                Map<String, Object> taskArgs = parallelExecuteArgsByTask.getOrDefault(taskId, Map.of());
                MutableInvocation invocation = createInvocation(
                        "parallel-" + taskId + "-" + safeSeq(event),
                        safeSeq(event),
                        event.getCreatedAt(),
                        "PARALLEL_TASK",
                        extractCode(taskArgs),
                        extractDatasetIds(taskArgs),
                        null
                );
                invocations.add(invocation);
                invocationByRef.put(invocation.ref, invocation);
                pendingParallelRefsByTask.put(taskId, invocation.ref);
                continue;
            }

            if ("PARALLEL_TASK_FINISHED".equals(eventType)) {
                String taskId = readAsString(payload.get("task_id"));
                String preview = readAsString(payload.get("output_preview"));
                JsonNode outputNode = parseToolOutput(preview);
                String ref = pendingParallelRefsByTask.remove(taskId);
                if (ref != null) {
                    MutableInvocation invocation = invocationByRef.get(ref);
                    if (invocation != null) {
                        invocation.success = toNullableBoolean(payload.get("success"));
                        if (invocation.success == null) {
                            invocation.success = isToolOutputSuccess(outputNode);
                        }
                        invocation.datasetIds.addAll(extractDatasetIdsFromToolOutput(outputNode));
                    }
                }
                continue;
            }

            if ("TODO_STARTED".equals(eventType)) {
                String toolName = firstNonBlank(
                        readAsString(payload.get("tool")),
                        readAsString(payload.get("tool_name"))
                );
                if (!"executePython".equals(toolName)) {
                    continue;
                }
                String todoId = readAsString(payload.get("todo_id"));
                Map<String, Object> todoArgs = todoExecuteArgsByTodo.getOrDefault(todoId, Map.of());
                MutableInvocation invocation = createInvocation(
                        "todo-" + todoId + "-" + safeSeq(event),
                        safeSeq(event),
                        event.getCreatedAt(),
                        "TODO_TASK",
                        extractCode(todoArgs),
                        extractDatasetIds(todoArgs),
                        null
                );
                invocations.add(invocation);
                invocationByRef.put(invocation.ref, invocation);
                pendingTodoRefsByTodoId.put(todoId, invocation.ref);
                continue;
            }

            if ("TODO_FINISHED".equals(eventType) || "TODO_FAILED".equals(eventType)) {
                String todoId = readAsString(payload.get("todo_id"));
                String ref = pendingTodoRefsByTodoId.remove(todoId);
                if (ref == null) {
                    continue;
                }
                String preview = firstNonBlank(
                        readAsString(payload.get("output_preview")),
                        readAsString(payload.get("result_preview")),
                        readAsString(payload.get("summary"))
                );
                JsonNode outputNode = parseToolOutput(preview);
                MutableInvocation invocation = invocationByRef.get(ref);
                if (invocation != null) {
                    invocation.success = toNullableBoolean(payload.get("success"));
                    if (invocation.success == null) {
                        invocation.success = isToolOutputSuccess(outputNode);
                    }
                    invocation.datasetIds.addAll(extractDatasetIdsFromToolOutput(outputNode));
                }
                continue;
            }

            if ("SUB_AGENT_PYTHON_REFINED".equals(eventType)) {
                List<Map<String, Object>> traces = readMapList(payload.get("traces"));
                String taskId = readAsString(payload.get("task_id"));
                String stepIndex = readAsString(payload.get("step_index"));
                for (Map<String, Object> trace : traces) {
                    String code = firstNonBlank(
                            readAsString(trace.get("code")),
                            readAsString(trace.get("code_preview"))
                    );
                    Map<String, Object> runArgs = readNestedMap(
                            trace.get("run_args"),
                            trace.get("run_args_preview")
                    );
                    LinkedHashSet<String> datasetIds = new LinkedHashSet<>(extractDatasetIds(runArgs));
                    String outputPreview = readAsString(trace.get("output_preview"));
                    datasetIds.addAll(extractDatasetIdsFromToolOutput(parseToolOutput(outputPreview)));

                    int attempt = toInt(trace.get("attempt"), 0);
                    MutableInvocation invocation = createInvocation(
                            "subtrace-" + taskId + "-" + stepIndex + "-" + attempt + "-" + safeSeq(event),
                            safeSeq(event),
                            event.getCreatedAt(),
                            "SUB_AGENT_TRACE",
                            code,
                            datasetIds,
                            toNullableBoolean(trace.get("success"))
                    );
                    invocations.add(invocation);
                }
            }
        }

        List<PythonInvocation> stableInvocations = new ArrayList<>();
        for (MutableInvocation invocation : invocations) {
            if (invocation.code == null || invocation.code.isBlank()) {
                continue;
            }
            stableInvocations.add(new PythonInvocation(
                    invocation.ref,
                    invocation.seq,
                    invocation.createdAt,
                    invocation.code,
                    new ArrayList<>(invocation.datasetIds),
                    invocation.success,
                    invocation.source
            ));
        }
        return new ParsedEvents(stableInvocations, new ArrayList<>(fallbackDatasetIds));
    }

    private List<PythonInvocation> selectInvocations(List<PythonInvocation> invocations, boolean isAdmin) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (isAdmin) {
            return invocations;
        }
        List<PythonInvocation> success = new ArrayList<>();
        for (PythonInvocation invocation : invocations) {
            if (Boolean.TRUE.equals(invocation.success())) {
                success.add(invocation);
            }
        }
        return success;
    }

    private MutableInvocation createInvocation(String ref,
                                               int seq,
                                               OffsetDateTime createdAt,
                                               String source,
                                               String code,
                                               LinkedHashSet<String> datasetIds,
                                               Boolean success) {
        MutableInvocation invocation = new MutableInvocation();
        invocation.ref = ref;
        invocation.seq = seq;
        invocation.createdAt = createdAt;
        invocation.source = source;
        invocation.code = code == null ? "" : code;
        invocation.datasetIds = datasetIds == null ? new LinkedHashSet<>() : datasetIds;
        invocation.success = success;
        return invocation;
    }

    private void collectParallelExecutePythonArgs(Map<String, Object> payload,
                                                  Map<String, Map<String, Object>> parallelExecuteArgsByTask) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> plan = readNestedMap(payload.get("plan"));
        if (plan.isEmpty()) {
            return;
        }
        for (Map<String, Object> task : readMapList(plan.get("tasks"))) {
            if (!"executePython".equals(readAsString(task.get("tool")))) {
                continue;
            }
            String taskId = readAsString(task.get("id"));
            if (taskId.isBlank()) {
                continue;
            }
            parallelExecuteArgsByTask.put(taskId, readNestedMap(task.get("args")));
        }
    }

    private void collectTodoExecutePythonArgs(Map<String, Object> payload,
                                              Map<String, Map<String, Object>> todoExecuteArgsByTodo) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> plan = readNestedMap(payload.get("plan"));
        if (plan.isEmpty()) {
            return;
        }
        for (Map<String, Object> item : readMapList(plan.get("items"))) {
            if (!"executePython".equals(readAsString(item.get("toolName")))) {
                continue;
            }
            String todoId = readAsString(item.get("id"));
            if (todoId.isBlank()) {
                continue;
            }
            todoExecuteArgsByTodo.put(todoId, readNestedMap(item.get("params")));
        }
    }

    private DatasetFileKind resolveDatasetFileKind(String datasetId, String fileName) {
        String safeFileName = fileName == null ? "" : fileName;
        if (safeFileName.equals(datasetId + ".meta.json") || safeFileName.endsWith(".meta.json")) {
            return new DatasetFileKind("dataset_meta", "application/json");
        }
        if (safeFileName.endsWith(".csv")) {
            return new DatasetFileKind("dataset_csv", "text/csv");
        }
        if (safeFileName.endsWith(".json")) {
            return new DatasetFileKind("dataset_json", "application/json");
        }
        return new DatasetFileKind("dataset_file", "application/octet-stream");
    }

    private String canonicalDatasetArtifactRef(String datasetId, String fileName, String type) {
        if (fileName.equals(datasetId + ".csv") && "dataset_csv".equals(type)) {
            return datasetId;
        }
        if (fileName.equals(datasetId + ".meta.json") && "dataset_meta".equals(type)) {
            return datasetId;
        }
        if (fileName.equals(datasetId + ".json") && "dataset_json".equals(type)) {
            return datasetId;
        }
        return datasetId + "/" + fileName;
    }

    private String datasetFormat(String fileName, String type) {
        if ("dataset_meta".equals(type)) {
            return "meta";
        }
        if (fileName.endsWith(".csv")) {
            return "csv";
        }
        if (fileName.endsWith(".json")) {
            return "json";
        }
        return "file";
    }

    private void collectDatasetIds(LinkedHashSet<String> target, String text) {
        if (target == null || text == null || text.isBlank()) {
            return;
        }
        target.addAll(extractDatasetIdsFromToolOutput(parseToolOutput(text)));
    }

    private LinkedHashSet<String> extractDatasetIds(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return mergeDatasetIds(
                firstNonBlank(
                        readAsString(args.get("dataset_id")),
                        readAsString(args.get("datasetId")),
                        readAsString(args.get("arg1"))
                ),
                firstNonBlank(
                        readAsString(args.get("dataset_ids")),
                        readAsString(args.get("datasetIds")),
                        readAsString(args.get("arg2"))
                )
        );
    }

    private String extractCode(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        return firstNonBlank(
                readAsString(args.get("code")),
                readAsString(args.get("arg0"))
        );
    }

    private LinkedHashSet<String> extractDatasetIdsFromToolOutput(JsonNode root) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return ids;
        }
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            return ids;
        }

        String primary = readDatasetId(data.path("dataset_id"));
        if (!primary.isBlank()) {
            ids.add(primary);
        }

        JsonNode datasetIdsNode = data.path("dataset_ids");
        if (datasetIdsNode.isArray()) {
            for (JsonNode item : datasetIdsNode) {
                String id = readDatasetId(item);
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        } else if (datasetIdsNode.isTextual()) {
            ids.addAll(mergeDatasetIds("", datasetIdsNode.asText("")));
        }
        return ids;
    }

    private String readDatasetId(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String id = node.asText("");
        if (id == null || id.isBlank()) {
            return "";
        }
        String cleaned = id.trim();
        if (!DATASET_ID_PATTERN.matcher(cleaned).matches()) {
            return "";
        }
        return cleaned;
    }

    private JsonNode parseToolOutput(String text) {
        if (text == null || text.isBlank()) {
            return objectMapper.getNodeFactory().missingNode();
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().missingNode();
        }
    }

    private boolean isToolOutputSuccess(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (!root.has("ok")) {
            return false;
        }
        return root.path("ok").asBoolean(false);
    }

    private Boolean toNullableBoolean(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return toNullableBoolean(node.asText(""));
        }
        return null;
    }

    private Boolean toNullableBoolean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        return null;
    }

    private Boolean toNullableBoolean(Object value) {
        if (value instanceof JsonNode node) {
            return toNullableBoolean(node);
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return toNullableBoolean(String.valueOf(value).trim());
    }

    private int safeSeq(AgentRunEvent event) {
        return event == null || event.getSeq() == null ? 0 : event.getSeq();
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Map<String, Object> readNestedMap(Object obj) {
        if (obj == null) {
            return Map.of();
        }
        if (obj instanceof Map<?, ?> raw) {
            Map<String, Object> m = new HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                m.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return m;
        }
        if (obj instanceof String text && !text.isBlank()) {
            return readJsonMap(text);
        }
        return Map.of();
    }

    private Map<String, Object> readNestedMap(Object... candidates) {
        if (candidates == null || candidates.length == 0) {
            return Map.of();
        }
        for (Object candidate : candidates) {
            Map<String, Object> parsed = readNestedMap(candidate);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return Map.of();
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private LinkedHashSet<String> mergeDatasetIds(String primary, String others) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            String cleaned = primary.trim();
            if (DATASET_ID_PATTERN.matcher(cleaned).matches()) {
                ids.add(cleaned);
            }
        }
        if (others != null && !others.isBlank()) {
            String normalized = others.trim();
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            String[] parts = normalized.split(",");
            for (String part : parts) {
                String id = part == null ? "" : part.trim();
                if (id.startsWith("\"") && id.endsWith("\"") && id.length() >= 2) {
                    id = id.substring(1, id.length() - 1).trim();
                }
                if (!id.isBlank() && DATASET_ID_PATTERN.matcher(id).matches()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private List<Map<String, Object>> readMapList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof List<?> rawList) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : rawList) {
                Map<String, Object> map = readNestedMap(item);
                if (!map.isEmpty()) {
                    result.add(map);
                }
            }
            return result;
        }
        return List.of();
    }

    private String readAsString(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "artifact";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private ArtifactRef decodeArtifactId(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifact_id is required");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(artifactId), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 3);
            if (parts.length != 3 || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid artifact id");
            }
            return new ArtifactRef(parts[0], parts[1], parts[2]);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid artifact id");
        }
    }

    private long calcExpiresAtMillis(long createdAtMillis, boolean isAdmin) {
        int days = isAdmin ? adminRetentionDays : normalRetentionDays;
        if (days <= 0) {
            return Long.MAX_VALUE;
        }
        return createdAtMillis + days * 24L * 60L * 60L * 1000L;
    }

    private boolean isExpired(long expiresAtMillis) {
        return expiresAtMillis != Long.MAX_VALUE && System.currentTimeMillis() > expiresAtMillis;
    }

    private long toMillis(OffsetDateTime time, AgentRun run) {
        if (time != null) {
            return time.toInstant().toEpochMilli();
        }
        if (run != null && run.getUpdatedAt() != null) {
            return run.getUpdatedAt().toInstant().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    private OffsetDateTime toOffsetDateTime(long epochMillis) {
        return OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private String formatTime(OffsetDateTime time) {
        if (time == null) {
            return "";
        }
        return TIME_FORMATTER.format(time);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ParsedEvents(List<PythonInvocation> invocations, List<String> fallbackDatasetIds) {
    }

    private record PythonInvocation(String ref,
                                    int seq,
                                    OffsetDateTime createdAt,
                                    String code,
                                    List<String> datasetIds,
                                    Boolean success,
                                    String source) {
    }

    private record ArtifactRef(String type, String runId, String ref) {
    }

    private record DatasetFileKind(String type, String contentType) {
    }

    /** 事件派生候选：脚本携带内容（内容制品），dataset 文件携带原位路径（external 引用制品）。 */
    private record EventDerivedCandidate(String type,
                                         String logicalId,
                                         String displayName,
                                         long createdAtMillis,
                                         String scriptContent,
                                         Path externalPath,
                                         Map<String, Object> metaExtras) {
    }

    /** list 排序辅助：createdAt 降序（与降级前一致）。 */
    private record RankedArtifact(AgentArtifactMessage message, long createdAtMillis) {
    }

    private static class MutableInvocation {
        private String ref;
        private int seq;
        private OffsetDateTime createdAt;
        private String source;
        private String code;
        private LinkedHashSet<String> datasetIds = new LinkedHashSet<>();
        private Boolean success;
    }

    public record ArtifactContent(String artifactId, String filename, String contentType, byte[] content) {
    }

    /**
     * 给 workspace 用的 parsed events 投影（避免把 internal ParsedEvents 暴露给跨模块 caller）。
     *
     * @param pythonScripts       python invocation 列表
     * @param fallbackDatasetIds  fallback 阶段抓到的 dataset id 列表
     */
    public record ParsedEventsView(
            List<PythonScript> pythonScripts,
            List<String> fallbackDatasetIds) {
    }

    /**
     * python invocation 公开投影。
     */
    public record PythonScript(
            String ref,
            int seq,
            OffsetDateTime createdAt,
            String code,
            List<String> datasetIds,
            Boolean success,
            String source) {
    }
}
