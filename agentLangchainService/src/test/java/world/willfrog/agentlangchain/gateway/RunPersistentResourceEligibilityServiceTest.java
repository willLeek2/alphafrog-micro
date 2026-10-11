package world.willfrog.agentlangchain.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityRequest;
import world.willfrog.alphafrogmicro.agent.idl.RunPersistentResourceEligibility;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RunPersistentResourceEligibilityServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final RunOwnershipGateway ownership = new RunOwnershipGateway(
            GatewayTestFixtures.identityProvider("beta-test", GENERATION), runs);
    private final RunPersistentResourceEligibilityService service =
            new RunPersistentResourceEligibilityService(ownership, runs);

    @AfterEach
    void clearLane() {
        LaneContext.clear();
    }

    @Test
    void executingRunInItsOwnDeploymentAndLaneIsAllowed() {
        LaneContext.setTrafficScopeId("lane-a");
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, "lane-a"));

        assertThat(verdict("run-1")).isEqualTo(allowed());
        verify(runs).isDeletionStarted("run-1");
    }

    @Test
    void mainBetaWithoutDubboTagCanReadOnlyMainBetaRun() {
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, null));

        assertThat(verdict("run-1")).isEqualTo(allowed());
    }

    @Test
    void wrongLaneOrDeploymentGenerationIsRefused() {
        LaneContext.setTrafficScopeId("lane-b");
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, "lane-a"));
        assertThat(verdict("run-1")).isEqualTo(refused());
        verify(runs, never()).isDeletionStarted("run-1");

        reset(runs);
        LaneContext.setTrafficScopeId("lane-a");
        // 其他部署或旧代际即使持有相同 runId，也不会被归属查询返回。
        assertThat(verdict("run-1")).isEqualTo(refused());
        verify(runs).findByIdForDeployment("run-1", "beta-test", GENERATION);
        verify(runs, never()).isDeletionStarted("run-1");
    }

    @ParameterizedTest
    @EnumSource(value = AgentRunStatus.class, names = "EXECUTING", mode = EnumSource.Mode.EXCLUDE)
    void nonExecutingRunCannotCreatePersistentResources(AgentRunStatus status) {
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(status, null));

        assertThat(verdict("run-1")).isEqualTo(refused());
        verify(runs, never()).isDeletionStarted("run-1");
    }

    @Test
    void startedDeletionOrMalformedRunIdIsRefused() {
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, null));
        when(runs.isDeletionStarted("run-1")).thenReturn(true);

        assertThat(verdict("run-1")).isEqualTo(refused());
        assertThat(verdict("  ")).isEqualTo(refused());
        verify(runs, never()).findByIdForDeployment("", "beta-test", GENERATION);
    }

    @Test
    void cleanupInProgressOrExpiredWorkspaceCannotBeReacquired() {
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, null));
        when(runs.findWorkspaceLifecycleState("run-1")).thenReturn("CLEANING", "EXPIRED");

        assertThat(verdict("run-1")).isEqualTo(refused());
        assertThat(verdict("run-1")).isEqualTo(refused());
    }

    @Test
    void onlyFrozenTrueWorkspaceSettingCanAcquirePersistentResources() {
        AgentRun run = run(AgentRunStatus.EXECUTING, null);
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION)).thenReturn(run);

        run.setExt("{\"python_workspace_enabled\":false}");
        assertThat(verdict("run-1")).isEqualTo(refused());
        run.setExt("{}");
        assertThat(verdict("run-1")).isEqualTo(refused());
        run.setExt(null);
        assertThat(verdict("run-1")).isEqualTo(refused());
        run.setExt("{\"python_workspace_enabled\":\"true\"}");
        assertThat(verdict("run-1")).isEqualTo(refused());
        verify(runs, never()).isDeletionStarted("run-1");
    }

    @Test
    void malformedOrAmbiguousFrozenSettingFailsClosed() {
        AgentRun run = run(AgentRunStatus.EXECUTING, null);
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION)).thenReturn(run);

        run.setExt("{broken");
        assertThat(verdict("run-1")).isEqualTo(unavailable());
        run.setExt("{\"python_workspace_enabled\":false,\"python_workspace_enabled\":true}");
        assertThat(verdict("run-1")).isEqualTo(unavailable());
        run.setExt("{\"python_workspace_enabled\":true} false");
        assertThat(verdict("run-1")).isEqualTo(unavailable());
        verify(runs, never()).isDeletionStarted("run-1");
    }

    @Test
    void unavailableDeploymentIdentityOrDatabaseFailsClosed() {
        RunOwnershipGateway noIdentity = new RunOwnershipGateway(
                () -> { throw new IllegalStateException("deployment identity missing"); }, runs);
        RunPersistentResourceEligibilityService failedIdentity =
                new RunPersistentResourceEligibilityService(noIdentity, runs);
        assertThat(failedIdentity.check(request("run-1")).getEligibility())
                .isEqualTo(unavailable());

        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, null));
        when(runs.isDeletionStarted("run-1")).thenThrow(new IllegalStateException("database unavailable"));
        assertThat(verdict("run-1")).isEqualTo(unavailable());
    }

    @Test
    void dedicatedDubboProviderOnlyDelegatesToReadOnlyEligibilityService() {
        AgentRunPersistentResourceEligibilityDubboService provider =
                new AgentRunPersistentResourceEligibilityDubboService(service);
        when(runs.findByIdForDeployment("run-1", "beta-test", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING, null));

        assertThat(provider.checkEligibility(request("run-1")).getEligibility()).isEqualTo(allowed());
    }

    private RunPersistentResourceEligibility verdict(String runId) {
        return service.check(request(runId)).getEligibility();
    }

    private static CheckRunPersistentResourceEligibilityRequest request(String runId) {
        return CheckRunPersistentResourceEligibilityRequest.newBuilder().setRunId(runId).build();
    }

    private static AgentRun run(AgentRunStatus status, String laneTag) {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(status);
        run.setLaneTag(laneTag);
        run.setExt("{\"python_workspace_enabled\":true}");
        return run;
    }

    private static RunPersistentResourceEligibility allowed() {
        return RunPersistentResourceEligibility.RUN_PERSISTENT_RESOURCE_ELIGIBILITY_ALLOWED;
    }

    private static RunPersistentResourceEligibility refused() {
        return RunPersistentResourceEligibility.RUN_PERSISTENT_RESOURCE_ELIGIBILITY_REFUSED;
    }

    private static RunPersistentResourceEligibility unavailable() {
        return RunPersistentResourceEligibility.RUN_PERSISTENT_RESOURCE_ELIGIBILITY_TEMPORARILY_UNAVAILABLE;
    }
}
