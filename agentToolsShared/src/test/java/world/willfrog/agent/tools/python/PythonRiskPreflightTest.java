package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.PythonRiskReplayEvidenceMissingException;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.PythonRiskReviewService;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import com.google.protobuf.util.JsonFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonRiskPreflightTest {
    @AfterEach
    void clearContext() {
        AgentContext.clearToolCallId();
    }

    @Test
    void firstPassDoesNotRequireReplayRequestThatCannotExistYet() {
        PythonRiskReviewService review = mock(PythonRiskReviewService.class);
        when(review.evaluate("run:durable-tool:1", "run", "print(1)"))
                .thenReturn(new PythonRiskReviewService.Evaluation(true, null, false));
        PythonSandboxTools tools = toolsWith(review);
        AgentContext.setToolCallId("durable-tool");

        assertNull(ReflectionTestUtils.invokeMethod(tools,
                "reviewPythonBeforeWorkspace", "run", "print(1)"));
        verify(review).evaluate("run:durable-tool:1", "run", "print(1)");
    }

    @Test
    void oldPassWithoutFullRequestStopsRunBeforeDispatch() {
        PythonRiskReviewService review = mock(PythonRiskReviewService.class);
        when(review.evaluate("run:durable-tool:1", "run", "print(1)"))
                .thenReturn(new PythonRiskReviewService.Evaluation(true, 0, true));
        PythonSandboxTools tools = toolsWith(review);
        AgentContext.setToolCallId("durable-tool");

        assertThrows(PythonRiskReplayEvidenceMissingException.class,
                () -> ReflectionTestUtils.invokeMethod(tools,
                        "reviewPythonBeforeWorkspace", "run", "print(1)"));
    }

    @Test
    void oldPassWithOriginalFullRequestCanContinueUnderItsSavedDecision() throws Exception {
        PythonRiskReviewService review = mock(PythonRiskReviewService.class);
        when(review.evaluate("run:durable-tool:1", "run", "print(1)"))
                .thenReturn(new PythonRiskReviewService.Evaluation(true, 10, true));
        PythonSandboxTools tools = toolsWith(review);
        AgentContext.setToolCallId("durable-tool");
        String generation = "gen-" + "a".repeat(64);
        ReflectionTestUtils.setField(tools, "deploymentIdentityProvider",
                (DeploymentIdentityProvider) () -> new DeploymentIdentity("deployment", generation));
        AgentRunMapper runs = mock(AgentRunMapper.class);
        ReflectionTestUtils.setField(tools, "agentRunMapper", runs);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run:durable-tool:1");
        anchor.setCreateRequestJson(JsonFormat.printer().print(ExecuteRequest.newBuilder()
                .setOperationId("run:durable-tool:1").setCode("print(1)").build()));
        AgentRun run = new AgentRun();
        run.setToolJobAnchorJson(anchor.toJson());
        when(runs.findByIdForDeployment("run", "deployment", generation)).thenReturn(run);

        assertNull(ReflectionTestUtils.invokeMethod(tools,
                "reviewPythonBeforeWorkspace", "run", "print(1)"));
    }

    @Test
    void rejectedDecisionNeverNeedsDispatchProof() {
        PythonRiskReviewService review = mock(PythonRiskReviewService.class);
        when(review.evaluate("run:durable-tool:1", "run", "print(1)"))
                .thenReturn(new PythonRiskReviewService.Evaluation(false, 80, true));
        PythonSandboxTools tools = toolsWith(review);
        AgentContext.setToolCallId("durable-tool");

        String result = ReflectionTestUtils.invokeMethod(tools,
                "reviewPythonBeforeWorkspace", "run", "print(1)");
        assertNotNull(result);
        assertTrue(result.contains("PYTHON_RISK_REJECTED"));
    }

    @Test
    void absentReviewServiceCannotSilentlyDispatch() {
        PythonSandboxTools tools = new PythonSandboxTools(new ObjectMapper());
        AgentContext.setToolCallId("durable-tool");

        String result = ReflectionTestUtils.invokeMethod(tools,
                "reviewPythonBeforeWorkspace", "run", "print(1)");
        assertNotNull(result);
        assertTrue(result.contains("PYTHON_RISK_REVIEW_UNAVAILABLE"));
    }

    private PythonSandboxTools toolsWith(PythonRiskReviewService review) {
        PythonSandboxTools tools = new PythonSandboxTools(new ObjectMapper());
        ReflectionTestUtils.setField(tools, "pythonRiskReviewService", review);
        return tools;
    }
}
