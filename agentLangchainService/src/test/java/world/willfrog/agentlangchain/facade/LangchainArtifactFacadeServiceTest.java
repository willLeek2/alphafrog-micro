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
    void listArtifactsForAdminReadsCrossUserRunWithoutOwnerFilter() {
        AgentRun run = runWithArtifactsEnabled();
        when(readService.requireReadableRunForAdmin("r1")).thenReturn(run);
        when(artifactService.listArtifacts(run, true)).thenReturn(List.of(
                AgentArtifactMessage.newBuilder().setArtifactId("a1").build()));

        var response = service.listArtifacts(ListAgentArtifactsRequest.newBuilder()
                .setId("r1")
                .setUserId("admin-user")
                .setIsAdmin(true)
                .build());

        assertEquals(1, response.getItemsCount());
        verify(readService).requireReadableRunForAdmin("r1");
        verify(readService, never()).requireReadableRun("r1", "admin-user");
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
    void downloadArtifactForAdminReadsCrossUserRunWithoutOwnerFilter() {
        AgentRun run = runWithArtifactsEnabled();
        when(artifactService.extractRunId("artifact-1")).thenReturn("r1");
        when(readService.requireReadableRunForAdmin("r1")).thenReturn(run);
        when(artifactService.loadArtifact(run, true, "artifact-1")).thenReturn(
                new AgentArtifactService.ArtifactContent(
                        "artifact-1", "out.csv", "text/csv", "x".getBytes(StandardCharsets.UTF_8)));

        var response = service.downloadArtifact(DownloadAgentArtifactRequest.newBuilder()
                .setUserId("admin-user")
                .setArtifactId("artifact-1")
                .setIsAdmin(true)
                .build());

        assertEquals("artifact-1", response.getArtifactId());
        verify(readService).requireReadableRunForAdmin("r1");
        verify(readService, never()).requireReadableRun("r1", "admin-user");
    }

    @Test
    void artifactPartsForAdminReadCrossUserRunWithoutOwnerFilter() {
        AgentRun run = runWithArtifactsEnabled();
        byte[] content = "artifact-content".getBytes(StandardCharsets.UTF_8);
        when(artifactService.extractRunId("artifact-1")).thenReturn("r1");
        when(readService.requireReadableRunForAdmin("r1")).thenReturn(run);
        when(artifactService.loadArtifactForParts(run, true, "artifact-1")).thenReturn(
                new AgentArtifactService.ArtifactContent("artifact-1", "out.csv", "text/csv", content));
        when(snapshotPartService.getOrBuildBytesMeta("artifact:artifact-1", content, 1024)).thenReturn(
                SnapshotPartsMeta.builder()
                        .partSize(1024)
                        .totalParts(1)
                        .uncompressedSize(content.length)
                        .compressedSize(content.length)
                        .compression("none")
                        .checksum("checksum")
                        .build());
        when(snapshotPartService.getBytesPart("artifact:artifact-1", content, 0, 1024)).thenReturn(content);

        var meta = service.getArtifactPartsMeta(GetAgentArtifactPartsRequest.newBuilder()
                .setUserId("admin-user")
                .setArtifactId("artifact-1")
                .setMaxPartSize(1024)
                .setIsAdmin(true)
                .build());
        var part = service.getArtifactPart(GetAgentArtifactPartRequest.newBuilder()
                .setUserId("admin-user")
                .setArtifactId("artifact-1")
                .setPartIndex(0)
                .setMaxPartSize(1024)
                .setIsAdmin(true)
                .build());

        assertEquals(1, meta.getTotalParts());
        assertEquals("artifact-content", part.getContent().toStringUtf8());
        verify(readService, times(2)).requireReadableRunForAdmin("r1");
        verify(readService, never()).requireReadableRun("r1", "admin-user");
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
