package world.willfrog.agent.platform.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 260814 scheduler-03: local-disk backend for rawRef (run-scoped large tool
 * output storage), replacing the Redis-backed {@link PersistentArtifactRegistry}
 * path for tool raw output only.
 *
 * <p>Contract (plan §7.3 / §6.3):</p>
 * <ul>
 *   <li>Content files live under {@code <root>/<runId>/<ref>.txt} with
 *       temp-file + atomic-rename writes (no half-written files visible).</li>
 *   <li>One global index {@code <root>/index.json} maps ref -> entry
 *       (runId, userId, displayName, bytes, createdAtMillis, ttlSeconds, seq).
 *       The index is rewritten atomically on every register and loaded lazily
 *       on first access, so a service restart on the SAME machine keeps
 *       rawRefs of non-terminal Runs readable.</li>
 *   <li>Ownership: read requires the caller's runId AND userId to strictly
 *       equal the entry's (both non-blank), fail-closed.</li>
 *   <li>Caps: per-entry bytes, per-run entry count and per-run total bytes.
 *       Exceeding any cap rejects the registration.</li>
 *   <li>Cleanup: {@link RunRawRefCleanupListener} deletes the run directory +
 *       index entries at Run terminal state; TTL-expired entries are evicted
 *       lazily on access; the startup sweep drops expired entries and stale
 *       directories.</li>
 * </ul>
 *
 * <p>NOT provided here (by design): Redis Lua claims, sliding expiry,
 * cross-node reads, ghost-record cleanup, artifact list/download/shard
 * interfaces.</p>
 */
@Component
@Slf4j
public class RunRawRefLocalStore {

    static final String INDEX_FILE_NAME = "index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path rootDir;
    private final long maxEntryBytes;
    private final int maxEntriesPerRun;
    private final long maxTotalBytesPerRun;

    /**
     * (runId, ref) -> entry. The single source of truth in-process; the disk
     * index mirrors it. Short IDs repeat across runs (every run starts at
     * raw_ref_001), so ref alone is NOT a unique key — keying by ref would
     * silently overwrite another run's entry.
     */
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    /** runId -> next sequential number for {@code raw_ref_%03d} short IDs. */
    private final ConcurrentHashMap<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    /** Loaded when the disk index has been read at least once (lazily, once). */
    private volatile boolean indexLoaded = false;

    public RunRawRefLocalStore(
            @Value("${agent.run.raw-ref.root-dir:${java.io.tmpdir}/alphafrog-raw-ref}") String rootDir,
            @Value("${agent.run.raw-ref.max-entry-bytes:8388608}") long maxEntryBytes,
            @Value("${agent.run.raw-ref.max-entries-per-run:512}") int maxEntriesPerRun,
            @Value("${agent.run.raw-ref.max-total-bytes-per-run:536870912}") long maxTotalBytesPerRun) {
        this.rootDir = Path.of(rootDir).toAbsolutePath().normalize();
        this.maxEntryBytes = maxEntryBytes;
        this.maxEntriesPerRun = maxEntriesPerRun;
        this.maxTotalBytesPerRun = maxTotalBytesPerRun;
    }

