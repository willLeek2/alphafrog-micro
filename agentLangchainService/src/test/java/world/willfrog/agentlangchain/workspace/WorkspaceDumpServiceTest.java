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







    // ===== conservative（EXPIRED）路径 =====






    // ===== shouldSkip 判定表（直接钉住） =====


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
