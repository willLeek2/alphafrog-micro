package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.agent.platform.debug.DebugObservabilityRpcKeys;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 260809-26Q3-stage1-w3 D13: typed HTTP error semantics — frozen contract + per-category
 * + transport-vs-timeout split + dual-write + authoritative-absence preservation tests.
 *
 * Frozen contract (codex msg 2110f215 + Cindy 4b89c2d6/807334eb):
 *   - SandboxHttpErrorCategory enum 8 values tag 0-7
 *   - SandboxErrorDetail { category=1; optional downstream_http_status=2; } (no message, no retryable)
 *   - 4 responses additive errorDetail tags: ExecuteResponse=6, TaskStatusResponse=6,
 *     TaskResultResponse=12, GetTaskByOperationIdResponse=6
 *   - Authoritative absence (D13 red line 6): ONLY found=false + error blank + error_detail
 *     absent on GetTaskByOperationIdResponse; any present detail (incl. NOT_FOUND) is failure
 *   - Downstream 504 → DOWNSTREAM_FAILURE (NOT GATEWAY_TIMEOUT); 401/403/unknown 4xx →
 *     UNSPECIFIED (NOT INVALID_ARGUMENT)
 */
class PythonSandboxGatewayServiceImplD13Test {

    // 260809-26Q3-stage1-w3 D13 round-2 #3 (Cindy 1b29792d #3): JSONL telemetry
    // assertions need a real sessionDir + RpcContext attachments so DebugObservabilityJsonlAppender
    // writes sandbox-<runId>.jsonl on the temp dir; clear attachments after each test so the
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
        // D13 dual RestTemplate constructor. Tests use the same instance for both beans;
        // MockRestServiceServer intercepts at the request level regardless of which bean
        // issued the call.
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        // D14: existing D13 HTTP-error-surface fixtures intentionally exercise keyless
        // create against MockRestServiceServer. Explicit non-production compat mode —
        // production gate coverage lives in PythonSandboxGatewayServiceImplD14Test.
        ReflectionTestUtils.setField(gateway, "allowCreateWithoutOperationId", true);
        return gateway;
    }

    // 260809-26Q3-stage1-w3 D13 round-2 #3 (Cindy 1b29792d #3 + 179de723):
    // distinct-client helper. Long + short client each get their own MockRestServiceServer
    // so tests can prove which bean a given entry point routes through. Per Cindy 179de723:
    //   - createTask: long only (1 POST on long, 0 on short)
    //   - getTaskStatus: short only (1 GET on short, 0 on long)
    //   - getTaskByOperationId: short only (1 GET on short, 0 on long)
    //   - getTaskResult: short for status + long for result (1 GET each)
    private static PythonSandboxGatewayServiceImpl newGatewayDistinctClients(
            RestTemplate longClient, RestTemplate shortClient
    ) {
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(longClient, shortClient, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        ReflectionTestUtils.setField(gateway, "allowCreateWithoutOperationId", true);
        return gateway;
    }

    // === Frozen proto tag / enum lock ===





    // === createTask per-category mapping ===







    // === Transport vs timeout split ===



    // === Authoritative absence preservation (D13 fail-closed red line 6) ===




    // === getTaskStatus 404 with NOT_FOUND detail ===


    // === getTaskResult per-category ===


    // === Dual-write invariant ===


    private static ExecuteRequest buildMinimalCreateRequest() {
        return ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .build();
    }

    // === MUST-FIX 1 (Cindy 91490076 #1): getTaskResult preserves status typed failure ===



    // === MUST-FIX 2a (Cindy 91490076 #2): blank operationId dual-write ===


    // === MUST-FIX 2b (Cindy 91490076 #2): found=true + error nonblank writes detail ===


    // === MUST-FIX 2c (Cindy 91490076 #2): blank exception message uses fallback ===


    // === MUST-FIX 3 (Cindy 91490076 #3 + 6a6e6158): timeout validation ===







    private static void assertTimeoutValidationFails(
            long connect, long shortRead, long longRead, long max, long margin, String expectedToken
    ) {
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> world.willfrog.sandbox.config.RestTemplateConfig.validateTimeoutConfiguration(
                        connect, shortRead, longRead, max, margin)
        );
        assertTrue(ex.getMessage().contains(expectedToken),
                "error message MUST mention " + expectedToken + "; got: " + ex.getMessage());
    }



    // === MUST-FIX 4 (Cindy 91490076 #4): downstream_http_status = ACTUAL response status ===


    // === MUST-FIX 5 (Cindy 91490076 #5): 404 emits ERROR not OK ===
    // (Implicitly tested via getTaskStatus404ReturnsNotFoundDetailWithDownstreamStatus above;
    // telemetry-level JSONL emission assertion is out of unit-test scope but the production
    // code at PythonSandboxGatewayServiceImpl.java emits "ERROR" + frozen category for 404.)




    // === Round-2 MUST-FIX #2 (Cindy 1b29792d #2 + codex aa8987d1): effective-timeout
    //     precision + NaN/Infinity/negative reject + zero-margin reject ===
















    // === Round-2 MUST-FIX #3a (Cindy 1b29792d #3 + 179de723): distinct-client tests ===





    // === Round-2 MUST-FIX #3b (Cindy 1b29792d #3): JSONL telemetry assertions ===

    private void bindSession(String runId, String sessionId) {
        // Bind RpcContext attachments so DebugObservabilityJsonlAppender writes to the
        // @TempDir-backed sessionDir. Verifies the emitSandboxHttp path actually appends
        // one final sandbox_http event per entry, with status + category matching the
        // behavioral response shape.
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





    // === Round-2 MUST-FIX #1 regression (Cindy 50caba3f): terminal status + /result 2xx
    //     empty body — exact regression for the production branch added in 7b6180cc.
    //     Combines distinct-client layering + behavioral shape + JSONL telemetry in one
    //     end-to-end case so the branch can never silently regress to not-ready again. ===

}