    @PostConstruct
    void sweepAtStartup() {
        ensureIndexLoaded();
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> isExpired(e.getValue(), now));
        try {
            if (!Files.isDirectory(rootDir)) {
                return;
            }
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(rootDir, Files::isDirectory)) {
                for (Path runDir : dirs) {
                    boolean stillReferenced = entries.values().stream()
                            .anyMatch(entry -> runDir.equals(entry.runDir()));
                    if (!stillReferenced) {
                        deleteRecursively(runDir);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("rawRef startup sweep failed: {}", e.getMessage());
        }
        persistIndexQuietly();
    }

    /**
     * Register content and return the run-scoped reference.
     *
     * @param shortIdStyle true -> {@code raw_ref_%03d} (sequential, the
     *                     reread/rag short-ID contract); false -> {@code raw_<uuid>}
     *                     (unguessable reference for the compaction flow)
     */
    public String register(String runId, String userId, String displayName,
                           String content, long ttlSeconds, boolean shortIdStyle) {
        requireSafeSegment(runId, "runId");
        requireText(userId, "userId");
        if (content == null) {
            throw new IllegalArgumentException("rawRef content must not be null");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("rawRef ttlSeconds must be positive");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxEntryBytes) {
            throw new IllegalArgumentException("rawRef content exceeds max-entry-bytes ("
                    + bytes.length + " > " + maxEntryBytes + ")");
        }
        ensureIndexLoaded();

        String ref = shortIdStyle
                ? String.format("raw_ref_%03d", sequences
                        .computeIfAbsent(runId, key -> new AtomicInteger(0))
                        .incrementAndGet())
                : "raw_" + UUID.randomUUID();
        Path runDir = rootDir.resolve(runId);
        if (!runDir.getParent().equals(rootDir)) {
            // requireSafeSegment 已挡分隔符/./..，这里是防御性不变量：run 目录
            // 必须是 root 的直接子目录。
            throw new IllegalStateException("rawRef run dir escaped root: " + runDir);
        }
        // review fix：字符串校验挡不住 root/<合法runId> 本身是符号链接——
        // 注册必须拒绝跟随链接写入 root 之外。
        requireSafeRunDir(runDir);
        Path filePath = runDir.resolve(ref + ".txt");

        Entry candidate = new Entry(ref, runId, userId, nvl(displayName),
                filePath, bytes.length, System.currentTimeMillis(), ttlSeconds, runDir);
        synchronized (this) {
            long runCount = entries.values().stream()
                    .filter(e -> runId.equals(e.runId())).count();
            if (runCount >= maxEntriesPerRun) {
                throw new IllegalArgumentException("rawRef per-run entry cap exceeded for run " + runId);
            }
            long runBytes = entries.values().stream()
                    .filter(e -> runId.equals(e.runId())).mapToLong(Entry::bytes).sum();
            if (runBytes + bytes.length > maxTotalBytesPerRun) {
                throw new IllegalArgumentException("rawRef per-run byte cap exceeded for run " + runId);
            }
            writeContentAtomic(filePath, bytes);
            entries.put(new Key(runId, ref), candidate);
            try {
                // 索引是重启后恢复引用的唯一依据：落盘失败绝不能静默放行，
                // 否则进程内可用、重启即丢失。失败时回滚本条注册。
                persistIndex();
            } catch (RuntimeException e) {
                entries.remove(new Key(runId, ref));
                deleteQuietly(filePath);
                throw e;
            }
        }
        log.debug("rawRef registered ref={} runId={} bytes={}", ref, runId, bytes.length);
        return ref;
    }

    /** Ownership-checked full-content read (strict runId+userId). */
    public String read(String runId, String userId, String ref) {
        Entry entry = requireOwnedEntry(runId, userId, ref);
        return readContentOf(entry);
    }

    /** Ownership-checked existence. */
    public boolean belongsToRun(String runId, String ref) {
        if (runId == null || ref == null) {
            return false;
        }
        ensureIndexLoaded();
        Entry entry = entries.get(new Key(runId, ref));
        return entry != null && runId.equals(entry.runId());
    }

    /** Ownership assertion used by locator/read facades. */
    public void assertOwned(String runId, String userId, String ref) {
        requireOwnedEntry(runId, userId, ref);
    }

    /** Delete the run directory and all its index entries (Run terminal cleanup). */
    public void cleanupRun(String runId) {
        if (runId == null) {
            return;
        }
        // 递归删除前的强制校验：runId 含分隔符/.. 时绝不能碰 root 之外的目录。
        requireSafeSegment(runId, "runId");
        ensureIndexLoaded();
        synchronized (this) {
            entries.entrySet().removeIf(e -> runId.equals(e.getValue().runId()));
            sequences.remove(runId);
            persistIndexQuietly();
        }
        deleteRecursively(rootDir.resolve(runId));
        log.info("rawRef cleanup: runId={} done", runId);
    }

    private Entry requireOwnedEntry(String runId, String userId, String ref) {
        requireSafeSegment(runId, "runId");
        requireText(userId, "userId");
        ensureIndexLoaded();
        Entry entry = entries.get(new Key(runId, ref));
        if (entry == null || isExpired(entry, System.currentTimeMillis())) {
            if (entry != null) {
                evict(entry);
            }
            throw new IllegalArgumentException("rawRef not found: " + ref + " for run " + runId);
        }
        if (!runId.equals(entry.runId()) || !userId.equals(entry.userId())) {
            throw new IllegalArgumentException("rawRef does not belong to current run/user: " + ref);
        }
        return entry;
    }

    private String readContentOf(Entry entry) {
        // 符号链接 run 目录直接拒绝：root/<runId> 被换成指向 root 外的链接时，
        // 即使文件真实路径仍在链接目标"目录"内也必须 fail-closed。
        if (Files.isSymbolicLink(entry.runDir())) {
            throw new IllegalArgumentException(
                    "rawRef run dir must not be a symlink: " + entry.runDir());
        }
        // Defense in depth against traversal/corruption: the resolved real
        // paths must stay inside this entry's run directory. Both sides go
        // through toRealPath() — the run directory itself may sit under a
        // symlinked prefix (e.g. macOS /var -> /private/var), so comparing a
        // resolved file path against an unresolved directory path would
        // reject every legitimate read on such machines.
        Path real;
        Path realDir;
        try {
            real = entry.path().toRealPath();
            realDir = entry.runDir().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("rawRef content unreadable: " + entry.ref(), e);
        }
        if (!real.startsWith(realDir)) {
            throw new IllegalArgumentException("rawRef path escaped its run directory: " + entry.ref());
        }
        try {
            return new String(Files.readAllBytes(real), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("rawRef content unreadable: " + entry.ref(), e);
        }
    }

    private boolean isExpired(Entry entry, long now) {
        return now > entry.createdAtMillis() + entry.ttlSeconds() * 1000L;
    }

    private void evict(Entry entry) {
        entries.remove(new Key(entry.runId(), entry.ref()));
        deleteQuietly(entry.path());
        persistIndexQuietly();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * 路径安全段校验：值必须是单个合法文件名段——非空白、不含 '/' 或 '\'、
     * 不是 "." 或 ".."、不是绝对路径。runId 与索引里的 ref 会被直接拼进
     * rootDir 下的路径，任一不满足都会造成目录穿越。
     */
    private static void requireSafeSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || ".".equals(value) || "..".equals(value)
                || Path.of(value).isAbsolute()) {
            throw new IllegalArgumentException(
                    name + " must be a single path segment without separators: " + value);
        }
    }

    /**
     * run 目录安全检查：字符串穿越校验挡不住 root/<合法runId> 本身是指向
     * root 之外的符号链接。规则：run 目录不得是符号链接；若已存在，其真实
     * 路径的父目录必须就是 root 的真实路径（root 自身是链接的合法情形由
     * 两侧同时解析真实路径来兼容）。
     */
    private void requireSafeRunDir(Path runDir) {
        try {
            if (Files.isSymbolicLink(runDir)) {
                throw new IllegalArgumentException(
                        "rawRef run dir must not be a symlink: " + runDir);
            }
            if (Files.exists(runDir) && Files.exists(rootDir)
                    && !runDir.toRealPath().getParent().equals(rootDir.toRealPath())) {
                throw new IllegalArgumentException("rawRef run dir escaped root: " + runDir);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException("rawRef run dir unverifiable: " + runDir, e);
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private void writeContentAtomic(Path filePath, byte[] bytes) {
        try {
            Files.createDirectories(filePath.getParent());
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, filePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("rawRef write failed for " + filePath, e);
        }
    }

    /**
     * 索引落盘（throwing 版）：注册路径用——索引是重启后恢复引用的唯一依据，
     * 写失败必须让注册显式失败并回滚，绝不静默放行。
     */
    private void persistIndex() {
        try {
            writeIndexAtomic();
        } catch (IOException e) {
            log.error("rawRef index persist failed: {}", e.getMessage());
            throw new IllegalStateException("rawRef index persist failed", e);
        }
    }

    /** 索引落盘（尽力而为版）：清理/驱逐路径用，失败只告警（TTL 与重启加载的
     * 过期过滤兜底，最坏情况是重启后残留条目因文件缺失而 fail-closed）。 */
    private void persistIndexQuietly() {
        try {
            writeIndexAtomic();
        } catch (IOException e) {
            log.warn("rawRef index persist failed (best effort): {}", e.getMessage());
        }
    }

    private void writeIndexAtomic() throws IOException {
        Files.createDirectories(rootDir);
        List<IndexEntry> index = entries.values().stream()
                .map(e -> new IndexEntry(e.ref(), e.runId(), e.userId(), e.displayName(),
                        e.path().toString(), e.bytes(), e.createdAtMillis(), e.ttlSeconds()))
                .toList();
        Path tmp = rootDir.resolve(INDEX_FILE_NAME + ".tmp-" + UUID.randomUUID());
        Files.write(tmp, MAPPER.writeValueAsBytes(index));
        try {
            Files.move(tmp, rootDir.resolve(INDEX_FILE_NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, rootDir.resolve(INDEX_FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureIndexLoaded() {
        if (indexLoaded) {
            return;
        }
        synchronized (this) {
            if (indexLoaded) {
                return;
            }
            Path indexFile = rootDir.resolve(INDEX_FILE_NAME);
            if (Files.isRegularFile(indexFile)) {
                List<IndexEntry> loaded;
                try {
                    loaded = MAPPER.readValue(
                            indexFile.toFile(), new TypeReference<List<IndexEntry>>() { });
                } catch (IOException e) {
                    // 索引存在但读不出来 = 无法核验哪些目录还有引用。此时绝不
                    // 允许启动清扫（那会把全部内容目录当孤儿删掉），fail-fast。
                    throw new IllegalStateException(
                            "rawRef index unreadable, refusing to start: " + indexFile, e);
                }
                long now = System.currentTimeMillis();
                for (IndexEntry indexEntry : loaded) {
                    try {
                        requireSafeSegment(indexEntry.runId(), "index runId");
                        requireSafeSegment(indexEntry.ref(), "index ref");
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(
                                "rawRef index entry invalid, refusing to start: " + e.getMessage(), e);
                    }
                    // 不信任 index.json 里存的任意路径：一律按 root/runId/ref.txt
                    // 重新推导，杜绝被篡改/损坏的索引把读取或清扫引到 root 之外。
                    Path derived = rootDir.resolve(indexEntry.runId())
                            .resolve(indexEntry.ref() + ".txt");
                    Entry entry = new Entry(indexEntry.ref(), indexEntry.runId(), indexEntry.userId(),
                            indexEntry.displayName(), derived, indexEntry.bytes(),
                            indexEntry.createdAtMillis(), indexEntry.ttlSeconds(),
                            derived.getParent());
                    if (isExpired(entry, now)) {
                        deleteQuietly(entry.path());
                        continue;
                    }
                    entries.putIfAbsent(new Key(entry.runId(), entry.ref()), entry);
                    // 序列必须恢复到已见最大序号：索引条目按任意顺序加载，
                    // computeIfAbsent 只认第一条会把后续更大的 seq 丢掉，
                    // 重启后重新发号就会与已存在条目撞号。
                    sequences.compute(entry.runId(), (key, current) -> {
                        if (current == null) {
                            return new AtomicInteger(entry.seq());
                        }
                        if (current.get() < entry.seq()) {
                            current.set(entry.seq());
                        }
                        return current;
                    });
                }
            }
            indexLoaded = true;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort; the startup sweep retries later.
        }
    }

    /**
     * 不跟随符号链接的递归删除：遇到符号链接只删除链接本身，绝不遍历/删除
     * 链接目标里的内容（root/<runId> 可能被换成指向 root 外的链接）。
     */
    private void deleteRecursively(Path dir) {
        try {
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir)) {
                Files.deleteIfExists(dir);
                return;
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("rawRef delete failed for {}: {}", dir, e.getMessage());
        }
    }

    /** Composite index key: short IDs repeat per run, so ref alone is ambiguous. */
    record Key(String runId, String ref) {
    }

    record Entry(String ref, String runId, String userId, String displayName,
                 Path path, long bytes, long createdAtMillis, long ttlSeconds,
                 Path runDir) {
        int seq() {
            if (ref != null && ref.startsWith("raw_ref_")) {
                try {
                    return Integer.parseInt(ref.substring("raw_ref_".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /** Jackson-serializable index form (paths as strings). 加载时不再信任 path
     * 字段本身，一律按 root/runId/ref.txt 重新推导（见 ensureIndexLoaded）。 */
    record IndexEntry(String ref, String runId, String userId, String displayName,
                      String path, long bytes, long createdAtMillis, long ttlSeconds) {
    }
}
