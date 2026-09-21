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

    @Test
    void aFixtureRunResolvesToItsRow() throws Exception {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().plusHours(1))));

        AcceptanceFixtureRow resolved = resolver.resolve(fixtureRun("fx-1")).orElseThrow();

        assertThat(resolved.fixtureId()).isEqualTo("fx-1");
        assertThat(resolved.scenarioId()).isEqualTo("scenario-a");
    }

    @Test
    void theFixtureIdIsReadFromTheRequestContextNotTheWholeExt() throws Exception {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        // 编号写在 ext 顶层不算数：创建时只把请求上下文原样存进 context_json，读取口径与创建那道门一致。
        run.setExt(objectMapper.writeValueAsString(Map.of(
                "acceptanceFixtureId", "fx-1",
                "context_json", "{\"execution_mode\":\"DAG\"}")));

        assertThat(resolver.resolve(run)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void anOrdinaryRunNeverTouchesTheStore() throws Exception {
        assertThat(resolver.resolve(ordinaryRun("{\"execution_mode\":\"DAG\"}"))).isEmpty();

        verifyNoInteractions(store);
        verifyNoInteractions(identityProvider);
    }

    @Test
    void aRunFromAnotherLaneIsRefused() throws Exception {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        AgentRun run = fixtureRun("fx-1");
        run.setDeploymentGenerationId("gen-" + "b".repeat(64));

        assertThatThrownBy(() -> resolver.resolve(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_lane_mismatch"));
    }

    @Test
    void aFixtureThatIsNotThereOrNotUsableIsRefused() throws Exception {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));

        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(fixtureRun("fx-1")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_found"));

        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(false, OffsetDateTime.now().plusHours(1))));
        assertThatThrownBy(() -> resolver.resolve(fixtureRun("fx-1")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_enabled"));

        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusSeconds(1))));
        assertThatThrownBy(() -> resolver.resolve(fixtureRun("fx-1")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void aContextThatMentionsTheFixtureButCannotBeReadIsRefused() throws Exception {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));

        assertThatThrownBy(() -> resolver.resolve(ordinaryRun("{\"acceptanceFixtureId\":")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));
        assertThatThrownBy(() -> resolver.resolve(ordinaryRun("\"acceptanceFixtureId\"")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));
        assertThatThrownBy(() -> resolver.resolve(ordinaryRun("{\"acceptanceFixtureId\":\"  \"}")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));
        // 上下文里没有这个词，坏不坏都按普通 Run 处理：一次解析都不做，也不查库。
        assertThat(resolver.resolve(ordinaryRun("[1,2,3]"))).isEmpty();
        verifyNoInteractions(store);
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
