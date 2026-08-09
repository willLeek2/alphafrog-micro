package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
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

    private static PythonSandboxGatewayServiceImpl newGateway(RestTemplate restTemplate) {
        // D13 dual RestTemplate constructor. Tests use the same instance for both beans;
        // MockRestServiceServer intercepts at the request level regardless of which bean
        // issued the call.
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        return gateway;
    }

    // === Frozen proto tag / enum lock ===

    @Test
    void sandboxHttpErrorCategoryShouldKeepFrozenEnumTags() {
        assertEquals(0, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED.getNumber());
        assertEquals(1, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT.getNumber());
        assertEquals(2, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT.getNumber());
        assertEquals(3, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE.getNumber());
        assertEquals(4, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND.getNumber());
        assertEquals(5, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_GATEWAY_TIMEOUT.getNumber());
        assertEquals(6, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_TRANSPORT_FAILURE.getNumber());
        assertEquals(7, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE.getNumber());
    }

    @Test
    void sandboxErrorDetailShouldKeepFrozenFieldNumbers() {
        assertEquals(1, SandboxErrorDetail.CATEGORY_FIELD_NUMBER);
        assertEquals(2, SandboxErrorDetail.DOWNSTREAM_HTTP_STATUS_FIELD_NUMBER);
    }

    @Test
    void errorDetailShouldKeepFrozenTagsAcrossFourResponses() {
        assertEquals(6, ExecuteResponse.ERRORDETAIL_FIELD_NUMBER);
        assertEquals(6, TaskStatusResponse.ERRORDETAIL_FIELD_NUMBER);
        assertEquals(12, TaskResultResponse.ERRORDETAIL_FIELD_NUMBER);
        assertEquals(6, GetTaskByOperationIdResponse.ERRORDETAIL_FIELD_NUMBER);
    }

    @Test
    void sandboxErrorDetailParentPresenceDistinguishesLegacyFromMapped() {
        // Absent parent = legacy producer; consumer MUST NOT read default category as mapped.
        ExecuteResponse legacyResp = ExecuteResponse.newBuilder().setError("some text").build();
        assertFalse(legacyResp.hasErrorDetail());

        // Present parent with explicit category = D13 mapped.
        SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND)
                .setDownstreamHttpStatus(404)
                .build();
        ExecuteResponse d13Resp = ExecuteResponse.newBuilder()
                .setError("not found")
                .setErrorDetail(detail)
                .build();
        assertTrue(d13Resp.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND,
                d13Resp.getErrorDetail().getCategory()
        );
        assertTrue(d13Resp.getErrorDetail().hasDownstreamHttpStatus());
        assertEquals(404, d13Resp.getErrorDetail().getDownstreamHttpStatus());
    }

    // === createTask per-category mapping ===

    @Test
    void createTaskConflictReturnsConflictCategoryWithDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                response.getErrorDetail().getCategory()
        );
        assertEquals(409, response.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(response.getError().isBlank(), "parent error MUST stay non-blank on failure");
    }

    @Test
    void createTaskBadRequestReturnsInvalidArgumentCategoryWithDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory()
        );
        assertEquals(400, response.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void createTaskOverloadedReturnsOverloadedCategoryWithDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory()
        );
        assertEquals(503, response.getErrorDetail().getDownstreamHttpStatus());
    }

    @Test
    void createTaskDownstream500ReturnsDownstreamFailureCategoryWithDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE,
                response.getErrorDetail().getCategory()
        );
        assertEquals(500, response.getErrorDetail().getDownstreamHttpStatus());
    }

    @Test
    void createTaskDownstream504ReturnsDownstreamFailureNotGatewayTimeout() {
        // Cindy 4b89c2d6 #4: downstream 504 is DOWNSTREAM_FAILURE not GATEWAY_TIMEOUT
        // (downstream did respond, even if with a 504).
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE,
                response.getErrorDetail().getCategory(),
                "downstream 504 must map to DOWNSTREAM_FAILURE, NOT GATEWAY_TIMEOUT"
        );
        assertEquals(504, response.getErrorDetail().getDownstreamHttpStatus());
    }

    @Test
    void createTaskDownstream401ReturnsUnspecifiedNotInvalidArgument() {
        // Cindy 4b89c2d6 #4: 401/403/unknown 4xx → UNSPECIFIED, NOT INVALID_ARGUMENT
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                response.getErrorDetail().getCategory(),
                "401 must map to UNSPECIFIED, NOT INVALID_ARGUMENT"
        );
        assertEquals(401, response.getErrorDetail().getDownstreamHttpStatus());
    }

    // === Transport vs timeout split ===

    @Test
    void createTaskReadTimeoutReturnsGatewayTimeoutCategoryWithoutDownstreamStatus() {
        // Cindy 313d871e #3: read timeout → GATEWAY_TIMEOUT, downstream_http_status absent
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        // Spring wraps read timeout as ResourceAccessException with SocketTimeoutException cause
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException(
                            "I/O error on POST request for \"http://sandbox/tasks\": Read timed out",
                            new java.net.SocketTimeoutException("Read timed out"));
                });

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_GATEWAY_TIMEOUT,
                response.getErrorDetail().getCategory()
        );
        assertFalse(
                response.getErrorDetail().hasDownstreamHttpStatus(),
                "downstream_http_status MUST be absent on gateway timeout (no downstream response)"
        );
        assertFalse(response.getError().isBlank());
    }

    @Test
    void createTaskTransportFailureReturnsTransportFailureCategoryWithoutDownstreamStatus() {
        // Cindy 313d871e #3: DNS/conn refused/TLS/non-timeout IO → TRANSPORT_FAILURE
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException(
                            "I/O error on POST request for \"http://sandbox/tasks\": sandbox.local",
                            new java.net.UnknownHostException("sandbox.local"));
                });

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_TRANSPORT_FAILURE,
                response.getErrorDetail().getCategory()
        );
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus());
    }

    // === Authoritative absence preservation (D13 fail-closed red line 6) ===

    @Test
    void getTaskByOperationIdBusinessNotFoundWithBlankErrorIsAuthoritativeAbsence() {
        // D13 v2 修订 3: ONLY found=false + error blank + error_detail absent = authoritative
        // absence on GetTaskByOperationIdResponse. This is the only path that MAY release
        // PREPARING on the consumer side.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/operations/run-1:call-1:1"))
                .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder()
                        .setOperationId("run-1:call-1:1")
                        .build());

        server.verify();
        assertFalse(response.getFound());
        assertTrue(response.getError().isBlank(), "blank error signal for authoritative absence");
        assertFalse(response.hasErrorDetail(), "error_detail MUST be absent for authoritative absence");
    }

    @Test
    void getTaskByOperationIdHttp404ReturnsNotFoundDetailButNotAuthoritativeAbsence() {
        // D13 v2 修订 3 + Cindy 4b89c2d6 #3: HTTP 404 from downstream gets NOT_FOUND
        // category + downstream_http_status=404, BUT this is NOT authoritative absence.
        // Any present error_detail (including NOT_FOUND) is fail-closed — consumer MUST
        // preserve PREPARING and not release it.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/operations/run-1:call-1:1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder()
                        .setOperationId("run-1:call-1:1")
                        .build());

        server.verify();
        assertFalse(response.getFound());
        assertTrue(response.hasErrorDetail(), "404 must surface error_detail so consumer fails closed");
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND,
                response.getErrorDetail().getCategory()
        );
        assertEquals(404, response.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(response.getError().isBlank(), "parent error MUST stay non-blank");
    }

    @Test
    void getTaskByOperationIdHttp503ReturnsOverloadedCategory() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/operations/run-1:call-1:1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder()
                        .setOperationId("run-1:call-1:1")
                        .build());

        server.verify();
        assertFalse(response.getFound());
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory()
        );
        assertEquals(503, response.getErrorDetail().getDownstreamHttpStatus());
    }

    // === getTaskStatus 404 with NOT_FOUND detail ===

    @Test
    void getTaskStatus404ReturnsNotFoundDetailWithDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task-missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        TaskStatusResponse response = gateway.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId("task-missing").build());

        server.verify();
        assertEquals("UNKNOWN", response.getStatus());
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND,
                response.getErrorDetail().getCategory()
        );
        assertEquals(404, response.getErrorDetail().getDownstreamHttpStatus());
    }

    // === getTaskResult per-category ===

    @Test
    void getTaskResultConflictReturnsConflictCategoryWithDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        // getTaskResult first calls getTaskStatus (200 SUCCEEDED) then fetches /result
        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(withSuccess(
                        "{\"task_id\":\"task-1\",\"status\":\"SUCCEEDED\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://sandbox/tasks/task-1/result"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        TaskResultResponse response = gateway.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId("task-1").build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                response.getErrorDetail().getCategory()
        );
        assertEquals(409, response.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(response.getError().isBlank());
    }

    // === Dual-write invariant ===

    @Test
    void dualWriteBothErrorAndErrorDetailArePresentOnFailure() {
        // D13 §4.2 + §6 red line 4: new producer MUST dual-write error (string) +
        // error_detail (message) on every failure path. Old consumers reading only
        // `error` retain fail-closed behavior; new consumers switch to category.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertFalse(response.getError().isBlank(), "parent error MUST be non-blank");
        assertTrue(response.hasErrorDetail(), "typed error_detail MUST be present");
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                response.getErrorDetail().getCategory()
        );
    }

    private static ExecuteRequest buildMinimalCreateRequest() {
        return ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .build();
    }

    // === MUST-FIX 1 (Cindy 91490076 #1): getTaskResult preserves status typed failure ===

    @Test
    void getTaskResultPropagatesStatusTypedFailure503WithoutCallingResultEndpoint() {
        // Status pre-check returns 503 with typed detail; getTaskResult MUST propagate the
        // same detail and NOT call /result endpoint.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        // No expectation for /tasks/task-1/result — the test framework will fail if called.

        TaskResultResponse response = gateway.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId("task-1").build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory()
        );
        assertEquals(503, response.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(response.getError().isBlank(), "parent error MUST be non-blank");
    }

    @Test
    void getTaskResultPropagatesStatusGatewayTimeoutWithoutCallingResultEndpoint() {
        // Status pre-check times out (GATEWAY_TIMEOUT); getTaskResult MUST propagate the
        // same category + absent downstream_http_status, no result call.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(request -> {
                    throw new org.springframework.web.client.ResourceAccessException(
                            "I/O error: Read timed out",
                            new java.net.SocketTimeoutException("Read timed out"));
                });

        TaskResultResponse response = gateway.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId("task-1").build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_GATEWAY_TIMEOUT,
                response.getErrorDetail().getCategory()
        );
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus());
        assertFalse(response.getError().isBlank());
    }

    // === MUST-FIX 2a (Cindy 91490076 #2): blank operationId dual-write ===

    @Test
    void getTaskByOperationIdBlankOperationIdDualWritesInvalidArgumentWithoutDownstreamStatus() {
        // Local reject — no downstream HTTP call, downstream_http_status absent.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        // No expectation — blank operationId MUST short-circuit before HTTP.

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder().setOperationId("").build());

        server.verify();
        assertFalse(response.getFound());
        assertFalse(response.getError().isBlank());
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory()
        );
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus(),
                "local reject MUST NOT fabricate downstream_http_status");
    }

    // === MUST-FIX 2b (Cindy 91490076 #2): found=true + error nonblank writes detail ===

    @Test
    void getTaskByOperationIdFoundTrueWithErrorNonblankWritesUnspecifiedDetail() {
        // Contradictory body: found=true but error non-blank. Cindy 91490076 #2 says
        // any non-blank body error MUST surface as typed failure detail.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/operations/run-1"))
                .andRespond(withSuccess(
                        "{\"found\":true,\"task_id\":\"task-x\",\"error\":\"partial state\"}",
                        MediaType.APPLICATION_JSON));

        GetTaskByOperationIdResponse response = gateway.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder().setOperationId("run-1").build());

        server.verify();
        assertTrue(response.getFound(), "found=true from body still propagated");
        assertTrue(response.hasErrorDetail(), "non-blank body error MUST surface typed detail");
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                response.getErrorDetail().getCategory()
        );
        assertEquals(200, response.getErrorDetail().getDownstreamHttpStatus());
        assertEquals("partial state", response.getError());
    }

    // === MUST-FIX 2c (Cindy 91490076 #2): blank exception message uses fallback ===

    @Test
    void blankExceptionMessageUsesFallbackText() {
        // nonBlankOr(Throwable, fallback) — null/blank/whitespace message MUST fall back.
        Exception blankMsg = new RuntimeException("   ");
        Exception nullMsg = new RuntimeException((String) null);
        Exception realMsg = new RuntimeException("real failure");
        assertEquals("fallback", PythonSandboxGatewayServiceImpl.nonBlankOr(blankMsg, "fallback"));
        assertEquals("fallback", PythonSandboxGatewayServiceImpl.nonBlankOr(nullMsg, "fallback"));
        assertEquals("real failure", PythonSandboxGatewayServiceImpl.nonBlankOr(realMsg, "fallback"));
    }

    // === MUST-FIX 3 (Cindy 91490076 #3 + 6a6e6158): timeout validation ===

    @Test
    void restTemplateConfigRejectsZeroConnectTimeout() {
        assertTimeoutValidationFails(0, 10000, 2100000, 1800000, 300000, "connect-timeout-millis");
    }

    @Test
    void restTemplateConfigRejectsNegativeLongReadTimeout() {
        assertTimeoutValidationFails(5000, 10000, -1, 1800000, 300000, "long-read-timeout-millis");
    }

    @Test
    void restTemplateConfigRejectsShortNotStrictlyLessThanLong() {
        assertTimeoutValidationFails(5000, 2100000, 2100000, 1800000, 300000,
                "short-read-timeout-millis");
    }

    @Test
    void restTemplateConfigRejectsLongBelowMaxPlusMargin() {
        // long = 1500000, max + margin = 1800000 + 300000 = 2100000 → long < required → reject.
        assertTimeoutValidationFails(5000, 10000, 1500000, 1800000, 300000,
                "long-read-timeout-millis");
    }

    @Test
    void restTemplateConfigRejectsZeroMaxTaskTimeout() {
        assertTimeoutValidationFails(5000, 10000, 2100000, 0, 300000, "max-task-timeout-millis");
    }

    @Test
    void restTemplateConfigAcceptsValidFrozenDefaults() {
        // Cindy 91490076 + ccqwen 5c543fea frozen defaults: connect=5s, short=10s, long=35min,
        // max=30min, margin=5min → long (2100000) = max + margin (1800000 + 300000) ✓ and
        // short (10000) < long (2100000) ✓.
        org.springframework.context.ApplicationContextException thrown = null;
        try {
            world.willfrog.sandbox.config.RestTemplateConfig.validateTimeoutConfiguration(
                    5000, 10000, 2100000, 1800000, 300000);
        } catch (org.springframework.context.ApplicationContextException e) {
            thrown = e;
        }
        org.junit.jupiter.api.Assertions.assertNull(thrown,
                "frozen defaults MUST be accepted without throwing");
    }

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

    @Test
    void createTaskLocalRejectsEffectiveTimeoutOverMaxWithInvalidArgumentAndAbsentDownstreamStatus() {
        // Cindy 6a6e6158: threshold is `effective > max` (NOT > max + margin).
        // max default = 1800000ms; set timeoutMillis to 1800001 → effective > max → local reject.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        // No HTTP expectation — local reject MUST short-circuit.

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setTimeoutMillis(1800001L)
                .build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory()
        );
        assertFalse(response.getErrorDetail().hasDownstreamHttpStatus(),
                "local reject MUST NOT fabricate downstream_http_status");
        assertFalse(response.getError().isBlank());
    }

    @Test
    void createTaskLocalRejectUsesMaxOfLegacySecondsAndCanonicalMillis() {
        // Legacy timeoutSeconds = 31 min (1860 sec → 1860000ms) > max 1800000 → reject.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        ExecuteResponse response = gateway.createTask(ExecuteRequest.newBuilder()
                .setCode("print(1)")
                .setTimeoutSeconds(1860.0)
                .build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory()
        );
    }

    // === MUST-FIX 4 (Cindy 91490076 #4): downstream_http_status = ACTUAL response status ===

    @Test
    void createTaskEmptyBody204UsesActualDownstreamStatusNotHardcoded200() {
        // Spring's MockRestResponseCreators.withSuccess emits 200 by default; use withStatus
        // for 204 explicitly to verify the actual status propagates.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        ExecuteResponse response = gateway.createTask(buildMinimalCreateRequest());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(204, response.getErrorDetail().getDownstreamHttpStatus(),
                "downstream_http_status MUST be actual 204, not hardcoded 200");
    }

    // === MUST-FIX 5 (Cindy 91490076 #5): 404 emits ERROR not OK ===
    // (Implicitly tested via getTaskStatus404ReturnsNotFoundDetailWithDownstreamStatus above;
    // telemetry-level JSONL emission assertion is out of unit-test scope but the production
    // code at PythonSandboxGatewayServiceImpl.java emits "ERROR" + frozen category for 404.)

    @Test
    void getTaskStatusEmptyBodyUsesActualDownstreamStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        server.expect(once(), requestTo("http://sandbox/tasks/task-1"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse response =
                gateway.getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId("task-1").build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertEquals(204, response.getErrorDetail().getDownstreamHttpStatus(),
                "downstream_http_status MUST be actual 204, not hardcoded 200");
    }

    @Test
    void computeEffectiveTimeoutMillisReturnsMaxOfLegacyAndCanonical() {
        // Both set: max wins.
        ExecuteRequest both = ExecuteRequest.newBuilder()
                .setTimeoutSeconds(10.0)   // 10000ms
                .setTimeoutMillis(20000L)  // 20000ms
                .build();
        assertEquals(20000L, PythonSandboxGatewayServiceImpl.computeEffectiveTimeoutMillis(both));

        // Only legacy seconds set.
        ExecuteRequest secondsOnly = ExecuteRequest.newBuilder()
                .setTimeoutSeconds(15.0)
                .build();
        assertEquals(15000L, PythonSandboxGatewayServiceImpl.computeEffectiveTimeoutMillis(secondsOnly));

        // Only canonical millis set.
        ExecuteRequest millisOnly = ExecuteRequest.newBuilder()
                .setTimeoutMillis(25000L)
                .build();
        assertEquals(25000L, PythonSandboxGatewayServiceImpl.computeEffectiveTimeoutMillis(millisOnly));

        // Neither set → 0 (no local reject; sandbox-side enforcement is ccqwen's slice).
        ExecuteRequest neither = ExecuteRequest.newBuilder().build();
        assertEquals(0L, PythonSandboxGatewayServiceImpl.computeEffectiveTimeoutMillis(neither));
    }

    @Test
    void computeEffectiveTimeoutMillisGuardsAgainstOverflowFromSeconds() {
        // Double seconds * 1000 may overflow long; helper MUST clamp to Long.MAX_VALUE so
        // the request is rejected rather than computing a wrapped negative sum.
        ExecuteRequest huge = ExecuteRequest.newBuilder()
                .setTimeoutSeconds(Double.MAX_VALUE)
                .build();
        assertEquals(Long.MAX_VALUE, PythonSandboxGatewayServiceImpl.computeEffectiveTimeoutMillis(huge));
    }
}
