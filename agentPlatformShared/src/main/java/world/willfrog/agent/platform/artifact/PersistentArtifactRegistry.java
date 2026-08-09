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
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersistentArtifactRegistry {

    private static final String META_PREFIX = "agent:persistent-artifact:";

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

    public PersistentArtifactRegistration register(String artifactType,
                                                   String logicalId,
                                                   String displayName,
                                                   String content) {
        return register(artifactType, logicalId, displayName, content, defaultTtlHours);
    }

    public PersistentArtifactRegistration register(String artifactType,
                                                   String logicalId,
                                                   String displayName,
                                                   String content,
                                                   long ttlHours) {
        String safeType = hasText(artifactType) ? artifactType.trim() : "artifact";
        String artifactId = safeType + ":" + UUID.randomUUID().toString().replace("-", "");
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
                (long) bytes.length, ttlHours, false, true);
        save(meta);
        return PersistentArtifactRegistration.builder()
                .artifactId(artifactId)
                .meta(meta)
                .locator(RawPayloadLocator.builder().path(path.toString()).contentHash(hash).build())
                .build();
    }

    public PersistentArtifactRegistration registerExternal(String artifactType,
                                                           String logicalId,
                                                           String displayName,
                                                           Path path,
                                                           long ttlHours) {
        return registerExternal(artifactType, logicalId, displayName, path, ttlHours, false);
    }

    public PersistentArtifactRegistration registerExternal(String artifactType,
                                                           String logicalId,
                                                           String displayName,
                                                           Path path,
                                                           long ttlHours,
                                                           boolean cleanupPath) {
        if (path == null) {
            throw new IllegalArgumentException("External artifact path is required");
        }
        String safeType = hasText(artifactType) ? artifactType.trim() : "artifact";
        String artifactId = safeType + ":" + UUID.randomUUID().toString().replace("-", "");
        Path normalized = path.toAbsolutePath().normalize();
        Long size = null;
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                size = Files.size(normalized);
            }
        } catch (IOException e) {
            log.debug("External artifact size unavailable for {}: {}", normalized, e.getMessage());
        }
        PersistentArtifactMeta meta = buildMeta(artifactId, safeType, logicalId, displayName, normalized, null,
                size, ttlHours, true, cleanupPath);
        save(meta);
        return PersistentArtifactRegistration.builder()
                .artifactId(artifactId)
                .meta(meta)
                .locator(RawPayloadLocator.builder().path(normalized.toString()).build())
                .build();
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

    private PersistentArtifactMeta buildMeta(String artifactId,
                                             String artifactType,
                                             String logicalId,
                                             String displayName,
                                             Path path,
                                             String contentHash,
                                             Long sizeBytes,
                                             long ttlHours,
                                             boolean external,
                                             boolean cleanupPath) {
        long ttl = ttlHours > 0 ? ttlHours : defaultTtlHours;
        long now = System.currentTimeMillis();
        return PersistentArtifactMeta.builder()
                .artifactId(artifactId)
                .artifactType(artifactType)
                .runId(AgentContext.getRunId())
                .userId(AgentContext.getUserId())
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

    private Path rootPath() {
        return storagePaths.artifactRoot();
    }

    private String key(String artifactId) {
        return META_PREFIX + artifactId;
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
