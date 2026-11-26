package world.willfrog.agent.platform.prompt;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 终态结果中的操作建议读取原根指令版本，事实字段由实际产生者明确提供。 */
@Component
public final class TerminalResultPromptTexts {
    private final AgentPromptTextReader reader;

    public TerminalResultPromptTexts(AgentPromptTextReader reader) {
        this.reader = reader;
    }

    public PromptText instruction(String fragment) {
        if (reader == null) throw missing("终态建议正文读取组件未配置");
        var body = reader.read(reader.current(), Map.of(fragment, fragment)).get(fragment);
        if (body == null || body.type() != PromptBodyType.FIXED) {
            throw missing("终态建议需要可读取的固定正文版本");
        }
        PromptText text = reader.render(body, Map.of(), Map.of());
        if (text == null || text.text().isBlank() || text.sources().isEmpty()) {
            throw missing("终态建议缺少实际正文或指令版本");
        }
        return text;
    }

    public static List<PromptVersionSource> sources(String message, String action, PromptText messagePrompt, PromptText actionPrompt) {
        if (messagePrompt == null || actionPrompt == null || actionPrompt.sources().isEmpty()
                || !messagePrompt.text().equals(message) || !actionPrompt.text().equals(action)) {
            throw missing("终态说明或操作建议未保留与正文一致的来源");
        }
        List<PromptVersionSource> result = new ArrayList<>(messagePrompt.sources());
        result.addAll(actionPrompt.sources());
        return result.stream().distinct().toList();
    }

    public static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }
}
