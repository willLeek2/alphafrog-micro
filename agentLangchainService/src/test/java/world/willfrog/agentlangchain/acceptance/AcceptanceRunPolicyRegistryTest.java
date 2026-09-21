package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 按 Run 取回放行策略：不带夹具编号的 Run 拿到空，带编号的按夹具里的那份策略走。
 *
 * <p>这里量三件事：普通 Run 一步都不查；同一份策略读两次拿到同一个对象（策略是不可变值，解析一次
 * 就够了）；夹具在跑的中途失效时读策略当场报错，不许「夹具不让用了，被压住的成员却被悄悄放过去」。</p>
 */
class AcceptanceRunPolicyRegistryTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String POLICY = "{\"members\":{\"call-1\":{\"holdUntilPoint\":\"point-a\"}}}";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anOrdinaryRunNeverLooksAnythingUp() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();

        Optional<AcceptanceReleasePolicy> policy =
                registry.policyForRun(run("run-1", "{\"execution_mode\":\"DAG\"}"));

        assertThat(policy).isEmpty();
        verifyNoInteractions(store);
        verifyNoInteractions(identityProvider);
    }

    @Test
    void aFixtureRunGetsItsPolicyAndReadsItOnce() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);

        AcceptanceReleasePolicy first = registry.policyForRun(fixtureRun("fx-1")).orElseThrow();
        AcceptanceReleasePolicy second = registry.policyForRun(fixtureRun("fx-1")).orElseThrow();

        assertThat(first).isSameAs(second);
        assertThat(first.releasePointKey("call-1")).contains("point-a");
        // 每次取用仍然重新核对夹具还在、已启用、没过期；只有解析结果被记住。
        verify(store, times(2)).find(LANE, GENERATION, "fx-1");
    }

    @Test
    void aFixtureWithoutAPolicyYieldsNothing() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", null);

        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isEmpty();
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isEmpty();
    }

    @Test
    void aFixtureThatTurnsInvalidMidwayIsRefused() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isPresent();

        // 夹具在跑的中途被停用：下一次读策略必须停住，不能让被点名的成员照常收尾。
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(new AcceptanceFixtureRow("fx-1", "scenario-a", false,
                        null, OffsetDateTime.now().plusHours(1), null, null, POLICY)));

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_not_enabled"));
    }

    @Test
    void aPolicyThatCannotBeReadIsRefused() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", "{\"members\":{\"call-1\":{\"holdUntillPoint\":\"point-a\"}}}");

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_policy_invalid"));
    }

    @Test
    void theFixtureOfARunCannotChangeWhileItRuns() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        row("fx-2", "{\"members\":{\"call-1\":{\"fail\":\"故意失败\"}}}");
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isPresent();

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-2")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_identity_changed"));
    }

    @Test
    void thePolicyIsDroppedWhenTheRunFinishes() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        AcceptanceReleasePolicy first = registry.policyForRun(fixtureRun("fx-1")).orElseThrow();

        registry.onRunFinalized(new AgentRunFinalizedEvent(
                "run-1", 7L, AgentRunStatus.COMPLETED.name(), false));

        assertThat(registry.policyForRun(fixtureRun("fx-1")).orElseThrow()).isNotSameAs(first);
    }

    @Test
    void tooManyTrackedRunsIsRefusedInsteadOfDroppingALiveOne() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        when(store.find(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new AcceptanceFixtureRow("fx-1", "scenario-a", true,
                        OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusHours(1),
                        null, null, POLICY)));
        for (int index = 0; index < AcceptanceRunPolicyRegistry.MAX_TRACKED_RUNS; index++) {
            assertThat(registry.policyForRun(fixtureRun("run-" + index, "fx-1"))).isPresent();
        }

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("run-overflow", "fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_tracked_runs_full"));
    }

    private AcceptanceRunPolicyRegistry registry() {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceRunPolicyRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), objectMapper);
    }

    private void row(String fixtureId, String policyJson) {
        when(store.find(LANE, GENERATION, fixtureId)).thenReturn(Optional.of(
                new AcceptanceFixtureRow(fixtureId, "scenario-a", true,
                        OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().plusHours(1),
                        null, null, policyJson)));
    }

    private AgentRun fixtureRun(String fixtureId) throws Exception {
        return fixtureRun("run-1", fixtureId);
    }

    private AgentRun fixtureRun(String runId, String fixtureId) throws Exception {
        return run(runId, "{\"execution_mode\":\"DAG\",\"acceptanceFixtureId\":\"" + fixtureId + "\"}");
    }

    private AgentRun run(String runId, String contextJson) throws Exception {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        run.setExt(objectMapper.writeValueAsString(Map.of("context_json", contextJson)));
        return run;
    }
}
