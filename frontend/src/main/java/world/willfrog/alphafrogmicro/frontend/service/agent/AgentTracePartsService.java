package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.frontend.model.agent.TraceDetailResponse;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/** 为仅管理员可用的完整 trace API 构建确定性的 gzip 分片。 */
@Service
public class AgentTracePartsService {

    private static final int DEFAULT_PART_SIZE = 512 * 1024;
    private static final int MIN_PART_SIZE = 64 * 1024;
    private static final int MAX_PART_SIZE = 2 * 1024 * 1024;

    public PreparedFullParts prepare(AgentRawTraceDetailMapper.FullTracePayload payload, int requestedPartSize) {
        byte[] compressed = gzip(payload.fullDetailBytes());
        int partSize = resolvePartSize(requestedPartSize);
        int totalParts = compressed.length == 0 ? 0 : (compressed.length + partSize - 1) / partSize;
        return new PreparedFullParts(compressed, partSize, totalParts, md5Hex(compressed));
    }

    public TraceDetailResponse.FullDetailParts describe(String runId,
                                                        String traceId,
                                                        AgentRawTraceDetailMapper.FullTracePayload payload,
                                                        int requestedPartSize) {
        PreparedFullParts prepared = prepare(payload, requestedPartSize);
        return new TraceDetailResponse.FullDetailParts(
                "/api/agent/runs/" + runId + "/traces/" + traceId
                        + "/full/parts?maxPartSize=" + prepared.partSize(),
                prepared.partSize(),
                prepared.totalParts(),
                (long) payload.fullDetailBytes().length,
                (long) prepared.compressed().length,
                "gzip",
                prepared.checksum(),
                nullableLong(payload.fullDetail().get("createdAtMillis")),
                nullableLong(payload.fullDetail().get("expiresAtMillis"))
        );
    }

    public byte[] part(PreparedFullParts prepared, int partIndex) {
        if (partIndex < 0 || partIndex >= prepared.totalParts()) {
            throw new IndexOutOfBoundsException("trace full part index out of range");
        }
        return Arrays.copyOfRange(
                prepared.compressed(),
                partIndex * prepared.partSize(),
                Math.min(prepared.compressed().length, (partIndex + 1) * prepared.partSize()));
    }

    private int resolvePartSize(int requestedPartSize) {
        int effective = requestedPartSize > 0 ? requestedPartSize : DEFAULT_PART_SIZE;
        return Math.max(MIN_PART_SIZE, Math.min(MAX_PART_SIZE, effective));
    }

    private byte[] gzip(byte[] raw) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length);
             GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(raw);
            gzip.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("failed to gzip trace full detail", e);
        }
    }

    private String md5Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute trace full checksum", e);
        }
    }

    private Long nullableLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    public record PreparedFullParts(byte[] compressed, int partSize, int totalParts, String checksum) {
    }
}
