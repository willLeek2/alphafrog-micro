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





    // ===== D21-A：workspace_state 的 mode/brokenRefsCount 落盘与读回 =====





    // ===== D21-A：writeConservative 减量写入 =====




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
