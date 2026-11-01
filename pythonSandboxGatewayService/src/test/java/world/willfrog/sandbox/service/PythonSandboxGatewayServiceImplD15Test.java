package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.agent.platform.debug.DebugObservabilityRpcKeys;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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

    // D15 round-2 (codex 8d293d31 #3): JSONL telemetry endpoint assertions need a real
    // sessionDir + RpcContext attachments so DebugObservabilityJsonlAppender writes
    // sandbox-<runId>.jsonl on the temp dir; clear attachments after each test so the
    // thread-local does not leak between cases.
    @TempDir
    Path sessionDir;

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

    // === D15 round-2 (codex 8d293d31 #3): JSONL telemetry helpers ===

    private void bindSession(String runId, String sessionId) {
        // Bind RpcContext attachments so DebugObservabilityJsonlAppender writes to the
        // @TempDir-backed sessionDir. Lets D15 round-2 tests assert sandbox_http.endpoint
        // equals the encoded form (D15 red line 6: telemetry出口和 HTTP URL出口编码一致).
        RpcContext ctx = RpcContext.getServiceContext();
        ctx.setAttachment(DebugObservabilityRpcKeys.SESSION_DIR, sessionDir.toString());
        ctx.setAttachment(DebugObservabilityRpcKeys.RUN_ID, runId);
        ctx.setAttachment(DebugObservabilityRpcKeys.SESSION_ID, sessionId);
    }

    private static List<String> readSandboxHttpEvents(Path sessionDir, String runId) throws Exception {
        Path runFile = sessionDir.resolve("sandbox-" + runId + ".jsonl");
        try (Stream<String> lines = Files.lines(runFile)) {
            return lines.filter(line -> line.contains("\"eventType\":\"sandbox_http\"")).toList();
        }
    }

    // === getTaskStatus: each special character must encode as a single path segment ===





    // === getTaskResult: same encoding applies on /result endpoint ===




    // === Sanity: plain ASCII taskId round-trips unchanged (no over-encoding) ===



    // === Encoding consistency: failure paths ALSO encode (D15 red line 6) ===



    // === D15 round-2 (codex 8d293d31): missing coverage gaps ===



}
