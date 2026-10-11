package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxHttpErrorCategory;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskIdCancelTarget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PythonSandboxGatewayCancelTaskTest {

    @Test
    void runningTaskCancellationUsesShortClientAndPreservesNonterminalOutcome() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andExpect(content().json("""
                        {"by_task_id":{"task_id":"task-1"},
                         "cancel_request_id":"cancel-1","reason":"RUN_CANCELED"}
                        """, false))
                .andRespond(withSuccess("""
                        {"outcome":"CANCEL_INTENT_RECORDED","task_id":"task-1","status":"RUNNING"}
                        """, MediaType.APPLICATION_JSON));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_INTENT_RECORDED, result.getOutcome());
        assertEquals("RUNNING", result.getStatus());
        assertEquals("task-1", result.getTaskId());
        assertFalse(result.hasErrorDetail());
    }

    @Test
    void replayKeepsOriginalIntentOutcomeEvenAfterSandboxReachesTerminalStatus() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withSuccess("""
                        {"outcome":"CANCEL_INTENT_RECORDED","task_id":"task-1","status":"CANCELED"}
                        """, MediaType.APPLICATION_JSON));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_INTENT_RECORDED, result.getOutcome());
        assertEquals("CANCELED", result.getStatus());
        assertFalse(result.hasErrorDetail());
    }

    @Test
    void operationCancellationForwardsIdentityAndReturnsPhysicalTerminalOnlyWhenSandboxSaysSo() {
        Fixture fixture = new Fixture();
        String fingerprint = "sha256:" + "a".repeat(64);
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andExpect(content().json("""
                        {"by_operation":{"operation_id":"run:call:1",
                         "request_fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                         "cancel_request_id":"cancel-2"}
                        """, false))
                .andRespond(withSuccess("""
                        {"outcome":"CANCELED","task_id":"task-stable","status":"CANCELED"}
                        """, MediaType.APPLICATION_JSON));
        CancelTaskResponse result = fixture.gateway.cancelTask(CancelTaskRequest.newBuilder()
                .setCancelRequestId("cancel-2")
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId("run:call:1").setRequestFingerprint(fingerprint))
                .build());
        fixture.verify();
        assertEquals(CancelOutcome.CANCELED, result.getOutcome());
        assertEquals("task-stable", result.getTaskId());
        assertEquals("CANCELED", result.getStatus());
    }

    @Test
    void malformedTerminalClaimFailsClosed() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withSuccess("""
                        {"outcome":"CANCELED","task_id":"task-1","status":"RUNNING"}
                        """, MediaType.APPLICATION_JSON));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED, result.getOutcome());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                result.getErrorDetail().getCategory());
        assertEquals(200, result.getErrorDetail().getDownstreamHttpStatus());
        assertFalse(result.getError().isBlank());
    }

    @Test
    void alreadyTerminalPreservesObservedSandboxStatus() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withSuccess("""
                        {"outcome":"ALREADY_TERMINAL","task_id":"task-1","status":"SUCCEEDED"}
                        """, MediaType.APPLICATION_JSON));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.ALREADY_TERMINAL, result.getOutcome());
        assertEquals("SUCCEEDED", result.getStatus());
    }

    @Test
    void bodyErrorCannotMasqueradeAsSuccessfulCancellation() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withSuccess("""
                        {"outcome":"CANCELED","task_id":"task-1","status":"CANCELED",
                         "error":"downstream could not persist result"}
                        """, MediaType.APPLICATION_JSON));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED, result.getOutcome());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                result.getErrorDetail().getCategory());
    }

    @Test
    void httpConflictIsTypedFailureRatherThanBusinessNotFoundOrTerminal() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED, result.getOutcome());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                result.getErrorDetail().getCategory());
        assertEquals(409, result.getErrorDetail().getDownstreamHttpStatus());
    }

    @Test
    void unavailableSandboxIsTypedRetryableFailure() {
        Fixture fixture = new Fixture();
        fixture.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        CancelTaskResponse result = fixture.gateway.cancelTask(byTask());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED, result.getOutcome());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                result.getErrorDetail().getCategory());
        assertEquals(503, result.getErrorDetail().getDownstreamHttpStatus());
    }

    @Test
    void businessNotFoundHasNoErrorDetailButHttpNotFoundDoes() {
        Fixture business = new Fixture();
        business.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withSuccess("{\"outcome\":\"NOT_FOUND\",\"task_id\":null,\"status\":null}",
                        MediaType.APPLICATION_JSON));
        CancelTaskResponse notFound = business.gateway.cancelTask(byTask());
        business.verify();
        assertEquals(CancelOutcome.NOT_FOUND, notFound.getOutcome());
        assertFalse(notFound.hasErrorDetail());

        Fixture http = new Fixture();
        http.shortServer.expect(once(), requestTo("http://sandbox/tasks/cancel"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        CancelTaskResponse failure = http.gateway.cancelTask(byTask());
        http.verify();
        assertEquals(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED, failure.getOutcome());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND,
                failure.getErrorDetail().getCategory());
    }

    @Test
    void invalidOperationIdentityIsRejectedBeforeHttp() {
        Fixture fixture = new Fixture();
        CancelTaskResponse result = fixture.gateway.cancelTask(CancelTaskRequest.newBuilder()
                .setCancelRequestId("cancel-3")
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId("bad")
                        .setRequestFingerprint("sha256:" + "a".repeat(64)))
                .build());
        fixture.verify();
        assertEquals(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED, result.getOutcome());
        assertEquals(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                result.getErrorDetail().getCategory());
        assertFalse(result.getErrorDetail().hasDownstreamHttpStatus());
    }

    private static CancelTaskRequest byTask() {
        return CancelTaskRequest.newBuilder().setCancelRequestId("cancel-1")
                .setReason("RUN_CANCELED")
                .setByTaskId(TaskIdCancelTarget.newBuilder().setTaskId("task-1"))
                .build();
    }

    private static final class Fixture {
        final RestTemplate longClient = new RestTemplate();
        final RestTemplate shortClient = new RestTemplate();
        final MockRestServiceServer longServer = MockRestServiceServer.createServer(longClient);
        final MockRestServiceServer shortServer = MockRestServiceServer.createServer(shortClient);
        final PythonSandboxGatewayServiceImpl gateway =
                new PythonSandboxGatewayServiceImpl(longClient, shortClient, new ObjectMapper());

        Fixture() {
            ReflectionTestUtils.setField(gateway, "sandboxUrl", "http://sandbox");
        }

        void verify() {
            shortServer.verify();
            longServer.verify();
        }
    }
}
