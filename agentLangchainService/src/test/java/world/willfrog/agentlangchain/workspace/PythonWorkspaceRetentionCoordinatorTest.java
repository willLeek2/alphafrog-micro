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
