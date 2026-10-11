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
import world.willfrog.alphafrogmicro.agent.idl.AgentRunPersistentResourceEligibilityService;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityRequest;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityResponse;
import world.willfrog.alphafrogmicro.agent.idl.RunPersistentResourceEligibility;
import world.willfrog.alphafrogmicro.sandbox.idl.AcquireWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.AcquireWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ListWorkspaceExpiryCandidatesRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ListWorkspaceExpiryCandidatesResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;
import world.willfrog.alphafrogmicro.sandbox.idl.SealWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.SealWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceDeleteOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceQueryOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Permanent shape tests for the workspace RPC surface: every LEGAL business
 * form maps through, and every ILLEGAL combination (both sides of a mutual
 * exclusion, unknown enum values, missing required flags) fails closed as a
 * system failure — never surfaced as success and never silently dropping
 * half of a corrupted downstream body.
 */
class PythonSandboxGatewayWorkspaceRpcTest {

    // Telemetry assertions need a real session directory plus RpcContext
    // attachments so the JSONL appender writes sandbox-<runId>.jsonl there;
    // attachments are cleared after each test so the thread-local does not
    // leak between cases.
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

    // ------------------------------------------------------------------
    // createTask response shapes
    // ------------------------------------------------------------------

    @Test
    void createTaskLegalTaskShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess("""
                        {"task_id":"task-ok","status":"QUEUED","existing":false}
                        """, MediaType.APPLICATION_JSON));
        ExecuteResponse response = gateway.createTask(createRequest());
        assertEquals("task-ok", response.getTaskId());
        assertEquals("QUEUED", response.getStatus());
        assertFalse(response.hasWorkspaceResult());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void createTaskLegalRefusalShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess("""
                        {"task_id":"","workspace_result":"WORKSPACE_DIRTY"}
                        """, MediaType.APPLICATION_JSON));
        ExecuteResponse response = gateway.createTask(createRequest());
        assertEquals("", response.getTaskId());
        assertTrue(response.hasWorkspaceResult());
        assertEquals(WorkspaceResult.WORKSPACE_DIRTY, response.getWorkspaceResult());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void createTaskBothTaskAndRefusalFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess("""
                        {"task_id":"task-x","status":"QUEUED","workspace_result":"WORKSPACE_DIRTY"}
                        """, MediaType.APPLICATION_JSON));
        ExecuteResponse response = gateway.createTask(createRequest());
        assertTrue(response.hasErrorDetail());
        assertEquals(200, response.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(response.hasWorkspaceResult());
        assertEquals("", response.getTaskId());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void createTaskUnknownRefusalValueFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess("""
                        {"task_id":"","workspace_result":"WORKSPACE_NOT_A_REAL_VALUE"}
                        """, MediaType.APPLICATION_JSON));
        ExecuteResponse response = gateway.createTask(createRequest());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspaceResult());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void createTaskNeitherTaskNorRefusalFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/tasks"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        ExecuteResponse response = gateway.createTask(createRequest());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspaceResult());
        assertFalse(response.getError().isBlank());
    }

    // ------------------------------------------------------------------
    // acquireWorkspace response shapes
    // ------------------------------------------------------------------

    @Test
    void acquireLegalWorkspaceShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("""
                        {"workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                         "status":"active","owned_by_run_id":"run-1"}}
                        """, MediaType.APPLICATION_JSON));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasWorkspace());
        assertEquals("ws-1", response.getWorkspace().getWorkspaceId());
        assertEquals("1", response.getWorkspace().getWorkspaceGeneration());
        assertEquals("active", response.getWorkspace().getStatus());
        assertEquals("run-1", response.getWorkspace().getOwnedByRunId());
        assertFalse(response.hasWorkspaceResult());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void acquireLegalRefusalShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("""
                        {"workspace_result":"WORKSPACE_UNSUPPORTED"}
                        """, MediaType.APPLICATION_JSON));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertFalse(response.hasWorkspace());
        assertTrue(response.hasWorkspaceResult());
        assertEquals(WorkspaceResult.WORKSPACE_UNSUPPORTED, response.getWorkspaceResult());
        server.verify();
    }

    @Test
    void acquireBothWorkspaceAndRefusalFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("""
                        {"workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                         "status":"active","owned_by_run_id":"run-1"},
                         "workspace_result":"WORKSPACE_DIRTY"}
                        """, MediaType.APPLICATION_JSON));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspace());
        assertFalse(response.hasWorkspaceResult());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void acquireUnknownRefusalValueFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("""
                        {"workspace_result":"WORKSPACE_WAT"}
                        """, MediaType.APPLICATION_JSON));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspaceResult());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void acquireNeitherWorkspaceNorRefusalFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspace());
        assertFalse(response.hasWorkspaceResult());
    }

    @Test
    void acquireEmptyBodyEmitsSingleErrorTelemetry() throws Exception {
        // One rejected downstream attempt must produce exactly ONE sandbox_http
        // ERROR record for the request: the workspace-op failure helper is the
        // single emission site and records every attempted call regardless of
        // the measured duration, so one failed call can never write two
        // records nor lose its only record to a fast exchange.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        bindSession("run-acq-empty", "sess-acq-empty");
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-acq-empty").build());

        server.verify();
        assertTrue(response.hasErrorDetail());

        List<String> httpEvents = readSandboxHttpEvents(sessionDir, "run-acq-empty");
        assertEquals(1, httpEvents.size(),
                "empty-body acquire MUST emit exactly one sandbox_http event; got: " + httpEvents);
        String event = httpEvents.get(0);
        assertTrue(event.contains("\"status\":\"ERROR\""),
                "empty-body acquire event status MUST be ERROR; got: " + event);
        assertTrue(event.contains(
                "\"errorCategory\":\"WORKSPACE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED\""),
                "the single record MUST come from the workspace-op failure site; got: " + event);
        assertTrue(event.contains("\"httpStatus\":204"),
                "event MUST carry the actual downstream status; got: " + event);
    }

    @Test
    void acquireMalformedBodyEmitsSingleErrorTelemetry() throws Exception {
        // Same one-request-one-record contract on the corrupted-body branch:
        // a 200 body that carries both a workspace and a refusal verdict fails
        // closed and records exactly one ERROR event for the request.
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        bindSession("run-acq-bad", "sess-acq-bad");
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("""
                        {"workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                         "status":"active","owned_by_run_id":"run-acq-bad"},
                         "workspace_result":"WORKSPACE_DIRTY"}
                        """, MediaType.APPLICATION_JSON));

        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-acq-bad").build());

        server.verify();
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspace());
        assertFalse(response.hasWorkspaceResult());

        List<String> httpEvents = readSandboxHttpEvents(sessionDir, "run-acq-bad");
        assertEquals(1, httpEvents.size(),
                "malformed acquire MUST emit exactly one sandbox_http event; got: " + httpEvents);
        String event = httpEvents.get(0);
        assertTrue(event.contains("\"status\":\"ERROR\""),
                "malformed acquire event status MUST be ERROR; got: " + event);
        assertTrue(event.contains(
                "\"errorCategory\":\"WORKSPACE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED\""),
                "the single record MUST come from the workspace-op failure site; got: " + event);
    }

    @Test
    void acquireBlankRunIdRejectedLocally() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("   ").build());
        assertTrue(response.hasErrorDetail());
        assertEquals(
                world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory
                        .SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        assertFalse(response.hasWorkspace());
        server.verify(); // no downstream call was made
    }

    @Test
    void acquireHalfKnownIdentityRejectedLocally() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1")
                        .setKnownWorkspaceId("ws-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasWorkspace());
        server.verify();
    }

    // ------------------------------------------------------------------
    // deleteWorkspace response shapes
    // ------------------------------------------------------------------

    @Test
    void deleteLegalConfirmedShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":true,"retryable_failure":false}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertTrue(response.getDeleted());
        assertFalse(response.getRetryableFailure());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void deleteLegalRetryableShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":false,"retryable_failure":true}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertFalse(response.getDeleted());
        assertTrue(response.getRetryableFailure());
        server.verify();
    }

    @Test
    void deleteBothFlagsTrueFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":true,"retryable_failure":true}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.getDeleted());
        assertFalse(response.getRetryableFailure());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void deleteBothFlagsAbsentFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.getDeleted());
        assertFalse(response.getRetryableFailure());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void deleteBlankIdentifiersRejectedLocally() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId(" ")
                        .setIdempotencyKey("dk-1").build());
        assertTrue(response.hasErrorDetail());
        assertEquals(
                world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory
                        .SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        server.verify();
    }

    // ------------------------------------------------------------------
    // acquireWorkspace eligibility pre-check
    // ------------------------------------------------------------------

    @Test
    void acquireEligibilityAllowedForwardsExactlyOnce() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AtomicInteger eligibilityCalls = new AtomicInteger();
        AgentRunPersistentResourceEligibilityService counting =
                new AgentRunPersistentResourceEligibilityService() {
                    @Override
                    public CheckRunPersistentResourceEligibilityResponse checkEligibility(
                            CheckRunPersistentResourceEligibilityRequest request) {
                        eligibilityCalls.incrementAndGet();
                        assertEquals("run-1", request.getRunId());
                        return CheckRunPersistentResourceEligibilityResponse.newBuilder()
                                .setEligibility(RunPersistentResourceEligibility
                                        .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_ALLOWED)
                                .build();
                    }

                    @Override
                    public CompletableFuture<CheckRunPersistentResourceEligibilityResponse>
                            checkEligibilityAsync(CheckRunPersistentResourceEligibilityRequest request) {
                        return CompletableFuture.completedFuture(checkEligibility(request));
                    }
                };
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate, counting);
        server.expect(once(), requestTo("http://sandbox/workspaces/acquire"))
                .andRespond(withSuccess("""
                        {"workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                         "status":"active","owned_by_run_id":"run-1"}}
                        """, MediaType.APPLICATION_JSON));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasWorkspace());
        assertEquals(1, eligibilityCalls.get(),
                "the eligibility pre-check must be consulted exactly once");
        server.verify(); // exactly one downstream POST
    }

    @Test
    void acquireEligibilityRefusedReturnsBusinessVerdictWithoutPost() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate,
                eligibilityStub(RunPersistentResourceEligibility
                        .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_REFUSED));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertFalse(response.hasWorkspace());
        assertTrue(response.hasWorkspaceResult());
        assertEquals(WorkspaceResult.WORKSPACE_RUN_INELIGIBLE, response.getWorkspaceResult());
        assertFalse(response.hasErrorDetail());
        assertEquals("", response.getError());
        server.verify(); // no downstream call was made
    }

    @Test
    void acquireEligibilityTemporarilyUnavailableFailsClosedWithoutPost() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate,
                eligibilityStub(RunPersistentResourceEligibility
                        .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_TEMPORARILY_UNAVAILABLE));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertFalse(response.hasWorkspace());
        assertFalse(response.hasWorkspaceResult());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory());
        server.verify();
    }

    @Test
    void acquireEligibilityUnspecifiedFailsClosedWithoutPost() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate,
                eligibilityStub(RunPersistentResourceEligibility
                        .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_UNSPECIFIED));
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertFalse(response.hasWorkspace());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory());
        server.verify();
    }

    @Test
    void acquireEligibilityNullResponseFailsClosedWithoutPost() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AgentRunPersistentResourceEligibilityService nullAnswering =
                new AgentRunPersistentResourceEligibilityService() {
                    @Override
                    public CheckRunPersistentResourceEligibilityResponse checkEligibility(
                            CheckRunPersistentResourceEligibilityRequest request) {
                        return null;
                    }

                    @Override
                    public CompletableFuture<CheckRunPersistentResourceEligibilityResponse>
                            checkEligibilityAsync(CheckRunPersistentResourceEligibilityRequest request) {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate, nullAnswering);
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertFalse(response.hasWorkspace());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory());
        server.verify();
    }

    @Test
    void acquireEligibilityExceptionFailsClosedWithoutPost() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        // 超时与传输异常在 Dubbo 消费端都表现为 RpcException；这里用泛化
        // RuntimeException 覆盖同一失败关闭路径。
        AgentRunPersistentResourceEligibilityService throwing =
                new AgentRunPersistentResourceEligibilityService() {
                    @Override
                    public CheckRunPersistentResourceEligibilityResponse checkEligibility(
                            CheckRunPersistentResourceEligibilityRequest request) {
                        throw new RuntimeException("simulated eligibility RPC failure");
                    }

                    @Override
                    public CompletableFuture<CheckRunPersistentResourceEligibilityResponse>
                            checkEligibilityAsync(CheckRunPersistentResourceEligibilityRequest request) {
                        throw new RuntimeException("simulated eligibility RPC failure");
                    }
                };
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate, throwing);
        AcquireWorkspaceResponse response = gateway.acquireWorkspace(
                AcquireWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertFalse(response.hasWorkspace());
        assertFalse(response.hasWorkspaceResult());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                response.getErrorDetail().getCategory());
        server.verify();
    }

    // ------------------------------------------------------------------
    // deleteWorkspace outcome-first validation
    // ------------------------------------------------------------------

    @Test
    void deleteExpectedLastActiveAtPassesThrough() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andExpect(jsonPath("$.workspace_id").value("ws-1"))
                .andExpect(jsonPath("$.idempotency_key").value("dk-1"))
                .andExpect(jsonPath("$.expected_last_active_at")
                        .value("2026-09-29T08:00:00.123000Z"))
                .andRespond(withSuccess("""
                        {"deleted":true,"retryable_failure":false,
                         "outcome":"WORKSPACE_DELETE_DELETED"}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1")
                        .setExpectedLastActiveAt("2026-09-29T08:00:00.123000Z")
                        .build());
        assertTrue(response.getDeleted());
        assertTrue(response.hasOutcome());
        assertEquals(WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED, response.getOutcome());
        server.verify();
    }

    @Test
    void deleteRevokedNewActivityFalseFalseIsLegal() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        // REVOKED 对应 deleted=false + retryableFailure=false：这是合法
        // 形态，不得落入旧互斥布尔校验判损坏。
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":false,"retryable_failure":false,
                         "outcome":"WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY"}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1")
                        .setExpectedLastActiveAt("2026-09-29T08:00:00Z")
                        .build());
        assertFalse(response.getDeleted());
        assertFalse(response.getRetryableFailure());
        assertTrue(response.hasOutcome());
        assertEquals(WorkspaceDeleteOutcome.WORKSPACE_DELETE_REVOKED_NEW_ACTIVITY,
                response.getOutcome());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void deleteOutcomeInconsistentBooleansFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        // 分类说删成，布尔却是可重试失败：应答自相矛盾，失败关闭。
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":false,"retryable_failure":true,
                         "outcome":"WORKSPACE_DELETE_DELETED"}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasOutcome());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void deleteUnknownOutcomeValueFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":true,"retryable_failure":false,"outcome":"GARBAGE"}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.hasOutcome());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void deleteLegacyShapeWithoutOutcomeStillMaps() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/delete"))
                .andRespond(withSuccess("""
                        {"deleted":false,"retryable_failure":true}
                        """, MediaType.APPLICATION_JSON));
        DeleteWorkspaceResponse response = gateway.deleteWorkspace(
                DeleteWorkspaceRequest.newBuilder().setWorkspaceId("ws-1")
                        .setIdempotencyKey("dk-1").build());
        assertFalse(response.getDeleted());
        assertTrue(response.getRetryableFailure());
        assertFalse(response.hasOutcome());
        server.verify();
    }

    // ------------------------------------------------------------------
    // sealWorkspace response shapes
    // ------------------------------------------------------------------

    @Test
    void sealLegalWithDiskShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/seal"))
                .andRespond(withSuccess("""
                        {"sealed":true,
                         "workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                          "status":"sealed","owned_by_run_id":"run-1"}}
                        """, MediaType.APPLICATION_JSON));
        SealWorkspaceResponse response = gateway.sealWorkspace(
                SealWorkspaceRequest.newBuilder().setRunId("run-1")
                        .setIdempotencyKey("sk-1").build());
        assertTrue(response.getSealed());
        assertTrue(response.hasWorkspace());
        assertEquals("sealed", response.getWorkspace().getStatus());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void sealLegalDisklessShapeMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/seal"))
                .andRespond(withSuccess("""
                        {"sealed":true}
                        """, MediaType.APPLICATION_JSON));
        SealWorkspaceResponse response = gateway.sealWorkspace(
                SealWorkspaceRequest.newBuilder().setRunId("run-1")
                        .setIdempotencyKey("sk-1").build());
        assertTrue(response.getSealed());
        assertFalse(response.hasWorkspace());
        server.verify();
    }

    @Test
    void sealFalseFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/seal"))
                .andRespond(withSuccess("""
                        {"sealed":false}
                        """, MediaType.APPLICATION_JSON));
        SealWorkspaceResponse response = gateway.sealWorkspace(
                SealWorkspaceRequest.newBuilder().setRunId("run-1")
                        .setIdempotencyKey("sk-1").build());
        assertFalse(response.getSealed());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void sealBlankIdentifiersRejectedLocally() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        SealWorkspaceResponse response = gateway.sealWorkspace(
                SealWorkspaceRequest.newBuilder().setRunId(" ")
                        .setIdempotencyKey("sk-1").build());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        server.verify(); // no downstream call was made
    }

    // ------------------------------------------------------------------
    // queryWorkspace response shapes
    // ------------------------------------------------------------------

    @Test
    void queryNotFoundMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"WORKSPACE_QUERY_NOT_FOUND"}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertEquals(WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND, response.getOutcome());
        assertFalse(response.hasWorkspace());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void queryFoundWithWorkspaceMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"WORKSPACE_QUERY_FOUND",
                         "workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                          "status":"sealed","owned_by_run_id":"run-1"},
                         "last_active_at":"2026-09-29T08:00:00.123000Z"}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertEquals(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND, response.getOutcome());
        assertTrue(response.hasWorkspace());
        assertEquals("sealed", response.getWorkspace().getStatus());
        assertEquals("2026-09-29T08:00:00.123000Z", response.getLastActiveAt());
        server.verify();
    }

    @Test
    void queryFoundDisklessSealMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"WORKSPACE_QUERY_FOUND",
                         "last_active_at":"2026-09-29T08:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertEquals(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND, response.getOutcome());
        assertFalse(response.hasWorkspace());
        assertEquals("2026-09-29T08:00:00Z", response.getLastActiveAt());
        server.verify();
    }

    @Test
    void queryFoundDeletedCarriesOldIdentity() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"WORKSPACE_QUERY_FOUND_DELETED",
                         "workspace":{"workspace_id":"ws-1","workspace_generation":"3",
                          "status":"deleted","owned_by_run_id":"run-1"},
                         "last_active_at":"2026-09-28T08:00:00Z",
                         "deleted_at":"2026-09-29T08:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertEquals(WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED,
                response.getOutcome());
        assertTrue(response.hasWorkspace());
        assertEquals("ws-1", response.getWorkspace().getWorkspaceId());
        assertEquals("3", response.getWorkspace().getWorkspaceGeneration());
        assertEquals("deleted", response.getWorkspace().getStatus());
        assertEquals("2026-09-29T08:00:00Z", response.getDeletedAt());
        server.verify();
    }

    @Test
    void queryFoundDeletedWithoutWorkspaceFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"WORKSPACE_QUERY_FOUND_DELETED"}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void queryNotFoundWithWorkspaceFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"WORKSPACE_QUERY_NOT_FOUND",
                         "workspace":{"workspace_id":"ws-1","workspace_generation":"1",
                          "status":"active","owned_by_run_id":"run-1"}}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void queryUnknownOutcomeFailsClosed() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/query"))
                .andRespond(withSuccess("""
                        {"outcome":"GARBAGE"}
                        """, MediaType.APPLICATION_JSON));
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId("run-1").build());
        assertTrue(response.hasErrorDetail());
        assertFalse(response.getError().isBlank());
    }

    @Test
    void queryBlankRunIdRejectedLocally() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        QueryWorkspaceResponse response = gateway.queryWorkspace(
                QueryWorkspaceRequest.newBuilder().setRunId(" ").build());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        server.verify();
    }

    // ------------------------------------------------------------------
    // listWorkspaceExpiryCandidates response shapes
    // ------------------------------------------------------------------

    @Test
    void candidatesPageMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/expiry-candidates"))
                .andRespond(withSuccess("""
                        {"candidates":[
                          {"run_id":"run-1","workspace_id":"ws-1","workspace_generation":"1",
                           "status":"active","last_active_at":"2026-09-28T08:00:00Z"},
                          {"run_id":"run-2","workspace_id":"ws-2","workspace_generation":"2",
                           "status":"dirty","last_active_at":"2026-09-27T08:00:00Z"}],
                         "next_page_token":"ws-2"}
                        """, MediaType.APPLICATION_JSON));
        ListWorkspaceExpiryCandidatesResponse response = gateway.listWorkspaceExpiryCandidates(
                ListWorkspaceExpiryCandidatesRequest.newBuilder()
                        .setPageSize(2).setPageToken("").build());
        assertEquals(2, response.getCandidatesCount());
        assertEquals("ws-1", response.getCandidates(0).getWorkspaceId());
        assertEquals("run-2", response.getCandidates(1).getRunId());
        assertEquals("2", response.getCandidates(1).getWorkspaceGeneration());
        assertEquals("dirty", response.getCandidates(1).getStatus());
        assertEquals("2026-09-27T08:00:00Z", response.getCandidates(1).getLastActiveAt());
        assertEquals("ws-2", response.getNextPageToken());
        assertEquals("", response.getError());
        server.verify();
    }

    @Test
    void candidatesLastPageEmptyTokenMapsThrough() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        server.expect(once(), requestTo("http://sandbox/workspaces/expiry-candidates"))
                .andRespond(withSuccess("""
                        {"candidates":[],"next_page_token":""}
                        """, MediaType.APPLICATION_JSON));
        ListWorkspaceExpiryCandidatesResponse response = gateway.listWorkspaceExpiryCandidates(
                ListWorkspaceExpiryCandidatesRequest.newBuilder()
                        .setPageSize(500).setPageToken("ws-9").build());
        assertEquals(0, response.getCandidatesCount());
        assertEquals("", response.getNextPageToken());
        server.verify();
    }

    @Test
    void candidatesNonPositivePageSizeRejectedLocally() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);
        ListWorkspaceExpiryCandidatesResponse response = gateway.listWorkspaceExpiryCandidates(
                ListWorkspaceExpiryCandidatesRequest.newBuilder()
                        .setPageSize(0).setPageToken("").build());
        assertTrue(response.hasErrorDetail());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                response.getErrorDetail().getCategory());
        server.verify(); // no downstream call was made
    }

    private void bindSession(String runId, String sessionId) {
        // Bind RpcContext attachments so the JSONL appender writes to the
        // temp session directory, letting the test count the sandbox_http
        // events a single request actually produced.
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

    @Test
    void workspaceFailureHelperZeroMillisecondAttemptStillEmits() throws Exception {
        // A real HTTP exchange can complete inside one millisecond tick, so
        // the workspace-op failure helper keys its one-record decision on
        // whether a downstream request was attempted, never on the measured
        // duration. Pin the zero-millisecond case directly: an attempted call
        // with durationMs == 0 still writes exactly one ERROR record.
        RestTemplate restTemplate = new RestTemplate();
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        bindSession("run-op-zero", "sess-op-zero");
        gateway.workspaceOpFailure("Invalid acquire response from sandbox",
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                503, "http://sandbox/workspaces/acquire", 0L, true);

        List<String> httpEvents = readSandboxHttpEvents(sessionDir, "run-op-zero");
        assertEquals(1, httpEvents.size(),
                "an attempted call with zero measured duration MUST still emit exactly one "
                        + "sandbox_http event; got: " + httpEvents);
        String event = httpEvents.get(0);
        assertTrue(event.contains("\"durationMs\":0"),
                "the record MUST carry the actual zero duration; got: " + event);
        assertTrue(event.contains("\"status\":\"ERROR\""),
                "zero-duration event status MUST be ERROR; got: " + event);
        assertTrue(event.contains(
                "\"errorCategory\":\"WORKSPACE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED\""),
                "the record MUST come from the workspace-op failure site; got: " + event);
        assertTrue(event.contains("\"httpStatus\":503"),
                "event MUST carry the actual downstream status; got: " + event);
    }

    @Test
    void workspaceFailureHelperLocalRejectionWritesNoRecord() throws Exception {
        // Mirror image of the zero-duration pin: a local rejection never
        // reached the network, so the same helper with httpAttempted=false
        // writes no sandbox_http record at all.
        RestTemplate restTemplate = new RestTemplate();
        PythonSandboxGatewayServiceImpl gateway = newGateway(restTemplate);

        bindSession("run-op-local", "sess-op-local");
        gateway.workspaceOpFailure("acquireWorkspace rejected: runId is required",
                SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                null, "http://sandbox/workspaces/acquire", 0L, false);

        Path runFile = sessionDir.resolve("sandbox-run-op-local.jsonl");
        assertTrue(Files.notExists(runFile) || readSandboxHttpEvents(sessionDir, "run-op-local").isEmpty(),
                "a local rejection MUST NOT emit any sandbox_http event");
    }

    private static ExecuteRequest createRequest() {
        // The gateway locally rejects keyless creates (production
        // idempotency gate), so every shape test carries operation
        // identity; the mocked downstream never validates it.
        return ExecuteRequest.newBuilder()
                .setDatasetId("dataset-1")
                .setCode("print(1)")
                .setOperationId("run-ws:call-1:1")
                .setRequestFingerprint("sha256:" + "f".repeat(64))
                .setRuntimeEnvironmentVersion("python-v1")
                .setCanonicalSpecSchemaVersion("sandbox_create_v1")
                .setCodeHash("sha256:" + "a".repeat(64))
                .setImmutableDatasetSnapshotDigest("sha256:" + "b".repeat(64))
                .setLibrariesDigest("sha256:" + "c".repeat(64))
                .setSandboxOptionsDigest("sha256:" + "d".repeat(64))
                .setTimeoutMillis(60000)
                .setMemoryLimitBytes(512 * 1024 * 1024)
                .setResourceClass("STANDARD")
                .setCapacityUnits(1)
                .build();
    }

    private PythonSandboxGatewayServiceImpl newGateway(RestTemplate restTemplate) {
        // Default: the eligibility pre-check answers ALLOWED so the existing
        // forwarding tests exercise the HTTP path unchanged.
        return newGateway(restTemplate,
                eligibilityStub(RunPersistentResourceEligibility
                        .RUN_PERSISTENT_RESOURCE_ELIGIBILITY_ALLOWED));
    }

    private PythonSandboxGatewayServiceImpl newGateway(
            RestTemplate restTemplate,
            AgentRunPersistentResourceEligibilityService eligibilityService) {
        PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(restTemplate, restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        ReflectionTestUtils.setField(gateway, "runEligibilityService", eligibilityService);
        return gateway;
    }

    private static AgentRunPersistentResourceEligibilityService eligibilityStub(
            RunPersistentResourceEligibility verdict) {
        return new AgentRunPersistentResourceEligibilityService() {
            @Override
            public CheckRunPersistentResourceEligibilityResponse checkEligibility(
                    CheckRunPersistentResourceEligibilityRequest request) {
                return CheckRunPersistentResourceEligibilityResponse.newBuilder()
                        .setEligibility(verdict).build();
            }

            @Override
            public CompletableFuture<CheckRunPersistentResourceEligibilityResponse>
                    checkEligibilityAsync(CheckRunPersistentResourceEligibilityRequest request) {
                return CompletableFuture.completedFuture(checkEligibility(request));
            }
        };
    }
}
