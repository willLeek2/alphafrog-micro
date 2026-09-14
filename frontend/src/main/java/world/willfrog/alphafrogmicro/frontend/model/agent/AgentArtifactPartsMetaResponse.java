package world.willfrog.alphafrogmicro.frontend.model.agent;

public record AgentArtifactPartsMetaResponse(
        String artifactId,
        String filename,
        String contentType,
        int partSize,
        int totalParts,
        long uncompressedSize,
        long compressedSize,
        String compression,
        String checksum
) {
}
