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
        requireText(runId, "runId");
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
            persistIndexLocked();
        }
        log.debug("rawRef registered ref={} runId={} bytes={}", ref, runId, bytes.length);
        return ref;
    }

    /** Ownership-checked full-content read (strict runId+userId). */
    public String read(String runId, String userId, String ref) {
        Entry entry = requireOwnedEntry(runId, userId, ref);
        return readContentOf(entry);
    }

    /** Internal read by ref only (unguessable ref from a previously issued
     * registration/locator). Used by the cache-hit rebind seam; never exposed
     * to callers without ownership context. Callers must pass UUID-style refs
     * ({@code raw_<uuid>}): short IDs repeat across runs and are ambiguous
     * here, so multiple matches fail closed. */
    public String readByRefInternal(String ref) {
        requireText(ref, "ref");
        ensureIndexLoaded();
        Entry match = null;
        for (Entry entry : entries.values()) {
            if (ref.equals(entry.ref())) {
                if (match != null) {
                    throw new IllegalArgumentException("rawRef ambiguous: " + ref);
                }
                match = entry;
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("rawRef not found: " + ref);
        }
        return readContentOf(match);
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
        ensureIndexLoaded();
        synchronized (this) {
            entries.entrySet().removeIf(e -> runId.equals(e.getValue().runId()));
            sequences.remove(runId);
            persistIndexLocked();
        }
        deleteRecursively(rootDir.resolve(runId));
        log.info("rawRef cleanup: runId={} done", runId);
    }

    private Entry requireOwnedEntry(String runId, String userId, String ref) {
        requireText(runId, "runId");
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

    private synchronized void persistIndexLocked() {
        persistIndexQuietly();
    }

    private void persistIndexQuietly() {
        try {
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
        } catch (IOException e) {
            log.warn("rawRef index persist failed: {}", e.getMessage());
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
                try {
                    List<IndexEntry> loaded = MAPPER.readValue(
                            indexFile.toFile(), new TypeReference<List<IndexEntry>>() { });
                    long now = System.currentTimeMillis();
                    for (IndexEntry indexEntry : loaded) {
                        Entry entry = indexEntry.toEntry();
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
                } catch (IOException e) {
                    log.warn("rawRef index load failed, starting with empty registry: {}", e.getMessage());
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

    private void deleteRecursively(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
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

    /** Jackson-serializable index form (paths as strings). */
    record IndexEntry(String ref, String runId, String userId, String displayName,
                      String path, long bytes, long createdAtMillis, long ttlSeconds) {
        Entry toEntry() {
            Path resolved = Path.of(path).toAbsolutePath().normalize();
            return new Entry(ref, runId, userId, displayName, resolved, bytes,
                    createdAtMillis, ttlSeconds, resolved.getParent());
        }
    }
}
