package world.willfrog.agentlangchain.facade;

import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentArtifactService;
import world.willfrog.agent.platform.service.SnapshotPartService;
import world.willfrog.agent.platform.service.SnapshotPartsMeta;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactPartMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactPartsMetaMessage;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentArtifactPartsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsRequest;
import world.willfrog.alphafrogmicro.agent.idl.ListAgentArtifactsResponse;

@Service
@RequiredArgsConstructor
public class LangchainArtifactFacadeService {

    private final LangchainRunReadService readService;
    private final AgentArtifactService artifactService;
    private final SnapshotPartService snapshotPartService;

    public ListAgentArtifactsResponse listArtifacts(ListAgentArtifactsRequest request) {
        AgentRun run = readService.requireReadableRun(request.getId(), request.getUserId());
        if (!generateArtifactsRequested(run)) {
            // 260814 scheduler-03: artifact 默认关闭。未请求的 Run 查询 artifact
            // 列表返回空列表，且不触发 AgentArtifactService 的惰性注册。
            return ListAgentArtifactsResponse.newBuilder().build();
        }
        return ListAgentArtifactsResponse.newBuilder()
                .addAllItems(artifactService.listArtifacts(run, request.getIsAdmin()))
                .build();
    }

    public DownloadAgentArtifactResponse downloadArtifact(DownloadAgentArtifactRequest request) {
        String runId = artifactService.extractRunId(request.getArtifactId());
        AgentRun run = readService.requireReadableRun(runId, request.getUserId());
        requireArtifactsEnabled(run);
        AgentArtifactService.ArtifactContent artifact = artifactService.loadArtifact(
                run,
                request.getIsAdmin(),
                request.getArtifactId());
        return DownloadAgentArtifactResponse.newBuilder()
                .setArtifactId(artifact.artifactId())
                .setFilename(nvl(artifact.filename()))
                .setContentType(nvl(artifact.contentType()))
                .setContent(ByteString.copyFrom(artifact.content()))
                .build();
    }

    public AgentArtifactPartsMetaMessage getArtifactPartsMeta(GetAgentArtifactPartsRequest request) {
        String runId = artifactService.extractRunId(request.getArtifactId());
        AgentRun run = readService.requireReadableRun(runId, request.getUserId());
        requireArtifactsEnabled(run);
        AgentArtifactService.ArtifactContent artifact = artifactService.loadArtifactForParts(
                run,
                request.getIsAdmin(),
                request.getArtifactId());
        SnapshotPartsMeta meta = snapshotPartService.getOrBuildBytesMeta(
                artifactPartKey(request.getArtifactId()),
                artifact.content(),
                request.getMaxPartSize());
        return AgentArtifactPartsMetaMessage.newBuilder()
                .setArtifactId(artifact.artifactId())
                .setFilename(nvl(artifact.filename()))
                .setContentType(nvl(artifact.contentType()))
                .setPartSize(meta.getPartSize())
                .setTotalParts(meta.getTotalParts())
                .setUncompressedSize(meta.getUncompressedSize())
                .setCompressedSize(meta.getCompressedSize())
                .setCompression(nvl(meta.getCompression()))
                .setChecksum(nvl(meta.getChecksum()))
                .build();
    }

    public AgentArtifactPartMessage getArtifactPart(GetAgentArtifactPartRequest request) {
        String runId = artifactService.extractRunId(request.getArtifactId());
        AgentRun run = readService.requireReadableRun(runId, request.getUserId());
        requireArtifactsEnabled(run);
        AgentArtifactService.ArtifactContent artifact = artifactService.loadArtifactForParts(
                run,
                request.getIsAdmin(),
                request.getArtifactId());
        SnapshotPartsMeta meta = snapshotPartService.getOrBuildBytesMeta(
                artifactPartKey(request.getArtifactId()),
                artifact.content(),
                request.getMaxPartSize());
        byte[] content = snapshotPartService.getBytesPart(
                artifactPartKey(request.getArtifactId()),
                artifact.content(),
                request.getPartIndex(),
                request.getMaxPartSize());
        return AgentArtifactPartMessage.newBuilder()
                .setArtifactId(artifact.artifactId())
                .setFilename(nvl(artifact.filename()))
                .setContentType(nvl(artifact.contentType()))
                .setPartIndex(request.getPartIndex())
                .setPartSize(meta.getPartSize())
                .setTotalParts(meta.getTotalParts())
                .setContent(ByteString.copyFrom(content))
                .setCompression(nvl(meta.getCompression()))
                .build();
    }

    /**
     * 260814 scheduler-03: artifact 是显式按请求开启的能力。创建 Run 时由
     * task #118 把请求字段冻结进 ext.generate_artifacts（缺失/无法解析按
     * false）。未开启的 Run 不得产生或暴露任何 artifact。
     */
    private void requireArtifactsEnabled(AgentRun run) {
        if (!generateArtifactsRequested(run)) {
            throw new IllegalArgumentException("artifact not found");
        }
    }

    private static boolean generateArtifactsRequested(AgentRun run) {
        String ext = run == null ? null : run.getExt();
        if (ext == null || ext.isBlank()) {
            return false;
        }
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readTree(ext)
                    .path("generate_artifacts")
                    .asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String artifactPartKey(String artifactId) {
        return "artifact:" + nvl(artifactId);
    }
}
