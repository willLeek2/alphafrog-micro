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
