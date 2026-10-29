package world.willfrog.agentlangchain.execution;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.input.AgentModelInputs;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.input.PromptMessageCodec;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

/** SDK负责工具循环；每次真正调用模型时才装配消息来源和本线程归档作用域。 */
final class LangchainTodoPromptModel implements ChatModel {
    private final ChatModel delegate;
    private final PromptText system;
    private final PromptText user;
    private final String endpoint;
    private final AgentModelInputs inputs;
    private final ToolPromptCatalog tools;
    private final AgentPromptTextReader reader;
    private final RootTreeCallBudget budget;
    private final boolean includeToolBudget;
    private final Runnable ensureRunnable;
    private final String runId;

    LangchainTodoPromptModel(ChatModel delegate, PromptText system, PromptText user, String endpoint,
                            AgentModelInputs inputs, ToolPromptCatalog tools, AgentPromptTextReader reader,
                            RootTreeCallBudget budget, boolean includeToolBudget, String runId, Runnable ensureRunnable) {
        if (delegate == null || inputs == null || endpoint == null || endpoint.isBlank()) {
            throw missing("节点模型缺少归档模型、端点或输入保存组件");
        }
        // 两条初始消息由本次调用直接提供，不从SDK消息正文匹配或推断来源。
        PromptMessage.system(system);
        PromptMessage.user(user);
        this.delegate = delegate;
        this.system = system;
        this.user = user;
        this.endpoint = endpoint;
        this.inputs = inputs;
        this.tools = tools;
        this.reader = reader;
        this.budget = budget;
        this.includeToolBudget = includeToolBudget;
        this.ensureRunnable = ensureRunnable;
        this.runId = runId;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        ensureRunnable.run();
        List<PromptMessage> messages = messages(request);
        if (includeToolBudget && budget != null) {
            var status = budget.toolCallBudgetStatus(runId);
            if (status != null) {
                if (reader == null) throw missing("工具预算说明缺少正文读取组件");
                messages.add(1, PromptMessage.system(ToolBudgetPromptRenderer.render(reader, status)));
            }
        }
        PromptText hint = AgentContext.getLastMileHintPrompt();
        String legacyHint = AgentContext.getLastMileHint();
        if (legacyHint != null && !legacyHint.isBlank() && hint == null) {
            throw missing("预算最后提示尚未登记正文版本");
        }
        if (hint != null && !hint.text().isBlank()) {
            messages.add(PromptMessage.user(hint));
            AgentContext.clearLastMileHint();
        }
        List<ModelInputSource> sources = new ArrayList<>(PromptMessage.toModelInputSources(messages).parts());
        ChatRequest.Builder actual = request.toBuilder().messages(messages.stream().map(PromptMessage::message).toList());
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            if (tools == null) throw missing("实际工具定义尚未接入正文来源");
            var prepared = tools.prepare(request.toolSpecifications());
            ChatRequestParameters parameters = request.parameters();
            if (parameters == null) {
                actual.toolSpecifications(prepared.specifications());
            } else {
                actual.parameters(parameters.overrideWith(ChatRequestParameters.builder()
                        .toolSpecifications(prepared.specifications()).build()));
            }
            sources.addAll(prepared.sources().parts());
        }
        var parentSpec = AgentContext.getStructuredOutputSpec();
        AgentContext.clearStructuredOutputSpec();
        try (var ignored = inputs.open(endpoint, new ModelInputSources(sources))) {
            return delegate.doChat(actual.build());
        } finally {
            if (parentSpec == null) AgentContext.clearStructuredOutputSpec();
            else AgentContext.setStructuredOutputSpec(parentSpec);
        }
    }

    private List<PromptMessage> messages(ChatRequest request) {
        List<ChatMessage> original = request == null ? null : request.messages();
        if (original == null || original.size() < 2 || !(original.get(0) instanceof SystemMessage)
                || !(original.get(1) instanceof UserMessage)) {
            throw missing("SDK节点会话缺少本次已登记的初始消息");
        }
        SystemMessage actualSystem = (SystemMessage) original.get(0);
        UserMessage actualUser = (UserMessage) original.get(1);
        if (!system.text().equals(actualSystem.text()) || !actualUser.hasSingleText()
                || !user.text().equals(actualUser.singleText())) {
            throw missing("SDK初始消息与本次登记的正文不一致");
        }
        List<PromptMessage> result = new ArrayList<>();
        result.add(PromptMessage.system(system));
        result.add(PromptMessage.user(user));
        for (ChatMessage message : original.subList(2, original.size())) {
            if (message instanceof AiMessage) result.add(PromptMessage.runtimeData(message));
            else if (message instanceof ToolExecutionResultMessage tool) {
                PromptMessage attributed = PromptMessageCodec.fromSourceAttributes(tool, tool.attributes());
                if (attributed.origin() == PromptMessage.Origin.UNKNOWN) {
                    throw missing("工具结果尚未登记来源：" + tool.toolName());
                }
                result.add(attributed);
            } else if (message instanceof UserMessage userMessage) {
                result.add(PromptMessageCodec.fromSourceAttributes(userMessage, userMessage.attributes()));
            } else result.add(PromptMessage.unknown(message));
        }
        return result;
    }

    private static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }

    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
}
