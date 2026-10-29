package world.willfrog.agentlangchain.tooljob;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.Map;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.input.PromptMessageCodec;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

/** 正文和来源来自同一份持久记录；旧记录没有来源时保持未知并停止。 */
public final class BackgroundPromptResults {
    private BackgroundPromptResults() { }
    public static String encode(PromptText text) {
        return (String) PromptMessageCodec.sourceAttributes(text.sources().isEmpty()
                ? PromptMessage.Origin.RUNTIME_DATA : PromptMessage.Origin.APPLICATION_INSTRUCTION,
                text.sources()).get(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY);
    }
    public static PromptText decode(String text, String sourcesJson) {
        if (text == null || text.isBlank() || sourcesJson == null || sourcesJson.isBlank()) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, "background-terminal-result");
        }
        PromptMessage message = PromptMessageCodec.fromSourceAttributes(
                ToolExecutionResultMessage.from("persisted-call", "persisted-tool", text),
                Map.of(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY, sourcesJson));
        if (message.origin() == PromptMessage.Origin.UNKNOWN) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, "background-terminal-result");
        }
        return new PromptText(text, message.sources());
    }
}
