package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceRecordDecoderFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceRecordDecoder decoder = new FinanceRecordDecoder(objectMapper);
    private final FinanceRecordSchemaValidator validator = new FinanceRecordSchemaValidator();

    @Test
    void canonicalFixtureMatchesRawBytesDigestsOrderAndSchema() throws Exception {
        JsonNode root;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "finance/finance-record-channel-v1.json")) {
            root = objectMapper.readTree(input);
        }

        for (JsonNode testCase : root.path("cases")) {
            List<String> lines = new ArrayList<>();
            testCase.path("stdoutLines").forEach(line -> lines.add(line.asText()));
            JsonNode expected = testCase.path("expected");

            FinanceRecordDecoder.DecodedBatch actual = decoder.decode(String.join("\n", lines));

            assertThat(actual.ordinaryStdout())
                    .as(testCase.path("case").asText())
                    .isEqualTo(expected.path("ordinaryStdout").asText());
            assertThat(actual.records()).hasSize(expected.path("resultRecordCount").asInt());
            assertThat(actual.emittedRecordBytes()).isEqualTo(expected.path("emittedRecordBytes").asLong());
            assertThat(actual.recordDigest()).isEqualTo(expected.path("recordDigest").asText());
            List<String> rawDigests = new ArrayList<>();
            expected.path("rawDigests").forEach(digest -> rawDigests.add(digest.asText()));
            assertThat(actual.records().stream().map(FinanceRecordDecoder.DecodedRecord::rawDigest))
                    .containsExactlyElementsOf(rawDigests);

            boolean expectedSchemaValid = !expected.has("schemaValid") || expected.path("schemaValid").asBoolean();
            assertThat(actual.records().stream().allMatch(record -> validator.validate(record).valid()))
                    .isEqualTo(expectedSchemaValid);
        }
    }

    @Test
    void unknownMarkerIsAuditedAndNeverReturnedAsOrdinaryStdout() {
        FinanceRecordDecoder.DecodedBatch decoded = decoder.decode(
                "before\n__AF_FINANCE_RESULT_v2__{\"value\":1}\nafter\n");

        assertThat(decoded.ordinaryStdout()).isEqualTo("before\nafter\n");
        assertThat(decoded.records()).singleElement().satisfies(record -> {
            assertThat(record.knownVersion()).isFalse();
            assertThat(record.decodeError()).isEqualTo("UNSUPPORTED_MARKER_VERSION");
        });
    }

    @Test
    void embeddedOrIndentedMarkerFamilyIsAuditedAndNeverLeaksAsOrdinaryStdout() {
        FinanceRecordDecoder.DecodedBatch decoded = decoder.decode(
                "before\n  __AF_FINANCE_RESULT_v1__{\"value\":1}\n"
                        + "prefix __AF_FINANCE_RESULT_v2__{\"value\":2}\nafter\n");

        assertThat(decoded.ordinaryStdout()).isEqualTo("before\nafter\n");
        assertThat(decoded.records()).hasSize(2).allSatisfy(record -> {
            assertThat(record.knownVersion()).isFalse();
            assertThat(record.decodeError()).isEqualTo("MARKER_NOT_AT_LINE_START");
        });
    }

    @Test
    void emptyBatchUsesSha256OfEmptyBytes() {
        FinanceRecordDecoder.DecodedBatch decoded = decoder.decode("ordinary");

        assertThat(decoded.recordDigest()).isEqualTo(FinanceRecordDecoder.EMPTY_BATCH_DIGEST);
        assertThat(decoded.emittedRecordBytes()).isZero();
    }
}
