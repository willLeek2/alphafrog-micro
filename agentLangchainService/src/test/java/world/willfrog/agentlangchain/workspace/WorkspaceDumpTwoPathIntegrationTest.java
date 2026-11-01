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
