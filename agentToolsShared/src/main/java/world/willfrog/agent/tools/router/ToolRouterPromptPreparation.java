package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.failure.ToolFailureCategory;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.agent.tools.prompt.ProducerPromptTexts;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

/** 路由器产生的建议来自原根版本，预检代码和限流数字继续使用本次事实。 */
@Component
public final class ToolRouterPromptPreparation {
    private final AgentPromptTextReader reader;
    private final ObjectMapper json;

    public ToolRouterPromptPreparation(AgentPromptTextReader reader, ObjectMapper json) {
        this.reader = reader;
        this.json = json;
    }

    public PromptText weightLimit(String toolName, int effectiveWeight) {
        return encode(Map.of("ok", false, "tool", toolName == null ? "" : toolName, "data", Map.of(),
                "error", Map.of("code", "TOOL_WEIGHT_LIMIT_EXCEEDED", "message", policy("toolWeightLimitAdvice"),
                        "details", Map.of("effectiveWeight", Math.max(0, effectiveWeight), "tool", toolName == null ? "" : toolName),
                        "category", ToolFailureCategory.RATE_LIMITED_OR_CAPACITY.name())));
    }

    public PromptText precheck(String toolName, PythonStaticPrecheckService.Result result) {
        Object original = result.getReport() == null ? null : result.getReport().get("issues");
        if (!(original instanceof List<?> facts) || result.getIssueCodes() == null
                || facts.size() != result.getIssueCodes().size()) throw missing("预检缺少明确的问题分类");
        List<PromptText> issues = new ArrayList<>();
        for (int i = 0; i < facts.size(); i++) {
            String code = result.getIssueCodes().get(i);
            PromptText issue = switch (code) {
                case "DATASET_PATH_FORBIDDEN" -> policy("pythonPrecheckDatasetPathAdvice");
                case "DATASET_VARIABLE_UNDEFINED" -> policy("pythonPrecheckVariableAdvice");
                case "CODE_EMPTY", "DATASET_ID_FORMAT", "MANIFEST_ID_FORMAT" -> {
                    if (!(facts.get(i) instanceof String text)) throw missing("预检协议事实无法读取");
                    yield PromptText.runtimeData(text);
                }
                default -> throw missing("预检问题尚未定义正文来源");
            };
            issues.add(issue);
        }
        List<PromptText> message = new ArrayList<>();
        for (PromptText issue : issues) {
            if (!message.isEmpty()) message.add(PromptText.runtimeData("; "));
            message.add(issue);
        }
        Map<String, Object> report = new LinkedHashMap<>(result.getReport());
        report.put("issues", issues);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pre_validation_failed", true);
        details.put("report", report);
        return encode(Map.of("ok", false, "tool", toolName, "data", Map.of(),
                "error", Map.of("code", result.getErrorCode(), "message", PromptText.concat(message),
                        "details", details,
                        "category", ToolFailureCategory.INVALID_ARGUMENT.name())));
    }

    private PromptText policy(String fragment) {
        var root = reader.current();
        return reader.render(reader.read(root, Map.of(fragment, fragment)).get(fragment), Map.of(), Map.of());
    }

    private PromptText encode(Object value) {
        try {
            return ProducerPromptTexts.json(json, value);
        } catch (Exception failure) {
            ModelSendBlocks.rethrowIfBlocked(failure);
            throw missing("路由结果及建议无法编码");
        }
    }

    private static ModelSendBlockedException missing(String reason) {
        return new ModelSendBlockedException(ModelSendBlocked.PROMPT_SOURCE_MISSING, reason);
    }
}
