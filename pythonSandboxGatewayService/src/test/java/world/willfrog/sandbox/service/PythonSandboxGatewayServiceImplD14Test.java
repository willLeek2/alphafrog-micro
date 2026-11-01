package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * D14 (Q-14): production create refuses blank operationId and incomplete
 * canonical identity groups; resourceClass-only transitional clients are
 * non-production-only behind an explicit switch (no capacity admission /
 * no idempotent recovery — must not point at production).
 */
class PythonSandboxGatewayServiceImplD14Test {

    private static final long STANDARD_MEMORY = 512L * 1024L * 1024L;
    private static final long HEAVY_MEMORY = 1536L * 1024L * 1024L;

    private static PythonSandboxGatewayServiceImpl newGateway(RestTemplate restTemplate) {
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        // Production default: refuse create without operationId.
        ReflectionTestUtils.setField(gateway, "allowCreateWithoutOperationId", false);
        return gateway;
    }

    private static ExecuteRequest.Builder completeCanonicalBuilder() {
        return ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setOperationId("run-1:call-1:1")
                .setRequestFingerprint("sha256:" + "f".repeat(64))
                .setResourceClass("STANDARD")
                .setCapacityUnits(1)
                .setMemoryLimitBytes(STANDARD_MEMORY)
                .setTimeoutMillis(60_000L)
                .setRuntimeEnvironmentVersion("python-runtime-v1")
                .setCanonicalSpecSchemaVersion("sandbox_create_v1")
                .setCodeHash("sha256:" + "a".repeat(64))
                .setImmutableDatasetSnapshotDigest("sha256:" + "b".repeat(64))
                .setLibrariesDigest("sha256:" + "c".repeat(64))
                .setSandboxOptionsDigest("sha256:" + "d".repeat(64));
    }










}
