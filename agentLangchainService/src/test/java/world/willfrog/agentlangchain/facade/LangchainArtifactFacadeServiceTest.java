package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agent.platform.service.SnapshotPartsMeta;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 260814 scheduler-03：artifact 是显式按请求开启的能力（task #118 冻结进
 * ext.generate_artifacts，缺失/无法解析按 false）。未开启的 Run：list 返回空列表
 * 且不触发 AgentArtifactService 惰性注册；download/parts 一律按「产物不存在」
 * fail-closed。正路径测试的 mock run 必须显式开启该开关。
 */
class LangchainArtifactFacadeServiceTest {

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentArtifactService artifactService = mock(AgentArtifactService.class);
    private final SnapshotPartService snapshotPartService = mock(SnapshotPartService.class);
    private final LangchainArtifactFacadeService service =
            new LangchainArtifactFacadeService(readService, artifactService, snapshotPartService);

    private static AgentRun runWithArtifactsEnabled() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setExt("{\"generate_artifacts\": true}");
        return run;
    }










}
