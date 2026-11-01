package world.willfrog.agentlangchain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import world.willfrog.agentlangchain.workspace.WorkspaceDumpScheduler.DlqEntry;
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





    // ===== replayDlq =====






    // ===== 崩溃恢复 =====




    // ===== quarantine 失败保留原文件 =====


    // ===== 容量淘汰：.processing 不被淘汰 =====




    // ===== 淘汰 runId 映射正确性 =====


    // ===== 启动事件：确定性并发证明 =====


    // ===== 构造不阻塞 =====



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
