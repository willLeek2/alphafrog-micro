package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;
import world.willfrog.alphafrogmicro.sandbox.idl.DeleteWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.QueryWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.SealWorkspaceResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceDeleteOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceInfo;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceQueryOutcome;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DubboRunWorkspaceResourceGatewayTest {
    private final AgentRunMapper runs = mock(AgentRunMapper.class);
    private final PythonSandboxService sandbox = mock(PythonSandboxService.class);
    private final DeploymentIdentityProvider identity = () ->
            new DeploymentIdentity("beta-test", "gen-" + "a".repeat(64));
    private final DubboRunWorkspaceResourceGateway gateway =
            new DubboRunWorkspaceResourceGateway(runs, identity);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(gateway, "sandbox", sandbox);
        when(runs.findById("run-a")).thenReturn(run("run-a", "beta-test", "saved-lane"));
        LaneContext.setTrafficScopeId("inbound-lane");
    }

    @AfterEach
    void clearLane() {
        LaneContext.restore(null);
    }







    private static AgentRun run(String id, String deploymentId, String lane) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setDeploymentId(deploymentId);
        run.setLaneTag(lane);
        return run;
    }

    private static WorkspaceInfo workspace(String owner, String status) {
        return WorkspaceInfo.newBuilder().setWorkspaceId("workspace-a")
                .setWorkspaceGeneration("generation-a")
                .setOwnedByRunId(owner).setStatus(status).build();
    }
}
