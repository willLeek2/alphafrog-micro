package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D21-A 两路兜底集成测试（W5 task #105 kickoff §6 三场景）。
 *
 * <p>装配真实链路：listener / polling observer → scheduler → dump service →
 * writer 全走真对象（无 Spring 容器，@Async 不生效 → 同步确定性执行）；
 * 仅 DB 边界（AgentRunMapper）与收集边界（WorkspaceAssetCollector）用 Mockito 打桩，
 * 文件落 @TempDir——不碰生产 DB/Nacos。</p>
 *
 * <ol>
 *   <li>事件失败 → 轮询兜底：事件路 3 次重试耗尽入 DLQ 后，轮询扫到同一 run 补 dump 成功；</li>
 *   <li>两路皆败 → DLQ + 信号：事件路与轮询路各自耗尽重试，DLQ 计数累加，零落盘；</li>
 *   <li>无重复 dump 放大：事件路成功后，轮询两次重扫整段 skip，文件零字节变化。</li>
 * </ol>
 *
 * <p>已知边界（D21-B scope）：DLQ 仅 runId 内存态、无 dedupe/drain；polling 水位
 * 内存态。本测试只钉 D21-A 的兜底可达性与幂等性。</p>
 */
class WorkspaceDumpTwoPathIntegrationTest {

    @TempDir
    Path tempDir;

    private AgentRunMapper runMapper;
    private WorkspaceAssetCollector collector;
    private WorkspaceDumpScheduler scheduler;
    private WorkspaceFinalizedEventListener listener;
    private WorkspacePollingObserver observer;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspaces");
        Path datasetRoot = tempDir.resolve("datasets");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                workspaceRoot.toString(),
                tempDir.resolve("artifacts").toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        WorkspacePathResolver pathResolver = new WorkspacePathResolver(storagePaths);
        WorkspaceHealthVerifier verifier = new WorkspaceHealthVerifier(pathResolver);
        WorkspaceManifestWriter writer = new WorkspaceManifestWriter(storagePaths);

