package world.willfrog.agentlangchain.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agent.platform.workitem.SchedulerVersion;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 执行层按 Run 找夹具：不带编号的走原路，带编号的要么拿到脚本模型，要么当场报错。
 *
 * <p>消费位置不在这个组件里了（在数据库的认领表里），所以这里要核的是「给的是脚本模型、
 * 每次调用要自己带身份、旧调度版本与读不出来的脚本一律拒绝」。</p>
 */
class AcceptanceFixtureModelRegistryTest {

    private static final String LANE = "beta-lane-0910";
    private static final String GENERATION = "gen-" + "a".repeat(64);
    private static final String GOOD_SCRIPT = """
            {"turns":[{"for":{"stage":"planning","planPhase":"strategy"},"text":"计划"},
                      {"for":{"stage":"answer"},"text":"答案"}]}
            """;

    private final AcceptanceFixtureStore store = mock(AcceptanceFixtureStore.class);
    private final FixtureCallStore callStore = mock(FixtureCallStore.class);
    private final DeploymentIdentityProvider identityProvider = mock(DeploymentIdentityProvider.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aRunWithoutAFixtureIdNeverLooksAnythingUp() {
        AcceptanceFixtureModelRegistry registry = registry();

        Optional<AcceptanceFixtureModelRegistry.ScriptedStage> stage =
                registry.stageForRun(run("run-1", "{\"execution_mode\":\"DAG\"}"));

        assertThat(stage).isEmpty();
        // 普通 Run 连夹具表与部署身份都不碰，行为与从前完全一致。
        verifyNoInteractions(store);
        verifyNoInteractions(identityProvider);
    }

    @Test
    void aFixtureRunGetsAScriptedModelEveryTimeItAsks() {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));

        AcceptanceFixtureModelRegistry.ScriptedStage first = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();
        AcceptanceFixtureModelRegistry.ScriptedStage second = registry.stageForRun(fixtureRun("fx-1")).orElseThrow();

