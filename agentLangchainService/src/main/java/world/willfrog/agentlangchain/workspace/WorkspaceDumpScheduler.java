package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * workspace dump 异步调度器（单进程 writer）。
 *
 * <p>在 {@code workspaceDumpExecutor} 线程池上异步执行 dump；失败时 3 次重试，
 * 仍失败则通过 WorkspacePathResolver 写入磁盘 DLQ。
 *
 * <h3>DLQ 持久化</h3>
 * <ul>
 *   <li>介质：{workspaceRoot}/_dlq/entry-{uuid}.json</li>
 *   <li>原子写入：写 .tmp → ATOMIC_MOVE 替换</li>
 *   <li>单进程 writer：重放时 .json → .processing 原子 rename claim；
 *       若目标 .processing 已存在则保留源 .json（由 stale recovery 后续处理）</li>
 *   <li>崩溃恢复：启动时扫描超过 STALE_PROCESSING_TIMEOUT 的 .processing 文件，rename 回 .json 重新参与重放</li>
 *   <li>启动重放：{@code @EventListener(ApplicationReadyEvent.class)} 通过显式注入的 {@code workspaceDumpExecutor} 提交，
 *       不依赖 {@code @Async} 代理自调用；executor 拒绝时 ERROR + 递增 replayRejectedCount</li>
 *   <li>重放成功：删 .processing 文件；重放失败：保留 .processing 文件供下次重启重试</li>
 *   <li>容量计算：统计待处理 .json 与正在处理 .processing；quarantine/ 与 eviction.log 不计入</li>
 *   <li>淘汰：仅淘汰尚未 claim 的 .json；活跃 .processing 永远不被淘汰删除。
 *       若可淘汰 .json 不足，保留超限状态并 ERROR</li>
 *   <li>淘汰审计：先写 eviction.log，写成功后再删文件；审计写失败时禁止删除</li>
 *   <li>损坏：JSON 解析失败或必填字段缺失 → ERROR + 移入 quarantine/ 子目录隔离；
 *       quarantine 失败时保留原文件 + ERROR + 递增 quarantineFailedCount</li>
 *   <li>透明性：pushDlq 返回 boolean + dlqFailedCount / quarantineFailedCount / replayRejectedCount 原子计数器</li>
 * </ul>
 *
 * <h3>协作关系</h3>
 * <ul>
 *   <li>调用方：{@link WorkspaceFinalizedEventListener} / {@link WorkspacePollingObserver}</li>
 *   <li>被调方：{@code WorkspaceDumpService}</li>
 * </ul>
 *
 * @author wang
 */
@Component
// 260814 scheduler-03: workspace export 总开关默认关闭；关闭时本调度器不注册，
// 没有 DLQ 目录、启动重放或后台任务。
@ConditionalOnExpression("${agent.workspace.export-enabled:false}")
@Slf4j
public class WorkspaceDumpScheduler {

    private static final int MAX_ATTEMPTS = 3;
    private static final int DLQ_MAX_FILES = 1000;
    private static final int DLQ_EVICTION_BATCH = 100;
    private static final String DLQ_DIR_NAME = "_dlq";
    private static final String QUARANTINE_DIR_NAME = "quarantine";
    private static final String EVICTION_LOG_NAME = "eviction.log";
    /** .processing 文件超过此时间视为崩溃残留，rename 回 .json 重新参与重放 */
    private static final long STALE_PROCESSING_TIMEOUT_MS = 5 * 60 * 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final WorkspaceDumpService dumpService;
    private final ThreadPoolTaskExecutor dumpExecutor;
    private final Path dlqDir;
    private final Path quarantineDir;

    /** 内存热缓存，供快速查询当前 DLQ 条目；真相源是磁盘文件 */
    private final Deque<String> dlqMemory = new ConcurrentLinkedDeque<>();

    /** pushDlq 磁盘写入失败次数 */
    private final AtomicLong dlqFailedCount = new AtomicLong();
    /** quarantine 失败次数（原文件保留，但未能移入隔离目录） */
    private final AtomicLong quarantineFailedCount = new AtomicLong();
    /** 启动重放被 executor 拒绝的次数 */
    private final AtomicLong replayRejectedCount = new AtomicLong();

