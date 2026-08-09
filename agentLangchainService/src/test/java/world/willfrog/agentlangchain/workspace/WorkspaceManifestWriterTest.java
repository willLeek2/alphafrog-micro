package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.storage.AgentStoragePaths;
import world.willfrog.agent.platform.storage.StorageRootUnavailableException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
