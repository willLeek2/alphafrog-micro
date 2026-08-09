package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.agent.platform.storage.StorageRootUnavailableException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D04 对 WorkspaceManifestWriter 的两条改动验证（W5 task #105）：
 * <ol>
 *   <li>manifest.json assets[].refPath 经统一存储门面 datasetRoot 拼接，
 *       不再硬编码 {@code /data/agent_datasets/} 前缀；</li>
 *   <li>writeAll 入口 verifyDumpTarget：目标越出 workspace 根或根不可达时
 *       明确失败（§4.3），不静默写错位置。</li>
 * </ol>
 *
 * <p>D21-A 追加：workspace_state.json 的 mode/brokenRefsCount 落盘与读回、
 * writeConservative 减量写入（文件集合/降级/失败信号不降级）。</p>
 */
class WorkspaceManifestWriterTest {

    @TempDir
    Path tempDir;

    private Path workspaceRoot;
    private Path datasetRoot;
    private WorkspaceManifestWriter writer;

    @BeforeEach
    void setUp() {
        workspaceRoot = tempDir.resolve("workspaces");
        datasetRoot = tempDir.resolve("datasets");
        AgentStoragePaths storagePaths = new AgentStoragePaths(
                workspaceRoot.toString(),
                tempDir.resolve("artifacts").toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        writer = new WorkspaceManifestWriter(storagePaths);
    }

    @Test
    void refPathShouldUseConfiguredDatasetRoot() throws Exception {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of("ds-1"));
        WorkspaceHealth health = new WorkspaceHealth(1, List.of(), List.of());
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-1");

        writer.writeAll(runDir, run, assets, health);

        JsonNode manifest = new ObjectMapper().readTree(runDir.resolve("manifest.json").toFile());
        JsonNode refPath = manifest.get("assets").get(0).get("refPath");
        assertEquals(datasetRoot.resolve("ds-1").toAbsolutePath().normalize().toString(),
                refPath.asText());
        assertFalse(refPath.asText().startsWith("/data/agent_datasets"),
                "refPath 不应再使用硬编码 /data/agent_datasets 前缀");
    }

    @Test
    void writeAllShouldRejectTargetOutsideWorkspaceRoot() {
        AgentRun run = new AgentRun();
        run.setId("run-2");
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of());
        WorkspaceHealth health = new WorkspaceHealth(0, List.of(), List.of());
        Path outside = tempDir.resolve("elsewhere").resolve("run-2");

