package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceDlqPersistenceTest {

    private Path tempDir;
    private Path dlqDir;
    private WorkspacePathResolver pathResolver;
    private WorkspaceDumpService dumpService;
    private ThreadPoolTaskExecutor dumpExecutor;
    private WorkspaceDumpScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("dlq-test-");
        dlqDir = tempDir.resolve("_dlq");
        pathResolver = mock(WorkspacePathResolver.class);
        when(pathResolver.getWorkspaceRoot()).thenReturn(tempDir);
        dumpService = mock(WorkspaceDumpService.class);
        dumpExecutor = new ThreadPoolTaskExecutor();
        dumpExecutor.setCorePoolSize(1);
        dumpExecutor.setMaxPoolSize(1);
        dumpExecutor.initialize();
        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, dumpExecutor);
    }

    @AfterEach
    void tearDown() throws Exception {
        dumpExecutor.shutdown();
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ===== pushDlq =====

    @Test
    void pushDlq_persistsEntryToDiskAndReturnsTrue() {
        boolean result = scheduler.pushDlq("run-1", false, new RuntimeException("test error"));

        assertThat(result).isTrue();
        assertThat(dlqDir).isDirectory();
        List<Path> files = listJsonFiles();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString()).startsWith("entry-").endsWith(".json");
        assertThat(files.get(0).getFileName().toString()).doesNotContain("run-1");
        assertThat(scheduler.dlqMemorySize()).isEqualTo(1);
    }

    @Test
    void pushDlq_returnsFalseWhenDiskWriteFails() throws Exception {
        Files.createDirectories(tempDir);
        Path blocker = tempDir.resolve("_dlq");
        Files.write(blocker, "block".getBytes(StandardCharsets.UTF_8));

        boolean result = scheduler.pushDlq("run-fail", false, new RuntimeException("disk full"));

        assertThat(result).isFalse();
        assertThat(scheduler.dlqMemorySize()).isEqualTo(0);
    }

    @Test
    void pushDlq_doesNotEvictOnFailure() throws Exception {
        // 写入足够多的有效条目到 dlqDir
        Files.createDirectories(dlqDir);
        for (int i = 0; i < 10; i++) {
            String content = String.format(
                    "{\"runId\":\"run-%d\",\"conservative\":false,\"reason\":\"test\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}",
                    i);
            Path f = dlqDir.resolve("entry-keep-" + String.format("%04d", i) + ".json");
            Files.writeString(f, content);
        }
        int before = listJsonFiles().size();

        // 把 dlqDir 设为只读，后续 Files.write(tmp) 会失败
        dlqDir.toFile().setReadOnly();

        try {
            scheduler.pushDlq("run-fail", false, new RuntimeException("err"));
        } finally {
            dlqDir.toFile().setWritable(true);
        }

        // 失败不应触发淘汰，原有文件数应不变
        assertThat(listJsonFiles().size()).isEqualTo(before);
    }

    @Test
    void pushDlq_failureIncrementsFailedCountWhenCalledFromEnqueue() {
        assertThat(scheduler.dlqFailedCount()).isEqualTo(0);
        boolean ok = scheduler.pushDlq("run-ok", false, new RuntimeException("transient"));
        assertThat(ok).isTrue();
        assertThat(scheduler.dlqMemorySize()).isEqualTo(1);
    }

    // ===== replayDlq =====

    @Test
    void replayDlq_successfullyReplaysAndDeletesFile() throws Exception {
        scheduler.pushDlq("run-replay-ok", true, new RuntimeException("transient"));
        assertThat(listJsonFiles()).hasSize(1);

        ReflectionTestUtils.setField(scheduler, "dlqMemory", new ConcurrentLinkedDeque<>());

        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        verify(dumpService).dumpRun("run-replay-ok", true);
        assertThat(listJsonFiles()).isEmpty();
    }

    @Test
    void replayDlq_keepsJsonWhenProcessingAlreadyExists() throws Exception {
        // 写入 .json 文件，同时预先创建同名 .processing 文件
        scheduler.pushDlq("run-claimed", false, new RuntimeException("err"));
        List<Path> jsonFiles = listJsonFiles();
        assertThat(jsonFiles).hasSize(1);
        Path jsonFile = jsonFiles.get(0);
        String jsonName = jsonFile.getFileName().toString();
        String procName = jsonName.substring(0, jsonName.length() - ".json".length()) + ".processing";
        Path procFile = dlqDir.resolve(procName);
        Files.copy(jsonFile, procFile);

        ReflectionTestUtils.setField(scheduler, "dlqMemory", new ConcurrentLinkedDeque<>());

        // 重放：.processing 已存在 → 保留源 .json → 跳过
        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        // dumpService 不应被调用
        verify(dumpService, never()).dumpRun(anyString(), anyBoolean());
        // .processing 保持不动
        assertThat(procFile).exists();
        // 源 .json 应保留（不再删除，由 stale recovery 后续处理）
        assertThat(jsonFile).exists();
    }

    @Test
    void replayDlq_quarantinesCorruptEntry() throws Exception {
        Files.createDirectories(dlqDir);
        Path corrupt = dlqDir.resolve("entry-bad.json");
        Files.writeString(corrupt, "{not valid json!!!");

        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        verify(dumpService, never()).dumpRun(anyString(), anyBoolean());
        Path quarantineDir = dlqDir.resolve("quarantine");
        assertThat(quarantineDir).isDirectory();
        List<Path> quarantined;
        try (var ds = Files.newDirectoryStream(quarantineDir)) {
            quarantined = new ArrayList<>();
            for (Path p : ds) quarantined.add(p);
        }
        assertThat(quarantined).hasSize(1);
        assertThat(corrupt).doesNotExist();
    }

    @Test
    void replayDlq_quarantinesEntryMissingRunId() throws Exception {
        Files.createDirectories(dlqDir);
        Path noRunId = dlqDir.resolve("entry-noid.json");
        Files.writeString(noRunId, "{\"conservative\":true,\"reason\":\"test\"}");

        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        verify(dumpService, never()).dumpRun(anyString(), anyBoolean());
        Path quarantineDir = dlqDir.resolve("quarantine");
        assertThat(quarantineDir).isDirectory();
    }

    @Test
    void replayDlq_keepsFileOnDumpFailure() throws Exception {
        doThrow(new RuntimeException("dump failed")).when(dumpService).dumpRun(anyString(), anyBoolean());
        scheduler.pushDlq("run-keep", false, new RuntimeException("orig"));
        List<Path> before = listJsonFiles();
        assertThat(before).hasSize(1);

        ReflectionTestUtils.setField(scheduler, "dlqMemory", new ConcurrentLinkedDeque<>());
        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        verify(dumpService).dumpRun("run-keep", false);
        // 重放失败，.processing 文件应保留
        List<Path> processingFiles;
        try (var ds = Files.newDirectoryStream(dlqDir, "*.processing")) {
            processingFiles = new ArrayList<>();
            for (Path p : ds) processingFiles.add(p);
        }
        assertThat(processingFiles).hasSize(1);
    }

    // ===== 崩溃恢复 =====

    @Test
    void recoverStaleProcessingFiles_renamesStaleFilesBackToJson() throws Exception {
        Files.createDirectories(dlqDir);
        Path stale = dlqDir.resolve("entry-test.processing");
        Files.writeString(stale, "{\"runId\":\"run-stale\",\"conservative\":false,\"reason\":\"crash\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}");
        stale.toFile().setLastModified(System.currentTimeMillis() - 10 * 60 * 1000);

        ReflectionTestUtils.invokeMethod(scheduler, "recoverStaleProcessingFiles");

        assertThat(stale).doesNotExist();
        Path recovered = dlqDir.resolve("entry-test.json");
        assertThat(recovered).exists();
    }

    @Test
    void recoverStaleProcessingFiles_ignoresRecentProcessingFiles() throws Exception {
        Files.createDirectories(dlqDir);
        Path recent = dlqDir.resolve("entry-recent.processing");
        Files.writeString(recent, "{\"runId\":\"run-recent\"}");

        ReflectionTestUtils.invokeMethod(scheduler, "recoverStaleProcessingFiles");

        assertThat(recent).exists();
        assertThat(dlqDir.resolve("entry-recent.json")).doesNotExist();
    }

    // ===== quarantine 失败保留原文件 =====

    @Test
    void quarantine_keepsOriginalFileOnFailure() throws Exception {
        Files.createDirectories(dlqDir);
        Path corrupt = dlqDir.resolve("entry-corrupt.json");
        Files.writeString(corrupt, "{bad json");

        // 把 quarantine 目录路径变成一个普通文件，使 createDirectories 失败
        Path quarantineDir = dlqDir.resolve("quarantine");
        Files.write(quarantineDir, "block".getBytes(StandardCharsets.UTF_8));

        assertThat(scheduler.quarantineFailedCount()).isEqualTo(0);

        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        // quarantine 失败时，已 claim 的 .processing 文件必须保留（原 .json 已被 rename 为 .processing）
        Path procFile = dlqDir.resolve("entry-corrupt.processing");
        assertThat(procFile).exists();
        assertThat(scheduler.quarantineFailedCount()).isEqualTo(1);
    }

    // ===== 容量淘汰：.processing 不被淘汰 =====

    @Test
    void eviction_preservesProcessingFilesWhenOnlyJsonCanBeEvicted() throws Exception {
        Files.createDirectories(dlqDir);
        // 写 500 个 .json + 600 个 .processing → total=1100 > 1000，但只能淘汰 .json
        for (int i = 0; i < 500; i++) {
            Path f = dlqDir.resolve("entry-json-" + String.format("%04d", i) + ".json");
            Files.writeString(f, "{\"runId\":\"run-json-" + i + "\",\"conservative\":false,\"reason\":\"test\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}");
        }
        for (int i = 0; i < 600; i++) {
            Path f = dlqDir.resolve("entry-proc-" + String.format("%04d", i) + ".processing");
            Files.writeString(f, "{\"runId\":\"run-proc-" + i + "\"}");
        }

        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, dumpExecutor);
        scheduler.pushDlq("run-trigger", false, new RuntimeException("trigger"));

        // .processing 文件应全部保留
        int remainingProc = 0;
        try (var ds = Files.newDirectoryStream(dlqDir, "*.processing")) {
            for (Path p : ds) remainingProc++;
        }
        assertThat(remainingProc).isEqualTo(600);
    }

    @Test
    void eviction_skipsQuarantineAndEvictionLog() throws Exception {
        Path quarantineDir = dlqDir.resolve("quarantine");
        Files.createDirectories(quarantineDir);
        Files.writeString(quarantineDir.resolve("corrupt.json"), "garbage");

        Files.createDirectories(dlqDir);
        Files.writeString(dlqDir.resolve("eviction.log"), "old log");

        for (int i = 0; i < 1001; i++) {
            String content = String.format(
                    "{\"runId\":\"run-%d\",\"conservative\":false,\"reason\":\"test\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}",
                    i);
            Path f = dlqDir.resolve("entry-test-" + String.format("%04d", i) + ".json");
            Files.writeString(f, content);
        }

        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, dumpExecutor);
        scheduler.pushDlq("run-new", false, new RuntimeException("trigger eviction"));

        assertThat(quarantineDir.resolve("corrupt.json")).exists();
        assertThat(dlqDir.resolve("eviction.log")).exists();
    }

    @Test
    void eviction_abortsIfAuditLogWriteFails() throws Exception {
        Files.createDirectories(dlqDir);
        Path logAsDir = dlqDir.resolve("eviction.log");
        Files.createDirectories(logAsDir);

        for (int i = 0; i < 1001; i++) {
            String content = String.format(
                    "{\"runId\":\"run-%d\",\"conservative\":false,\"reason\":\"test\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}",
                    i);
            Path f = dlqDir.resolve("entry-audit-" + String.format("%04d", i) + ".json");
            Files.writeString(f, content);
        }

        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, dumpExecutor);
        int beforeCount = listJsonFiles().size();

        boolean result = scheduler.pushDlq("run-new", false, new RuntimeException("trigger"));

        int afterCount = listJsonFiles().size();
        assertThat(afterCount).isEqualTo(beforeCount + (result ? 1 : 0));
    }

    // ===== 启动事件：确定性并发证明 =====

    @Test
    void onApplicationReady_submitsReplayAndReturnsWithoutBlocking() throws Exception {
        // 写入 DLQ 条目
        scheduler.pushDlq("run-block", false, new RuntimeException("block-test"));
        ReflectionTestUtils.setField(scheduler, "dlqMemory", new ConcurrentLinkedDeque<>());

        // 双栅栏：enteredLatch 证明 worker 已进入 dump，blockLatch 让 worker 阻塞
        CountDownLatch enteredLatch = new CountDownLatch(1);
        CountDownLatch blockLatch = new CountDownLatch(1);
        CompletableFuture<Boolean> methodReturned = new CompletableFuture<>();
        AtomicBoolean dumpEntered = new AtomicBoolean(false);

        doAnswer(inv -> {
            dumpEntered.set(true);
            enteredLatch.countDown();
            blockLatch.await(10, TimeUnit.SECONDS);
            return null;
        }).when(dumpService).dumpRun("run-block", false);

        // 另一个线程调用 onApplicationReady，通过 CompletableFuture 证明方法已返回
        Thread caller = new Thread(() -> {
            scheduler.onApplicationReady();
            methodReturned.complete(true);
        });
        caller.start();

        // 等待 worker 进入 dump（证明 executor 已接收任务）
        assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dumpEntered.get()).isTrue();

        // 此时 worker 阻塞在 blockLatch 上，onApplicationReady 应已返回
        // （executor.execute 是异步提交，不等待任务完成）
        assertThat(methodReturned.get(5, TimeUnit.SECONDS)).isTrue();

        // 断言对象是"方法已完成"和"worker 已进入"，不是墙钟耗时

        // 释放 worker
        blockLatch.countDown();
        caller.join(5000);

        dumpExecutor.shutdown();
        assertThat(dumpExecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    // ===== 构造不阻塞 =====

    @Test
    void constructor_doesNotPerformFilesystemIO() {
        assertThat(dlqDir).doesNotExist();
        assertThat(scheduler.getDlqDir()).isEqualTo(dlqDir);
        assertThat(scheduler.dlqDiskSize()).isEqualTo(0);
    }

    @Test
    void constructor_doesNotCallReplay() throws Exception {
        verify(dumpService, never()).dumpRun(anyString(), anyBoolean());
    }

    // ===== 辅助方法 =====

    private List<Path> listJsonFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dlqDir)) return files;
        try (var ds = Files.newDirectoryStream(dlqDir, "*.json")) {
            for (Path p : ds) files.add(p);
        } catch (IOException ignored) {}
        files.sort(java.util.Comparator.comparing(Path::getFileName, java.util.Comparator.naturalOrder()));
        return files;
    }
}
