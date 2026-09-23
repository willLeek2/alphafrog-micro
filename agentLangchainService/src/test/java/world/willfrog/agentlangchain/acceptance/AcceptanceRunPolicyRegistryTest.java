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
import static org.mockito.Mockito.never;
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
    private static final String POLICY =
            "{\"rules\":[{\"for\":{\"planGeneration\":7,\"nodeId\":\"n1\",\"nodeAttempt\":1,\"segmentSequence\":0,\"modelTurn\":0,\"memberSeq\":0},\"holdUntilPoint\":\"point-a\"}]}";
    private static final String POLICY_HOLDING_POINT_A = "point-a";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final FixtureRuleHitStore ruleHitStore = mock(FixtureRuleHitStore.class);
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
        assertThat(first.rules()).singleElement()
                .satisfies(rule -> assertThat(rule.holdReleaseKey()).isEqualTo(POLICY_HOLDING_POINT_A));
        verify(ruleHitStore).snapshotPolicy("run-1", "fx-1", "scenario-a", first);
        // 每次取用仍然重新核对夹具还在、已启用、没过期；只有解析结果被记住。
        verify(store, times(2)).find(LANE, GENERATION, "fx-1");
    }

    @Test
    void aFixtureWithoutAPolicyYieldsNothing() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", null);

        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isEmpty();
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isEmpty();
        // 「没有策略」也冻结一次：之后夹具加了策略，这条 Run 就不能中途接受它。
        verify(ruleHitStore, times(1)).snapshotPolicyAbsent("run-1", "fx-1", "scenario-a");
    }

    /**
     * 一开始没有策略、跑到一半夹具加了策略：停下。
     *
     * <p>前半段的成员结果是当场收尾的（没有策略可依），后半段按新加的策略压住或判失败，这一次验收
     * 就说不清按哪一版跑完。</p>
     */
    @Test
    void aPolicyAddedAfterAnEmptyStartIsRefused() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", null);
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isEmpty();

        row("fx-1", POLICY);

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("新加了放行策略")
                .hasMessageContaining("说不清用的是哪一版");
    }

    /**
     * 两个进程第一次同时读、一个读到空、一个读到策略：输了的那一边当场停下。
     *
     * <p>库里只留一个赢家。这一次空读回来时发现赢家是策略（存储层读回比对时报出来），
     * 就把这个拒绝原样传出去——不许把「读不到策略」当成「没有策略」接着跑。</p>
     */
    @Test
    void aConcurrentFirstReadThatLosesToARealPolicyIsRefused() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", null);
        org.mockito.Mockito.doThrow(AcceptanceFixtureExecutionException.refuse(
                        "acceptance_fixture_content_changed", "库里已经冻结了策略摘要 abc"))
                .when(ruleHitStore).snapshotPolicyAbsent("run-1", "fx-1", "scenario-a");

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("已经冻结了策略摘要");
    }

    /** Run 走到终态之后「没有策略」这个冻结也放掉：下一次读到的才是新的事实。 */
    @Test
    void theAbsentFreezeIsDroppedWhenTheRunFinishes() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", null);
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isEmpty();

        registry.onRunFinalized(new AgentRunFinalizedEvent(
                "run-1", 7L, AgentRunStatus.COMPLETED.name(), false));
        row("fx-1", POLICY);

        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isPresent();
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

    /**
     * 夹具行在跑的中途被原位改过（策略换成另一版）：缓存命中那一次也要停住。
     *
     * <p>进程里继续按旧策略跑，库里记下来的却是另一版的内容，执行记录与证据对不上：一条要求压住
     * 某条成员的规则，可能在新一版里已经被删掉，被压住的成员就会被放过去。</p>
     */
    @Test
    void aPolicyChangedMidRunIsRefusedOnTheCacheHit() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isPresent();

        // 只改了策略原文，摘要跟着变：夹具编号、场景、启用状态都没动。
        row("fx-1", "{\"rules\":[{\"for\":{\"planGeneration\":7,\"nodeId\":\"n1\","
                + "\"nodeAttempt\":1,\"segmentSequence\":0,\"modelTurn\":0,\"memberSeq\":0},"
                + "\"fail\":\"换成判失败了\"}]}");

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("说不清用的是哪一版");
    }

    /** 策略被整段删掉（夹具行还在）：缓存命中那一次同样停住，不接着按旧策略跑。 */
    @Test
    void aPolicyRemovedMidRunIsRefusedOnTheCacheHit() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        assertThat(registry.policyForRun(fixtureRun("fx-1"))).isPresent();

        row("fx-1", null);

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_content_changed"))
                .hasMessageContaining("策略被删掉了");
    }

    @Test
    void aPolicyThatCannotBeReadIsRefused() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", "{\"rules\":[{\"for\":{\"nodeId\":\"n1\",\"memberSeq\":0},"
                + "\"holdUntillPoint\":\"point-a\"}]}");

        assertThatThrownBy(() -> registry.policyForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(((AcceptanceFixtureExecutionException) e).code())
                        .isEqualTo("acceptance_fixture_policy_invalid"));
    }

    @Test
    void theFixtureOfARunCannotChangeWhileItRuns() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        row("fx-2", "{\"rules\":[{\"for\":{\"nodeId\":\"n1\",\"memberSeq\":0},\"fail\":\"故意失败\"}]}");
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

    /** 只带控制编号、不带夹具编号：放行策略从这一行的 dispatchPolicyJson 读出来。 */
    @Test
    void aControlOnlyRunLoadsDispatchPolicy() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("ctrl-1", POLICY);

        AcceptanceReleasePolicy policy = registry.policyForRun(controlRun("ctrl-1")).orElseThrow();

        assertThat(policy.rules()).singleElement()
                .satisfies(rule -> assertThat(rule.holdReleaseKey()).isEqualTo(POLICY_HOLDING_POINT_A));
        verify(store).find(LANE, GENERATION, "ctrl-1");
        verify(ruleHitStore).snapshotPolicy("run-1", "ctrl-1", "scenario-a", policy);
    }

    /** 夹具编号和控制编号都在时，策略取夹具那一行，不把两行混在一起。 */
    @Test
    void fixtureAndControlTogetherUseTheFixturePolicy() throws Exception {
        AcceptanceRunPolicyRegistry registry = registry();
        row("fx-1", POLICY);
        row("ctrl-1", "{\"rules\":[{\"for\":{\"planGeneration\":7,\"nodeId\":\"n1\","
                + "\"nodeAttempt\":1,\"segmentSequence\":0,\"modelTurn\":0,\"memberSeq\":0},"
                + "\"fail\":\"控制行的策略\"}]}");

        AcceptanceReleasePolicy policy = registry.policyForRun(run("run-1",
                "{\"execution_mode\":\"DAG\",\"acceptanceFixtureId\":\"fx-1\","
                        + "\"acceptanceControlId\":\"ctrl-1\"}")).orElseThrow();

        assertThat(policy.rules()).singleElement()
                .satisfies(rule -> assertThat(rule.holdReleaseKey()).isEqualTo(POLICY_HOLDING_POINT_A));
        verify(store).find(LANE, GENERATION, "fx-1");
        verify(store, never()).find(LANE, GENERATION, "ctrl-1");
    }

    private AcceptanceRunPolicyRegistry registry() {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceRunPolicyRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), objectMapper,
                ruleHitStore);
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

    private AgentRun controlRun(String controlId) throws Exception {
        return run("run-1", "{\"execution_mode\":\"DAG\",\"acceptanceControlId\":\"" + controlId + "\"}");
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
