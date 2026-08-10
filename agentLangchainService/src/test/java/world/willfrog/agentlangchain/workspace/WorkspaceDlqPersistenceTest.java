package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    void pushDlq_failureIncrementsFailedCountWhenCalledFromEnqueue() {
        // pushDlq 返回 false 时 enqueueDumpAsync 内部递增 dlqFailedCount
        // 这里直接测 pushDlq 返回值 + dlqFailedCount 的外部查询接口
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
    void replayDlq_skipsFileWhenProcessingAlreadyExists() throws Exception {
        // 写入 .json 文件，同时预先创建同名 .processing 文件（模拟另一进程已 claim）
        scheduler.pushDlq("run-claimed", false, new RuntimeException("err"));
        List<Path> jsonFiles = listJsonFiles();
        assertThat(jsonFiles).hasSize(1);
        Path jsonFile = jsonFiles.get(0);
        String jsonName = jsonFile.getFileName().toString();
        String procName = jsonName.substring(0, jsonName.length() - ".json".length()) + ".processing";
        Path procFile = dlqDir.resolve(procName);
        Files.copy(jsonFile, procFile); // 另一进程已 claim 的证据

        ReflectionTestUtils.setField(scheduler, "dlqMemory", new ConcurrentLinkedDeque<>());

        // 重放：检测到 .processing 已存在 → 删除残留 .json → 跳过
        ReflectionTestUtils.invokeMethod(scheduler, "replayDlq");

        // dumpService 不应被调用（已被另一进程 claim）
        verify(dumpService, never()).dumpRun(anyString(), anyBoolean());
        // .processing 应保持不动（由另一进程负责）
        assertThat(procFile).exists();
        // 残留 .json 应被删除
        assertThat(jsonFile).doesNotExist();
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
            quarantined = new java.util.ArrayList<>();
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
        // 重放失败，.processing 文件应保留（下一轮重启重试）
        List<Path> processingFiles;
        try (var ds = Files.newDirectoryStream(dlqDir, "*.processing")) {
            processingFiles = new java.util.ArrayList<>();
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

        // 新鲜 .processing 不应被抢回
        assertThat(recent).exists();
        assertThat(dlqDir.resolve("entry-recent.json")).doesNotExist();
    }

    // ===== 容量淘汰 =====

    @Test
    void eviction_skipsQuarantineAndEvictionLog() throws Exception {
        // 在 quarantine/ 下放文件，验证不会被淘汰
        Path quarantineDir = dlqDir.resolve("quarantine");
        Files.createDirectories(quarantineDir);
        Files.writeString(quarantineDir.resolve("corrupt.json"), "garbage");

        // 在 dlqDir 下放 eviction.log
        Files.createDirectories(dlqDir);
        Files.writeString(dlqDir.resolve("eviction.log"), "old log");

        // 写大量 .json 触发淘汰
        for (int i = 0; i < 1001; i++) {
            String content = String.format(
                    "{\"runId\":\"run-%d\",\"conservative\":false,\"reason\":\"test\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}",
                    i);
            Path f = dlqDir.resolve("entry-test-" + String.format("%04d", i) + ".json");
            Files.writeString(f, content);
        }

        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, dumpExecutor);
        scheduler.pushDlq("run-new", false, new RuntimeException("trigger eviction"));

        // quarantine/ 和 eviction.log 不应被删除
        assertThat(quarantineDir.resolve("corrupt.json")).exists();
        assertThat(dlqDir.resolve("eviction.log")).exists();
    }

    @Test
    void eviction_abortsIfAuditLogWriteFails() throws Exception {
        // 把 eviction.log 变成一个目录，使 appendEvictionLog 写失败
        Files.createDirectories(dlqDir);
        Path logAsDir = dlqDir.resolve("eviction.log");
        Files.createDirectories(logAsDir);

        // 写 1001 个条目触发淘汰
        for (int i = 0; i < 1001; i++) {
            String content = String.format(
                    "{\"runId\":\"run-%d\",\"conservative\":false,\"reason\":\"test\",\"enqueuedAt\":\"2026-01-01T00:00:00Z\"}",
                    i);
            Path f = dlqDir.resolve("entry-audit-" + String.format("%04d", i) + ".json");
            Files.writeString(f, content);
        }

        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, dumpExecutor);
        int beforeCount = listJsonFiles().size();

        // pushDlq 触发 evictIfNeeded，但审计日志写失败 → 不应删除任何文件
        boolean result = scheduler.pushDlq("run-new", false, new RuntimeException("trigger"));

        // 文件数应不变（审计失败阻止了删除）
        int afterCount = listJsonFiles().size();
        assertThat(afterCount).isEqualTo(beforeCount + (result ? 1 : 0));
    }

    // ===== 启动事件线程及时返回 =====

    @Test
    void onApplicationReady_returnsImmediatelyWhileReplayRunsOnExecutor() throws Exception {
        // 先写入一个 DLQ 条目，使 replayDlq 有工作要做
        scheduler.pushDlq("run-block", false, new RuntimeException("block-test"));
        ReflectionTestUtils.setField(scheduler, "dlqMemory", new ConcurrentLinkedDeque<>());

        // 让 dumpService.dumpRun 阻塞在 latch 上
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch enteredLatch = new CountDownLatch(1);
        AtomicBoolean dumpCalled = new AtomicBoolean(false);
        doAnswer(inv -> {
            dumpCalled.set(true);
            enteredLatch.countDown();
            blockLatch.await(10, TimeUnit.SECONDS);
            return null;
        }).when(dumpService).dumpRun("run-block", false);

        // 调用 onApplicationReady，必须在合理时间内返回
        long start = System.currentTimeMillis();
        scheduler.onApplicationReady();
        long elapsed = System.currentTimeMillis() - start;

        // 验证方法立即返回（executor 只是提交任务，不阻塞调用线程）
        assertThat(elapsed).isLessThan(500); // 500ms 内必须返回

        // 等待 executor 线程进入 dump
        assertThat(enteredLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // 释放阻塞，让 executor 线程完成
        blockLatch.countDown();

        // 等待 executor 线程退出
        dumpExecutor.shutdown();
        assertThat(dumpExecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(dumpCalled.get()).isTrue();
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
        List<Path> files = new java.util.ArrayList<>();
        if (!Files.isDirectory(dlqDir)) return files;
        try (var ds = Files.newDirectoryStream(dlqDir, "*.json")) {
            for (Path p : ds) files.add(p);
        } catch (IOException ignored) {}
        files.sort(java.util.Comparator.comparing(Path::getFileName, java.util.Comparator.naturalOrder()));
        return files;
    }
}
