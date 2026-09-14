package world.willfrog.agent.platform.artifact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RunRawRefCleanupListener 契约测试（260814 scheduler-03）：Run 终态事件到达即
 * 删除该 run 的 rawRef 目录与索引条目；null 事件与空 runId 安全忽略。
 */
class RunRawRefCleanupListenerTest {

    @TempDir
    Path tempDir;

    private Path root;
    private RunRawRefLocalStore localStore;
    private RunRawRefCleanupListener listener;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("raw-ref");
        localStore = new RunRawRefLocalStore(root.toString(),
                8_388_608L, 512, 536_870_912L);
        listener = new RunRawRefCleanupListener(localStore);
    }

    @Test
    void onFinalized_shouldCleanupRunAndKeepOthers() {
        String kept = localStore.register("run_keep", "user_1", "test", "keep", 3600, true);
        localStore.register("run_done", "user_1", "test", "drop", 3600, true);

        listener.onRunFinalized(new AgentRunFinalizedEvent("run_done", 1L, "COMPLETED", false));

        assertFalse(localStore.belongsToRun("run_done", "raw_ref_001"), "终态 run 的条目必须清除");
        assertFalse(java.nio.file.Files.exists(root.resolve("run_done")), "终态 run 的目录必须删除");
        assertTrue(localStore.belongsToRun("run_keep", kept), "其他 run 不受影响");
        assertThrows(IllegalArgumentException.class,
                () -> localStore.read("run_done", "user_1", "raw_ref_001"));
    }

    @Test
    void onFinalized_shouldIgnoreNullEventAndBlankRunId() {
        listener.onRunFinalized(null);
        listener.onRunFinalized(new AgentRunFinalizedEvent(" ", 1L, "COMPLETED", false));
        listener.onRunFinalized(new AgentRunFinalizedEvent(null, 1L, "COMPLETED", false));

        String ref = localStore.register("run_safe", "user_1", "test", "safe", 3600, true);
        assertTrue(localStore.belongsToRun("run_safe", ref), "空事件不得误伤任何条目");
    }
}
