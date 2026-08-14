package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;

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

    @Test
    void listArtifactsDelegatesToSharedService() {
        AgentRun run = runWithArtifactsEnabled();
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);
        when(artifactService.listArtifacts(run, false)).thenReturn(List.of(
                AgentArtifactMessage.newBuilder().setArtifactId("a1").build()));

        var response = service.listArtifacts(ListAgentArtifactsRequest.newBuilder()
                .setId("r1")
                .setUserId("u1")
                .build());

        assertEquals(1, response.getItemsCount());
        assertEquals("a1", response.getItems(0).getArtifactId());
    }

    @Test
    void downloadArtifactValidatesOwnershipThroughReadService() {
        AgentRun run = runWithArtifactsEnabled();
        when(artifactService.extractRunId("artifact-1")).thenReturn("r1");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);
        when(artifactService.loadArtifact(run, false, "artifact-1")).thenReturn(
                new AgentArtifactService.ArtifactContent("artifact-1", "out.csv", "text/csv", "x".getBytes()));

        var response = service.downloadArtifact(DownloadAgentArtifactRequest.newBuilder()
                .setUserId("u1")
                .setArtifactId("artifact-1")
                .build());

        assertEquals("artifact-1", response.getArtifactId());
        assertEquals("out.csv", response.getFilename());
    }

    @Test
    void listArtifactsReturnsEmptyWithoutRequestAndSkipsLazyRegistration() {
        // ext 缺失（老 Run 或未请求）：空列表，且绝不触发 AgentArtifactService
        // 的惰性注册路径
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);

        var response = service.listArtifacts(ListAgentArtifactsRequest.newBuilder()
                .setId("r1")
                .setUserId("u1")
                .build());

        assertEquals(0, response.getItemsCount());
        verify(artifactService, never()).listArtifacts(any(), anyBoolean());
    }

    @Test
    void listArtifactsReturnsEmptyWhenExtCannotBeParsed() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setExt("{broken json");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);

        var response = service.listArtifacts(ListAgentArtifactsRequest.newBuilder()
                .setId("r1")
                .setUserId("u1")
                .build());

        assertEquals(0, response.getItemsCount());
        verify(artifactService, never()).listArtifacts(any(), anyBoolean());
    }

    @Test
    void downloadFailsClosedWithoutRequest() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        when(artifactService.extractRunId("artifact-1")).thenReturn("r1");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);

        assertThrows(IllegalArgumentException.class,
                () -> service.downloadArtifact(DownloadAgentArtifactRequest.newBuilder()
                        .setUserId("u1")
                        .setArtifactId("artifact-1")
                        .build()));
        verify(artifactService, never()).loadArtifact(any(), anyBoolean(), anyString());
    }

    @Test
    void partsMetaFailsClosedWithoutRequest() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        when(artifactService.extractRunId("artifact-1")).thenReturn("r1");
        when(readService.requireReadableRun("r1", "u1")).thenReturn(run);

        assertThrows(IllegalArgumentException.class,
                () -> service.getArtifactPartsMeta(GetAgentArtifactPartsRequest.newBuilder()
                        .setUserId("u1")
                        .setArtifactId("artifact-1")
                        .build()));
        verify(artifactService, never()).loadArtifactForParts(any(), anyBoolean(), anyString());
    }
}
