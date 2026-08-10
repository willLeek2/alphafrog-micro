package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * D14 (Q-14): production create refuses blank operationId; resourceClass-only
 * transitional clients are non-production-only behind an explicit switch.
 */
class PythonSandboxGatewayServiceImplD14Test {

    private static PythonSandboxGatewayServiceImpl newGateway(RestTemplate restTemplate) {
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        // Production default: refuse create without operationId.
        ReflectionTestUtils.setField(gateway, "allowCreateWithoutOperationId", false);
        return gateway;
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

    @Test
    void createTaskWithOperationIdStillForwardsCanonicalCreate() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"t-d14\",\"status\":\"QUEUED\"}",
                        MediaType.APPLICATION_JSON));

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setOperationId("run-1:call-1:1")
                .setRequestFingerprint("fp-1")
                .setResourceClass("STANDARD")
                .setCapacityUnits(1)
                .setMemoryLimitBytes(512L * 1024 * 1024)
                .build());

        server.verify();
        assertEquals("t-d14", response.getTaskId());
        assertTrue(response.getError().isBlank());
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
}