        // 每一次都按夹具行当场建：位置不在进程里，所以「换个实例/重启之后」与第一次没有区别。
        assertThat(first.model()).isInstanceOf(ScriptedChatModel.class);
        assertThat(second.model()).isInstanceOf(ScriptedChatModel.class);
        assertThat(first.fixtureId()).isEqualTo("fx-1");
        assertThat(first.scenarioId()).isEqualTo("scenario-a");
        assertThat(first.model().scriptSize()).isEqualTo(2);
        assertThat(first.model().scriptDigest()).hasSize(64);
        assertThat(first.modelName()).isEqualTo("acceptance-fixture:fx-1");
        assertThat(first.describe()).contains("fx-1").contains("scenario-a");
    }

    @Test
    void aRealModelPassesThroughAndAScriptedOneGetsTheCallIdentity() {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        ChatModel real = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                return ChatResponse.builder().aiMessage(AiMessage.from("真实模型")).build();
            }
        };
        ChatModel scripted = registry.stageForRun(fixtureRun("fx-1")).orElseThrow().model();

        FixtureCallIdentity identity = FixtureCallIdentity.answer("run-1", 0);
        ChatModel boundScripted = AcceptanceFixtureModelRegistry.forCall(scripted, () -> identity);
        ChatModel boundReal = AcceptanceFixtureModelRegistry.forCall(real, () -> {
            throw new AssertionError("普通 Run 上不该去拼调用身份");
        });

        // 普通 Run 的调用点不用为夹具分叉：真实模型原样返回，脚本模型返回绑好身份的那一个。
        assertThat(boundReal).isSameAs(real);
        assertThat(boundScripted).isNotSameAs(scripted).isInstanceOf(ChatModel.class);
    }

    @Test
    void aFixtureThatIsNotThereIsRefused() {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_found"));
    }

    @Test
    void aFixtureThatIsNotEnabledOrAlreadyExpiredIsRefused() {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(false, future(), GOOD_SCRIPT)));
        when(store.find(LANE, GENERATION, "fx-2"))
                .thenReturn(Optional.of(row(true, OffsetDateTime.now().minusMinutes(1), GOOD_SCRIPT)));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_not_enabled"));
        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-2")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_expired"));
    }

    @Test
    void aRunFromAnotherLaneIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(identityProvider.current()).thenReturn(new DeploymentIdentity("other-lane", GENERATION));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_lane_mismatch"));
    }

    @Test
    void aScriptThatCannotBeReadIsRefused() {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), "{\"turns\":[{\"text\":\"没有声明\"}]}")));

        assertThatThrownBy(() -> registry.stageForRun(fixtureRun("fx-1")))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_script_invalid"))
                .hasMessageContaining("没写 for");
    }

    @Test
    void aRunOnTheLegacySchedulerIsRefusedInsteadOfHandedTheScript() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AgentRun run = fixtureRun("fx-1");
        run.setSchedulerVersion(SchedulerVersion.LEGACY.name());

        assertThatThrownBy(() -> registry.stageForRun(run))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_scheduler_version_unusable"))
                .hasMessageContaining("一个分段一次模型调用");
    }

    @Test
    void aSchedulerVersionThatCannotBeReadIsRefused() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AgentRun run = fixtureRun("fx-1");
        run.setSchedulerVersion("WHAT_IS_THIS");

        assertThatThrownBy(() -> registry.stageForRun(run))
                .satisfies(e -> assertThat(code(e)).isEqualTo("acceptance_fixture_scheduler_version_unusable"));
    }

    @Test
    void theOldSchedulerVersionOfTheDualPoolFamilyIsAccepted() throws Exception {
        AcceptanceFixtureModelRegistry registry = registry();
        when(store.find(LANE, GENERATION, "fx-1"))
                .thenReturn(Optional.of(row(true, future(), GOOD_SCRIPT)));
        AgentRun run = fixtureRun("fx-1");
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());

        assertThat(registry.stageForRun(run)).isPresent();
    }

    @Test
    void aRunFinishingMakesTheStoreCheckTheScriptDeclarations() {
        AcceptanceFixtureModelRegistry registry = registry();

        registry.onRunFinalized(new AgentRunFinalizedEvent("run-1", 7L, "COMPLETED", false));

        verify(callStore).recordVerdict("run-1");
    }

    @Test
    void aFailingVerdictCheckDoesNotBreakTheRunFinalization() {
        AcceptanceFixtureModelRegistry registry = registry();
        doThrow(refuse("acceptance_fixture_content_changed", "内容被改过")).when(callStore).recordVerdict(any());

        // 核对本身不能把收尾带下去：留日志，Run 该怎样还是怎样。
        assertThatCode(() -> registry.onRunFinalized(new AgentRunFinalizedEvent("run-1", 7L, "COMPLETED", false)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.onRunFinalized(null)).doesNotThrowAnyException();
    }

    private AcceptanceFixtureModelRegistry registry() {
        when(identityProvider.current()).thenReturn(new DeploymentIdentity(LANE, GENERATION));
        return new AcceptanceFixtureModelRegistry(
                new AcceptanceFixtureResolver(store, identityProvider, objectMapper), callStore, objectMapper);
    }

    private AgentRun fixtureRun(String fixtureId) {
        return run("run-1", "{\"execution_mode\":\"DAG\",\"acceptanceFixtureId\":\"" + fixtureId + "\"}");
    }

    private AgentRun run(String runId, String contextJson) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setUserId("7");
        run.setDeploymentId(LANE);
        run.setDeploymentGenerationId(GENERATION);
        // 夹具只在「一个分段一次模型调用」的调度器版本下跑：默认按那个版本造数据。
        run.setSchedulerVersion(SchedulerVersion.DUAL_POOL_V2.name());
        try {
            run.setExt(objectMapper.writeValueAsString(Map.of("context_json", contextJson)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
