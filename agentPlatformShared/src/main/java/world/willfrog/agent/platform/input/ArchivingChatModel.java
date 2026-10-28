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

/** 每次模型请求产生逻辑编号，底层SDK自动HTTP重试继续复用该编号。 */
public final class ArchivingChatModel implements ChatModel {
    private final ChatModel delegate;

    public ArchivingChatModel(ChatModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        try (ModelInputScope ignored = ModelInputScope.open(
                ModelInputScope.currentRequired().nextLogicalCall())) {
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
