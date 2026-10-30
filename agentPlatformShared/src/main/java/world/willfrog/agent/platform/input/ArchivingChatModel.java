package world.willfrog.agent.platform.input;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputScope;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputInvocation;

/** 每次模型请求产生逻辑编号，底层SDK自动HTTP重试继续复用该编号。 */
public final class ArchivingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final String endpointName;

    public ArchivingChatModel(ChatModel delegate) {
        this(delegate, null);
    }

    /** 实际模型持有解析后的端点，预录调用转真实模型时不沿用预录标记。 */
    public ArchivingChatModel(ChatModel delegate, String endpointName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.endpointName = endpointName;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        ModelInputInvocation current = ModelInputScope.currentRequired();
        ModelInputInvocation invocation = endpointName == null ? current.nextLogicalCall()
                : ModelInputInvocation.start(endpointName, current.sources(), current.store());
        try (ModelInputScope ignored = ModelInputScope.open(
                invocation)) {
            return delegate.doChat(request);
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
