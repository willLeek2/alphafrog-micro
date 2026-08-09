package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentArtifactService.PythonScript;
import world.willfrog.agent.platform.storage.AgentStoragePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D21-A（W5 task #105）：WorkspaceDumpService 的指纹 skip 判定与 EXPIRED 真 conservative。
 *
 * <p>覆盖 kickoff §6 判定点：
 * <ul>
 *   <li>指纹匹配 + 上次完整 → 事件/轮询任一路整段 skip，零重写；</li>
 *   <li>指纹变化 / 上次不完整（brokenRefs&gt;0）/ legacy 状态 / 状态损坏 → 重 dump；</li>
 *   <li>EXPIRED conservative：减量写入、收集失败降级、写失败上抛（不静默吞）。</li>
 * </ul>
 * mapper/collector 用 Mockito 打桩，文件落临时目录，不碰生产 DB/Nacos。</p>
 */
class WorkspaceDumpServiceTest {

    @TempDir
    Path tempDir;

    private AgentRunMapper runMapper;
    private WorkspaceAssetCollector collector;
    private WorkspaceDumpService dumpService;
    private Path workspaceRoot;
    private Path datasetRoot;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspaces");
        datasetRoot = tempDir.resolve("datasets");
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
        dumpService = new WorkspaceDumpService(runMapper, pathResolver, collector, verifier, writer);
    }

    // ===== full 路径：指纹 skip 判定 =====

    @Test
    void fullDumpShouldWriteAllFilesWithModeFull() throws Exception {
        AgentRun run = run("run-1", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-1")).thenReturn(run);
        // 带 createdAt 的 PythonScript：钉住 JavaTimeModule 修复（此前 OffsetDateTime
        // 裸序列化失败，脚本被逐条 warn 静默丢失）
        PythonScript script = new PythonScript("script-1", 1,
                OffsetDateTime.parse("2026-08-09T10:30:00Z"), "print(1)", List.of(), true, "test");
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(message("run-1", 3)), List.of(script), List.of()));

        dumpService.dumpRun("run-1", false);

        Path runDir = runDir("run-1");
        for (String f : List.of("conversation.jsonl", "python_scripts.jsonl",
                "manifest.json", "meta.json", "workspace_state.json")) {
            assertTrue(Files.exists(runDir.resolve(f)), "缺文件: " + f);
        }
        String scripts = Files.readString(runDir.resolve("python_scripts.jsonl"));
        assertTrue(scripts.contains("script-1"), "python script 不得静默丢失");
        JsonNode state = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        assertEquals("full", state.get("mode").asText());
        assertEquals(0, state.get("brokenRefsCount").asInt(-1));
    }

    @Test
    void secondDumpShouldSkipWhenFingerprintMatchesAndComplete() throws Exception {
        AgentRun run = run("run-2", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-2")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(message("run-2", 2)), List.of(), List.of()));

        dumpService.dumpRun("run-2", false);
        Path runDir = runDir("run-2");
        byte[] manifestBefore = Files.readAllBytes(runDir.resolve("manifest.json"));
        byte[] stateBefore = Files.readAllBytes(runDir.resolve("workspace_state.json"));

        // 第二次触发（事件/轮询重复进入）：整段 skip，零字节变化
        dumpService.dumpRun("run-2", false);

        assertTrue(java.util.Arrays.equals(manifestBefore,
                Files.readAllBytes(runDir.resolve("manifest.json"))), "skip 后 manifest 不得重写");
        assertTrue(java.util.Arrays.equals(stateBefore,
                Files.readAllBytes(runDir.resolve("workspace_state.json"))), "skip 后 state 不得重写");
        verify(collector, times(2)).collectWorkspaceAssets(run);
    }

    @Test
    void shouldRedumpWhenFingerprintChanged() throws Exception {
        AgentRun run = run("run-3", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-3")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(message("run-3", 1)), List.of(), List.of()));
        dumpService.dumpRun("run-3", false);
        Path runDir = runDir("run-3");
        String fingerprintBefore = readFingerprint(runDir);

        // run 的 updatedAt 推进 → 指纹变化 → 必须重 dump
        run.setUpdatedAt(OffsetDateTime.parse("2026-08-09T12:00:00Z"));
        dumpService.dumpRun("run-3", false);

        String fingerprintAfter = readFingerprint(runDir);
        assertFalse(fingerprintBefore.equals(fingerprintAfter), "指纹必须随 updatedAt 变化");
    }

    @Test
    void shouldRedumpWhilePreviousDumpHadBrokenRefsThenHealAndSkip() throws Exception {
        // dataset 引用先缺失 → brokenRefs>0 → 不允许 skip；补齐后重 dump 变完整 → 之后可 skip。
        AgentRun run = run("run-4", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-4")).thenReturn(run);
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of("ds-heal"));
        when(collector.collectWorkspaceAssets(run)).thenReturn(assets);

        dumpService.dumpRun("run-4", false);
        Path runDir = runDir("run-4");
        JsonNode state1 = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        assertEquals(1, state1.get("brokenRefsCount").asInt());

        // brokenRefs>0 时不得 skip：再次 dump 应重写（lastExtractedAt 变化）
        String extractedAt1 = state1.get("lastExtractedAt").asText();
        dumpService.dumpRun("run-4", false);
        JsonNode state2 = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        assertFalse(extractedAt1.equals(state2.get("lastExtractedAt").asText()),
                "上次 dump 不完整时禁止 skip");

        // 补齐 dataset 文件 → 重 dump 后完整 → 第四次触发 skip
        Path dsDir = datasetRoot.resolve("ds-heal");
        Files.createDirectories(dsDir);
        Files.writeString(dsDir.resolve("ds-heal.csv"), "col\n1\n");
        dumpService.dumpRun("run-4", false);
        JsonNode state3 = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        assertEquals(0, state3.get("brokenRefsCount").asInt());
        byte[] stateBytes3 = Files.readAllBytes(runDir.resolve("workspace_state.json"));
        dumpService.dumpRun("run-4", false);
        assertTrue(java.util.Arrays.equals(stateBytes3,
                Files.readAllBytes(runDir.resolve("workspace_state.json"))), "完整后必须 skip");
    }

    @Test
    void shouldRedumpWhenLegacyStateLacksModeAndBrokenRefsCount() throws Exception {
        AgentRun run = run("run-5", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-5")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(), List.of(), List.of()));

        // 手工放一个 D21-A 之前的 legacy state（7 字段，无 mode/brokenRefsCount），指纹故意匹配
        Path runDir = runDir("run-5");
        Files.createDirectories(runDir);
        String legacyFingerprint = WorkspaceFingerprints.compute(run, List.of());
        String legacyState = "{\"lastRunId\":\"run-5\",\"lastExtractedAt\":\"2026-08-09T10:00:00Z\","
                + "\"lastManifestVersion\":\"v0\",\"sourceRunCompletedAt\":\"2026-08-09T11:00:00Z\","
                + "\"sourceRunUpdatedAt\":\"2026-08-09T11:00:30Z\",\"lastMessageSeq\":0,"
                + "\"fingerprint\":\"" + legacyFingerprint + "\"}";
        Files.writeString(runDir.resolve("workspace_state.json"), legacyState);

        dumpService.dumpRun("run-5", false);

        JsonNode state = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        assertEquals("full", state.get("mode").asText(),
                "legacy 状态永不 skip，重 dump 一次后收敛到新格式");
        assertNotNull(state.get("brokenRefsCount"));
    }

    @Test
    void shouldRedumpWhenStateFileMalformed() throws Exception {
        AgentRun run = run("run-6", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-6")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(), List.of(), List.of()));
        Path runDir = runDir("run-6");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("workspace_state.json"), "{corrupted");

        dumpService.dumpRun("run-6", false);

        assertTrue(Files.exists(runDir.resolve("manifest.json")), "状态损坏必须触发重 dump 自愈");
    }

    // ===== conservative（EXPIRED）路径 =====

    @Test
    void conservativeDumpShouldWriteOnlyStateAndMeta() throws Exception {
        AgentRun run = run("run-7", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        run.setStatus(AgentRunStatus.EXPIRED);
        when(runMapper.findById("run-7")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(message("run-7", 4)), List.of(), List.of()));

        dumpService.dumpRun("run-7", true);

        Path runDir = runDir("run-7");
        assertTrue(Files.exists(runDir.resolve("workspace_state.json")));
        assertTrue(Files.exists(runDir.resolve("meta.json")));
        assertFalse(Files.exists(runDir.resolve("conversation.jsonl")));
        assertFalse(Files.exists(runDir.resolve("python_scripts.jsonl")));
        assertFalse(Files.exists(runDir.resolve("manifest.json")));

        // 同指纹再次 conservative 触发 → skip（EXPIRED run 的轮询重扫不放大写入）
        byte[] stateBefore = Files.readAllBytes(runDir.resolve("workspace_state.json"));
        dumpService.dumpRun("run-7", true);
        assertTrue(java.util.Arrays.equals(stateBefore,
                Files.readAllBytes(runDir.resolve("workspace_state.json"))));
    }

    @Test
    void conservativeDumpShouldDegradeWhenCollectionFails() throws Exception {
        AgentRun run = run("run-8", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        run.setStatus(AgentRunStatus.EXPIRED);
        when(runMapper.findById("run-8")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenThrow(new IllegalStateException("message table unreachable"));

        // 收集失败不阻断保守落盘：仍写最小状态 + 有限 meta
        dumpService.dumpRun("run-8", true);

        Path runDir = runDir("run-8");
        assertTrue(Files.exists(runDir.resolve("workspace_state.json")));
        assertTrue(Files.exists(runDir.resolve("meta.json")));
        JsonNode meta = new ObjectMapper().readTree(runDir.resolve("meta.json").toFile());
        assertEquals("conservative", meta.get("mode").asText());
        assertFalse(meta.has("health"), "收集失败降级时不伪造 health 块");
    }

    @Test
    void conservativeDumpShouldPropagateWriteFailure() {
        // workspace 根被普通文件占位 → 不可达；conservative 不得静默吞错，必须上抛进重试/DLQ。
        AgentRun run = run("run-9", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        run.setStatus(AgentRunStatus.EXPIRED);
        AgentStoragePaths brokenPaths = new AgentStoragePaths(
                tempDir.resolve("blocked-file").resolve("workspaces").toString(),
                tempDir.resolve("artifacts").toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        Path blocker = tempDir.resolve("blocked-file");
        try {
            Files.writeString(blocker, "x");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WorkspaceDumpService brokenService = new WorkspaceDumpService(runMapper,
                new WorkspacePathResolver(brokenPaths), collector,
                new WorkspaceHealthVerifier(new WorkspacePathResolver(brokenPaths)),
                new WorkspaceManifestWriter(brokenPaths));
        when(runMapper.findById("run-9")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(), List.of(), List.of()));

        // 根被文件占位时，显式失败信号可能在两层之一冒出：pathResolver.runBaseDir
        // 的 toRealPath 先触发 SecurityException，或门面 verifyDumpTarget 触发
        // StorageRootUnavailableException。契约是"必须上抛、零落盘"，不是具体类型。
        assertThrows(RuntimeException.class, () -> brokenService.dumpRun("run-9", true));
        assertFalse(Files.exists(tempDir.resolve("blocked-file").resolve("workspaces")
                .resolve("_by_run_id_index").resolve("run-9").resolve("workspace_state.json")),
                "conservative 失败不得留下任何文件");
    }

    @Test
    void fullDumpShouldPropagateCollectionFailure() {
        AgentRun run = run("run-10", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        when(runMapper.findById("run-10")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenThrow(new IllegalStateException("db blip"));

        // full 路径收集失败必须上抛（scheduler 重试/DLQ 的前提）
        assertThrows(IllegalStateException.class, () -> dumpService.dumpRun("run-10", false));
    }

    @Test
    void dumpRunShouldNotFailOnNonNumericUserId() throws Exception {
        // 裁定：userId 只用于日志；解析失败降级为原样打印，不占用重试/DLQ。
        AgentRun run = run("run-11", OffsetDateTime.parse("2026-08-09T11:00:30Z"));
        run.setUserId("svc-account");
        when(runMapper.findById("run-11")).thenReturn(run);
        when(collector.collectWorkspaceAssets(run))
                .thenReturn(new CollectedAssets(List.of(), List.of(), List.of()));

        dumpService.dumpRun("run-11", false);

        assertTrue(Files.exists(runDir("run-11").resolve("workspace_state.json")));
        verify(collector, atLeastOnce()).collectWorkspaceAssets(any());
    }

    // ===== shouldSkip 判定表（直接钉住） =====

    @Test
    void shouldSkipDecisionTable() {
        String fingerprint = "2026-08-09T11:00:00Z|2026-08-09T11:00:30Z|3";
        WorkspaceManifestWriter.WorkspaceState fullComplete = state(fingerprint, "full", 0);
        WorkspaceManifestWriter.WorkspaceState fullBroken = state(fingerprint, "full", 2);
        WorkspaceManifestWriter.WorkspaceState conservative = state(fingerprint, "conservative", null);
        WorkspaceManifestWriter.WorkspaceState legacy = state(fingerprint, null, null);

        // full 触发：仅"上次 full 且完整"可 skip
        assertTrue(WorkspaceDumpService.shouldSkip(Optional.of(fullComplete), fingerprint, false));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(fullBroken), fingerprint, false));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(conservative), fingerprint, false));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(legacy), fingerprint, false));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.empty(), fingerprint, false));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(fullComplete), "other|other|0", false));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(fullComplete), null, false));

        // conservative 触发：上次 full 或 conservative 且指纹一致均可 skip
        assertTrue(WorkspaceDumpService.shouldSkip(Optional.of(fullComplete), fingerprint, true));
        assertTrue(WorkspaceDumpService.shouldSkip(Optional.of(conservative), fingerprint, true));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(legacy), fingerprint, true));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.empty(), fingerprint, true));
        assertFalse(WorkspaceDumpService.shouldSkip(Optional.of(conservative), "other|other|0", true));
    }

    // ===== helpers =====

    private Path runDir(String runId) {
        return workspaceRoot.resolve("_by_run_id_index").resolve(runId);
    }

    private String readFingerprint(Path runDir) throws Exception {
        JsonNode state = new ObjectMapper().readTree(runDir.resolve("workspace_state.json").toFile());
        return state.get("fingerprint").asText();
    }

    private static WorkspaceManifestWriter.WorkspaceState state(String fingerprint, String mode,
                                                                Integer brokenRefsCount) {
        return new WorkspaceManifestWriter.WorkspaceState("run-x", "2026-08-09T10:00:00Z", "v0",
                "2026-08-09T11:00:00Z", "2026-08-09T11:00:30Z", 3, fingerprint, mode, brokenRefsCount);
    }

    private static AgentRun run(String runId, OffsetDateTime updatedAt) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("1001");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setStartedAt(OffsetDateTime.parse("2026-08-09T10:00:00Z"));
        run.setCompletedAt(OffsetDateTime.parse("2026-08-09T11:00:00Z"));
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
