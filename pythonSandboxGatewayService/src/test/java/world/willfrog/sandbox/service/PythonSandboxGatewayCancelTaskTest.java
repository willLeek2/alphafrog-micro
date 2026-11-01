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
