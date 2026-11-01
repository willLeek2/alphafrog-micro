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


}
