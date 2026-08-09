package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 260809-26Q3-stage1-w3 D15 §4.3.1 (Risks 3.3.1 / S3C-03): taskId URL path-segment encoding.
 *
 * Frozen requirement (D15 plan §4.3 + red line 5/6):
 *   - getTaskStatus / getTaskResult (and same-source telemetry) MUST encode taskId as a
 *     single path segment using UriComponentsBuilder.pathSegment(...).build().encode().toUri(),
 *     matching the getTaskByOperationId pattern.
 *   - Bare `sandboxUrl + "/tasks/" + taskId` is forbidden (D15 red line 5).
 *   - Encoding consistency: status AND result AND telemetry all use the same encoded form
 *     (D15 red line 6 — half-fix on one side still leaves injection面).
 *
 * Edge cases this suite锁住:
 *   - `/` in taskId → must encode as `%2F` (single segment, NOT multiple path levels)
 *   - `%` in taskId → must encode as `%25` (avoid being interpreted as a percent-escape)
 *   - space in taskId → must encode as `%20`
 *   - non-ASCII (中文) in taskId → must encode as UTF-8 percent-escaped octets
 *
 * These tests do NOT depend on Docker / Testcontainers; they use MockRestServiceServer to
 * assert the exact encoded URL the gateway emits.
 */
class PythonSandboxGatewayServiceImplD15Test {

    @AfterEach
    void clearRpcContext() {
        try {
            RpcContext.getServiceContext().clearAttachments();
        } catch (Exception ignored) {
            // defensive: RPC context cleanup must not fail the test
        }
    }

    private static PythonSandboxGatewayServiceImpl newGateway(RestTemplate restTemplate) {
        // D13 dual RestTemplate constructor — same instance for both beans; MockRestServiceServer
        // intercepts at the request level regardless of which bean issued the call.
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        return gateway;
    }

    private static String taskStatusJsonBody(String taskId, String status) {
        // Minimal HttpTask JSON the gateway reads back on success path. Only fields read by
        // getTaskStatus's success branch are populated.
        return "{"
                + "\"task_id\": \"" + taskId + "\", "
                + "\"status\": \"" + status + "\""
                + "}";
    }

    private static String taskResultJsonBody() {
        // Minimal HttpExecuteResult JSON the gateway reads back on terminal result fetch.
        return "{"
                + "\"exit_code\": 0, "
                + "\"stdout\": \"ok\", "
                + "\"stderr\": \"\""
                + "}";
    }

    // === getTaskStatus: each special character must encode as a single path segment ===

