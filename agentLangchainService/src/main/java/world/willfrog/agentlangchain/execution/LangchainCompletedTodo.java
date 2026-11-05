package world.willfrog.agentlangchain.execution;

import lombok.Builder;
import lombok.Data;
import dev.langchain4j.data.message.UserMessage;
import java.util.Map;
import world.willfrog.agent.platform.input.PromptMessage;
import world.willfrog.agent.platform.input.PromptMessageCodec;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

@Data
@Builder
public class LangchainCompletedTodo {
    private String todoId;
    private int sequence;
    private String description;
    /** LLM-facing compact output (summary + rawRef template when compaction applied). */
    private String modelOutput;
    /** Legacy/raw output kept for observability and backward compatibility. */
    private String output;
    private String summary;
    /** 输出与摘要共用的来源；旧快照缺少来源时保持未知。 */
    private String outputSourceJson;

    public static String sourceJson(PromptText output) {
        if (output == null) throw missingSource();
        return (String) PromptMessageCodec.sourceAttributes(output.sources().isEmpty()
                ? PromptMessage.Origin.RUNTIME_DATA : PromptMessage.Origin.APPLICATION_INSTRUCTION,
                output.sources()).get(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY);
    }

    public static String modelOutputSourceJson() {
        return sourceJson(PromptText.runtimeData(""));
    }

    /** 摘要截取自同项输出，沿用该输出的来源。 */
    public PromptText attributedText(String text) {
        if (outputSourceJson == null || outputSourceJson.isBlank()) throw missingSource();
        PromptMessage message = PromptMessageCodec.fromSourceAttributes(UserMessage.from("completed-todo"),
                Map.of(PromptMessageCodec.SOURCE_ATTRIBUTE_KEY, outputSourceJson));
        if (message.origin() == PromptMessage.Origin.UNKNOWN) throw missingSource();
        return new PromptText(text == null ? "" : text, message.sources());
    }

    private static ModelSendBlockedException missingSource() {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                "已完成节点的输出缺少明确来源");
    }

    public String displayOutput() {
        if (modelOutput != null && !modelOutput.isBlank()) {
            return modelOutput;
        }
        return output == null ? "" : output;
    }
}
