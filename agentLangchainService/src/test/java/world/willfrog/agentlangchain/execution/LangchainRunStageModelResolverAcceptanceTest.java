package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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
import world.willfrog.agentlangchain.acceptance.FixtureCallIdentity;
import world.willfrog.agentlangchain.acceptance.FixtureCallStore;
import world.willfrog.agentlangchain.acceptance.FrozenModelScript;
import world.willfrog.agentlangchain.acceptance.ScriptedChatModel;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.acceptance.AcceptanceFixtureExecutionException.refuse;

/**
 * 阶段模型解析：带夹具的 Run 先拿到脚本模型，未声明调用才懒建真实客户端；普通 Run 照原样解析。
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







    private AcceptanceFixtureModelRegistry.ScriptedStage scriptedStage() {
        FrozenModelScript script = FrozenModelScript.parse("fx-1",
                "{\"turns\":[{\"for\":{\"stage\":\"planning\",\"planPhase\":\"strategy\"},\"text\":\"计划\"},"
                        + "{\"for\":{\"stage\":\"answer\"},\"text\":\"答案\"}]}", objectMapper);
        return new AcceptanceFixtureModelRegistry.ScriptedStage(
                new ScriptedChatModel("run-1", "fx-1", "scenario-a", script,
                        mock(FixtureCallStore.class),
                        () -> new ScriptedChatModel.FixtureScript("fx-1", "scenario-a", script)),
                "fx-1", "scenario-a");
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("7");
        run.setExt("{\"context_json\":\"{\\\"acceptanceFixtureId\\\":\\\"fx-1\\\"}\"}");
        return run;
    }
}
