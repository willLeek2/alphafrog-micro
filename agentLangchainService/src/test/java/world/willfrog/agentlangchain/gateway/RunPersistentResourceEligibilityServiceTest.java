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