        runMapper = mock(AgentRunMapper.class);
        collector = mock(WorkspaceAssetCollector.class);
        WorkspaceDumpService dumpService =
                new WorkspaceDumpService(runMapper, pathResolver, collector, verifier, writer);
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        scheduler = new WorkspaceDumpScheduler(dumpService, pathResolver, executor);
        listener = new WorkspaceFinalizedEventListener(scheduler);
        observer = new WorkspacePollingObserver(runMapper, scheduler);
        ReflectionTestUtils.setField(observer, "enabled", true);
        ReflectionTestUtils.setField(observer, "batchSize", 100);
        ReflectionTestUtils.setField(observer, "initialLookbackMinutes", 60);
    }

    @Test
    void eventPathFailureThenPollingFallbackShouldCompleteDump() throws Exception {
        AgentRun run = run("run-evt", OffsetDateTime.now());
        when(runMapper.findById("run-evt")).thenReturn(run);
        when(runMapper.listByStatusAndUpdatedAfterComposite(
                anyList(), any(OffsetDateTime.class), any(), anyInt()))
                .thenReturn(List.of(run));
        // 事件路 3 次尝试全部失败（瞬时故障），第 4 次（轮询路）恢复
        CollectedAssets assets = new CollectedAssets(List.of(message("run-evt", 2)), List.of(), List.of());
        when(collector.collectWorkspaceAssets(any()))
                .thenThrow(new IllegalStateException("transient db blip 1"))
                .thenThrow(new IllegalStateException("transient db blip 2"))
                .thenThrow(new IllegalStateException("transient db blip 3"))
                .thenReturn(assets);

        // 事件路：listener → scheduler 3 次重试耗尽 → DLQ，零落盘
        listener.onRunFinalized(new AgentRunFinalizedEvent("run-evt", 1001L, "COMPLETED", false));
        assertEquals(1, scheduler.dlqMemorySize(), "事件路耗尽重试必须入 DLQ（信号）");
        assertFalse(Files.exists(runDir("run-evt")), "事件路失败不得留下半截文件");

        // 轮询兜底：同一 run 被 reconciliation 扫到 → dump 成功
        observer.scan();
        Path runDir = runDir("run-evt");
        assertTrue(Files.exists(runDir.resolve("manifest.json")), "轮询必须兜底完成 dump");
        assertTrue(Files.exists(runDir.resolve("workspace_state.json")));
        JsonNode state = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        assertEquals("full", state.get("mode").asText());
        assertEquals(WorkspaceFingerprints.compute(run, assets.messages()),
                state.get("fingerprint").asText());
        assertEquals(1, scheduler.dlqMemorySize(), "兜底成功不得新增 DLQ 条目");
    }

    @Test
    void bothPathsFailedShouldAccumulateDlqAndWriteNothing() throws Exception {
        AgentRun run = run("run-doomed", OffsetDateTime.now());
        when(runMapper.findById("run-doomed")).thenReturn(run);
        when(runMapper.listByStatusAndUpdatedAfterComposite(
                anyList(), any(OffsetDateTime.class), any(), anyInt()))
                .thenReturn(List.of(run));
        when(collector.collectWorkspaceAssets(any()))
                .thenThrow(new IllegalStateException("persistent failure"));

        // 事件路：3 次重试 → DLQ
        listener.onRunFinalized(new AgentRunFinalizedEvent("run-doomed", 1001L, "COMPLETED", false));
        assertEquals(1, scheduler.dlqMemorySize());

        // 轮询路：再 3 次重试 → DLQ 再次累加（两路皆败的可见信号；dedupe/drain 归 D21-B）
        observer.scan();
        assertEquals(2, scheduler.dlqMemorySize(), "两路皆败必须在 DLQ 留下两条信号");
        assertFalse(Files.exists(runDir("run-doomed")), "两路皆败必须零落盘");
    }

    @Test
    void repeatedTriggersAfterSuccessShouldNotAmplifyWrites() throws Exception {
        AgentRun run = run("run-once", OffsetDateTime.now());
        when(runMapper.findById("run-once")).thenReturn(run);
        when(runMapper.listByStatusAndUpdatedAfterComposite(
                anyList(), any(OffsetDateTime.class), any(), anyInt()))
                .thenReturn(List.of(run));
        when(collector.collectWorkspaceAssets(any()))
                .thenReturn(new CollectedAssets(List.of(message("run-once", 1)), List.of(), List.of()));

        // 事件路首次 dump 成功
        listener.onRunFinalized(new AgentRunFinalizedEvent("run-once", 1001L, "COMPLETED", false));
        Path runDir = runDir("run-once");
        byte[] manifestAfterEvent = Files.readAllBytes(runDir.resolve("manifest.json"));
        byte[] stateAfterEvent = Files.readAllBytes(runDir.resolve("workspace_state.json"));
        assertEquals(0, scheduler.dlqMemorySize());

        // 轮询两次重扫同一 run：指纹匹配 + 完整 → 整段 skip，零字节变化
        observer.scan();
        observer.scan();
        assertTrue(java.util.Arrays.equals(manifestAfterEvent,
                Files.readAllBytes(runDir.resolve("manifest.json"))), "重复触发不得重写 manifest");
        assertTrue(java.util.Arrays.equals(stateAfterEvent,
                Files.readAllBytes(runDir.resolve("workspace_state.json"))), "重复触发不得重写 state");
        assertEquals(0, scheduler.dlqMemorySize(), "skip 路径不得产生失败/DLQ");
    }

    // ===== helpers =====

    private Path runDir(String runId) {
        return workspaceRoot.resolve("_by_run_id_index").resolve(runId);
    }

    private static AgentRun run(String runId, OffsetDateTime updatedAt) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("1001");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setStartedAt(updatedAt.minusMinutes(10));
        run.setCompletedAt(updatedAt.minusMinutes(1));
        run.setUpdatedAt(updatedAt);
        return run;
    }

    private static AgentRunMessage message(String runId, int seq) {
        AgentRunMessage m = new AgentRunMessage();
        m.setRunId(runId);
        m.setSeq(seq);
        m.setRole(AgentRunMessage.ROLE_USER);
        m.setMsgType(AgentRunMessage.MSG_TYPE_INITIAL);
        m.setContent("msg-" + seq);
        return m;
    }
}
