package world.willfrog.alphafrogmicro.frontend.lane;

import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.lane.LaneCallBinding;
import world.willfrog.alphafrogmicro.common.lane.LaneEndpoint;
import world.willfrog.alphafrogmicro.common.lane.LaneDubboServiceKey;

final class LaneRouteFactsTestData {

    static final String GENERATION = "gen-" + "a".repeat(64);
    static final String INTERFACE = "world.willfrog.alphafrogmicro.agent.idl.AgentDubboService";
    static final LaneDubboServiceKey DUBBO_SERVICE_KEY = new LaneDubboServiceKey("langchain", INTERFACE, "");
    static final String REGISTRATION = "providers:" + INTERFACE + "::langchain";

    private LaneRouteFactsTestData() {
    }

    static LaneRouteFacts facts(String instanceId, int port, long routeVersion) {
        return new LaneRouteFacts(
                "lane-test",
                "agent-langchain-service",
                new DeploymentIdentity("beta-main-001", GENERATION),
                DUBBO_SERVICE_KEY,
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
