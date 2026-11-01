package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.sandbox.idl.AcquireWorkspaceRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.AcquireWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceInfo;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonWorkspaceAcquisitionTest {
    private static final String GENERATION = "gen-" + "a".repeat(64);

    @AfterEach
    void clearRun() {
        AgentContext.clear();
    }







    private static AcquireWorkspaceResponse activeWorkspace(String runId) {
        return AcquireWorkspaceResponse.newBuilder()
                .setWorkspace(WorkspaceInfo.newBuilder()
                        .setWorkspaceId("workspace-a")
                        .setWorkspaceGeneration("1")
                        .setOwnedByRunId(runId)
                        .setStatus("active"))
                .build();
    }

    private static Fixture fixture(boolean enabled) {
        AgentContext.setToolCallId("workspace-call");
        PythonSandboxTools tools = new PythonSandboxTools(new ObjectMapper());
        world.willfrog.agent.platform.service.PythonRiskReviewService review =
                mock(world.willfrog.agent.platform.service.PythonRiskReviewService.class);
        ReflectionTestUtils.setField(tools, "pythonRiskReviewService", review);
        when(review.evaluate(any(), any(), any())).thenReturn(
                new world.willfrog.agent.platform.service.PythonRiskReviewService.Evaluation(true, null, false));
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        AgentRunDatasetRegistry registry = mock(AgentRunDatasetRegistry.class);
        AgentRunMapper runs = mock(AgentRunMapper.class);
        DeploymentIdentityProvider deployment = () -> new DeploymentIdentity("stable", GENERATION);
        tools.setAgentRunDatasetRegistry(registry);
        ReflectionTestUtils.setField(tools, "pythonSandboxService", sandbox);
        ReflectionTestUtils.setField(tools, "agentRunMapper", runs);
        ReflectionTestUtils.setField(tools, "deploymentIdentityProvider", deployment);
        ReflectionTestUtils.setField(tools, "allowLegacyWithoutCapacity", true);
        when(registry.snapshot("run-a")).thenReturn(AgentRunDatasetSnapshot.empty());
        AgentRun run = new AgentRun();
        run.setExt("{\"python_workspace_enabled\":" + enabled + "}");
        when(runs.findByIdForDeployment("run-a", "stable", GENERATION)).thenReturn(run);
        return new Fixture(tools, sandbox, runs, review);
    }

    private record Fixture(PythonSandboxTools tools, PythonSandboxService sandbox,
                           AgentRunMapper runs,
                           world.willfrog.agent.platform.service.PythonRiskReviewService review) {
    }
}
