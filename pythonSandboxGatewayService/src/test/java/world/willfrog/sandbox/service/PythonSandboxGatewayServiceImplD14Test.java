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

    @Test
    void createTaskRejectsMissingOperationIdAsInvalidArgumentWithoutHttp() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(never(), requestTo("http://sandbox/tasks"));

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .build());

        server.verify();
        assertFalse(response.getError().isBlank());
        assertTrue(response.getError().contains("operationId is required"));
        assertFalse(response.getError().contains("allow-create-without-operation-id"),
                "caller-facing error must not expose config keys");
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus());
    }

    @Test
    void createTaskRejectsResourceClassOnlyWithoutOperationId() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(never(), requestTo("http://sandbox/tasks"));

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setResourceClass("STANDARD")
                .build());

        server.verify();
        assertTrue(response.getError().contains("operationId is required"));
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
    }

    @Test
    void createTaskRejectsWhitespaceOnlyOperationIdWithoutHttp() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(never(), requestTo("http://sandbox/tasks"));

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setOperationId("   ")
                .setResourceClass("STANDARD")
                .build());

        server.verify();
        assertTrue(response.getError().contains("operationId is required"));
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requestFingerprint",
            "resourceClass",
            "capacityUnits",
            "memoryLimitBytes",
            "timeoutMillis",
            "canonicalSpecSchemaVersion",
            "runtimeEnvironmentVersion",
            "codeHash",
            "immutableDatasetSnapshotDigest",
            "librariesDigest",
            "sandboxOptionsDigest"
    })
    void createTaskRejectsEachMissingCanonicalFieldWithoutHttp(String missingField) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(never(), requestTo("http://sandbox/tasks"));

        ExecuteRequest.Builder builder = completeCanonicalBuilder();
        switch (missingField) {
            case "requestFingerprint" -> builder.clearRequestFingerprint();
            case "resourceClass" -> builder.clearResourceClass();
            case "capacityUnits" -> builder.clearCapacityUnits();
            case "memoryLimitBytes" -> builder.clearMemoryLimitBytes();
            case "timeoutMillis" -> builder.clearTimeoutMillis();
            case "canonicalSpecSchemaVersion" -> builder.clearCanonicalSpecSchemaVersion();
            case "runtimeEnvironmentVersion" -> builder.clearRuntimeEnvironmentVersion();
            case "codeHash" -> builder.clearCodeHash();
            case "immutableDatasetSnapshotDigest" -> builder.clearImmutableDatasetSnapshotDigest();
            case "librariesDigest" -> builder.clearLibrariesDigest();
            case "sandboxOptionsDigest" -> builder.clearSandboxOptionsDigest();
            default -> throw new IllegalArgumentException(missingField);
        }

        ExecuteResponse response = gateway.createTask(builder.build());

        server.verify();
        assertTrue(response.getError().contains("incomplete canonical identity"));
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus());
        assertEquals(missingField,
                PythonSandboxGatewayServiceImpl.findCanonicalCreateDefect(builder.build()));
    }

    @Test
    void createTaskWithCompleteCanonicalForwardsFullIdentityGroup() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andExpect(content().json("""
                        {
                          "resource_class":"STANDARD",
                          "capacity_units":1,
                          "operation_id":"run-1:call-1:1",
                          "request_fingerprint":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                          "memory_limit_bytes":536870912,
                          "timeout_millis":60000,
                          "runtime_environment_version":"python-runtime-v1",
                          "canonical_spec_schema_version":"sandbox_create_v1",
                          "code_hash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                          "immutable_dataset_snapshot_digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                          "libraries_digest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                          "sandbox_options_digest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                        }
                        """, false))
                .andRespond(withSuccess(
                        "{\"task_id\":\"t-d14\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));

        ExecuteResponse response = gateway.createTask(completeCanonicalBuilder().build());

        server.verify();
        assertEquals("t-d14", response.getTaskId());
        assertTrue(response.getError().isBlank());
    }

    @Test
    void createTaskForwardsNonDefaultPositiveMemoryWithoutHardcodingTierBytes() {
        long customMemory = 777L * 1024L * 1024L;
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andExpect(content().json("""
                        {
                          "resource_class":"STANDARD",
                          "capacity_units":1,
                          "operation_id":"run-1:call-1:1",
                          "memory_limit_bytes":814743552
                        }
                        """, false))
                .andRespond(withSuccess(
                        "{\"task_id\":\"t-mem\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));

        ExecuteResponse response = gateway.createTask(completeCanonicalBuilder()
                .setMemoryLimitBytes(customMemory)
                .build());

        server.verify();
        assertEquals("t-mem", response.getTaskId());
        assertTrue(response.getError().isBlank());
    }

    @Test
    void createTaskRejectsNonPositiveMemoryWithoutHttp() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(never(), requestTo("http://sandbox/tasks"));

        ExecuteResponse zero = gateway.createTask(completeCanonicalBuilder()
                .setMemoryLimitBytes(0L)
                .build());
        ExecuteResponse negative = gateway.createTask(completeCanonicalBuilder()
                .setMemoryLimitBytes(-1L)
                .build());

        server.verify();
        assertTrue(zero.getError().contains("incomplete canonical identity"));
        assertTrue(negative.getError().contains("incomplete canonical identity"));
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                zero.getErrorDetail().getCategory());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requestFingerprint",
            "codeHash",
            "immutableDatasetSnapshotDigest",
            "librariesDigest",
            "sandboxOptionsDigest"
    })
    void createTaskRejectsMalformedSha256IdentityFieldWithoutHttp(String field) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(never(), requestTo("http://sandbox/tasks"));

        ExecuteRequest.Builder builder = completeCanonicalBuilder();
        switch (field) {
            case "requestFingerprint" -> builder.setRequestFingerprint("fp-1");
            case "codeHash" -> builder.setCodeHash("x");
            case "immutableDatasetSnapshotDigest" -> builder.setImmutableDatasetSnapshotDigest("sha256:not-hex");
            case "librariesDigest" -> builder.setLibrariesDigest("sha256:" + "g".repeat(64));
            case "sandboxOptionsDigest" -> builder.setSandboxOptionsDigest("sha256:" + "a".repeat(63));
            default -> throw new IllegalArgumentException(field);
        }

        ExecuteResponse response = gateway.createTask(builder.build());

        server.verify();
        assertTrue(response.getError().contains("incomplete canonical identity"));
        assertEquals(field, PythonSandboxGatewayServiceImpl.findCanonicalCreateDefect(builder.build()));
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus());
    }

    @Test
    void explicitNonProductionFlagAllowsKeylessCreate() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        ReflectionTestUtils.setField(gateway, "allowCreateWithoutOperationId", true);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"t-legacy\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setResourceClass("STANDARD")
                .build());

        server.verify();
        assertEquals("t-legacy", response.getTaskId());
    }

    @Test
    void heavyTierRequireMatchingUnitsAndPositiveMemory() {
        assertEquals("capacityUnits",
                PythonSandboxGatewayServiceImpl.findCanonicalCreateDefect(
                        completeCanonicalBuilder()
                                .setResourceClass("HEAVY")
                                .setCapacityUnits(1)
                                .setMemoryLimitBytes(HEAVY_MEMORY)
                                .build()));
        assertEquals(null,
                PythonSandboxGatewayServiceImpl.findCanonicalCreateDefect(
                        completeCanonicalBuilder()
                                .setResourceClass("HEAVY")
                                .setCapacityUnits(3)
                                .setMemoryLimitBytes(1L)
                                .build()));
        assertEquals("memoryLimitBytes",
                PythonSandboxGatewayServiceImpl.findCanonicalCreateDefect(
                        completeCanonicalBuilder()
                                .setResourceClass("HEAVY")
                                .setCapacityUnits(3)
                                .setMemoryLimitBytes(0L)
                                .build()));
    }
}
