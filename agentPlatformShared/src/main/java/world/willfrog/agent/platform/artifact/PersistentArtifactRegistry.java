package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 持久制品唯一权威注册表（D22-5.1.3 起）。
 *
 * <p>职责：artifact 的注册、元数据（Redis）、文件落盘、run 级有界索引、TTL 清理与
 * 路径哈希校验。{@link world.willfrog.agent.platform.service.AgentArtifactService}
 * 降级为 user API 门面 + 历史兼容适配器后，所有制品（rawRef / external / 脚本 / 数据集）
 * 的注册与 list/load 权威均在本网关。</p>
 *
 * <h3>D22-5.1.3：显式上下文入口</h3>
 * <ul>
 *   <li>{@link #registerExplicit} / {@link #registerExternalExplicit}：非幂等，runId/userId
 *       显式传入，不依赖 {@link AgentContext} 线程态；每次调用生成新 artifactId。</li>
 *   <li>{@link #registerIdempotent} / {@link #registerExternalIdempotent}：幂等，稳定身份
 *       (runId|type|logicalId[|path]) 经 Redis hash HSETNX 原子抢占；重复注册（重复 list、
 *       重启后 list、admin/normal 双 list）返回同一 artifactId，零重写、零重复项。</li>
 *   <li>{@link #listByRunId}：run 级有界索引（SET），只读，不生成新 ID。</li>
 * </ul>
 *
 * <h3>Redis 结构</h3>
 * <ul>
 *   <li>{@code agent:persistent-artifact:{artifactId}} — meta JSON，TTL = ttlHours；</li>
 *   <li>{@code agent:persistent-artifact:run-list:{runId}} — SET，run 的 artifactId 索引，
 *       上限 {@code agent.persistent-artifact.run-list-cap}（默认 1000）；</li>
 *   <li>{@code agent:persistent-artifact:run-identity:{runId}} — hash，幂等身份
 *       field={@code type|logicalId[|path]} → artifactId。</li>
 * </ul>
 * 注意：后两类键与 meta 共享 {@link #META_PREFIX} 前缀，cleanup 的 SCAN 会命中它们，
 * 循环内按前缀显式跳过（它们不是 meta JSON）。
 *
 * <h3>D22-5.1.3：external 路径门槛</h3>
 * <p>external 制品只允许落在 D04 批准根内（artifactRoot 或 datasetRoot），
 * 规范化后做 containment 校验；已存在的路径额外解析真实路径，拒绝 symlink 逃逸。</p>
 *
 * <h3>兼容语义</h3>
 * <p>旧 {@link #register}/{@link #registerExternal} 入口保留为有界兼容 delegate：
 * runId/userId 从 {@link AgentContext} 线程态补齐后转调显式入口。清理（cleanup）
 * 在删除 meta + 文件的同时移除 run 索引与幂等身份字段（同删，不留悬挂引用）。</p>
 *
 * @author wang
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersistentArtifactRegistry {

    private static final String META_PREFIX = "agent:persistent-artifact:";

    /** run 级 artifactId 索引（SET）。与 meta 共享前缀，cleanup SCAN 时按前缀跳过。 */
    private static final String RUN_LIST_KEY_PREFIX = META_PREFIX + "run-list:";

    /** 幂等身份索引（hash：field type|logicalId[|path] → artifactId）。同上，SCAN 跳过。 */
    private static final String RUN_IDENTITY_KEY_PREFIX = META_PREFIX + "run-identity:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * D04：artifact 根经统一存储门面解析（新键 agent.storage.artifact-root，
     * 旧键别名 agent.persistent-artifact.root，默认 /data/agent_artifacts）。
     */
    private final AgentStoragePaths storagePaths;

    @Value("${agent.persistent-artifact.ttl-hours:12}")
    private long defaultTtlHours;

    @Value("${agent.persistent-artifact.cleanup-scan-count:500}")
    private int cleanupScanCount;

    /** run 级索引上限：超过后新制品仍可注册/按 ID 访问，只是不进入 listByRunId 索引。 */
    @Value("${agent.persistent-artifact.run-list-cap:1000}")
    private int maxRunListEntries;

    // ===== 兼容入口（有界 delegate：AgentContext 补上下文后转显式入口） =====

    public PersistentArtifactRegistration register(String artifactType,
                                                   String logicalId,
                                                   String displayName,
                                                   String content) {
        return registerExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, content, defaultTtlHours);
    }

    public PersistentArtifactRegistration register(String artifactType,
                                                   String logicalId,
                                                   String displayName,
                                                   String content,
                                                   long ttlHours) {
        return registerExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, content, ttlHours);
    }

    public PersistentArtifactRegistration registerExternal(String artifactType,
                                                           String logicalId,
                                                           String displayName,
                                                           Path path,
                                                           long ttlHours) {
        return registerExternalExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, path, ttlHours, false);
    }

    public PersistentArtifactRegistration registerExternal(String artifactType,
                                                           String logicalId,
                                                           String displayName,
                                                           Path path,
                                                           long ttlHours,
                                                           boolean cleanupPath) {
        return registerExternalExplicit(AgentContext.getRunId(), AgentContext.getUserId(),
                artifactType, logicalId, displayName, path, ttlHours, cleanupPath);
    }

    // ===== D22-5.1.3：显式上下文入口 =====

    /**
     * 非幂等注册（显式上下文）：每次调用生成新 artifactId。
     *
     * <p>适用于每次调用本就产生新制品的桥接方（如 RunRawRefStore 的逐条 rawRef——
     * 其 logicalId 固定为 runId，绝不能走幂等路径，否则同 run 第二条即撞 ID）。</p>
     *
     * @param runId  显式 run 上下文（可为空：历史兼容语义，meta.runId 落 null，不进 run 索引）
     * @param userId 显式 user 上下文（可为空，同上）
     * @return 注册结果
     */
    public PersistentArtifactRegistration registerExplicit(String runId,
                                                             String userId,
                                                             String artifactType,
                                                             String logicalId,
                                                             String displayName,
                                                             String content,
                                                             long ttlHours) {
        return doRegisterContent(runId, userId, artifactType, logicalId, displayName, content, ttlHours, false);
    }

    /**
     * 幂等注册（显式上下文）：稳定身份 (runId|type|logicalId)，重复注册返回同一 artifactId。
     *
     * <p>经 Redis hash HSETNX 原子抢占身份字段：赢家写文件 + meta + 索引；输家直接返回赢家
     * 结果，零重写。事件派生制品的 lazy external registration 用
     * {@link #registerExternalIdempotent}。</p>
     *
     * @param runId 不得为空（幂等身份的组成部分）
     * @return 注册结果（重复注册时 meta 为既有制品）
     */
    public PersistentArtifactRegistration registerIdempotent(String runId,
                                                             String userId,
                                                             String artifactType,
                                                             String logicalId,
                                                             String displayName,
                                                             String content,
                                                             long ttlHours) {
        return doRegisterContent(runId, userId, artifactType, logicalId, displayName, content, ttlHours, true);
    }

    /**
     * 非幂等 external 注册（显式上下文）。path 必须位于 D04 批准根内。
     *
     * @param cleanupPath true 时清理阶段允许删除该路径（仅限 symlink，见 cleanup 逻辑）
     */
    public PersistentArtifactRegistration registerExternalExplicit(String runId,
                                                                   String userId,
                                                                   String artifactType,
                                                                   String logicalId,
                                                                   String displayName,
                                                                   Path path,
                                                                   long ttlHours,
                                                                   boolean cleanupPath) {
        return doRegisterExternal(runId, userId, artifactType, logicalId, displayName,
                path, ttlHours, cleanupPath, false);
    }

    /**
     * 幂等 external 注册（显式上下文）：稳定身份 (runId|type|logicalId|path)。
     *
     * <p>事件派生旧制品（脚本/数据集文件）的 lazy registration 走这里：不复制文件、
     * 不双写两棵树、重复 list / 重启后 list / 并发 list 均不产生第二 artifactId。
     * 幂等 external 固定 cleanupPath=false——引用制品的清理只删 meta 与索引，不动底层文件。</p>
     *
     * @param runId 不得为空（幂等身份的组成部分）
     */
    public PersistentArtifactRegistration registerExternalIdempotent(String runId,
                                                                     String userId,
                                                                     String artifactType,
                                                                     String logicalId,
                                                                     String displayName,
                                                                     Path path,
                                                                     long ttlHours) {
        return doRegisterExternal(runId, userId, artifactType, logicalId, displayName,
                path, ttlHours, false, true);
    }

    // ===== 读取 / 列表 =====

    /**
     * run 级制品列表：读 run 索引 SET → 逐条取 meta → 过滤已过期/缺失项。
     *
     * <p>只读，不生成新 artifactId；重复调用结果一致（meta 缺失项自动滤掉）。
     * 返回按创建时间升序、artifactId 次序的列表。</p>
     */
    public List<PersistentArtifactMeta> listByRunId(String runId) {
        if (!hasText(runId)) {
            return List.of();
        }
        Set<String> artifactIds = redisTemplate.opsForSet().members(runListKey(runId));
        if (artifactIds == null || artifactIds.isEmpty()) {
            return List.of();
        }
        List<PersistentArtifactMeta> metas = new ArrayList<>(artifactIds.size());
        for (String artifactId : artifactIds) {
            find(artifactId).ifPresent(meta -> {
                // 防御陈旧索引项：meta 的 runId 必须与请求 run 一致
                if (runId.equals(meta.getRunId())) {
                    metas.add(meta);
                }
            });
        }
        metas.sort(Comparator
                .comparing((PersistentArtifactMeta m) -> m.getCreatedAtMillis() == null ? 0L : m.getCreatedAtMillis())
                .thenComparing(m -> m.getArtifactId() == null ? "" : m.getArtifactId()));
        return metas;
    }

    /**
     * 归属校验：meta 的 runId/userId 与调用方匹配（list/download 授权共用此规则）。
     *
     * <p>宽容语义与历史数据兼容：meta 侧为空（早期无上下文注册）时不强校验该侧；
     * 调用方侧为空（内部系统调用）时不校验该侧。两侧都有值则必须相等。</p>
     */
    public static boolean matchesOwner(PersistentArtifactMeta meta, String runId, String userId) {
        if (meta == null) {
            return false;
        }
        if (hasText(meta.getRunId()) && hasText(runId) && !meta.getRunId().equals(runId)) {
            return false;
        }
        return !hasText(meta.getUserId()) || !hasText(userId) || meta.getUserId().equals(userId);
    }

    public Optional<PersistentArtifactMeta> find(String artifactId) {
        if (!hasText(artifactId)) {
            return Optional.empty();
        }
        String json = redisTemplate.opsForValue().get(key(artifactId));
        if (!hasText(json)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, PersistentArtifactMeta.class));
        } catch (Exception e) {
            log.warn("Failed to parse artifact meta {}", artifactId, e);
            return Optional.empty();
        }
    }

    public RawPayloadLocator locatorFor(String artifactId) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        touch(meta);
        return RawPayloadLocator.builder()
                .path(meta.getPath())
                .contentHash(meta.getContentHash())
                .build();
    }

    public String readContent(String artifactId) {
        PersistentArtifactMeta meta = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        if (Boolean.TRUE.equals(meta.getExternal())) {
            throw new IllegalArgumentException("External artifact has no registry-owned content: " + artifactId);
        }
        touch(meta);
        return readPath(Path.of(meta.getPath()), meta.getContentHash());
    }

    public String readLocator(RawPayloadLocator locator) {
        if (locator == null || !hasText(locator.getPath())) {
            throw new IllegalArgumentException("Raw payload locator path is required");
        }
        return readPath(Path.of(locator.getPath()), locator.getContentHash());
    }

    @Scheduled(initialDelayString = "${agent.persistent-artifact.cleanup-initial-delay-ms:300000}",
            fixedDelayString = "${agent.persistent-artifact.cleanup-delay-ms:300000}")
    public void cleanupExpiredArtifacts() {
        long now = System.currentTimeMillis();
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(META_PREFIX + "*")
                .count(Math.max(1, cleanupScanCount))
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // run 索引 / 幂等身份键与 meta 共享前缀，不是 meta JSON，显式跳过。
                if (key.startsWith(RUN_LIST_KEY_PREFIX) || key.startsWith(RUN_IDENTITY_KEY_PREFIX)) {
                    continue;
                }
                String json = redisTemplate.opsForValue().get(key);
                if (!hasText(json)) {
                    continue;
                }
                try {
                    PersistentArtifactMeta meta = objectMapper.readValue(json, PersistentArtifactMeta.class);
                    Long expiresAt = meta.getExpiresAtMillis();
                    if (expiresAt != null && expiresAt <= now) {
                        deleteMetaAndFile(meta);
                    }
                } catch (Exception e) {
                    log.warn("Failed to cleanup artifact meta {}", key, e);
                }
            }
        } catch (Exception e) {
            log.warn("Persistent artifact cleanup failed", e);
        }
    }

    // ===== 内部实现 =====

    private PersistentArtifactRegistration doRegisterContent(String runId,
                                                             String userId,
                                                             String artifactType,
                                                             String logicalId,
                                                             String displayName,
                                                             String content,
                                                             long ttlHours,
                                                             boolean idempotent) {
        String safeType = hasText(artifactType) ? artifactType.trim() : "artifact";
        if (idempotent) {
            requireRunIdForIdentity(runId);
        }
        String artifactId = safeType + ":" + UUID.randomUUID().toString().replace("-", "");
        if (idempotent) {
            PersistentArtifactRegistration existing = claimIdentity(runId,
                    identityField(safeType, logicalId, null), artifactId, ttlHours);
            if (existing != null) {
                return existing;
            }
        }
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        Path root = rootPath();
        // D04 §4.3：写入前校验 artifact 根可达（挂载缺失/权限不足 → 显式失败信号）。
        storagePaths.requireWritableRoot(root, AgentStoragePaths.KEY_ARTIFACT_ROOT);
        Path path = root.resolve(safeType).resolve(artifactId.replace(':', '_') + ".txt").normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Artifact path escapes root");
        }
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write persistent artifact " + artifactId, e);
        }
        PersistentArtifactMeta meta = buildMeta(artifactId, safeType, logicalId, displayName, path, hash,
                (long) bytes.length, ttlHours, false, true, runId, userId);
        save(meta);
        addToRunList(runId, artifactId, ttlHours);
        return registration(meta);
    }

    private PersistentArtifactRegistration doRegisterExternal(String runId,
                                                              String userId,
                                                              String artifactType,
                                                              String logicalId,
                                                              String displayName,
                                                              Path path,
                                                              long ttlHours,
                                                              boolean cleanupPath,
                                                              boolean idempotent) {
        if (path == null) {
            throw new IllegalArgumentException("External artifact path is required");
        }
        String safeType = hasText(artifactType) ? artifactType.trim() : "artifact";
        Path normalized = path.toAbsolutePath().normalize();
        // D22-5.1.3：external 路径只能落 D04 批准根内（artifactRoot / datasetRoot）。
        verifyExternalPath(normalized);
        if (idempotent) {
            requireRunIdForIdentity(runId);
        }
        String artifactId = safeType + ":" + UUID.randomUUID().toString().replace("-", "");
        if (idempotent) {
            PersistentArtifactRegistration existing = claimIdentity(runId,
                    identityField(safeType, logicalId, normalized.toString()), artifactId, ttlHours);
            if (existing != null) {
                return existing;
            }
        }
        Long size = null;
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                size = Files.size(normalized);
            }
        } catch (IOException e) {
            log.debug("External artifact size unavailable for {}: {}", normalized, e.getMessage());
        }
        PersistentArtifactMeta meta = buildMeta(artifactId, safeType, logicalId, displayName, normalized, null,
                size, ttlHours, true, cleanupPath, runId, userId);
        save(meta);
        addToRunList(runId, artifactId, ttlHours);
        return registration(meta);
    }

    /**
     * 幂等身份原子抢占：HSETNX 成功 → 返回 null（调用方继续写文件 + meta）；
     * 失败（已有赢家）→ 读赢家 artifactId，meta 有效则直接返回既有注册（零重写）；
     * 赢家 meta 已失效（极端竞态：清理恰好删掉）→ 覆盖残留字段后按新注册继续。
     */
    private PersistentArtifactRegistration claimIdentity(String runId, String field,
                                                         String candidateArtifactId, long ttlHours) {
        String identityKey = runIdentityKey(runId);
        Boolean claimed = redisTemplate.opsForHash().putIfAbsent(identityKey, field, candidateArtifactId);
        if (Boolean.TRUE.equals(claimed)) {
            extendTtlIfNeeded(identityKey, ttlHours);
            return null;
        }
        Object winnerId = redisTemplate.opsForHash().get(identityKey, field);
        if (winnerId != null) {
            Optional<PersistentArtifactMeta> winnerMeta = find(winnerId.toString());
            if (winnerMeta.isPresent()) {
                return registration(winnerMeta.get());
            }
        }
        // 残留身份（赢家 meta 已被清理）：覆盖后按新注册继续，索引随 cleanup 自愈。
        redisTemplate.opsForHash().put(identityKey, field, candidateArtifactId);
        extendTtlIfNeeded(identityKey, ttlHours);
        return null;
    }

    private void addToRunList(String runId, String artifactId, long ttlHours) {
        if (!hasText(runId)) {
            return;
        }
        String listKey = runListKey(runId);
        Long size = redisTemplate.opsForSet().size(listKey);
        if (size != null && maxRunListEntries > 0 && size >= maxRunListEntries) {
            log.warn("Run artifact index full, skip indexing: runId={} artifactId={} cap={}",
                    runId, artifactId, maxRunListEntries);
            return;
        }
        redisTemplate.opsForSet().add(listKey, artifactId);
        extendTtlIfNeeded(listKey, ttlHours);
    }

    /** 仅在当前 TTL 不足时延长（索引键被同 run 多制品共享，不得用短 TTL 覆盖长 TTL）。 */
    private void extendTtlIfNeeded(String redisKey, long ttlHours) {
        long desired = Math.max(1L, ttlHours);
        try {
            Long current = redisTemplate.getExpire(redisKey, TimeUnit.HOURS);
            if (current == null || current < 0 || current < desired) {
                redisTemplate.expire(redisKey, desired, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            // TTL 维护失败不阻断注册：最坏结果是索引早于 meta 过期，list 侧自愈。
            log.warn("Failed to maintain TTL for {}: {}", redisKey, e.getMessage());
        }
    }

    /**
     * D22-5.1.3：external 路径门槛。
     *
     * <p>规范化路径必须位于 artifactRoot 或 datasetRoot 内（拒绝 traversal / 根逃逸）；
     * 已存在的路径额外解析真实路径（跟随 symlink），真实位置仍必须在批准根内。
     * 真实路径比较时批准根同样解析 symlink（如 macOS /var → /private/var），
     * 避免根自身处于链接路径下时误拒合法文件。</p>
     */
    private void verifyExternalPath(Path normalized) {
        Path artifactRoot = rootPath();
        Path datasetRoot = storagePaths.datasetRoot().toAbsolutePath().normalize();
        if (!normalized.startsWith(artifactRoot) && !normalized.startsWith(datasetRoot)) {
            throw new SecurityException("External artifact path outside approved storage roots: " + normalized);
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path real = normalized.toRealPath();
                Path realArtifactRoot = toRealPathIfPossible(artifactRoot);
                Path realDatasetRoot = toRealPathIfPossible(datasetRoot);
                if (!real.startsWith(realArtifactRoot) && !real.startsWith(realDatasetRoot)) {
                    throw new SecurityException(
                            "External artifact path resolves outside approved storage roots: " + normalized);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to resolve external artifact path: " + normalized, e);
            }
        }
    }

    /** 根目录存在时解析真实路径（跟随 symlink），否则原样返回；失败降级为原路径。 */
    private static Path toRealPathIfPossible(Path root) {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return root.toRealPath();
            }
        } catch (IOException ignored) {
            // 解析失败时按规范化路径比较，保持保守不放宽。
        }
        return root;
    }

    private static void requireRunIdForIdentity(String runId) {
        if (!hasText(runId)) {
            throw new IllegalArgumentException("runId is required for idempotent artifact registration");
        }
    }

    /** 幂等身份字段：内容制品 type|logicalId；external 制品 type|logicalId|normalizedPath。 */
    private static String identityField(String artifactType, String logicalId, String externalPath) {
        String base = artifactType + "|" + logicalId;
        return externalPath == null ? base : base + "|" + externalPath;
    }

    private PersistentArtifactMeta buildMeta(String artifactId,
                                             String artifactType,
                                             String logicalId,
                                             String displayName,
                                             Path path,
                                             String contentHash,
                                             Long sizeBytes,
                                             long ttlHours,
                                             boolean external,
                                             boolean cleanupPath,
                                             String runId,
                                             String userId) {
        long ttl = ttlHours > 0 ? ttlHours : defaultTtlHours;
        long now = System.currentTimeMillis();
        return PersistentArtifactMeta.builder()
                .artifactId(artifactId)
                .artifactType(artifactType)
                .runId(hasText(runId) ? runId : null)
                .userId(hasText(userId) ? userId : null)
                .logicalId(logicalId)
                .displayName(displayName)
                .path(path.toAbsolutePath().normalize().toString())
                .contentHash(contentHash)
                .sizeBytes(sizeBytes)
                .createdAtMillis(now)
                .lastAccessAtMillis(now)
                .expiresAtMillis(now + TimeUnit.HOURS.toMillis(ttl))
                .ttlHours(ttl)
                .external(external)
                .cleanupPath(cleanupPath)
                .build();
    }

    private void save(PersistentArtifactMeta meta) {
        try {
            redisTemplate.opsForValue().set(key(meta.getArtifactId()),
                    objectMapper.writeValueAsString(meta),
                    Math.max(1L, meta.getTtlHours()),
                    TimeUnit.HOURS);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save persistent artifact meta " + meta.getArtifactId(), e);
        }
    }

    private void touch(PersistentArtifactMeta meta) {
        meta.setLastAccessAtMillis(System.currentTimeMillis());
        save(meta);
    }

    private void deleteMetaAndFile(PersistentArtifactMeta meta) {
        if (meta == null || !hasText(meta.getArtifactId())) {
            return;
        }
        redisTemplate.delete(key(meta.getArtifactId()));
        removeFromIndices(meta);
        if (!hasText(meta.getPath())) {
            return;
        }
        Path path = Path.of(meta.getPath()).toAbsolutePath().normalize();
        if (Boolean.TRUE.equals(meta.getExternal())) {
            if (Boolean.TRUE.equals(meta.getCleanupPath()) && Files.isSymbolicLink(path)) {
                deletePath(path);
            }
            return;
        }
        if (!path.startsWith(rootPath())) {
            log.warn("Skip artifact file delete outside root: {}", path);
            return;
        }
        deletePath(path);
    }

    /**
     * D22-5.1.3：过期清理同删 run 索引与幂等身份字段，不留悬挂引用。
     * 身份字段仅在仍指向本 artifactId 时删除（避免误删并发新注册抢占的字段）。
     */
    private void removeFromIndices(PersistentArtifactMeta meta) {
        String runId = meta.getRunId();
        if (!hasText(runId)) {
            return;
        }
        try {
            redisTemplate.opsForSet().remove(runListKey(runId), meta.getArtifactId());
        } catch (Exception e) {
            log.warn("Failed to remove artifact from run index: runId={} artifactId={} err={}",
                    runId, meta.getArtifactId(), e.getMessage());
        }
        String field = identityField(meta.getArtifactType(), meta.getLogicalId(),
                Boolean.TRUE.equals(meta.getExternal()) ? meta.getPath() : null);
        String identityKey = runIdentityKey(runId);
        try {
            Object current = redisTemplate.opsForHash().get(identityKey, field);
            if (current != null && meta.getArtifactId().equals(current.toString())) {
                redisTemplate.opsForHash().delete(identityKey, field);
            }
        } catch (Exception e) {
            log.warn("Failed to remove artifact identity: runId={} artifactId={} err={}",
                    runId, meta.getArtifactId(), e.getMessage());
        }
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete artifact path {}", path, e);
        }
    }

    private String readPath(Path path, String expectedHash) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(rootPath())) {
            throw new IllegalArgumentException("Raw payload path escapes artifact root");
        }
        try {
            byte[] bytes = Files.readAllBytes(normalized);
            if (hasText(expectedHash)) {
                String actual = sha256(bytes);
                if (!expectedHash.equals(actual)) {
                    throw new IllegalStateException("Raw payload hash mismatch");
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read raw payload " + normalized, e);
        }
    }

    private static PersistentArtifactRegistration registration(PersistentArtifactMeta meta) {
        return PersistentArtifactRegistration.builder()
                .artifactId(meta.getArtifactId())
                .meta(meta)
                .locator(RawPayloadLocator.builder()
                        .path(meta.getPath())
                        .contentHash(meta.getContentHash())
                        .build())
                .build();
    }

    private Path rootPath() {
        return storagePaths.artifactRoot().toAbsolutePath().normalize();
    }

    private String key(String artifactId) {
        return META_PREFIX + artifactId;
    }

    private static String runListKey(String runId) {
        return RUN_LIST_KEY_PREFIX + runId;
    }

    private static String runIdentityKey(String runId) {
        return RUN_IDENTITY_KEY_PREFIX + runId;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
