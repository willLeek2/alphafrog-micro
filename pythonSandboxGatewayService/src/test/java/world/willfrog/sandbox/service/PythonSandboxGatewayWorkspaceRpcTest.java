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






    // ------------------------------------------------------------------
    // acquireWorkspace response shapes
    // ------------------------------------------------------------------










    // ------------------------------------------------------------------
    // deleteWorkspace response shapes
    // ------------------------------------------------------------------






    // ------------------------------------------------------------------
    // acquireWorkspace eligibility pre-check
    // ------------------------------------------------------------------







    // ------------------------------------------------------------------
    // deleteWorkspace outcome-first validation
    // ------------------------------------------------------------------






    // ------------------------------------------------------------------
    // sealWorkspace response shapes
    // ------------------------------------------------------------------





    // ------------------------------------------------------------------
    // queryWorkspace response shapes
    // ------------------------------------------------------------------









    // ------------------------------------------------------------------
    // listWorkspaceExpiryCandidates response shapes
    // ------------------------------------------------------------------




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
