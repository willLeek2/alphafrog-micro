package world.willfrog.agentlangchain.support;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.experiment.ExperimentRunContextFactory;
import world.willfrog.agent.platform.input.AgentModelInputs;
import world.willfrog.agent.platform.input.ArchivingChatModel;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.platform.prompt.DefaultPromptVariantSelector;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.prompt.PromptSelectionCoordinator;
import world.willfrog.agent.platform.service.PromptBootstrapExporter;
import world.willfrog.agentlangchain.execution.ToolPromptCatalog;
import world.willfrog.alphafrogmicro.common.racing.RacingInputClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptClient;
import world.willfrog.alphafrogmicro.common.racing.RacingPromptSetReader;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputStore;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVariableDeclaration;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionBody;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptSetVersionSource;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptSelection;
import world.willfrog.racing.idl.PromptSelectionOrigin;
import world.willfrog.racing.idl.PromptSetVersionRef;
import world.willfrog.racing.idl.PromptTargetRef;
import world.willfrog.racing.idl.RootIdentity;
import world.willfrog.racing.idl.RuntimeCallContext;
import world.willfrog.racing.idl.TargetIdentity;
import world.willfrog.racing.idl.ValueSource;
import world.willfrog.racing.idl.ValueSourceKind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
import world.willfrog.agent.platform.service.AgentPromptService;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.planning.LangchainAiPlanner;
import world.willfrog.agentlangchain.planning.LangchainPlanningStructuredOutputSettings;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class LangchainTestFixtures {

    private LangchainTestFixtures() {
    }

    public static AgentLlmProperties llmProperties() {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Planning planning = new AgentLlmProperties.Planning();
        AgentLlmProperties.StructuredOutput structuredOutput = new AgentLlmProperties.StructuredOutput();
        planning.setStructuredOutput(structuredOutput);
        runtime.setPlanning(planning);
        properties.setRuntime(runtime);
        // Prompt 正文统一从 agentPlatformShared classpath 权威目录加载；测试夹具不再造第二份正文。
        properties.setPrompts(new AgentLlmProperties.Prompts());
        return properties;
    }

    /** 仅供显式验证事故降级路径的测试配置。 */
    public static AgentLlmProperties legacySingleStageLlmProperties() {
        AgentLlmProperties properties = llmProperties();
        properties.getRuntime().getPlanning().getStructuredOutput().setStrategyStageEnabled(false);
        return properties;
    }

    public static AgentPromptService promptService() {
        return new AgentPromptService(llmProperties(), new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainPlanningStructuredOutputSettings structuredOutputSettings() {
        return new LangchainPlanningStructuredOutputSettings(
                llmProperties(),
                new AgentLlmLocalConfigLoader(new ObjectMapper()));
    }

    public static LangchainAiPlanner planner() {
        return new LangchainAiPlanner(promptService(), structuredOutputSettings(), JsonMapper.builder().build());
    }

    /** 仅供带 legacy/fallback 语义的用例使用，不能作为生产规划回归夹具。 */
    public static LangchainAiPlanner legacySingleStagePlanner() {
        AgentLlmProperties properties = legacySingleStageLlmProperties();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(objectMapper);
        return new LangchainAiPlanner(
                new AgentPromptService(properties, loader),
                new LangchainPlanningStructuredOutputSettings(properties, loader),
                objectMapper);
    }

    public static LangchainTodoNodeExecutor todoNodeExecutor() {
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = new ObjectProvider<>() {
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject() {
                return null;
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject(Object... args) {
                return null;
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfAvailable() {
                return null;
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfUnique() {
                return null;
            }
        };
        return new LangchainTodoNodeExecutor(promptService(), provider, noopExecutionGuard(), noopBudgetService(), noopStateStore(), noopFinanceResultComposer());
    }

    public static LangchainTodoNodeExecutor todoNodeExecutor(Optional<dev.langchain4j.service.tool.ToolProvider> toolProvider) {
        ObjectProvider<dev.langchain4j.service.tool.ToolProvider> provider = new ObjectProvider<>() {
            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject() {
                return toolProvider.orElse(null);
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getObject(Object... args) {
                return toolProvider.orElse(null);
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfAvailable() {
                return toolProvider.orElse(null);
            }

            @Override
            public dev.langchain4j.service.tool.ToolProvider getIfUnique() {
                return toolProvider.orElse(null);
            }
        };
        return new LangchainTodoNodeExecutor(promptService(), provider, noopExecutionGuard(), noopBudgetService(), noopStateStore(), noopFinanceResultComposer());
    }

    /**
     * ccmax #59: noop budget service. effectiveConfig() returns all-zero (no limit) so shouldRecover() sees budgetHit=false.
     */
    public static world.willfrog.agent.platform.service.AgentRunBudgetService noopBudgetService() {
        world.willfrog.agent.platform.service.AgentRunBudgetService budget = mock(world.willfrog.agent.platform.service.AgentRunBudgetService.class);
        world.willfrog.agent.platform.service.AgentRunBudgetService.EffectiveRunBudget empty = new world.willfrog.agent.platform.service.AgentRunBudgetService.EffectiveRunBudget(0L, 0, 0, 0, 0);
        when(budget.effectiveConfig()).thenReturn(empty);
        return budget;
    }

    /**
     * 默认 no-op 金融结果块组合器：原样返回模型文本（不写事件、不查记录）。
     * 需要真实块行为的测试应自行构造 composer 并直接 new executor。
     */
    public static world.willfrog.agentlangchain.finance.FinanceResultComposer noopFinanceResultComposer() {
        world.willfrog.agentlangchain.finance.FinanceResultComposer composer =
                mock(world.willfrog.agentlangchain.finance.FinanceResultComposer.class);
        when(composer.appendFinanceResultBlock(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        return composer;
    }

    /**
     * ccmax #59: noop state store. loadObservability() returns empty Optional so readBudgetStatus() fail-soft 为未命中。
     */
    public static world.willfrog.agent.platform.service.AgentRunStateStore noopStateStore() {
        world.willfrog.agent.platform.service.AgentRunStateStore store = mock(world.willfrog.agent.platform.service.AgentRunStateStore.class);
        when(store.loadObservability(any())).thenReturn(java.util.Optional.empty());
        return store;
    }

    /** 现有工作流用例的真实正文组装；只替换持久根、远程版本读取和输入存储边界。 */
    public static LangchainTodoNodeExecutor versionedTodoNodeExecutor() {
        var fixture = new VersionedNodeInputs();
        ObjectProvider<ToolProvider> provider = mock(ObjectProvider.class);
        var executor = new LangchainTodoNodeExecutor(fixture.prompts, provider, noopExecutionGuard(),
                noopBudgetService(), noopStateStore(), noopFinanceResultComposer());
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "promptReader", fixture.reader);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "modelInputs", fixture.inputs);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "toolPrompts",
                new ToolPromptCatalog(fixture.reader));
        return executor;
    }

    public static LangchainTodoNodeExecutor versionedTodoNodeExecutor(Optional<ToolProvider> tools) {
        return versionedNode(todoNodeExecutor(tools));
    }

    public static LangchainTodoNodeExecutor versionedNode(LangchainTodoNodeExecutor executor) {
        var fixture = new VersionedNodeInputs();
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "promptService", fixture.prompts);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "promptReader", fixture.reader);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "modelInputs", fixture.inputs);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "toolPrompts", new ToolPromptCatalog(fixture.reader));
        return executor;
    }

    public static LangchainAiPlanner versionedPlanner() { return versionedPlanner(llmProperties()); }

    public static LangchainAiPlanner versionedPlanner(AgentLlmProperties properties) {
        var fixture = new VersionedNodeInputs();
        var mapper = new ObjectMapper();
        var loader = new AgentLlmLocalConfigLoader(mapper);
        var planner = new LangchainAiPlanner(new AgentPromptService(properties, loader,
                new DefaultPromptVariantSelector(), fixture.reader),
                new LangchainPlanningStructuredOutputSettings(properties, loader), mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(planner, "promptPreparation",
                new world.willfrog.agentlangchain.planning.PlanningPromptPreparation(fixture.reader, fixture.inputs));
        return planner;
    }

    /** 调用方仅用于本测试自行产生的工具事实；历史结果、真实工具和指令正文不能用此替身补来源。 */
    public static ToolProvider runtimeDataTools(ToolProvider scriptedFacts) {
        return request -> {
            var provided = scriptedFacts.provideTools(request);
            var executors = new java.util.LinkedHashMap<dev.langchain4j.agent.tool.ToolSpecification,
                    dev.langchain4j.service.tool.ToolExecutor>();
            provided.tools().forEach((spec, delegate) -> executors.put(spec, new dev.langchain4j.service.tool.ToolExecutor() {
                @Override public String execute(dev.langchain4j.agent.tool.ToolExecutionRequest call, Object memoryId) {
                    return delegate.execute(call, memoryId);
                }
                @Override public dev.langchain4j.service.tool.ToolExecutionResult executeWithContext(
                        dev.langchain4j.agent.tool.ToolExecutionRequest call, dev.langchain4j.invocation.InvocationContext context) {
                    return dev.langchain4j.service.tool.ToolExecutionResult.builder()
                            .resultText(delegate.execute(call, context.chatMemoryId()))
                            .attributes(world.willfrog.agent.platform.input.PromptMessageCodec.sourceAttributes(
                                    world.willfrog.agent.platform.input.PromptMessage.Origin.RUNTIME_DATA, java.util.List.of())).build();
                }
            }));
            return new dev.langchain4j.service.tool.ToolProviderResult(executors);
        };
    }

    /** 注入测试自己声明的固定预算提示版本，保留既有逐字节断言。 */
    public static world.willfrog.alphafrogmicro.common.racing.prompt.PromptText versionedTestHint(String text) {
        return world.willfrog.alphafrogmicro.common.racing.prompt.PromptText.instruction(text,
                VersionedNodeInputs.source("fixtureLastMileHint", "BUDGET_HINT"));
    }

    public static final String NODE_ENDPOINT = "fixture-node-endpoint";

    /** 使用简化文本消息JSON执行真实保存确认，再调用原有队列模型；不访问HTTP。 */
    public static ChatModel archivedNodeModel(ChatModel delegate) {
        ChatModel boundary = new ChatModel() {
            @Override public ModelProvider provider() {
                return ModelProvider.OPEN_AI;
            }
            @Override public dev.langchain4j.model.chat.request.ChatRequestParameters defaultRequestParameters() {
                return dev.langchain4j.model.openai.OpenAiChatRequestParameters.builder().build();
            }
            @Override public ChatResponse doChat(
                    ChatRequest request) {
                var messages = new java.util.ArrayList<java.util.Map<String, Object>>();
                for (var message : request.messages()) {
                    if (message instanceof SystemMessage system) {
                        messages.add(java.util.Map.of("role", "system", "content", system.text()));
                    } else if (message instanceof UserMessage user) {
                        messages.add(java.util.Map.of("role", "user", "content", user.singleText()));
                    } else if (message instanceof dev.langchain4j.data.message.AiMessage ai) {
                        var value = new java.util.LinkedHashMap<String, Object>();
                        value.put("role", "assistant");
                        if (ai.text() != null) value.put("content", ai.text());
                        if (ai.hasToolExecutionRequests()) value.put("tool_calls", ai.toolExecutionRequests().stream().map(tool ->
                                java.util.Map.of("id", tool.id() == null ? "" : tool.id(), "type", "function", "function",
                                        java.util.Map.of("name", tool.name(), "arguments", tool.arguments()))).toList());
                        messages.add(value);
                    } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tool) {
                        messages.add(java.util.Map.of("role", "tool", "tool_call_id", tool.id(),
                                "name", tool.toolName(), "content", tool.text()));
                    } else throw new AssertionError("未支持的既有模型消息 " + message.getClass());
                }
                try {
                    var body = new java.util.LinkedHashMap<String, Object>();
                    body.put("messages", messages);
                    if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
                        body.put("tools", request.toolSpecifications().stream().map(tool -> {
                            var function = new java.util.LinkedHashMap<String, Object>();
                            function.put("name", tool.name());
                            if (tool.description() != null) function.put("description", tool.description());
                            if (tool.parameters() != null) function.put("parameters",
                                    dev.langchain4j.internal.JsonSchemaElementJsonUtils.toMap(tool.parameters()));
                            return java.util.Map.of("type", "function", "function", function);
                        }).toList());
                    }
                    if (request.parameters() instanceof dev.langchain4j.model.openai.OpenAiChatRequestParameters openAi
                            && openAi.customParameters() != null && openAi.customParameters().containsKey("response_format")) {
                        body.put("response_format", openAi.customParameters().get("response_format"));
                    }
                    var json = new ObjectMapper().writeValueAsString(body);
                    ModelInputScope.currentRequired().beforeSend(json);
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new AssertionError(failure);
                }
                return delegate.chat(request);
            }
        };
        return new ArchivingChatModel(boundary, NODE_ENDPOINT);
    }

    public static final class VersionedNodeInputs {
        private final AgentPromptTextReader reader;
        private final AgentModelInputs inputs;
        private final AgentPromptService prompts;

        public VersionedNodeInputs() {
            var json = new ObjectMapper();
            var scope = PromptScope.newBuilder()
                    .setEnvironment("test").setScopeInstanceId("fixture-racing").build();
            var root = RootIdentity.newBuilder().setOwnerNamespace("alphafrog")
                    .setOwnerKind("agent_run").setOwnerScopeInstanceId("fixture-alpha").setRootId("fixture-root").build();
            var target = TargetIdentity.newBuilder().setEnvironment("test")
                    .setTargetScopeInstanceId("fixture-racing").setTargetDeploymentId("fixture-racing")
                    .setExpectedTargetGenerationId("fixture-generation").build();
            var selection = PromptSelection.newBuilder().setRoot(root).setTarget(target)
                    .setSelectionId("fixture-selection")
                    .setSelectionKey(PromptSelectionCoordinator.SELECTION_KEY)
                    .setSelectedTarget(PromptTargetRef.newBuilder().setPromptSetVersion(
                            PromptSetVersionRef.newBuilder().setScope(scope)
                                    .setPromptSetId("fixture-set").setVersion(1)))
                    .setOrigin(PromptSelectionOrigin.newBuilder().setSourceScope(scope)
                            .setBusinessName(PromptSelectionCoordinator.BUSINESS_NAME)
                            .setDefaultRevision(1).setValueSource(ValueSource.newBuilder()
                                    .setKind(ValueSourceKind.ORDINARY_DEFAULT))).build();
            var date = java.time.LocalDate.of(2026, 10, 29);
            var ext = json.createObjectNode();
            ext.set("prompt_selection", json.valueToTree(
                    PromptRunSelection.fromRacing(selection, date).toExtMap()));
            ext.put(PromptSelectionCoordinator.REFERENCE_DATE_FIELD, date.toString());
            var run = new AgentRun();
            run.setId(root.getRootId());
            run.setExt(ext.toString());
            var runs = mock(AgentRunMapper.class);
            when(runs.findById(root.getRootId())).thenReturn(run);
            var contexts = mock(ExperimentRunContextFactory.class, org.mockito.Mockito.withSettings().mockMaker("mock-maker-inline"));
            when(contexts.current(any(), any())).thenAnswer(call ->
                    RuntimeCallContext.newBuilder().setRoot(root).setTarget(target)
                            .setCurrentRunId(call.getArgument(0)).setOperationId(call.getArgument(1)).build());
            var versions = new java.util.LinkedHashMap<String, PromptVersionBody>();
            for (var draft : PromptBootstrapExporter.exportCurrentProcess()
                    .classpathBaseline().fragments()) {
                versions.put(draft.fragment(), new PromptVersionBody(
                        source(draft.fragment(), draft.purpose()), draft.bodyType(), draft.body(), draft.variables().stream().map(variable ->
                                new PromptVariableDeclaration(
                                        variable.name(), variable.required(), variable.allowEmpty(), variable.defaultValue())).toList()));
            }
            for (String name : java.util.List.of("agentTimeReferenceSystemPrompt", "dynamicContextPrefix",
                    "dagReactToolCapabilitiesSection", "dataFreshnessInstruction")) {
                try (var stream = LangchainTestFixtures.class.getResourceAsStream(
                        "/prompt-import/agent-prompt-service/" + name + ".json")) {
                    if (stream == null) throw new AssertionError("missing explicit fixture import " + name);
                    var body = json.readTree(stream);
                    var variables = new java.util.ArrayList<PromptVariableDeclaration>();
                    body.path("variables").forEach(variable -> variables.add(
                            new PromptVariableDeclaration(
                                    variable.path("name").asText(), variable.path("required").asBoolean(),
                                    variable.path("allowEmpty").asBoolean(), variable.hasNonNull("defaultValue")
                                            ? variable.path("defaultValue").asText() : null)));
                    versions.put(name, new PromptVersionBody(
                            source(name, body.path("purpose").asText()),
                            PromptBodyType.valueOf(body.path("bodyType").asText()),
                            body.path("body").asText(), variables));
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
            // 附加政策及工具参数也按实际导入文件建立本次固定版本，不从旧消息猜测来源。
            for (String group : java.util.List.of("execution", "tool-parameters", "tool-executor", "sandbox-producers", "terminal-results", "router-results", "planning", "background-results")) {
                try {
                    var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
                    for (var resource : resolver.getResources("classpath*:prompt-import/" + group + "/*.json")) {
                        try (var stream = resource.getInputStream()) {
                            var body = json.readTree(stream);
                            String name = body.path("fragment").asText();
                            var variables = new java.util.ArrayList<PromptVariableDeclaration>();
                            body.path("variables").forEach(v -> variables.add(new PromptVariableDeclaration(
                                    v.path("name").asText(), v.path("required").asBoolean(), v.path("allowEmpty").asBoolean(),
                                    v.hasNonNull("defaultValue") ? v.path("defaultValue").asText() : null)));
                            versions.put(name, new PromptVersionBody(source(name, body.path("purpose").asText()),
                                    PromptBodyType.valueOf(body.path("bodyType").asText()), body.path("body").asText(), variables));
                        }
                    }
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            }
            var remote = mock(RacingPromptSetReader.class,
                    org.mockito.Mockito.withSettings().mockMaker("mock-maker-inline"));
            when(remote.readFragments(any(), any(), any())).thenAnswer(call -> {
                if (!selection.equals(call.getArgument(1))) throw new AssertionError("unexpected fixture selection");
                java.util.Map<String, String> purposes = call.getArgument(2);
                var selected = new java.util.LinkedHashMap<String, PromptVersionBody>();
                purposes.forEach((fragment, purpose) -> {
                    var body = versions.get(fragment);
                    if (body == null) throw new AssertionError("missing explicit fixture version " + fragment);
                    selected.put(fragment, body.requirePurpose(purpose));
                });
                return selected;
            });
            var promptClient = mock(RacingPromptClient.class, org.mockito.Mockito.withSettings().mockMaker("mock-maker-inline"));
            var promptSet = world.willfrog.racing.idl.PromptSetVersion.newBuilder();
            versions.keySet().forEach(fragment -> promptSet.addMembers(
                    world.willfrog.racing.idl.PromptSetMember.newBuilder().setFragmentName(fragment)));
            when(promptClient.readSet(any())).thenReturn(promptSet.build());
            reader = new AgentPromptTextReader(contexts, runs, json, promptClient, remote);
            prompts = new AgentPromptService(llmProperties(), new AgentLlmLocalConfigLoader(json),
                    new DefaultPromptVariantSelector(), reader);
            var storage = mock(RacingInputClient.class, org.mockito.Mockito.withSettings().mockMaker("mock-maker-inline"));
            when(storage.boundTo(any())).thenReturn(attempt ->
                    new ModelInputStore.Confirmation(
                            "fixture-" + attempt.attemptId(), attempt.attemptId(), attempt.sha256(), attempt.utf8Length()));
            inputs = new AgentModelInputs(contexts, storage);
        }

        public AgentPromptService prompts() { return prompts; }
        public AgentPromptTextReader reader() { return reader; }

        public <T> T configureDualPool(T executor) {
            org.springframework.test.util.ReflectionTestUtils.setField(executor, "modelInputs", inputs);
            org.springframework.test.util.ReflectionTestUtils.setField(executor, "promptTexts", reader);
            org.springframework.test.util.ReflectionTestUtils.setField(executor, "toolPrompts", new ToolPromptCatalog(reader));
            return executor;
        }

        private static PromptVersionSource source(String fragment, String purpose) {
            return new PromptVersionSource(
                    "test", "fixture-racing", fragment, 1, purpose, "fixture-selection", null,
                    "{\"kind\":\"ORDINARY_DEFAULT\"}", PromptSelectionCoordinator.SELECTION_KEY,
                    new PromptSetVersionSource("test", "fixture-racing", "fixture-set", 1), fragment, null);
        }
    }

    private static LangchainRunExecutionGuard noopExecutionGuard() {
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        return guard;
    }
}