    @Test
    void getTaskStatusEncodesSlashInTaskIdAsSinglePathSegment() {
        // D15 §4.3.2: taskId with `/` MUST encode as `%2F`. If unencoded, `/` would split
        // the URL into multiple path levels and route to a different (likely non-existent)
        // resource — defeating the "single task resource" semantics.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%2Fwith%2Fslash"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task/with/slash", "RUNNING"),
                        MediaType.APPLICATION_JSON));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task/with/slash").build());

        server.verify();
        assertEquals("task/with/slash", response.getTaskId());
        assertEquals("RUNNING", response.getStatus());
    }

    @Test
    void getTaskStatusEncodesPercentInTaskIdAsPercent25() {
        // D15 §4.3.2: taskId with `%` MUST encode as `%25`. If unencoded, `%xx` sequences
        // would be interpreted as percent-escapes when the downstream parses them — leading
        // to silent data corruption or 404 on a different ID.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%25percent"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task%percent", "RUNNING"),
                        MediaType.APPLICATION_JSON));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task%percent").build());

        server.verify();
        assertEquals("task%percent", response.getTaskId());
    }

    @Test
    void getTaskStatusEncodesSpaceInTaskIdAsPercent20() {
        // D15 §4.3.2: space in taskId → `%20`. Bare space would break HTTP request line
        // parsing.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%20space"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task space", "RUNNING"),
                        MediaType.APPLICATION_JSON));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task space").build());

        server.verify();
        assertEquals("task space", response.getTaskId());
    }

    @Test
    void getTaskStatusEncodesNonAsciiAsUtf8PercentEscaped() {
        // D15 §4.3.2: non-ASCII (中文) in taskId → UTF-8 percent-escaped octets. 中文 in
        // UTF-8 is E4 B8 AD E6 96 87 → `%E4%B8%AD%E6%96%87`.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%E4%B8%AD%E6%96%87"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task中文", "RUNNING"),
                        MediaType.APPLICATION_JSON));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task中文").build());

        server.verify();
        assertEquals("task中文", response.getTaskId());
    }

    // === getTaskResult: same encoding applies on /result endpoint ===

    @Test
    void getTaskResultEncodesSlashInTaskIdAsSinglePathSegmentOnResultEndpoint() {
        // D15 §4.3.3 + red line 6: status AND result一致 encoded. Result endpoint appends
        // "/result" as a separate path segment after the encoded taskId.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        // getTaskResult first calls getTaskStatus (1 GET on /tasks/{encoded}), then if
        // terminal fetches /tasks/{encoded}/result (1 GET on long client). Both URLs must
        // contain the encoded taskId.
        server.expect(once(), requestTo("http://sandbox/tasks/task%2Fwith%2Fslash"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task/with/slash", "SUCCEEDED"),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task%2Fwith%2Fslash/result"))
                .andRespond(withSuccess(taskResultJsonBody(), MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(GetTaskResultRequest.newBuilder()
                .setTaskId("task/with/slash").build());

        server.verify();
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals(0, response.getExitCode());
    }

    @Test
    void getTaskResultEncodesPercentInTaskIdAsPercent25OnResultEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%25percent"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task%percent", "SUCCEEDED"),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task%25percent/result"))
                .andRespond(withSuccess(taskResultJsonBody(), MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(GetTaskResultRequest.newBuilder()
                .setTaskId("task%percent").build());

        server.verify();
        assertEquals("SUCCEEDED", response.getStatus());
    }

    @Test
    void getTaskResultEncodesNonAsciiAsUtf8PercentEscapedOnResultEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%E4%B8%AD%E6%96%87"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task中文", "SUCCEEDED"),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task%E4%B8%AD%E6%96%87/result"))
                .andRespond(withSuccess(taskResultJsonBody(), MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(GetTaskResultRequest.newBuilder()
                .setTaskId("task中文").build());

        server.verify();
        assertEquals("SUCCEEDED", response.getStatus());
    }

    // === Sanity: plain ASCII taskId round-trips unchanged (no over-encoding) ===

    @Test
    void getTaskStatusLeavesPlainAsciiTaskIdUnchanged() {
        // D15 §4.3.1: encoding MUST NOT change plain-ASCII taskIds — existing consumers
        // (D13 tests use `task-1`, `task-missing`) keep working without re-encoding.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task-1", "RUNNING"),
                        MediaType.APPLICATION_JSON));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task-1").build());

        server.verify();
        assertEquals("task-1", response.getTaskId());
    }

    @Test
    void getTaskResultLeavesPlainAsciiTaskIdUnchangedOnResultEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(withSuccess(
                        taskStatusJsonBody("task-1", "SUCCEEDED"),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-1/result"))
                .andRespond(withSuccess(taskResultJsonBody(), MediaType.APPLICATION_JSON));

        TaskResultResponse response = gateway.getTaskResult(GetTaskResultRequest.newBuilder()
                .setTaskId("task-1").build());

        server.verify();
        assertEquals("SUCCEEDED", response.getStatus());
        assertTrue(response.getExitCode() == 0);
    }

    // === Encoding consistency: failure paths ALSO encode (D15 red line 6) ===

    @Test
    void getTaskStatusEncodesSlashInTaskIdOn404NotFoundFailurePath() {
        // D15 §4.3.1 + red line 6: 404 failure path emits telemetry with the SAME encoded
        // URL. Pre-D15 this branch used bare concat; D15 must encode consistently.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%2Fslash"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task/slash").build());

        server.verify();
        // D13 typed-detail semantics still apply on the failure branch.
        assertTrue(response.hasErrorDetail());
        assertEquals("UNKNOWN", response.getStatus());
    }

    @Test
    void getTaskStatusEncodesSlashInTaskIdOn500FailurePath() {
        // D15 §4.3.1 + red line 6: 5xx failure path through buildStatusFailureResponse
        // helper must also use encoded URL (helper internal concat removed).
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task%2Fslash"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        TaskStatusResponse response = gateway.getTaskStatus(GetTaskStatusRequest.newBuilder()
                .setTaskId("task/slash").build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals("UNKNOWN", response.getStatus());
    }
}
