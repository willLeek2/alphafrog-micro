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
 * 执行层按 Run 找夹具：不带编号的走原路，带编号的要么拿到脚本模型，要么当场报错。
 */
class AcceptanceFixtureModelRegistryTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String GOOD_SCRIPT = "{\"turns\":[{\"text\":\"计划\"},{\"text\":\"答案\"}]}";

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aRunWithoutAFixtureIdNeverLooksAnythingUp() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();

        Optional<AcceptanceFixtureModelRegistry.ScriptedStage> stage =
                registry.stageForRun(run("run-1", "{\"execution_mode\":\"DAG\"}"));

        assertThat(stage).isEmpty();
        // 普通 Run 连夹具表与部署身份都不碰，行为与从前完全一致。
        verifyNoInteractions(store);
        verifyNoInteractions(identityProvider);
    }

    @Test
    void aFixtureRunGetsTheSameModelForEverySegmentOfThatRun() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));

        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();
        AcceptanceFixtureModelRegistry.ScriptedStage second = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        // 同一个实例：脚本的位置跟着 Run 走，一段一个位置会从头上再喂一遍。
        assertThat(second.model()).isSameAs(first.model());
        assertThat(second.fixtureId()).isEqualTo("fx-1");
        assertThat(second.scenarioId()).isEqualTo("scenario-a");
        assertThat(second.modelName()).isEqualTo("acceptance-fixture:fx-1");
        // 每次取用都重新核对夹具还在、已启用、没过期。
        verify(store, times(2)).find(LANE, GENERATION, "fx-1");
    }

    @Test
    void aFixtureThatIsNotThereIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_found"));
    }

    @Test
    void aFixtureThatIsNotEnabledOrAlreadyExpiredIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(false, future(), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_enabled"));

        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusMinutes(1), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void aFixtureThatExpiresWhileTheRunIsMidwayIsRefusedOnTheNextSegment() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        assertThat(registry.stageForRun(fixtureRun("fx-1"))).isPresent();

        // 夹具在跑的中途到期：下一次取模型必须停住，不能接着用脚本，更不能换真实模型。
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusSeconds(1), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void aRunThatWasAlreadyPlannedWithoutALivePositionIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AgentRun run = fixtureRun("fx-1");
        run.setPlanJson("{\"items\":[]}");

        // 进程重启或换了实例：脚本位置重建不了，从头再喂一遍会让后面的回合与实际调用错开。
        assertThatThrownBy(() -> registry.stageForRun(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_position_lost"));
    }

    @Test
    void aRunFromAnotherLaneIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        AgentRun run = fixtureRun("fx-1");
        run.setDeploymentGenerationId("gen-" + "b".repeat(64));

        assertThatThrownBy(() -> registry.stageForRun(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_lane_mismatch"));
        verify(store, never()).find(anyString(), anyString(), anyString());
    }

    @Test
    void aContextThatMentionsTheFixtureButCannotBeReadIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();

        assertThatThrownBy(() -> registry.stageForRun(run("run-1", "{\"acceptanceFixtureId\":")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));

        assertThatThrownBy(() -> registry.stageForRun(run("run-1", "{\"acceptanceFixtureId\":\"  \"}")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_invalid"));
    }

    @Test
    void aMessageThatMerelyMentionsTheFixtureFieldIsStillAnOrdinaryRun() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        // ext 里还存着用户消息正文：正文里出现这个词不等于这次请求带了夹具编号。
        run.setExt(objectMapper.writeValueAsString(Map.of(
                "message", "acceptanceFixtureId 这个字段是干什么用的？",
                "context_json", "{\"execution_mode\":\"DAG\"}")));

        assertThat(registry.stageForRun(run)).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void aScriptThatCannotBeReadIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"turns\":[{\"toolcalls\":[]}]}")));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"));
    }

    @Test
    void theFixtureOfARunCannotChangeWhileItRuns() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        when(store.find(LANE, GENERATION, "fx-2"))
                .thenReturn(Optional.of(new AcceptanceFixtureRow("fx-2", "scenario-b", true,
                        OffsetDateTime.now(), future(), null, GOOD_SCRIPT, null)));
        assertThat(registry.stageForRun(fixtureRun("fx-1"))).isPresent();

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-2")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_identity_changed"));
    }

    @Test
    void thePositionIsDroppedWhenTheRunFinishes() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        registry.evict("run-1");

        AcceptanceFixtureModelRegistry.ScriptedStage afterEviction =
                registry.stageForRun(fixtureRun("fx-1")).orElseThrow();
        assertThat(afterEviction.model()).isNotSameAs(first.model());
    }

    @Test
    void aTerminalEventDropsThePosition() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        registry.onRunFinalized(new AgentRunFinalizedEvent(
                "run-1", 7L, AgentRunStatus.COMPLETED.name(), false));

        assertThat(registry.stageForRun(fixtureRun("fx-1")).orElseThrow().model())
                .isNotSameAs(first.model());
    }

    @Test
    void tooManyTrackedRunsIsRefusedInsteadOfDroppingALiveOne() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        for (int index = 0; index < AcceptanceFixtureModelRegistry.MAX_TRACKED_RUNS; index++) {
            assertThat(registry.stageForRun(fixtureRun("run-" + index, "fx-1"))).isPresent();
        }

        // 到顶时拒绝新的夹具 Run，不去挤掉正在跑的那些：挤掉会让那条 Run 的脚本位置凭空消失。
        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("run-overflow", "fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_tracked_runs_full"));
    }

    private AcceptanceFixtureModelRegistry registry() {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceFixtureModelRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), objectMapper);
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

    private static AcceptanceFixtureRow row(boolean enabled, OffsetDateTime expiresAt, String scriptJson) {
        return new AcceptanceFixtureRow("fx-1", "scenario-a", enabled,
                enabled ? OffsetDateTime.now().minusMinutes(5) : null, expiresAt, null, scriptJson, null);
    }

    private static OffsetDateTime future() {
        return OffsetDateTime.now().plusHours(1);
    }

    private static String code(Throwable error) {
        return ((AcceptanceFixtureExecutionException) error).code();
    }
}
