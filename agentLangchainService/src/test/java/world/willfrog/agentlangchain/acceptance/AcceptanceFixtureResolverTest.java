package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按 Run 找夹具：编号从请求上下文读，泳道代际两边一致，查回来还要现在能用。
 */
class AcceptanceFixtureResolverTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AcceptanceFixtureResolver resolver =
            new AcceptanceFixtureResolver(store, identityProvider, objectMapper);









    private void assertRefusedAsInvalid(AgentRun run) {
        assertThatThrownBy(() -> resolver.resolve(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    /** ext 原样写进去，故意写坏，用来验证「读不回来时不放过去」。 */
    private AgentRun rawRun(String runId, String ext) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        run.setExt(ext);
        return run;
    }

    private AgentRun fixtureRun(String fixtureId) throws Exception {
        return ordinaryRun("{\"acceptanceFixtureId\":\"" + fixtureId + "\"}");
    }

    private AgentRun ordinaryRun(String contextJson) throws Exception {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        run.setExt(objectMapper.writeValueAsString(Map.of("context_json", contextJson)));
        return run;
    }

    private static AcceptanceFixtureRow row(boolean enabled, OffsetDateTime expiresAt) {
        return new AcceptanceFixtureRow("fx-1", "scenario-a", enabled,
                enabled ? OffsetDateTime.now().minusMinutes(5) : null, expiresAt,
                null, "{\"turns\":[{\"text\":\"计划\"}]}", null);
    }

    private static String code(Throwable error) {
        return ((AcceptanceFixtureExecutionException) error).code();
    }
}
