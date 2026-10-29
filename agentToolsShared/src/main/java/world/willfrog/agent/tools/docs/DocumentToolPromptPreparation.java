package world.willfrog.agent.tools.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 指南及文档工具的应用建议读取原根版本；业务数据与结构字段由产生端明确提供。 */
@Component
public final class DocumentToolPromptPreparation {
    private final AgentPromptTextReader reader;

    public DocumentToolPromptPreparation(AgentPromptTextReader reader) {
        this.reader = reader;
    }

    public PromptText guide(String topic) {
        String fragment = "tool.guide." + topic;
        var body = reader.read(reader.current(), Map.of(fragment, "TOOL_GUIDE")).get(fragment);
        if (body == null || body.type() != PromptBodyType.FIXED) {
            throw missing("工具指南需要完整固定正文版本");
        }
        PromptText text = reader.render(body, Map.of(), Map.of());
        if (text.text().isBlank() || text.sources().isEmpty()) throw missing("工具指南正文或版本来源为空");
        return text;
    }

    public PromptText policy(String fragment, Map<String, String> data) {
        var body = reader.read(reader.current(), Map.of(fragment, fragment)).get(fragment);
        return reader.render(body, data, Map.of());
    }

    /** 只为实际进入响应的应用文字附带来源，不从JSON正文猜测来源。 */
    public static PromptText envelope(ObjectMapper json, Object payload, List<PromptVersionSource> sources) {
        try {
            return new PromptText(json.writeValueAsString(payload), sources);
        } catch (Exception failure) {
            throw missing("工具结果无法与指令来源一起编码");
        }
    }

    public static PromptText failure(ObjectMapper json, String tool, String code, PromptText message,
                                      Map<String, Object> details) {
        if (message == null) throw missing("工具失败正文缺少来源载体");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", Map.of());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code == null ? "" : code);
        error.put("message", message.text());
        error.put("details", details == null ? Map.of() : details);
        ToolFailureCategory.putCategoryIfResolved(error, code, null);
        payload.put("error", error);
        return envelope(json, payload, message.sources());
    }

    public static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }
}
