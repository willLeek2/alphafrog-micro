package world.willfrog.agent.platform.input;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSource;
import world.willfrog.alphafrogmicro.common.racing.input.ModelInputSources;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 消息与实际指令来源一起移动；未迁移的消息显式保持未知，不根据正文猜测。 */
public record PromptMessage(ChatMessage message, Origin origin, List<PromptVersionSource> sources) {
    public enum Origin { APPLICATION_INSTRUCTION, RUNTIME_DATA, UNKNOWN }

    public PromptMessage {
        if (message == null || origin == null || sources == null || sources.stream().anyMatch(s -> s == null)) {
            throw blocked("消息或来源结构不完整");
        }
        sources = List.copyOf(sources);
        if (origin == Origin.APPLICATION_INSTRUCTION ? sources.isEmpty() : !sources.isEmpty()) {
            throw blocked("消息归类与指令版本来源不一致");
        }
    }

    public static PromptMessage application(ChatMessage message, List<PromptVersionSource> sources) {
        return new PromptMessage(message, Origin.APPLICATION_INSTRUCTION, sources);
    }

    public static PromptMessage system(PromptText prompt) {
        requirePrompt(prompt);
        return application(SystemMessage.from(prompt.text()), prompt.sources());
    }

    public static PromptMessage user(PromptText prompt) {
        requirePrompt(prompt);
        return application(UserMessage.from(prompt.text()), prompt.sources());
    }

    public static PromptMessage toolResult(String callId, String toolName, PromptText prompt) {
        requirePrompt(prompt);
        return application(ToolExecutionResultMessage.from(callId, toolName, prompt.text()), prompt.sources());
    }

    public static PromptMessage runtimeData(ChatMessage message) {
        return new PromptMessage(message, Origin.RUNTIME_DATA, List.of());
    }

    public static PromptMessage unknown(ChatMessage message) {
        return new PromptMessage(message, Origin.UNKNOWN, List.of());
    }

    /** 在最后一次消息增删之后计算位置，避免沿用插入预算消息之前的序号。 */
    public static ModelInputSources toModelInputSources(List<PromptMessage> messages) {
        return toModelInputSources(messages, "/messages");
    }

    public static ModelInputSources toModelInputSources(List<PromptMessage> messages, String messageArrayPointer) {
        if (messages == null || !("/messages".equals(messageArrayPointer) || "/input/messages".equals(messageArrayPointer))) {
            throw blocked("消息来源缺少实际请求位置");
        }
        List<ModelInputSource> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            PromptMessage entry = messages.get(index);
            if (entry == null || entry.origin() == Origin.UNKNOWN) {
                throw blocked("实际消息尚未登记来源或运行数据归类");
            }
            ModelInputSource.Origin origin = entry.origin() == Origin.APPLICATION_INSTRUCTION
                    ? ModelInputSource.Origin.APPLICATION_INSTRUCTION : ModelInputSource.Origin.RUNTIME_DATA;
            result.add(new ModelInputSource(messageArrayPointer + "/" + index, origin, entry.sources()));
        }
        return new ModelInputSources(result);
    }

    private static void requirePrompt(PromptText prompt) {
        if (prompt == null || prompt.sources().isEmpty()) throw blocked("应用消息缺少真实指令版本");
    }

    static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }

    @Override
    public String toString() {
        return "PromptMessage[type=" + message.type() + ", origin=" + origin + ", sourceCount=" + sources.size() + "]";
    }
}
