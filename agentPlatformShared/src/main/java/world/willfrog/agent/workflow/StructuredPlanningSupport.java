package world.willfrog.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * planning / sub-agent planning 的结构化输出工具。
 * 
 * <p>ReAct 模式下，Todo Plan 只包含简化的任务描述，
 * 具体工具调用由执行阶段的 LLM 自主决策。</p>
 */
public final class StructuredPlanningSupport {

    public static final String CATEGORY_JSON_PARSE_ERROR = "JSON_PARSE_ERROR";
    public static final String CATEGORY_SCHEMA_VALIDATION_ERROR = "SCHEMA_VALIDATION_ERROR";
    public static final String EXPECTED_TOOLS_DESCRIPTION = "todos.expectedTools";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.-]+))?}");
    private static final Map<String, String> LEGACY_DESCRIPTIONS = Map.of(
            "todos.extractedEntities", "用户明确提到的金融实体、指数、基金或股票名称。",
            "todos.description", "1-3句话描述该Todo要完成的任务",
            "todos.dependsOn", "依赖的todoId列表（DAG模式下可选）",
            "todos.groupKey", "可选：并行分组键",
            "todos.parallelizable", "可选：该节点是否可并行",
            "strategy.mode", "执行模式：LINEAR串行(1段描述)或DAG并行(2-3段描述)",
            "strategy.detail", "LINEAR模式用1个完整自然段，DAG模式用2-3个完整自然段，描述整体思路。严禁展开具体工作内容、代码、参数。");

    private StructuredPlanningSupport() {
    }

    public static JsonNode parseStructuredJson(ObjectMapper objectMapper, String raw) {
        String text = stripFence(raw);
        if (text.isBlank()) {
            throw new StructuredPlanningException(CATEGORY_JSON_PARSE_ERROR, "empty_response");
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new StructuredPlanningException(CATEGORY_JSON_PARSE_ERROR, safeMessage(e));
        }
    }

    public static ValidationResult validateTodoPlan(JsonNode root, int maxTodos) {
        return validateTodoPlan(root, maxTodos, false);
    }

    /** 返回校验采用本次所选说明字典支持的字段，不借用另一版本的约定。 */
    public static ValidationResult validateTodoPlan(JsonNode root, int maxTodos, boolean expectedToolsEnabled) {
        if (root == null || !root.isObject()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_not_object");
        }
        JsonNode analysisNode = root.get("analysis");
        if (analysisNode == null || !analysisNode.isTextual()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_missing_analysis");
        }
        JsonNode itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_missing_items");
        }
        if (itemsNode.isEmpty()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_items_empty");
        }
        if (itemsNode.size() > maxTodos) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_plan_items_exceed_max");
        }

        int index = 0;
        for (JsonNode itemNode : itemsNode) {
            if (!itemNode.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_not_object@" + index);
            }
            if (!isText(itemNode.get("id"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_id@" + index);
            }
            if (!itemNode.has("sequence") || !itemNode.get("sequence").isInt()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_sequence@" + index);
            }
            // ReAct 模式下，只需要 description 字段
            if (!isText(itemNode.get("description"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_missing_description@" + index);
            }
            // 依赖关系是可选的（DAG 模式）
            JsonNode dependsOnNode = itemNode.get("dependsOn");
            if (dependsOnNode != null && !dependsOnNode.isArray()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_depends_on@" + index);
            }
            JsonNode expectedToolsNode = itemNode.get("expectedTools");
            if (expectedToolsNode != null) {
                if (!expectedToolsEnabled) {
                    return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_unexpected_expected_tools@" + index);
                }
                if (!expectedToolsNode.isArray()) {
                    return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_expected_tools@" + index);
                }
                for (JsonNode toolName : expectedToolsNode) {
                    if (!toolName.isTextual()) {
                        return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_expected_tool@" + index);
                    }
                }
            }
            JsonNode groupKeyNode = itemNode.get("groupKey");
            if (groupKeyNode != null && !groupKeyNode.isTextual()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_group_key@" + index);
            }
            JsonNode parallelizableNode = itemNode.get("parallelizable");
            if (parallelizableNode != null && !parallelizableNode.isBoolean()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "todo_item_invalid_parallelizable@" + index);
            }
            index++;
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateSubAgentPlan(JsonNode root, int maxSteps, Set<String> toolWhitelist) {
        if (root == null || !root.isObject()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_not_object");
        }
        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_missing_steps");
        }
        if (stepsNode.isEmpty()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_steps_empty");
        }
        if (stepsNode.size() > maxSteps) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_plan_steps_exceed_max");
        }

        int index = 0;
        for (JsonNode stepNode : stepsNode) {
            if (!stepNode.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_not_object@" + index);
            }
            String tool = stepNode.path("tool").asText("");
            if (tool.isBlank()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_missing_tool@" + index);
            }
            if (toolWhitelist == null || !toolWhitelist.contains(tool)) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_tool_not_allowed@" + index);
            }
            JsonNode args = stepNode.get("args");
            if (args == null || !args.isObject()) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "sub_agent_step_args_not_object@" + index);
            }
            ValidationResult placeholderCheck = validatePlaceholders(args, false);
            if (!placeholderCheck.valid()) {
                return ValidationResult.invalid(placeholderCheck.category(), placeholderCheck.message() + "@" + index);
            }
            index++;
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validatePlaceholders(JsonNode node, boolean todoOnly) {
        if (node == null || node.isNull()) {
            return ValidationResult.ok();
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                ValidationResult result = validatePlaceholders(child, todoOnly);
                if (!result.valid()) {
                    return result;
                }
            }
            return ValidationResult.ok();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                ValidationResult result = validatePlaceholders(child, todoOnly);
                if (!result.valid()) {
                    return result;
                }
            }
            return ValidationResult.ok();
        }
        if (!node.isTextual()) {
            return ValidationResult.ok();
        }
        String value = node.asText();
        if (!value.contains("${")) {
            return ValidationResult.ok();
        }

        Matcher matcher = PLACEHOLDER.matcher(value);
        List<String> refs = new ArrayList<>();
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        if (refs.isEmpty()) {
            return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "placeholder_invalid_format");
        }
        for (String ref : refs) {
            if (todoOnly && !ref.startsWith("todo_")) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "placeholder_must_use_todo_ref");
            }
            if (!todoOnly && !(ref.startsWith("step_") || ref.startsWith("todo_"))) {
                return ValidationResult.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "placeholder_must_use_step_or_todo_ref");
            }
        }
        return ValidationResult.ok();
    }

    /**
     * ReAct 模式下的简化 Todo Plan JSON Schema。
     * 
     * <p>只包含 id、sequence、description 和可选的 dependsOn，
     * 具体工具调用由执行阶段的 LLM 自主决策。</p>
     */
    public static Map<String, Object> todoPlanningJsonSchema() {
        return todoPlanningJsonSchema(LEGACY_DESCRIPTIONS);
    }

    /** 协议结构继续由Java生成，说明只从业务已经核对的版本字典取值。 */
    public static Map<String, Object> todoPlanningJsonSchema(Map<String, String> descriptions) {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("id", Map.of("type", "string"));
        itemProperties.put("sequence", Map.of("type", "integer"));
        itemProperties.put("description", Map.of("type", "string", "description", description(descriptions, "todos.description")));
        itemProperties.put("dependsOn", Map.of("type", "array", "description", description(descriptions, "todos.dependsOn"),
                "items", Map.of("type", "string")));
        itemProperties.put("groupKey", Map.of("type", "string", "description", description(descriptions, "todos.groupKey")));
        itemProperties.put("parallelizable", Map.of("type", "boolean", "description", description(descriptions, "todos.parallelizable")));
        if (descriptions != null && descriptions.containsKey(EXPECTED_TOOLS_DESCRIPTION)) {
            itemProperties.put("expectedTools", Map.of("type", "array", "description", description(descriptions, EXPECTED_TOOLS_DESCRIPTION),
                    "items", Map.of("type", "string")));
        }
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("analysis", "items"),
                "properties", Map.of(
                        "analysis", Map.of("type", "string"),
                        "extractedEntities", Map.of(
                                "type", "array",
                                "description", description(descriptions, "todos.extractedEntities"),
                                "items", Map.of("type", "string")
                        ),
                        "items", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("id", "sequence", "description"),
                                        "properties", itemProperties
                                )
                        )
                )
        );
    }

    public static Map<String, Object> subAgentPlanningJsonSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("steps", "expected"),
                "properties", Map.of(
                        "expected", Map.of("type", "string"),
                        "steps", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("tool", "args", "note"),
                                        "properties", Map.of(
                                                "tool", Map.of("type", "string"),
                                                "args", Map.of("type", "object"),
                                                "note", Map.of("type", "string")
                                        )
                                )
                        )
                )
        );
    }

    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return "";
        }
        String body = text.substring(firstLineEnd + 1).trim();
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3).trim();
        }
        return body;
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText("").isBlank();
    }

    private static String safeMessage(Throwable e) {
        String message = e == null ? "" : e.getMessage();
        return message == null ? "" : message;
    }

    public record ValidationResult(boolean valid, String category, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "", "");
        }

        public static ValidationResult invalid(String category, String message) {
            return new ValidationResult(false, category == null ? "" : category, message == null ? "" : message);
        }
    }

    public static class StructuredPlanningException extends RuntimeException {
        private final String category;

        public StructuredPlanningException(String category, String message) {
            super(message);
            this.category = category == null ? CATEGORY_SCHEMA_VALIDATION_ERROR : category;
        }

        public String category() {
            return category;
        }
    }

    /**
     * 第一阶段统筹规划的输出结构。
     */
    public record OverallPlan(String mode, String detail) {
    }

    /**
     * 第一阶段（统筹规划）的 JSON Schema。
     *
     * @param maxDetailLength detail 字段最大长度限制
     */
    public static Map<String, Object> strategyStageJsonSchema(int maxDetailLength) {
        return strategyStageJsonSchema(maxDetailLength, LEGACY_DESCRIPTIONS);
    }

    public static Set<String> planningDescriptionKeys() {
        return LEGACY_DESCRIPTIONS.keySet();
    }

    /** 已发布七键字典及增加预计工具说明的八键字典是两种明确的协议。 */
    public static boolean acceptsPlanningDescriptionKeys(Set<String> keys) {
        Set<String> legacy = planningDescriptionKeys();
        return legacy.equals(keys) || keys != null && keys.size() == legacy.size() + 1
                && keys.containsAll(legacy) && keys.contains(EXPECTED_TOOLS_DESCRIPTION);
    }

    private static String description(Map<String, String> descriptions, String key) {
        String value = descriptions == null ? null : descriptions.get(key);
        if (value == null || value.isBlank()) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE, "规划输出说明缺少已知字段：" + key);
        }
        return value;
    }

    public static Map<String, Object> strategyStageJsonSchema(int maxDetailLength, Map<String, String> descriptions) {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("overallPlan"),
                "properties", Map.of(
                        "overallPlan", Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("mode", "detail"),
                                "properties", Map.of(
                                        "mode", Map.of(
                                                "type", "string",
                                                "enum", List.of("LINEAR", "DAG"),
                                                "description", description(descriptions, "strategy.mode")
                                        ),
                                        "detail", Map.of(
                                                "type", "string",
                                                "maxLength", maxDetailLength,
                                                "description", description(descriptions, "strategy.detail")
                                        )
                                )
                        )
                )
        );
    }

    /**
     * 验证第一阶段的统筹规划输出。
     *
     * @param root            JSON 根节点
     * @param maxDetailLength detail 最大长度限制
     * @return 包含 OverallPlan 的验证结果
     */
    public static ValidationResultWithData<OverallPlan> validateStrategyStage(
            JsonNode root,
            int maxDetailLength) {
        if (root == null || !root.isObject()) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_not_object", null);
        }

        JsonNode overallPlanNode = root.get("overallPlan");
        if (overallPlanNode == null || !overallPlanNode.isObject()) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_missing_overall_plan", null);
        }

        // 验证 mode
        JsonNode modeNode = overallPlanNode.get("mode");
        if (modeNode == null || !modeNode.isTextual()) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_missing_mode", null);
        }
        String mode = modeNode.asText().trim().toUpperCase();
        if (!"LINEAR".equals(mode) && !"DAG".equals(mode)) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_invalid_mode:" + mode, null);
        }

        // 验证 detail
        JsonNode detailNode = overallPlanNode.get("detail");
        if (detailNode == null || !detailNode.isTextual()) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_missing_detail", null);
        }
        String detail = detailNode.asText().trim();
        if (detail.isEmpty()) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_detail_empty", null);
        }
        if (detail.length() > maxDetailLength) {
            return ValidationResultWithData.invalid(CATEGORY_SCHEMA_VALIDATION_ERROR,
                    "strategy_plan_detail_too_long:" + detail.length() + ">" + maxDetailLength, null);
        }

        // 验证自然段数量（可选的软性约束）
        int paragraphCount = countParagraphs(detail);
        if ("LINEAR".equals(mode) && paragraphCount > 1) {
            // LINEAR 模式建议 1 段，但允许多段（仅警告）
            // 这里不做硬性返回错误，让调用方决定如何处理
        } else if ("DAG".equals(mode) && paragraphCount > 3) {
            // DAG 模式建议 2-3 段，超过 3 段可能过于详细
            // 同上，软性约束
        }

        return ValidationResultWithData.ok(new OverallPlan(mode, detail));
    }

    /**
     * 从 JSON 根节点提取 OverallPlan。
     *
     * @param root JSON 根节点
     * @return OverallPlan 对象
     * @throws StructuredPlanningException 如果提取失败
     */
    public static OverallPlan extractOverallPlan(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new StructuredPlanningException(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_not_object");
        }
        JsonNode overallPlanNode = root.get("overallPlan");
        if (overallPlanNode == null || !overallPlanNode.isObject()) {
            throw new StructuredPlanningException(CATEGORY_SCHEMA_VALIDATION_ERROR, "strategy_plan_missing_overall_plan");
        }
        String mode = overallPlanNode.path("mode").asText("LINEAR").trim().toUpperCase();
        String detail = overallPlanNode.path("detail").asText("").trim();
        return new OverallPlan(mode, detail);
    }

    /**
     * 统计自然段数量（按空行分隔）。
     */
    private static int countParagraphs(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 按一个或多个空行分割
        String[] paragraphs = text.split("\\n\\s*\\n");
        int count = 0;
        for (String p : paragraphs) {
            if (!p.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 带数据的验证结果。
     */
    public record ValidationResultWithData<T>(boolean valid, String category, String message, T data) {
        public static <T> ValidationResultWithData<T> ok(T data) {
            return new ValidationResultWithData<>(true, "", "", data);
        }

        public static <T> ValidationResultWithData<T> invalid(String category, String message, T data) {
            return new ValidationResultWithData<>(false, category == null ? "" : category, message == null ? "" : message, data);
        }
    }
}
