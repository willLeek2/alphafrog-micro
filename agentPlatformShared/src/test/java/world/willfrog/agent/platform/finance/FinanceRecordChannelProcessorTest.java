package world.willfrog.agent.platform.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinanceRecordChannelProcessorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private FinanceRecordPersister persister;
    private FinanceMethodResolutionQuery resolutionQuery;
    private FinanceRecordChannelProcessor processor;

    @BeforeEach
    void setUp() {
        persister = mock(FinanceRecordPersister.class);
        resolutionQuery = mock(FinanceMethodResolutionQuery.class);
        processor = new FinanceRecordChannelProcessor(
                new FinanceRecordDecoder(objectMapper),
                new FinanceRecordSchemaValidator(),
                new FinanceEnvironmentVerifier(),
                resolutionQuery,
                persister,
                mock(FinanceRecordChannelObservability.class),
                objectMapper);
    }

    @Test
    void validFixturePersistsAndReturnsOnlyOrdinaryStdout() throws Exception {
        FixtureCase fixture = fixture("one-valid-cagr-result");
        when(resolutionQuery.findExact(any(), any(), any(), any(), any()))
                .thenReturn(resolution("sha256:actual-runtime-example"));

        FinanceRecordExtractionResult result = processor.process(request(fixture, actualEnvironment(), true));

        assertThat(result.persisted()).isTrue();
        assertThat(result.ordinaryStdout()).isEqualTo("rows=5");
        assertThat(result.ordinaryStdout()).doesNotContain(FinanceRecordDecoder.MARKER_FAMILY);
        assertThat(result.modelNotices()).isEmpty();
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.getDeclaredEvidence()).isEqualTo("LIBRARY_CALL_DECLARED");
            assertThat(record.getEffectiveInternalEvidence()).isEqualTo("LIBRARY_CALL_DECLARED");
            assertThat(record.getRenderable()).isTrue();
            assertThat(record.getRecordId()).hasSize(64);
        });
        verify(persister).persist(result.batch(), result.records());
    }

    @Test
    void transportDigestMismatchPersistsOnlyBatchAudit() throws Exception {
        FixtureCase fixture = fixture("one-valid-cagr-result");
        FinanceRecordChannelMetadata badMetadata = new FinanceRecordChannelMetadata(
                fixture.metadata.emittedRecordCount(), fixture.metadata.emittedRecordBytes(), true,
                "", "0".repeat(64), false, false);
        FinanceRecordExtractionRequest request = request(fixture, actualEnvironment(), true, badMetadata);

        FinanceRecordExtractionResult result = processor.process(request);

        assertThat(result.records()).isEmpty();
        assertThat(result.batch().getValidationErrorJson()).contains("FINANCE_RECORD_DIGEST_MISMATCH");
        assertThat(result.modelNotices()).singleElement()
                .extracting(FinanceRecordExtractionResult.ModelNotice::code)
                .isEqualTo("FINANCE_RESULT_REJECTED");
        verify(persister).persist(result.batch(), List.of());
        verifyNoInteractions(resolutionQuery);
    }

    @Test
    void schemaInvalidRecordKeepsRawAuditAndMakesWholeBatchNonRenderable() throws Exception {
        FixtureCase fixture = fixture("one-schema-invalid-custom-result");

        FinanceRecordExtractionResult result = processor.process(request(fixture, actualEnvironment(), true));

        assertThat(result.batch().getSchemaValid()).isFalse();
        assertThat(result.batch().getRenderable()).isFalse();
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.getRawPayload()).contains("not-a-number");
            assertThat(record.getRenderable()).isFalse();
            assertThat(record.getValidationErrorJson()).isNotEqualTo("[]");
        });
        assertThat(result.modelNotices()).hasSize(1);
    }

    @Test
    void environmentMismatchDowngradesEvidenceButDoesNotForceRenderableFalse() throws Exception {
        FixtureCase fixture = fixture("one-valid-cagr-result");
        when(resolutionQuery.findExact(any(), any(), any(), any(), any()))
                .thenReturn(resolution("sha256:resolver-target"));
        FinanceEnvironmentFact otherActual = new FinanceEnvironmentFact(
                "sha256:other-actual", "sha256:image", "sha256:library",
                List.of(new FinanceEnvironmentFact.PackageApi(
                        "alphafrog_finance", "1.0.3", "1.0")), true);

        FinanceRecordExtractionResult result = processor.process(request(fixture, otherActual, true));

        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.getEffectiveInternalEvidence()).isEqualTo("CUSTOM_UNVERIFIED");
            assertThat(record.getRenderable()).isTrue();
            assertThat(record.getValidationErrorJson())
                    .contains("DECLARED_ACTUAL_ENVIRONMENT_MISMATCH", "FINANCE_CROSS_ENVIRONMENT");
        });
        assertThat(result.modelNotices()).isEmpty();
    }

    @Test
    void disabledOrFailedTerminalNeverPersistsBusinessRowsAndStillDemarkers() throws Exception {
        FixtureCase fixture = fixture("one-valid-cagr-result");

        FinanceRecordExtractionResult disabled = processor.process(request(fixture, actualEnvironment(), false));
        FinanceRecordExtractionRequest failedRequest = new FinanceRecordExtractionRequest(
                "run-1", "user-1", "todo-1", "execute-1", "sync", "task-1",
                "FAILED", 1, fixture.stdout, "traceback", fixture.metadata,
                actualEnvironment(), actualEnvironment(), limits(true));
        FinanceRecordExtractionResult failed = processor.process(failedRequest);

        assertThat(disabled.persisted()).isFalse();
        assertThat(failed.persisted()).isFalse();
        assertThat(disabled.ordinaryStdout()).doesNotContain(FinanceRecordDecoder.MARKER_FAMILY);
        assertThat(failed.ordinaryStdout()).doesNotContain(FinanceRecordDecoder.MARKER_FAMILY);
        verifyNoInteractions(persister);
    }

    @Test
    void persistenceFailureIsFailClosed() throws Exception {
        FixtureCase fixture = fixture("one-valid-custom-non-annual-result");
        when(persister.persist(any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> processor.process(request(fixture, actualEnvironment(), true)))
                .isInstanceOf(FinanceRecordProcessingException.class)
                .extracting("code")
                .isEqualTo("FINANCE_RECORD_PERSISTENCE_UNAVAILABLE");
    }

    @Test
    void syncToAsyncReplayKeepsTheSameBatchContentDigest() throws Exception {
        FixtureCase fixture = fixture("one-valid-custom-non-annual-result");
        FinanceRecordExtractionRequest sync = request(
                fixture, actualEnvironment(), true, fixture.metadata, "sync");
        FinanceRecordExtractionRequest async = request(
                fixture, actualEnvironment(), true, fixture.metadata, "async");

        FinanceRecordExtractionResult syncResult = processor.process(sync);
        FinanceRecordExtractionResult asyncResult = processor.process(async);

        assertThat(syncResult.batch().getEntryPoint()).isEqualTo("sync");
        assertThat(asyncResult.batch().getEntryPoint()).isEqualTo("async");
        assertThat(asyncResult.batch().getBatchContentDigest())
                .isEqualTo(syncResult.batch().getBatchContentDigest());
    }

    @Test
    void missingTrustedIdentityIsRejectedBeforePersistence() throws Exception {
        FixtureCase fixture = fixture("one-valid-cagr-result");
        FinanceRecordExtractionRequest request = new FinanceRecordExtractionRequest(
                "run-1", "user-1", "", "execute-1", "sync", "task-1",
                "SUCCEEDED", 0, fixture.stdout, "", fixture.metadata,
                actualEnvironment(), actualEnvironment(), limits(true));

        assertThatThrownBy(() -> processor.process(request))
                .isInstanceOf(FinanceRecordProcessingException.class)
                .extracting("code")
                .isEqualTo("FINANCE_RECORD_IDENTITY_MISSING");
        verifyNoInteractions(persister);
    }

    @Test
    void recordIdIsLengthPrefixedAndStableAcrossCalls() {
        String first = FinanceRecordChannelProcessor.recordId(
                "ab", "c", "tool", 1, "digest");
        String second = FinanceRecordChannelProcessor.recordId(
                "a", "bc", "tool", 1, "digest");

        assertThat(first).hasSize(64).isNotEqualTo(second);
        assertThat(FinanceRecordChannelProcessor.recordId("ab", "c", "tool", 1, "digest"))
                .isEqualTo(first);
    }

    private FinanceRecordExtractionRequest request(
            FixtureCase fixture,
            FinanceEnvironmentFact actual,
            boolean enabled) {
        return request(fixture, actual, enabled, fixture.metadata);
    }

    private FinanceRecordExtractionRequest request(
            FixtureCase fixture,
            FinanceEnvironmentFact actual,
            boolean enabled,
            FinanceRecordChannelMetadata metadata) {
        return request(fixture, actual, enabled, metadata, "sync");
    }

    private FinanceRecordExtractionRequest request(
            FixtureCase fixture,
            FinanceEnvironmentFact actual,
            boolean enabled,
            FinanceRecordChannelMetadata metadata,
            String entryPoint) {
        return new FinanceRecordExtractionRequest(
                "run-1", "user-1", "todo-1", "execute-1", entryPoint, "task-1",
                "SUCCEEDED", 0, fixture.stdout, "", metadata,
                actual, actualEnvironment(), limits(enabled));
    }

    private static FinanceRecordChannelLimits limits(boolean enabled) {
        return new FinanceRecordChannelLimits(
                enabled, 128, 16_384, 262_144, 1_048_576, 262_144,
                "sha256:actual-runtime-example");
    }

    private static FinanceEnvironmentFact actualEnvironment() {
        return new FinanceEnvironmentFact(
                "sha256:actual-runtime-example", "sha256:image", "sha256:library",
                List.of(new FinanceEnvironmentFact.PackageApi(
                        "alphafrog_finance", "1.0.3", "1.0")), true);
    }

    private static FinanceMethodResolution resolution(String targetEnvironmentId) {
        return FinanceMethodResolution.builder()
                .runId("run-1")
                .resolverToolCallId("tool-call-resolver-1")
                .todoId("todo-resolver")
                .methodId("finance.growth.cagr")
                .methodVersion("1.0.0")
                .specDigest("sha256:spec-example")
                .targetEnvironmentId(targetEnvironmentId)
                .targetPackageApiJson("[{\"name\":\"alphafrog_finance\",\"version\":\"1.0.3\",\"apiVersion\":\"1.0\"}]")
                .build();
    }

    private FixtureCase fixture(String name) throws Exception {
        JsonNode root;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "finance/finance-record-channel-v1.json")) {
            root = objectMapper.readTree(input);
        }
        for (JsonNode item : root.path("cases")) {
            if (!name.equals(item.path("case").asText())) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            item.path("stdoutLines").forEach(line -> lines.add(line.asText()));
            JsonNode expected = item.path("expected");
            return new FixtureCase(
                    String.join("\n", lines),
                    new FinanceRecordChannelMetadata(
                            expected.path("resultRecordCount").asInt(),
                            expected.path("emittedRecordBytes").asLong(),
                            expected.path("recordSetComplete").asBoolean(), "",
                            expected.path("recordDigest").asText(), false, false));
        }
        throw new IllegalArgumentException("missing fixture " + name);
    }

    private record FixtureCase(String stdout, FinanceRecordChannelMetadata metadata) {}
}
