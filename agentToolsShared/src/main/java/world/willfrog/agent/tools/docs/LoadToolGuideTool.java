package world.willfrog.agent.tools.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

@Component
public class LoadToolGuideTool {
    private static final Set<String> VALID_TOPICS = Set.of(
            "python_sandbox", "dataset_manifest", "advanced_market_data", "execute_python_tips", "finance_method_knowledge");
    private final ObjectMapper objectMapper;
    @Autowired
    private DocumentToolPromptPreparation promptPreparation;

    public LoadToolGuideTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool
    public String loadToolGuide(@P(value = "指南主题，必填：python_sandbox|dataset_manifest|advanced_market_data|execute_python_tips|finance_method_knowledge", required = true) String topic) {
        return loadToolGuidePrompt(topic).text();
    }

    public PromptText loadToolGuidePrompt(String topic) {
        String normalized = topic == null ? "" : topic.trim();
        if (normalized.isBlank() || !VALID_TOPICS.contains(normalized)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "INVALID_TOPIC");
            error.put("message", "topic must be one of " + VALID_TOPICS);
            error.put("details", Map.of("topic", normalized));
            ToolFailureCategory.putCategoryIfResolved(error, "INVALID_TOPIC", null);
            return DocumentToolPromptPreparation.envelope(objectMapper,
                    Map.of("ok", false, "tool", "loadToolGuide", "data", Map.of(), "error", error), List.of());
        }
        if (promptPreparation == null) {
            throw DocumentToolPromptPreparation.missing("工具指南缺少原根正文读取组件");
        }
        PromptText guide = promptPreparation.guide(normalized);
        var source = guide.sources().get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", normalized);
        data.put("content", guide.text());
        data.put("source_path", "racing:" + source.scopeInstanceId() + "/" + source.promptId() + "@" + source.version());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", "loadToolGuide");
        payload.put("data", data);
        payload.put("error", null);
        return DocumentToolPromptPreparation.envelope(objectMapper, payload, guide.sources());
    }
}
