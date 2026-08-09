package world.willfrog.agent.platform.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D04 统一存储路径门面测试（W5 task #105）。
 *
 * <p>覆盖：新键 → 旧键别名 → 默认值解析链、空白回退、dataset 旧键别名、
 * §4.3 可达性失败信号（requireWritableRoot / verifyDumpTarget）。
 */
class AgentStoragePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyEnvironmentShouldFallBackToDefaults() {
        AgentStoragePaths paths = new AgentStoragePaths(new MockEnvironment());

        assertEquals(Path.of(AgentStoragePaths.DEFAULT_WORKSPACE_ROOT).toAbsolutePath().normalize(),
                paths.workspaceRoot());
        assertEquals(Path.of(AgentStoragePaths.DEFAULT_ARTIFACT_ROOT).toAbsolutePath().normalize(),
                paths.artifactRoot());
        assertEquals(Path.of(AgentStoragePaths.DEFAULT_DATASET_ROOT).toAbsolutePath().normalize(),
                paths.datasetRoot());
        assertEquals(Path.of(AgentStoragePaths.DEFAULT_OBSERVABILITY_DEBUG_FILE).toAbsolutePath().normalize(),
                paths.observabilityDebugFile());
    }

    @Test
    void legacyKeysShouldActAsAliasesWhenNewKeysAbsent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(AgentStoragePaths.LEGACY_WORKSPACE_ROOT, "/legacy/workspaces")
                .withProperty(AgentStoragePaths.LEGACY_ARTIFACT_ROOT, "/legacy/artifacts")
                .withProperty(AgentStoragePaths.LEGACY_DATASET_ROOT, "/legacy/datasets")
                .withProperty(AgentStoragePaths.LEGACY_OBSERVABILITY_DEBUG_FILE, "/legacy/debug.log");

        AgentStoragePaths paths = new AgentStoragePaths(env);

        assertEquals(Path.of("/legacy/workspaces"), paths.workspaceRoot());
        assertEquals(Path.of("/legacy/artifacts"), paths.artifactRoot());
        assertEquals(Path.of("/legacy/datasets"), paths.datasetRoot());
        assertEquals(Path.of("/legacy/debug.log"), paths.observabilityDebugFile());
    }

    @Test
    void newKeysShouldWinOverLegacyAliases() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(AgentStoragePaths.KEY_WORKSPACE_ROOT, "/new/workspaces")
                .withProperty(AgentStoragePaths.LEGACY_WORKSPACE_ROOT, "/legacy/workspaces")
                .withProperty(AgentStoragePaths.KEY_ARTIFACT_ROOT, "/new/artifacts")
                .withProperty(AgentStoragePaths.LEGACY_ARTIFACT_ROOT, "/legacy/artifacts")
                .withProperty(AgentStoragePaths.KEY_DATASET_ROOT, "/new/datasets")
                .withProperty(AgentStoragePaths.LEGACY_DATASET_ROOT, "/legacy/datasets")
                .withProperty(AgentStoragePaths.KEY_OBSERVABILITY_DEBUG_FILE, "/new/debug.log")
                .withProperty(AgentStoragePaths.LEGACY_OBSERVABILITY_DEBUG_FILE, "/legacy/debug.log");

        AgentStoragePaths paths = new AgentStoragePaths(env);

        assertEquals(Path.of("/new/workspaces"), paths.workspaceRoot());
        assertEquals(Path.of("/new/artifacts"), paths.artifactRoot());
        assertEquals(Path.of("/new/datasets"), paths.datasetRoot());
        assertEquals(Path.of("/new/debug.log"), paths.observabilityDebugFile());
    }

    @Test
    void blankNewKeyShouldFallBackToLegacyAndBlankLegacyToDefault() {
        MockEnvironment env = new MockEnvironment()
                // yml ${ENV:} 占位在 env 未设时解析为空串——必须视为未设置。
                .withProperty(AgentStoragePaths.KEY_WORKSPACE_ROOT, "  ")
                .withProperty(AgentStoragePaths.LEGACY_WORKSPACE_ROOT, "/legacy/workspaces")
                .withProperty(AgentStoragePaths.KEY_ARTIFACT_ROOT, "")
                .withProperty(AgentStoragePaths.LEGACY_ARTIFACT_ROOT, "   ");

        AgentStoragePaths paths = new AgentStoragePaths(env);

        assertEquals(Path.of("/legacy/workspaces"), paths.workspaceRoot());
        assertEquals(Path.of(AgentStoragePaths.DEFAULT_ARTIFACT_ROOT).toAbsolutePath().normalize(),
                paths.artifactRoot());
    }

    @Test
    void datasetRootLegacyAliasShouldBeMarketDataDatasetPath() {
        // WorkspaceManifestWriter 原硬编码 /data/agent_datasets/；配置化后与现存
        // dataset 根键 agent.tools.market-data.dataset.path 共用同一解析链。
        MockEnvironment env = new MockEnvironment()
                .withProperty("agent.tools.market-data.dataset.path", "/custom/datasets");

        AgentStoragePaths paths = new AgentStoragePaths(env);

        assertEquals(Path.of("/custom/datasets"), paths.datasetRoot());
    }

    @Test
    void requireWritableRootShouldCreateMissingDirectory() {
        AgentStoragePaths paths = new AgentStoragePaths(new MockEnvironment());
        Path root = tempDir.resolve("new-root");

        Path verified = paths.requireWritableRoot(root, AgentStoragePaths.KEY_WORKSPACE_ROOT);

        assertEquals(root.toAbsolutePath().normalize(), verified);
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void requireWritableRootShouldRejectPathOccupiedByFile() throws Exception {
        AgentStoragePaths paths = new AgentStoragePaths(new MockEnvironment());
        Path file = tempDir.resolve("a-file");
        Files.writeString(file, "x");

        StorageRootUnavailableException ex = assertThrows(StorageRootUnavailableException.class,
                () -> paths.requireWritableRoot(file, AgentStoragePaths.KEY_ARTIFACT_ROOT));
        assertTrue(ex.getMessage().contains(AgentStoragePaths.KEY_ARTIFACT_ROOT));
        assertTrue(ex.getMessage().contains(file.toAbsolutePath().normalize().toString()));
        assertTrue(ex.getMessage().contains("not a directory"));
    }

    @Test
    void requireWritableRootShouldFailWhenParentIsRegularFile() throws Exception {
        AgentStoragePaths paths = new AgentStoragePaths(new MockEnvironment());
        Path parentFile = tempDir.resolve("parent-file");
        Files.writeString(parentFile, "x");
        Path impossibleRoot = parentFile.resolve("child-root");

        StorageRootUnavailableException ex = assertThrows(StorageRootUnavailableException.class,
                () -> paths.requireWritableRoot(impossibleRoot, AgentStoragePaths.KEY_WORKSPACE_ROOT));
        assertTrue(ex.getMessage().contains(AgentStoragePaths.KEY_WORKSPACE_ROOT));
        assertTrue(ex.getMessage().contains("createDirectories failed"));
    }

    @Test
    void verifyDumpTargetShouldAcceptTargetInsideWorkspaceRoot() {
        Path workspaceRoot = tempDir.resolve("ws");
        AgentStoragePaths paths = new AgentStoragePaths(
                workspaceRoot.toString(),
                tempDir.resolve("art").toString(),
                tempDir.resolve("ds").toString(),
                tempDir.resolve("debug.log").toString());

        Path target = workspaceRoot.resolve("_by_run_id_index").resolve("run-1");
        Path verified = paths.verifyDumpTarget(target);

        assertEquals(target.toAbsolutePath().normalize(), verified);
    }

    @Test
    void verifyDumpTargetShouldRejectTargetOutsideWorkspaceRoot() {
        Path workspaceRoot = tempDir.resolve("ws");
        AgentStoragePaths paths = new AgentStoragePaths(
                workspaceRoot.toString(),
                tempDir.resolve("art").toString(),
                tempDir.resolve("ds").toString(),
                tempDir.resolve("debug.log").toString());

        Path outside = tempDir.resolve("elsewhere").resolve("run-1");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> paths.verifyDumpTarget(outside));
        assertTrue(ex.getMessage().contains("escapes configured workspace root"));
    }

    @Test
    void verifyDumpTargetShouldRejectNull() {
        AgentStoragePaths paths = new AgentStoragePaths(new MockEnvironment());
        assertThrows(IllegalArgumentException.class, () -> paths.verifyDumpTarget(null));
    }

    @Test
    void explicitConstructorShouldNormalizeAbsolutePaths() {
        AgentStoragePaths paths = new AgentStoragePaths(
                "relative/ws", "relative/art", "relative/ds", "relative/debug.log");

        assertTrue(paths.workspaceRoot().isAbsolute());
        assertTrue(paths.artifactRoot().isAbsolute());
        assertTrue(paths.datasetRoot().isAbsolute());
        assertTrue(paths.observabilityDebugFile().isAbsolute());
    }

    @Test
    void explicitConstructorShouldRejectBlankValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentStoragePaths("  ", "/a", "/d", "/f.log"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentStoragePaths("/w", null, "/d", "/f.log"));
    }
}
