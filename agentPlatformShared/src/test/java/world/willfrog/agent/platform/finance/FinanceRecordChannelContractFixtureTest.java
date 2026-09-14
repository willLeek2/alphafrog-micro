package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FinanceRecordChannelContractFixtureTest {

    private static final String MARKER = "__AF_FINANCE_RESULT_v1__";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void expectedBytesAndDigestsMatchRawPayloads() throws Exception {
        JsonNode fixture = readFixture();

        for (JsonNode testCase : fixture.path("cases")) {
            List<byte[]> payloads = new ArrayList<>();
            List<String> ordinaryLines = new ArrayList<>();
            for (JsonNode stdoutLine : testCase.path("stdoutLines")) {
                String line = stdoutLine.asText();
                if (line.startsWith(MARKER)) {
                    payloads.add(line.substring(MARKER.length()).getBytes(StandardCharsets.UTF_8));
                } else {
                    ordinaryLines.add(line);
                }
            }

            JsonNode expected = testCase.path("expected");
            String caseName = testCase.path("case").asText();
            assertEquals(expected.path("ordinaryStdout").asText(),
                    String.join("\n", ordinaryLines), caseName);
            assertEquals(expected.path("resultRecordCount").asInt(), payloads.size(), caseName);
            assertEquals(expected.path("emittedRecordBytes").asLong(),
                    payloads.stream().mapToLong(payload -> payload.length).sum(), caseName);

            List<String> rawDigests = payloads.stream()
                    .map(FinanceRecordChannelContractFixtureTest::sha256Hex)
                    .toList();
            assertEquals(OBJECT_MAPPER.convertValue(expected.path("rawDigests"), List.class),
                    rawDigests, caseName);

            int digestInputLength = payloads.stream().mapToInt(payload -> Integer.BYTES + payload.length).sum();
            ByteBuffer digestInput = ByteBuffer.allocate(digestInputLength);
            for (byte[] payload : payloads) {
                digestInput.putInt(payload.length);
                digestInput.put(payload);
            }
            assertEquals(expected.path("recordDigest").asText(),
                    sha256Hex(digestInput.array()), caseName);
        }
    }

    @Test
    void fixtureCoversNonAnnualAndSchemaInvalidRecords() throws Exception {
        JsonNode fixture = readFixture();
        JsonNode nonAnnual = findCase(fixture, "one-valid-custom-non-annual-result");
        String payload = nonAnnual.path("stdoutLines").get(1).asText().substring(MARKER.length());
        JsonNode parameters = OBJECT_MAPPER.readTree(payload).path("parameters");

        assertEquals(37, parameters.path("lookbackTradingDays").asInt());
        assertNull(parameters.get("periods"));
        assertFalse(findCase(fixture, "one-schema-invalid-custom-result")
                .path("expected").path("schemaValid").asBoolean());
    }

    private JsonNode findCase(JsonNode fixture, String caseName) {
        for (JsonNode testCase : fixture.path("cases")) {
            if (caseName.equals(testCase.path("case").asText())) {
                return testCase;
            }
        }
        throw new AssertionError("missing fixture case: " + caseName);
    }

    private JsonNode readFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/finance/finance-record-channel-v1.json")) {
            assertNotNull(input, "finance record contract fixture must be on test classpath");
            return OBJECT_MAPPER.readTree(input);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}