        assertThrows(SecurityException.class, () -> writer.writeAll(outside, run, assets, health));
        assertFalse(Files.exists(outside.resolve("manifest.json")),
                "越界目标不得产生任何落盘");
    }

    @Test
    void writeAllShouldFailWhenWorkspaceRootUnreachable() throws Exception {
        // workspace 根被普通文件占位 → 不可达，必须显式失败而不是静默写错位置。
        Path blocker = tempDir.resolve("blocker-file");
        Files.writeString(blocker, "x");
        AgentStoragePaths brokenPaths = new AgentStoragePaths(
                blocker.resolve("workspaces").toString(),
                tempDir.resolve("artifacts").toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        WorkspaceManifestWriter brokenWriter = new WorkspaceManifestWriter(brokenPaths);

        AgentRun run = new AgentRun();
        run.setId("run-3");
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of());
        WorkspaceHealth health = new WorkspaceHealth(0, List.of(), List.of());
        Path runDir = blocker.resolve("workspaces").resolve("_by_run_id_index").resolve("run-3");

        assertThrows(StorageRootUnavailableException.class,
                () -> brokenWriter.writeAll(runDir, run, assets, health));
    }

    @Test
    void writeAllShouldKeepExistingManifestFields() throws Exception {
        AgentRun run = new AgentRun();
        run.setId("run-4");
        run.setUserId("user-4");
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of("ds-a", "ds-b"));
        WorkspaceHealth health = new WorkspaceHealth(2, List.of(), List.of());
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-4");

        WorkspaceManifestWriter.WriteResult result = writer.writeAll(runDir, run, assets, health);

        assertTrue(Files.exists(runDir.resolve("conversation.jsonl")));
        assertTrue(Files.exists(runDir.resolve("python_scripts.jsonl")));
        assertTrue(Files.exists(runDir.resolve("manifest.json")));
        assertTrue(Files.exists(runDir.resolve("meta.json")));
        assertTrue(Files.exists(runDir.resolve("workspace_state.json")));
        assertEquals(runDir, result.runDir());
        JsonNode manifest = new ObjectMapper().readTree(runDir.resolve("manifest.json").toFile());
        assertEquals("run-4", manifest.get("runId").asText());
        assertEquals(2, manifest.get("assets").size());
        assertEquals(datasetRoot.resolve("ds-a").toAbsolutePath().normalize().toString(),
                manifest.get("assets").get(0).get("refPath").asText());
        assertEquals(datasetRoot.resolve("ds-b").toAbsolutePath().normalize().toString(),
                manifest.get("assets").get(1).get("refPath").asText());
    }

    // ===== D21-A：workspace_state 的 mode/brokenRefsCount 落盘与读回 =====

    @Test
    void writeAllShouldRecordFullModeAndBrokenRefsCountInState() throws Exception {
        AgentRun run = runWithTimestamps("run-10");
        CollectedAssets assets = new CollectedAssets(List.of(message("run-10", 3)), List.of(), List.of());
        WorkspaceHealth health = new WorkspaceHealth(1,
                List.of(new BrokenRef("ds-x", "/somewhere/ds-x.csv", "atomic dataset csv 缺失")),
                List.of());
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-10");

        writer.writeAll(runDir, run, assets, health);

        WorkspaceManifestWriter.WorkspaceState state = writer.readWorkspaceState(runDir).orElseThrow();
        assertEquals(WorkspaceManifestWriter.MODE_FULL, state.mode());
        assertEquals(1, state.brokenRefsCount());
        assertEquals(3, state.lastMessageSeq());
        assertEquals(WorkspaceFingerprints.compute(run, assets.messages()), state.fingerprint());
    }

    @Test
    void writeAllCompleteHealthShouldRecordZeroBrokenRefsCount() throws Exception {
        AgentRun run = runWithTimestamps("run-11");
        CollectedAssets assets = new CollectedAssets(List.of(), List.of(), List.of());
        WorkspaceHealth health = new WorkspaceHealth(0, List.of(), List.of());
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-11");

        writer.writeAll(runDir, run, assets, health);

        WorkspaceManifestWriter.WorkspaceState state = writer.readWorkspaceState(runDir).orElseThrow();
        assertEquals(WorkspaceManifestWriter.MODE_FULL, state.mode());
        assertEquals(0, state.brokenRefsCount());
    }

    @Test
    void readWorkspaceStateShouldReturnEmptyWhenStateMissing() {
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-missing");
        assertTrue(writer.readWorkspaceState(runDir).isEmpty());
    }

    @Test
    void readWorkspaceStateShouldReturnEmptyForMalformedState() throws Exception {
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-12");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("workspace_state.json"), "{not valid json");
        assertTrue(writer.readWorkspaceState(runDir).isEmpty(),
                "损坏状态必须按缺失处理（触发重 dump），不能让 dump 失败");
    }

    // ===== D21-A：writeConservative 减量写入 =====

    @Test
    void writeConservativeShouldOnlyWriteStateAndLimitedMeta() throws Exception {
        AgentRun run = runWithTimestamps("run-13");
        run.setStatus(AgentRunStatus.EXPIRED);
        CollectedAssets assets = new CollectedAssets(List.of(message("run-13", 5)), List.of(), List.of());
        WorkspaceHealth health = new WorkspaceHealth(0, List.of(), List.of());
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-13");

        writer.writeConservative(runDir, run, assets, health);

        // 减量文件集合：只有状态 + 有限 meta，不写另外三个文件
        assertTrue(Files.exists(runDir.resolve("workspace_state.json")));
        assertTrue(Files.exists(runDir.resolve("meta.json")));
        assertFalse(Files.exists(runDir.resolve("conversation.jsonl")));
        assertFalse(Files.exists(runDir.resolve("python_scripts.jsonl")));
        assertFalse(Files.exists(runDir.resolve("manifest.json")));

        WorkspaceManifestWriter.WorkspaceState state = writer.readWorkspaceState(runDir).orElseThrow();
        assertEquals(WorkspaceManifestWriter.MODE_CONSERVATIVE, state.mode());
        assertNull(state.brokenRefsCount(), "conservative 不做完整性声明");
        assertEquals(5, state.lastMessageSeq());
        JsonNode meta = new ObjectMapper().readTree(runDir.resolve("meta.json").toFile());
        assertEquals("conservative", meta.get("mode").asText());
        assertTrue(meta.has("health"), "收集成功时 health 块仍落盘");
    }

    @Test
    void writeConservativeShouldTolerateNullAssetsAndHealth() throws Exception {
        AgentRun run = runWithTimestamps("run-14");
        run.setStatus(AgentRunStatus.EXPIRED);
        Path runDir = workspaceRoot.resolve("_by_run_id_index").resolve("run-14");

        writer.writeConservative(runDir, run, null, null);

        WorkspaceManifestWriter.WorkspaceState state = writer.readWorkspaceState(runDir).orElseThrow();
        assertEquals(WorkspaceManifestWriter.MODE_CONSERVATIVE, state.mode());
        assertEquals(0, state.lastMessageSeq());
        assertEquals(WorkspaceFingerprints.compute(run, null), state.fingerprint());
        JsonNode meta = new ObjectMapper().readTree(runDir.resolve("meta.json").toFile());
        assertEquals("conservative", meta.get("mode").asText());
        assertFalse(meta.has("health"), "无校验数据时不伪造 health 块");
    }

    @Test
    void writeConservativeShouldFailWhenRootUnreachable() throws Exception {
        // conservative 不降低失败信号：根被普通文件占位 → 显式上抛，而不是静默吞掉。
        Path blocker = tempDir.resolve("blocker-conservative");
        Files.writeString(blocker, "x");
        AgentStoragePaths brokenPaths = new AgentStoragePaths(
                blocker.resolve("workspaces").toString(),
                tempDir.resolve("artifacts").toString(),
                datasetRoot.toString(),
                tempDir.resolve("obs-debug.log").toString());
        WorkspaceManifestWriter brokenWriter = new WorkspaceManifestWriter(brokenPaths);

        AgentRun run = runWithTimestamps("run-15");
        run.setStatus(AgentRunStatus.EXPIRED);
        Path runDir = blocker.resolve("workspaces").resolve("_by_run_id_index").resolve("run-15");

        assertThrows(StorageRootUnavailableException.class,
                () -> brokenWriter.writeConservative(runDir, run, null, null));
    }

    private static AgentRun runWithTimestamps(String runId) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("1001");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setStartedAt(OffsetDateTime.parse("2026-08-09T10:00:00Z"));
        run.setCompletedAt(OffsetDateTime.parse("2026-08-09T11:00:00Z"));
        run.setUpdatedAt(OffsetDateTime.parse("2026-08-09T11:00:30Z"));
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
