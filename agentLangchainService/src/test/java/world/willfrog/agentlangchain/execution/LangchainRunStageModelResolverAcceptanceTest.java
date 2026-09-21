package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.RunStageConfig;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentAiServiceFactory;
import world.willfrog.agent.platform.service.AgentLlmResolver;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.StageConfigResolver;
import world.willfrog.agent.platform.service.StageConfigValidator;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureModelRegistry;
import world.willfrog.agentlangchain.acceptance.AcceptanceReleasePolicy;
import world.willfrog.agentlangchain.acceptance.AcceptanceRunPolicyRegistry;
import world.willfrog.agentlangchain.acceptance.FrozenModelScript;
import world.willfrog.agentlangchain.acceptance.ScriptedChatModel;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 阶段模型解析这一步：带夹具的 Run 只用脚本模型，普通 Run 照原样解析真实模型。
 */
class LangchainRunStageModelResolverAcceptanceTest {

    private final StageConfigResolver stageConfigResolver = mock(StageConfigResolver.class);
    private final StageConfigValidator stageConfigValidator = mock(StageConfigValidator.class);
    private final AgentAiServiceFactory aiServiceFactory = mock(AgentAiServiceFactory.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AcceptanceFixtureModelRegistry acceptanceFixtureModels =
            mock(AcceptanceFixtureModelRegistry.class);
    private final AcceptanceRunPolicyRegistry acceptancePolicies =
            mock(AcceptanceRunPolicyRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LangchainRunStageModelResolver resolver = new LangchainRunStageModelResolver(
            stageConfigResolver, stageConfigValidator, aiServiceFactory, eventService,
            objectMapper, acceptanceFixtureModels, acceptancePolicies);

    @Test
    void aFixtureRunGetsTheScriptedModelForAllThreeStages() {
        when(acceptanceFixtureModels.stageForRun(any())).thenReturn(Optional.of(scriptedStage()));

        LangchainRunStageModelResolver.StageModels models = resolver.resolve(run());

        // 三个阶段同一个实例：脚本的位置跟着这条 Run 走。
        assertThat(models.planningModel()).isSameAs(models.executionModel());
        assertThat(models.executionModel()).isSameAs(models.finalAnswerModel());
        assertThat(models.executionModel()).isInstanceOf(ScriptedChatModel.class);
        assertThat(models.planningEndpointName()).isEqualTo("acceptance-fixture");
        assertThat(models.planningModelName()).isEqualTo("acceptance-fixture:fx-1");
        assertThat(models.planningProviderOrder()).isEmpty();
        // 真实供应商这一侧一件都没做：没读配置、没解析端点、没建客户端。
        verifyNoInteractions(aiServiceFactory);
        verifyNoInteractions(stageConfigResolver);
        verifyNoInteractions(eventService);
    }

    @Test
    void anOrdinaryRunStillGoesThroughTheNormalResolution() {
        when(acceptanceFixtureModels.stageForRun(any())).thenReturn(Optional.empty());
        when(stageConfigResolver.resolve(any())).thenReturn(new RunStageConfig());
        when(aiServiceFactory.resolveLlm(nullable(String.class), nullable(String.class)))
                .thenReturn(new AgentLlmResolver.ResolvedLlm(
                        "openrouter", "https://example.test", "some-model", "k", null, List.of(), 4096));
        ChatModel realModel = mock(ChatModel.class);
        when(aiServiceFactory.buildChatModelWithProviderOrder(any(), any(), any())).thenReturn(realModel);

        LangchainRunStageModelResolver.StageModels models = resolver.resolve(run());

        // 没有独立配置的阶段退化成执行模型，与改动前的行为一致。
        assertThat(models.executionModel()).isSameAs(realModel);
        assertThat(models.planningModel()).isSameAs(realModel);
        assertThat(models.finalAnswerModel()).isSameAs(realModel);
        // 不是夹具 Run 就不该带任何放行策略：普通 Run 上不许出现「被压住的成员」。
        assertThat(models.acceptanceReleasePolicy()).isNull();
        verifyNoInteractions(acceptancePolicies);
    }

    @Test
    void aFixtureRunCarriesTheReleasePolicyToTheNodeExecutor() {
        when(acceptanceFixtureModels.stageForRun(any())).thenReturn(Optional.of(scriptedStage()));
        AcceptanceReleasePolicy policy = AcceptanceReleasePolicy.parse("fx-1",
                "{\"members\":{\"call-1\":{\"fail\":\"沙箱那边回不来了\"}}}", objectMapper).orElseThrow();
        when(acceptancePolicies.policyForRun(any())).thenReturn(Optional.of(policy));

        LangchainRunStageModelResolver.StageModels models = resolver.resolve(run());

        // 节点执行器在「工具当场完成」那一步要用它给被点名的成员写失败，所以必须跟着模型一起带出来。
        assertThat(models.acceptanceReleasePolicy()).isSameAs(policy);
    }

    @Test
    void aFixtureRunWithoutAPolicyCarriesNothing() {
        when(acceptanceFixtureModels.stageForRun(any())).thenReturn(Optional.of(scriptedStage()));
        when(acceptancePolicies.policyForRun(any())).thenReturn(Optional.empty());

        LangchainRunStageModelResolver.StageModels models = resolver.resolve(run());

        assertThat(models.acceptanceReleasePolicy()).isNull();
    }

    @Test
    void aFixtureRefusalReachesTheCaller() {
        when(acceptanceFixtureModels.stageForRun(any())).thenThrow(
                new AcceptanceFixtureExecutionException("acceptance_fixture_script_exhausted", "脚本用完了"));

        assertThatThrownBy(() -> resolver.resolve(run()))
                .isInstanceOf(AcceptanceFixtureExecutionException.class)
                .hasMessageContaining("acceptance_fixture_script_exhausted");
        verifyNoInteractions(aiServiceFactory);
    }

    private AcceptanceFixtureModelRegistry.ScriptedStage scriptedStage() {
        FrozenModelScript script = FrozenModelScript.parse("fx-1",
                "{\"turns\":[{\"text\":\"计划\"},{\"text\":\"答案\"}]}", objectMapper);
        return new AcceptanceFixtureModelRegistry.ScriptedStage(
                new ScriptedChatModel("fx-1", "scenario-a", script), "fx-1", "scenario-a");
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("7");
        run.setExt("{\"context_json\":\"{\\\"acceptanceFixtureId\\\":\\\"fx-1\\\"}\"}");
        return run;
    }
}
