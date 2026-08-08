package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Extracts marker-family lines without normalizing their raw JSON bytes. */
@Component
public class FinanceRecordDecoder {

    public static final String MARKER_FAMILY = "__AF_FINANCE_RESULT_";
    public static final String MARKER_V1 = "__AF_FINANCE_RESULT_v1__";
    public static final String EMPTY_BATCH_DIGEST = sha256Hex(new byte[0]);

    private final ObjectMapper objectMapper;

    public FinanceRecordDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecodedBatch decode(String stdout) {
        String source = stdout == null ? "" : stdout;
        List<DecodedRecord> records = new ArrayList<>();
        List<String> ordinaryLines = new ArrayList<>();
        ByteArrayOutputStream digestInput = new ByteArrayOutputStream();
        long totalBytes = 0L;

        String[] lines = source.split("\\n", -1);
        boolean endedWithNewline = source.endsWith("\n");
        int logicalLineCount = endedWithNewline ? lines.length - 1 : lines.length;
        for (int i = 0; i < logicalLineCount; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!line.startsWith(MARKER_FAMILY)) {
                ordinaryLines.add(line);
                continue;
            }

            int recordIndex = records.size();
            boolean knownVersion = line.startsWith(MARKER_V1);
            String rawPayload = knownVersion
                    ? line.substring(MARKER_V1.length())
                    : line.substring(MARKER_FAMILY.length());
            byte[] rawBytes = rawPayload.getBytes(StandardCharsets.UTF_8);
            totalBytes = Math.addExact(totalBytes, rawBytes.length);
            digestInput.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(rawBytes.length).array());
            digestInput.writeBytes(rawBytes);

            JsonNode node = null;
            String decodeError = null;
            if (!knownVersion) {
                decodeError = "UNSUPPORTED_MARKER_VERSION";
            } else {
                try {
                    node = objectMapper.readTree(rawBytes);
                    if (node == null || !node.isObject()) {
                        decodeError = "RECORD_JSON_NOT_OBJECT";
                    }
                } catch (Exception exception) {
                    decodeError = "RECORD_JSON_INVALID";
                }
            }
            records.add(new DecodedRecord(
                    recordIndex,
                    knownVersion,
                    rawPayload,
                    sha256Hex(rawBytes),
                    rawBytes.length,
                    node,
                    decodeError));
        }

        String ordinaryStdout = String.join("\n", ordinaryLines);
        if (endedWithNewline && !ordinaryLines.isEmpty()) {
            ordinaryStdout += "\n";
        }
        return new DecodedBatch(
                records,
                ordinaryStdout,
                totalBytes,
                sha256Hex(digestInput.toByteArray()));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record DecodedBatch(
            List<DecodedRecord> records,
            String ordinaryStdout,
            long emittedRecordBytes,
            String recordDigest) {
        public DecodedBatch {
            records = List.copyOf(records);
        }
    }

    public record DecodedRecord(
            int recordIndex,
            boolean knownVersion,
            String rawPayload,
            String rawDigest,
            int rawBytes,
            JsonNode json,
            String decodeError) {
    }
}
