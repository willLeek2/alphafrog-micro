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