    /** 构造只初始化路径，不执行 IO 和重放；重放由 ApplicationReadyEvent 通过显式 executor 触发。 */
    public WorkspaceDumpScheduler(WorkspaceDumpService dumpService,
                                  WorkspacePathResolver pathResolver,
                                  @Qualifier("workspaceDumpExecutor") ThreadPoolTaskExecutor dumpExecutor) {
        this.dumpService = dumpService;
        this.dumpExecutor = dumpExecutor;
        this.dlqDir = pathResolver.getWorkspaceRoot().resolve(DLQ_DIR_NAME);
        this.quarantineDir = dlqDir.resolve(QUARANTINE_DIR_NAME);
    }

    // ===== 启动重放（ApplicationReadyEvent + 显式 executor） =====

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            dumpExecutor.execute(() -> {
                recoverStaleProcessingFiles();
                replayDlq();
            });
        } catch (RejectedExecutionException e) {
            replayRejectedCount.incrementAndGet();
            log.error("DLQ replay rejected by workspaceDumpExecutor: {}", e.getMessage());
        }
    }

    /** 将超过 STALE_PROCESSING_TIMEOUT 的 .processing 文件 rename 回 .json，使其重新参与重放。 */
    private void recoverStaleProcessingFiles() {
        if (!Files.isDirectory(dlqDir)) {
            return;
        }
        List<Path> processingFiles = listDlqFilesByExtension(".processing");
        if (processingFiles.isEmpty()) return;
        long now = Instant.now().toEpochMilli();
        int recovered = 0;
        for (Path procFile : processingFiles) {
            try {
                long mtime = Files.getLastModifiedTime(procFile).toMillis();
                if (now - mtime >= STALE_PROCESSING_TIMEOUT_MS) {
                    String name = procFile.getFileName().toString();
                    String jsonName = name.substring(0, name.length() - ".processing".length()) + ".json";
                    Path jsonFile = dlqDir.resolve(jsonName);
                    // 若同名 .json 已存在（上次崩溃残留 + 新一轮写入），
                    // 为恢复条目生成唯一名以保留两份证据，避免 rename 冲突导致永久死锁
                    if (Files.exists(jsonFile)) {
                        String base = jsonName.substring(0, jsonName.length() - ".json".length());
                        String uniqueName = base + "-recovered-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
                        jsonFile = dlqDir.resolve(uniqueName);
                        log.info("Stale .processing recovered to unique name: {} → {}", name, uniqueName);
                    }
                    Files.move(procFile, jsonFile, StandardCopyOption.ATOMIC_MOVE);
                    recovered++;
                }
            } catch (IOException e) {
                log.warn("Failed to recover stale processing file {}: {}", procFile.getFileName(), e.getMessage());
            }
        }
        if (recovered > 0) {
            log.info("Recovered {} stale .processing files back to .json for re-claim", recovered);
        }
    }

    private void replayDlq() {
        if (!Files.isDirectory(dlqDir)) {
            return;
        }
        List<Path> entries = listDlqFilesByExtension(".json");
        if (entries.isEmpty()) return;
        log.info("DLQ replay starting: {} entries in {}", entries.size(), dlqDir);
        for (Path jsonFile : entries) {
            String jsonName = jsonFile.getFileName().toString();
            String procName = jsonName.substring(0, jsonName.length() - ".json".length()) + ".processing";
            Path procFile = dlqDir.resolve(procName);

            // 单进程 writer：若同名 .processing 已存在（上次崩溃残留），保留源 .json
            // 等待 stale recovery 将过期 .processing 回退为 .json 后重新参与重放
            if (Files.exists(procFile)) {
                log.warn("DLQ claim conflict: .processing already exists for {}, keeping source .json", jsonName);
                continue;
            }
            try {
                Files.move(jsonFile, procFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.warn("DLQ claim failed for {}: {}", jsonName, e.getMessage());
                continue;
            }
            // 已 claim，开始处理
            DlqEntry entry = readEntry(procFile);
            if (entry == null) {
                continue;
            }
            try {
                dumpService.dumpRun(entry.runId, entry.conservative);
                deleteFile(procFile);
                log.info("DLQ replay success, deleted: {}", procFile.getFileName());
            } catch (Exception e) {
                log.warn("DLQ replay failed, keeping {}: {}", procFile.getFileName(), e.getMessage());
            }
        }
    }

    // ===== 异步 dump =====

    @Async("workspaceDumpExecutor")
    public void enqueueDumpAsync(String runId, boolean conservative) {
        if (runId == null || runId.isBlank()) {
            log.warn("enqueueDumpAsync skipped: runId 为空");
            return;
        }
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("Workspace dump attempt {}/{}: runId={} conservative={}",
                        attempt, MAX_ATTEMPTS, runId, conservative);
                dumpService.dumpRun(runId, conservative);
                if (attempt > 1) {
                    log.info("Workspace dump succeeded on attempt {}/{}: runId={}",
                            attempt, MAX_ATTEMPTS, runId);
                }
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("Workspace dump attempt {}/{} failed: runId={} err={}",
                        attempt, MAX_ATTEMPTS, runId, e.getMessage());
            }
        }
        boolean persisted = pushDlq(runId, conservative, lastError);
        if (!persisted) {
            dlqFailedCount.incrementAndGet();
            log.error("DLQ push failed, entry permanently lost: runId={}", runId);
        }
    }

    // ===== DLQ 入队 =====

    /**
     * 将 dump 失败条目持久化到磁盘 DLQ。
     *
     * @return true 表示磁盘写入成功且已加入内存热队列；false 表示写入失败
     */
    boolean pushDlq(String runId, boolean conservative, Throwable lastError) {
        String reason = lastError == null ? "unknown" : lastError.getMessage();
        if (reason == null) reason = lastError.getClass().getName();
        DlqEntry entry = new DlqEntry(runId, conservative, reason, Instant.now());

        try {
            Files.createDirectories(dlqDir);
            String fileName = "entry-" + UUID.randomUUID() + ".json";
            writeEntryToDisk(fileName, entry);
            dlqMemory.addLast(runId);
            log.warn("DLQ push: runId={} conservative={} file={}", runId, conservative, fileName);
            evictIfNeeded();
            return true;
        } catch (IOException e) {
            log.error("DLQ push failed, entry lost: runId={} err={}", runId, e.getMessage());
            return false;
        }
    }

    /** 原子写入 DLQ 条目到磁盘（.tmp 写 → ATOMIC_MOVE）。提取为 package-private 方法以便测试注入失败。 */
    void writeEntryToDisk(String fileName, DlqEntry entry) throws IOException {
        Path tmp = dlqDir.resolve("." + fileName + ".tmp");
        Path target = dlqDir.resolve(fileName);
        byte[] json = MAPPER.writeValueAsBytes(entry.toMap());
        Files.write(tmp, json);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 删除单个淘汰候选文件。提取为 package-private 方法以便测试注入确定性的删除失败。 */
    void deleteEvictionCandidate(Path path) throws IOException {
        Files.delete(path);
    }

    /** 淘汰候选：Path 与可选的 runId 绑定，避免下标错位把错误的 runId 从内存移除。 */
    private record EvictionCandidate(Path path, String runId) {}

    private void evictIfNeeded() {
        // 容量统计 .json + .processing；quarantine/ 和 eviction.log 不计入
        List<Path> jsonFiles = listDlqFilesByExtension(".json");
        List<Path> processingFiles = listDlqFilesByExtension(".processing");
        int total = jsonFiles.size() + processingFiles.size();
        if (total <= DLQ_MAX_FILES) return;

        // 只淘汰尚未 claim 的 .json；活跃 .processing 永远不删
        List<Path> sortedJson = new ArrayList<>(jsonFiles);
        sortedJson.sort(Comparator.comparing(p -> {
            try { return Files.getLastModifiedTime(p).toMillis(); }
            catch (IOException e) { return 0L; }
        }));

        int toEvict = Math.min(DLQ_EVICTION_BATCH, sortedJson.size());
        if (toEvict == 0) {
            log.error("DLQ capacity exceeded (total={}, cap={}) but no evictable .json files available; "
                    + "{} .processing files are in-flight and cannot be evicted",
                    total, DLQ_MAX_FILES, processingFiles.size());
            return;
        }

        // 绑定 Path 与 runId，避免下标错位
        List<EvictionCandidate> evictionBatch = new ArrayList<>();
        List<String> auditRunIds = new ArrayList<>();
        for (int i = 0; i < toEvict; i++) {
            Path f = sortedJson.get(i);
            DlqEntry e = readEntry(f);
            String rid = e != null ? e.runId : null;
            evictionBatch.add(new EvictionCandidate(f, rid));
            if (rid != null) auditRunIds.add(rid);
        }

        if (!appendEvictionLog("capacity", auditRunIds)) {
            log.error("DLQ eviction audit log write failed, refusing to delete {} entries", evictionBatch.size());
            return;
        }

        int deleted = 0;
        List<String> actuallyDeleted = new ArrayList<>();
        for (EvictionCandidate c : evictionBatch) {
            try {
                deleteEvictionCandidate(c.path);
                deleted++;
                if (c.runId != null) {
                    actuallyDeleted.add(c.runId);
                }
            } catch (IOException ex) {
                log.error("DLQ eviction delete failed for {}: {}", c.path.getFileName(), ex.getMessage());
            }
        }
        actuallyDeleted.forEach(dlqMemory::remove);
        log.warn("DLQ evicted {}/{} attempted entries (cap={}, total={}): {}",
                deleted, evictionBatch.size(), DLQ_MAX_FILES, total, auditRunIds);
    }

    /** @return true if audit log was written successfully */
    private boolean appendEvictionLog(String reason, List<String> evictedRunIds) {
        try {
            Files.createDirectories(dlqDir);
            Path logFile = dlqDir.resolve(EVICTION_LOG_NAME);
            String line = Instant.now() + " reason=" + reason + " count=" + evictedRunIds.size()
                    + " runIds=" + evictedRunIds + "\n";
            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            log.error("Failed to write DLQ eviction log: {}", e.getMessage());
            return false;
        }
    }

    // ===== 磁盘读写 =====

    private List<Path> listDlqFilesByExtension(String extension) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dlqDir)) {
            return files;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dlqDir, "*" + extension)) {
            for (Path p : ds) files.add(p);
        } catch (IOException e) {
            log.warn("DLQ directory listing failed: {}", e.getMessage());
        }
        files.sort(Comparator.comparing(Path::getFileName, Comparator.naturalOrder()));
        return files;
    }

    private DlqEntry readEntry(Path file) {
        try {
            byte[] raw = Files.readAllBytes(file);
            Map<String, Object> map = MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
            String runId = (String) map.get("runId");
            if (runId == null || runId.isBlank()) {
                log.error("DLQ entry missing runId, quarantining: {}", file.getFileName());
                quarantine(file);
                return null;
            }
            Boolean conservative = map.get("conservative") instanceof Boolean b ? b : false;
            String reason = map.get("reason") instanceof String s ? s : "unknown";
            String enqueuedAtStr = map.get("enqueuedAt") instanceof String s ? s : null;
            Instant enqueuedAt = enqueuedAtStr != null ? Instant.parse(enqueuedAtStr) : Instant.now();
            return new DlqEntry(runId, conservative, reason, enqueuedAt);
        } catch (Exception e) {
            log.error("DLQ entry corrupt, quarantining: {} err={}", file.getFileName(), e.getMessage());
            quarantine(file);
            return null;
        }
    }

    /** 将损坏/不可解析的条目移入 quarantine 子目录。失败时保留原文件，不删除。 */
    private void quarantine(Path file) {
        try {
            Files.createDirectories(quarantineDir);
            Path target = quarantineDir.resolve(file.getFileName());
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            quarantineFailedCount.incrementAndGet();
            log.error("Failed to quarantine corrupt DLQ entry {}, keeping original file: {}",
                    file.getFileName(), e.getMessage());
        }
    }

    private void deleteFile(Path file) {
        try {
            Files.delete(file);
        } catch (IOException ignored) {}
    }

    // ===== 公共查询 =====

    public int dlqDiskSize() {
        return listDlqFilesByExtension(".json").size() + listDlqFilesByExtension(".processing").size();
    }

    public int dlqProcessingSize() {
        return listDlqFilesByExtension(".processing").size();
    }

    public int dlqMemorySize() {
        return dlqMemory.size();
    }

    /** 测试用：检查指定 runId 是否在内存热队列中。 */
    boolean dlqMemoryContains(String runId) {
        return dlqMemory.contains(runId);
    }

    public long dlqFailedCount() {
        return dlqFailedCount.get();
    }

    public long quarantineFailedCount() {
        return quarantineFailedCount.get();
    }

    public long replayRejectedCount() {
        return replayRejectedCount.get();
    }

    public Path getDlqDir() {
        return dlqDir;
    }

    // ===== DLQ 条目 =====

    record DlqEntry(String runId, boolean conservative, String reason, Instant enqueuedAt) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("conservative", conservative);
            m.put("reason", reason);
            m.put("enqueuedAt", DateTimeFormatter.ISO_INSTANT.format(enqueuedAt));
            return m;
        }
    }
}
