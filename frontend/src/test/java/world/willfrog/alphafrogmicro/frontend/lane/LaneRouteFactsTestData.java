package world.willfrog.alphafrogmicro.frontend.lane;

import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBinding;
import world.willfrog.alphafrogmicro.common.lane.LaneEndpoint;

final class LaneRouteFactsTestData {

    static final String GENERATION = "gen-" + "a".repeat(64);
    static final String REGISTRATION = "world.willfrog.alphafrogmicro.agent.idl.AgentDubboService:1.0@@providers";

    private LaneRouteFactsTestData() {
    }

    static LaneRouteFacts facts(String instanceId, int port, long routeVersion) {
        return new LaneRouteFacts(
                "lane-test",
                "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", GENERATION),
                REGISTRATION,
                new LaneCallBinding(
                        "lane-test",
                        "agent-langchain-service",
                        instanceId,
                        "release-a",
                        GENERATION,
                        routeVersion,
                        new LaneEndpoint("10.0.0.8", port)),
                17);
    }
}
