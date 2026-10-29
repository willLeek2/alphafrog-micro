package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.experiment.ExperimentRunContextFactory;
import world.willfrog.agent.platform.input.AgentModelInputs;
import world.willfrog.agent.platform.input.ArchivingChatModel;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.platform.prompt.DefaultPromptVariantSelector;
import world.willfrog.agent.platform.prompt.LightweightPromptPreparation;
import world.willfrog.alphafrogmicro.common.racing.RacingInputClient;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputAttempt;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputStore;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVariableDeclaration;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.RootIdentity;
import world.willfrog.racing.idl.TargetIdentity;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 既有业务测试的版本读取、根上下文和输入存储边界替身。
 * 使用真实版本校验、渲染、输入作用域和保存确认；模型边界发送简化消息JSON，未连接Racing、数据库或HTTP。
 */
final class VersionedModelTestFixture {
    private final Map<String, PromptVersionBody> versions = new LinkedHashMap<>();
    final List<ModelInputAttempt> saved = new ArrayList<>();
    boolean rejectSave;
    private final AgentPromptTextReader reader;
    private final AgentModelInputs inputs;

    VersionedModelTestFixture() {
        var root = mock(AgentPromptTextReader.RootPrompts.class, withSettings().mockMaker("mock-maker-inline"));
        reader = mock(AgentPromptTextReader.class, withSettings().mockMaker("mock-maker-inline")
                .defaultAnswer(call -> {
                    if (call.getMethod().getName().equals("current")) return root;
                    if (call.getMethod().getName().equals("read")) {
                        Map<String, String> purposes = call.getArgument(1);
                        Map<String, PromptVersionBody> result = new LinkedHashMap<>();
                        purposes.forEach((fragment, purpose) -> {
                            PromptVersionBody body = versions.get(fragment);
                            if (body == null) throw new ModelSendBlockedException(
                                    ModelSendBlocked.PROMPT_UNREADABLE, fragment);
                            result.put(fragment, body.requirePurpose(purpose));
                        });
                        return result;
                    }
                    return CALLS_REAL_METHODS.answer(call);
                }));
        ExperimentRunContextFactory contexts = mock(ExperimentRunContextFactory.class,
                withSettings().mockMaker("mock-maker-inline").defaultAnswer(call -> {
            if (call.getMethod().getName().equals("current")) {
                return RuntimeCallContext.newBuilder().setCurrentRunId(call.getArgument(0))
                        .setRoot(RootIdentity.newBuilder().setOwnerNamespace("alphafrog").setOwnerKind("agent_run")
                                .setOwnerScopeInstanceId("fixture-alpha-scope").setOwnerRouteTag("fixture-alpha-route")
                                .setRootId("fixture-root"))
                        .setTarget(TargetIdentity.newBuilder().setEnvironment("test")
                                .setTargetScopeInstanceId("shared-fixture-scope").setTargetRouteTag("fixture-route")
                                .setTargetDeploymentId("fixture-deployment").setExpectedTargetGenerationId("fixture-generation"))
                        .setOperationId(call.getArgument(1)).build();
            }
            return RETURNS_DEFAULTS.answer(call);
        }));
        RacingInputClient client = mock(RacingInputClient.class, withSettings().mockMaker("mock-maker-inline")
                .defaultAnswer(call -> {
                    if (call.getMethod().getName().equals("boundTo")) return (ModelInputStore) attempt -> {
                        saved.add(attempt);
                        if (rejectSave) throw new IllegalStateException("fixture store rejected input");
                        return new ModelInputStore.Confirmation("fixture-" + saved.size(),
                                attempt.attemptId(), attempt.sha256(), attempt.utf8Length());
                    };
                    return RETURNS_DEFAULTS.answer(call);
                }));
        inputs = new AgentModelInputs(contexts, client);
    }

    PromptVersionBody version(String fragment, String text, String... variables) {
        return new PromptVersionBody(new PromptVersionSource("test", "shared-fixture-scope", fragment, 7,
                fragment, "fixture-selection", null, null, "fixture-key", null, fragment, null),
                variables.length == 0 ? PromptBodyType.FIXED : PromptBodyType.TEMPLATE, text,
                java.util.Arrays.stream(variables).map(name ->
                        new PromptVariableDeclaration(name, true, true, null)).toList());
    }

    void put(String fragment, String body, String... variables) {
        versions.put(fragment, version(fragment, body, variables));
    }

    void putImported(String fragment) {
        String resource = "/prompt-import/lightweight-models/" + fragment + ".json";
        try (var stream = getClass().getResourceAsStream(resource)) {
            if (stream == null) throw new AssertionError("missing explicit fixture version " + resource);
            var body = new ObjectMapper().readTree(stream);
            List<PromptVariableDeclaration> variables = new ArrayList<>();
            body.path("variables").forEach(variable -> variables.add(new PromptVariableDeclaration(
                    variable.path("name").asText(), variable.path("required").asBoolean(),
                    variable.path("allowEmpty").asBoolean(), variable.hasNonNull("defaultValue")
                            ? variable.path("defaultValue").asText() : null)));
            versions.put(fragment, new PromptVersionBody(version(fragment, "").source(),
                    PromptBodyType.valueOf(body.path("bodyType").asText()), body.path("body").asText(), variables));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    AgentPromptService promptService() {
        return promptService(new AgentLlmProperties(), mock(AgentLlmLocalConfigLoader.class));
    }

    AgentPromptService promptService(AgentLlmProperties properties, AgentLlmLocalConfigLoader loader) {
        return new AgentPromptService(properties, loader, new DefaultPromptVariantSelector(), reader);
    }

    LightweightPromptPreparation preparation() {
        return new LightweightPromptPreparation(reader);
    }

    void inject(Object service) {
        ReflectionTestUtils.setField(service, "modelInputs", inputs);
        if (service instanceof PythonRiskReviewService || service instanceof SearchEvidenceJudgeService
                || service instanceof AgentContextCompressor) {
            ReflectionTestUtils.setField(service, "promptPreparation", preparation());
        }
        if (AgentContext.getRunId() == null) AgentContext.setRunId("fixture-run");
    }

    /** 保留业务测试的模型响应替身，同时真实校验本次来源和保存确认先于模型边界调用。 */
    ChatModel archived(ChatModel responseModel, String endpoint) {
        ChatModel boundary = new ChatModel() {
            @Override public ModelProvider provider() { return ModelProvider.OPEN_AI; }
            @Override public ChatResponse doChat(ChatRequest request) {
                List<Map<String, String>> messages = new ArrayList<>();
                for (ChatMessage message : request.messages()) {
                    if (message instanceof SystemMessage system) {
                        messages.add(Map.of("role", "system", "content", system.text()));
                    } else if (message instanceof UserMessage user) {
                        messages.add(Map.of("role", "user", "content", user.singleText()));
                    } else throw new AssertionError("fixture only accepts the existing lightweight text messages");
                }
                try {
                    String json = new ObjectMapper().writeValueAsString(Map.of("messages", messages));
                    ModelInputScope.currentRequired().beforeSend(json);
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new AssertionError(failure);
                }
                return responseModel.chat(request.messages());
            }
        };
        return new ArchivingChatModel(boundary, endpoint);
    }
}
