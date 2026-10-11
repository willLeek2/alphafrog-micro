package world.willfrog.agentlangchain.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonWorkspaceRetentionCoordinatorTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final AgentLlmLocalConfigLoader configLoader = mock(AgentLlmLocalConfigLoader.class);
    private final PythonWorkspaceRetentionPort sandbox = mock(PythonWorkspaceRetentionPort.class);
    private final RunOwnershipGateway ownership = mock(RunOwnershipGateway.class);
    private final PythonWorkspaceExpirationService expiration = mock(PythonWorkspaceExpirationService.class);
    private final PythonWorkspaceRetentionCoordinator coordinator =
            new PythonWorkspaceRetentionCoordinator(runs, configLoader, new ObjectMapper(), ownership, expiration);

    PythonWorkspaceRetentionCoordinatorTest() {
        ReflectionTestUtils.setField(coordinator, "sandbox", sandbox);
        configureRetention(24);
        when(ownership.requireIdentity()).thenReturn(new DeploymentIdentity("beta-test", "gen-" + "a".repeat(64)));
        when(runs.listWorkspaceRetentionLanes("beta-test")).thenReturn(List.of(run(
                AgentRunStatus.COMPLETED, true, null)));
        when(runs.scanWorkspaceCleanupStarted(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(sandbox.listCandidates(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void terminalRunAfterLastActivityIsDeletedUnderItsPersistedLane() {
        AgentRun run = run(AgentRunStatus.COMPLETED, true, "lane-a");
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(25));
        when(runs.findById("run-1")).thenReturn(run);
        when(runs.listWorkspaceRetentionLanes("beta-test")).thenReturn(List.of(run));
        when(runs.markWorkspaceCleanupStarted("run-1")).thenReturn(1);
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(candidate));
        when(sandbox.deleteIfUnchanged(candidate)).thenAnswer(call -> {
            assertThat(LaneScopeGateway.currentLaneTag()).isEqualTo("lane-a");
            return PythonWorkspaceRetentionPort.DeleteResult.DELETED;
        });

        coordinator.scan();

        verify(runs).markWorkspaceCleanupStarted("run-1");
        verify(expiration).confirmDeleted("run-1");
        assertThat(LaneScopeGateway.currentLaneTag()).isNull();
    }

    @Test
    void unavailableFirstLaneDoesNotStarveLaterLane() {
        AgentRun mainLane = run(AgentRunStatus.COMPLETED, true, null);
        AgentRun healthyLane = run(AgentRunStatus.COMPLETED, true, "lane-b");
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(25));
        when(runs.listWorkspaceRetentionLanes("beta-test"))
                .thenReturn(List.of(mainLane, healthyLane));
        when(runs.findById("run-1")).thenReturn(healthyLane);
        when(runs.markWorkspaceCleanupStarted("run-1")).thenReturn(1);
        when(sandbox.listCandidates(eq(""), eq(100))).thenAnswer(call -> {
            if (LaneScopeGateway.currentLaneTag() == null) {
                throw new IllegalStateException("main sandbox unavailable");
            }
            assertThat(LaneScopeGateway.currentLaneTag()).isEqualTo("lane-b");
            return List.of(candidate);
        });
        when(sandbox.deleteIfUnchanged(candidate))
                .thenReturn(PythonWorkspaceRetentionPort.DeleteResult.DELETED);

        coordinator.scan();

        verify(expiration).confirmDeleted("run-1");
        assertThat(LaneScopeGateway.currentLaneTag()).isNull();
    }

    @Test
    void liveRunAndRecentlyUsedWorkspaceAreKept() {
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(23));
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(candidate));
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.EXECUTING, true, null));

        coordinator.scan();
        verify(runs, never()).markWorkspaceCleanupStarted(anyString());

        reset(runs);
        when(runs.scanWorkspaceCleanupStarted(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.COMPLETED, true, null));
        coordinator.scan();
        verify(runs, never()).markWorkspaceCleanupStarted(anyString());
    }

    @Test
    void terminalRunWithOpenAsyncWorkCannotStartDiskDeletion() {
        AgentRun run = run(AgentRunStatus.FAILED, true, null);
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(25));
        when(runs.findById("run-1")).thenReturn(run);
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(candidate));
        // 数据库原子条件发现仍有等待组、成员或子代理责任，因此返回零行。
        when(runs.markWorkspaceCleanupStarted("run-1")).thenReturn(0);

        coordinator.scan();

        verify(runs).markWorkspaceCleanupStarted("run-1");
        verify(sandbox, never()).deleteIfUnchanged(any());
        verifyNoInteractions(expiration);
    }

    @Test
    void hotRetentionExtensionRechecksTheSameCandidate() {
        configureRetention(48);
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(30));
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(candidate));
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.FAILED, true, null));

        coordinator.scan();
        verify(runs, never()).markWorkspaceCleanupStarted(anyString());
        verify(sandbox).listCandidates(eq(""), eq(100));
    }

    @Test
    void retentionExtendedAfterMarkerDoesNotDeleteDisk() {
        AgentLlmProperties initial = new AgentLlmProperties();
        initial.getAgent().getPythonWorkspace().setRetentionHours(24);
        AgentLlmProperties extended = new AgentLlmProperties();
        extended.getAgent().getPythonWorkspace().setRetentionHours(48);
        when(configLoader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(initial, Set.of("agent")),
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(extended, Set.of("agent")));
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(30));
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(candidate));
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.COMPLETED, true, null));
        when(runs.markWorkspaceCleanupStarted("run-1")).thenReturn(1);

        coordinator.scan();

        verify(runs).markWorkspaceCleanupStarted("run-1");
        verify(runs).clearWorkspaceCleanupStarted("run-1");
        verify(sandbox, never()).deleteIfUnchanged(any());
    }

    @Test
    void invalidShortRetentionCannotDeleteBeforeTwentyFourHours() {
        configureRetention(2);
        assertThat(coordinator.retentionHours()).isEqualTo(24);
        configureRetention(24);
        assertThat(coordinator.retentionHours()).isEqualTo(24);
    }

    @Test
    void failedDeleteKeepsMarkAndNextScanRecoversDeletedAuditAcrossGenerations() {
        AgentRun run = run(AgentRunStatus.CANCELED, true, "old-lane");
        run.setDeploymentGenerationId("old-generation");
        PythonWorkspaceRetentionPort.Workspace candidate = workspace(hoursAgo(25));
        when(runs.findById("run-1")).thenReturn(run);
        when(runs.markWorkspaceCleanupStarted("run-1")).thenReturn(1);
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(candidate));
        when(sandbox.deleteIfUnchanged(candidate))
                .thenReturn(PythonWorkspaceRetentionPort.DeleteResult.RETRYABLE_FAILURE);

        coordinator.scan();
        verifyNoInteractions(expiration);
        verify(runs, never()).clearWorkspaceCleanupStarted("run-1");

        reset(sandbox);
        when(runs.scanWorkspaceCleanupStarted(eq("beta-test"), eq(""), eq(100))).thenReturn(List.of("run-1"));
        when(sandbox.findByRun("run-1")).thenAnswer(call -> {
            assertThat(LaneScopeGateway.currentLaneTag()).isEqualTo("old-lane");
            return new PythonWorkspaceRetentionPort.Lookup(
                    PythonWorkspaceRetentionPort.LookupState.DELETED, null);
        });

        coordinator.scan();
        verify(expiration).confirmDeleted("run-1");
    }

    @Test
    void changedActivityWithdrawsOnlyAutomaticCleanupMark() {
        when(runs.scanWorkspaceCleanupStarted(eq("beta-test"), eq(""), eq(100))).thenReturn(List.of("run-1"));
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.PARTIAL, true, null));
        when(sandbox.findByRun("run-1")).thenReturn(new PythonWorkspaceRetentionPort.Lookup(
                PythonWorkspaceRetentionPort.LookupState.FOUND, workspace(hoursAgo(1))));

        coordinator.scan();
        verify(runs).clearWorkspaceCleanupStarted("run-1");
        verifyNoInteractions(expiration);
    }

    @Test
    void absentAuditOrDisabledRunNeverClaimsDeletion() {
        when(runs.scanWorkspaceCleanupStarted(eq("beta-test"), eq(""), eq(100))).thenReturn(List.of("run-1"));
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.COMPLETED, true, null));
        when(sandbox.findByRun("run-1")).thenReturn(new PythonWorkspaceRetentionPort.Lookup(
                PythonWorkspaceRetentionPort.LookupState.NOT_FOUND, null));
        coordinator.scan();
        verifyNoInteractions(expiration);
        verify(runs, never()).clearWorkspaceCleanupStarted(anyString());

        reset(runs);
        when(runs.scanWorkspaceCleanupStarted(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(runs.findById("run-1")).thenReturn(run(AgentRunStatus.COMPLETED, false, null));
        when(sandbox.listCandidates(eq(""), eq(100)))
                .thenReturn(List.of(workspace(hoursAgo(30))));
        coordinator.scan();
        verify(runs, never()).markWorkspaceCleanupStarted(anyString());
    }

    @Test
    void malformedFrozenSwitchCannotAuthorizeCleanup() {
        AgentRun run = run(AgentRunStatus.COMPLETED, true, null);
        run.setExt("{\"python_workspace_enabled\":\"true\"}");
        when(runs.findById("run-1")).thenReturn(run);
        when(sandbox.listCandidates(eq(""), eq(100))).thenReturn(List.of(workspace(hoursAgo(30))));

        coordinator.scan();

        verify(runs, never()).markWorkspaceCleanupStarted(anyString());
        verify(sandbox, never()).deleteIfUnchanged(any());
    }

    private void configureRetention(int hours) {
        AgentLlmProperties properties = new AgentLlmProperties();
        properties.getAgent().getPythonWorkspace().setRetentionHours(hours);
        when(configLoader.hotConfigIsAuthoritative()).thenReturn(true);
        when(configLoader.currentSnapshot()).thenReturn(
                new AgentLlmLocalConfigLoader.LocalConfigSnapshot(properties, Set.of("agent")));
    }

    private static AgentRun run(AgentRunStatus status, boolean workspaceEnabled, String lane) {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setDeploymentId("beta-test");
        run.setStatus(status);
        run.setExt("{\"python_workspace_enabled\":" + workspaceEnabled + "}");
        run.setLaneTag(lane);
        return run;
    }

    private static PythonWorkspaceRetentionPort.Workspace workspace(OffsetDateTime active) {
        return new PythonWorkspaceRetentionPort.Workspace("run-1", "workspace-1", "generation-1", active);
    }

    private static OffsetDateTime hoursAgo(long hours) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
    }
}
