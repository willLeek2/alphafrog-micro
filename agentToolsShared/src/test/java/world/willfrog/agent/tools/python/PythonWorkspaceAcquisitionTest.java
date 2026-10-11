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

    @Test
    void repeatedCallsUseTheSameWorkspaceAndAllowNoNewDataset() {
        Fixture fixture = fixture(true);
        AgentContext.setRunId("run-a");
        AgentContext.setToolCallId("workspace-call");
        when(fixture.sandbox.acquireWorkspace(any(AcquireWorkspaceRequest.class)))
                .thenReturn(activeWorkspace("run-a"));
        when(fixture.sandbox.createTask(any(ExecuteRequest.class)))
                .thenReturn(ExecuteResponse.newBuilder().setError("test create stopped").build());

        fixture.tools.executePython("print(1)", "", "", "", 30);
        fixture.tools.executePython("print(2)", "", "", "", 30);

        var requests = org.mockito.ArgumentCaptor.forClass(ExecuteRequest.class);
        verify(fixture.sandbox, times(2)).createTask(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.getRunId()).isEqualTo("run-a");
            assertThat(request.getWorkspaceId()).isEqualTo("workspace-a");
            assertThat(request.getWorkspaceGeneration()).isEqualTo("1");
            assertThat(request.getDatasetId()).isEmpty();
            assertThat(request.getDatasetIdsList()).isEmpty();
            assertThat(request.getPathsDatasetCsv()).isEmpty();
            assertThat(request.getPathManifestCsv()).isEmpty();
        });
        verify(fixture.sandbox, times(2)).acquireWorkspace(any(AcquireWorkspaceRequest.class));
    }

    @Test
    void aWorkspaceOwnedByAnotherRunIsRejectedBeforeCreate() throws Exception {
        Fixture fixture = fixture(true);
        AgentContext.setRunId("run-a");
        when(fixture.sandbox.acquireWorkspace(any(AcquireWorkspaceRequest.class)))
                .thenReturn(activeWorkspace("run-b"));

        JsonNode response = new ObjectMapper().readTree(
                fixture.tools.executePython("print(1)", "", "", "", 30));

        assertThat(response.path("error").path("code").asText())
                .isEqualTo("WORKSPACE_IDENTITY_CONFLICT");
        verify(fixture.sandbox, never()).createTask(any(ExecuteRequest.class));
    }

    @Test
    void aDirtyWorkspaceReturnsItsBusinessResultWithoutTemporaryFallback() throws Exception {
        Fixture fixture = fixture(true);
        AgentContext.setRunId("run-a");
        when(fixture.sandbox.acquireWorkspace(any(AcquireWorkspaceRequest.class)))
                .thenReturn(AcquireWorkspaceResponse.newBuilder()
                        .setWorkspaceResult(WorkspaceResult.WORKSPACE_DIRTY).build());

        JsonNode response = new ObjectMapper().readTree(
                fixture.tools.executePython("print(1)", "", "", "", 30));

        assertThat(response.path("error").path("code").asText()).isEqualTo("WORKSPACE_DIRTY");
        verify(fixture.sandbox, never()).createTask(any(ExecuteRequest.class));
    }

    @Test
    void disabledRunKeepsTheOldMissingDatasetRule() throws Exception {
        Fixture fixture = fixture(false);
        AgentContext.setRunId("run-a");

        JsonNode response = new ObjectMapper().readTree(
                fixture.tools.executePython("print(1)", "", "", "", 30));

        assertThat(response.path("error").path("code").asText()).isEqualTo("MISSING_IDS");
        verify(fixture.sandbox, never()).acquireWorkspace(any(AcquireWorkspaceRequest.class));
        verify(fixture.sandbox, never()).createTask(any(ExecuteRequest.class));
    }

    @Test
    void deletingRunDoesNotReviewOrAcquireAWorkspace() throws Exception {
        Fixture fixture = fixture(true);
        AgentContext.setRunId("run-a");
        when(fixture.runs.isDeletionStarted("run-a")).thenReturn(true);

        JsonNode response = new ObjectMapper().readTree(
                fixture.tools.executePython("print(1)", "", "", "", 30));

        assertThat(response.path("error").path("code").asText())
                .isEqualTo("RUN_DELETION_IN_PROGRESS");
        verify(fixture.review, never()).evaluate(any(), any(), any());
        verify(fixture.sandbox, never()).acquireWorkspace(any(AcquireWorkspaceRequest.class));
        verify(fixture.sandbox, never()).createTask(any(ExecuteRequest.class));
    }

    @Test
    void cleaningOrExpiredRunCannotStartAnotherPythonCall() throws Exception {
        for (String lifecycle : java.util.List.of("CLEANING", "EXPIRED")) {
            Fixture fixture = fixture(true);
            AgentContext.setRunId("run-a");
            when(fixture.runs.findWorkspaceLifecycleState("run-a")).thenReturn(lifecycle);

            JsonNode response = new ObjectMapper().readTree(
                    fixture.tools.executePython("print(1)", "", "", "", 30));

            assertThat(response.path("error").path("code").asText())
                    .isEqualTo("EXPIRED".equals(lifecycle)
                            ? "WORKSPACE_EXPIRED" : "WORKSPACE_CLEANUP_IN_PROGRESS");
            verify(fixture.review, never()).evaluate(any(), any(), any());
            verify(fixture.sandbox, never()).acquireWorkspace(any(AcquireWorkspaceRequest.class));
        }
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
