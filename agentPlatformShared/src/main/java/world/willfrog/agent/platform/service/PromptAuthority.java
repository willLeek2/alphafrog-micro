package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Q-09 Prompt 权威源：{@code agentPlatformShared} classpath {@code prompts/}。
 *
 * <p>YAML 和外置目录只允许保存权威正文的投影。这个类统一加载关键 Prompt，
 * 并在投影内容为空或与权威正文不一致时失败，避免不同启动路径得到不同模型指令。</p>
 */
final class PromptAuthority {

    private static final Map<String, String> TEXT_FIELDS = Map.ofEntries(
            Map.entry("agentRunSystemPrompt", "prompts/agent/agent_run_system.txt"),
            Map.entry("followUpSummarySystemPrompt", "prompts/agent/follow_up_summary_system.txt"),
            Map.entry("todoPlannerSystemPromptTemplate", "prompts/todo/todo_planner_system.txt"),
            Map.entry("workflowFinalSystemPrompt", "prompts/workflow/workflow_final_system.txt"),
            Map.entry("workflowTodoRecoverySystemPrompt", "prompts/workflow/workflow_todo_recovery_system.txt"),
            Map.entry("parallelPlannerSystemPromptTemplate", "prompts/parallel/parallel_planner_system.txt"),
            Map.entry("parallelFinalSystemPrompt", "prompts/parallel/parallel_final_system.txt"),
            Map.entry("parallelPatchPlannerSystemPromptTemplate", "prompts/parallel/parallel_patch_planner_system.txt"),
            Map.entry("planJudgeSystemPromptTemplate", "prompts/plan/plan_judge_system.txt"),
            Map.entry("planJudgeRuntimeSystemPromptTemplate", "prompts/plan/plan_judge_runtime_system.txt"),
            Map.entry("semanticJudgeSystemPromptTemplate", "prompts/judge/semantic_judge_system.txt"),
            Map.entry("subAgentPlannerSystemPromptTemplate", "prompts/sub_agent/sub_agent_planner_system.txt"),
            Map.entry("subAgentSummarySystemPrompt", "prompts/sub_agent/sub_agent_summary_system.txt"),
            Map.entry("pythonRefineSystemPrompt", "prompts/python/python_refine_system.txt"),
            Map.entry("pythonRefineOutputInstruction", "prompts/python/python_refine_output_instruction.txt"),
            Map.entry("financeMethodResolverSystemPrompt", "prompts/finance/finance_method_resolver_system.txt"),
            Map.entry("orchestratorPlanningSystemPrompt", "prompts/orchestrator/orchestrator_planning_system.txt"),
            Map.entry("orchestratorSummarySystemPrompt", "prompts/orchestrator/orchestrator_summary_system.txt"),
            Map.entry("dagReactSystemPrompt", "prompts/todo/dag_react_system.txt"),
            Map.entry("dagModeGuidancePrompt", "prompts/todo/dag_mode_guidance.txt"),
            Map.entry("planningStrategyStage", "prompts/todo/planning_strategy_stage.txt"),
            Map.entry("planningTodosStage", "prompts/todo/planning_todos_stage.txt"),
            Map.entry("dagRecoveryJudgeSystemPromptTemplate", "prompts/judge/dag_recovery_judge_system.txt"),
            Map.entry("planningAnalysisStage", "prompts/todo/planning_analysis_stage.txt"),
            Map.entry("planningStructuredStage", "prompts/todo/planning_structured_stage.txt"),
            Map.entry("planningLinearModeGuidance", "prompts/todo/planning_linear_mode_guidance.txt"),
            Map.entry("planningDagModeGuidance", "prompts/todo/planning_dag_mode_guidance.txt"),
            Map.entry("planningLinearConstraint", "prompts/todo/planning_linear_constraint.txt"),
            Map.entry("pythonRepairStageInstruction", "prompts/python/python_repair_stage.txt"),
            Map.entry("emptyOutputRecoveryStageInstruction", "prompts/todo/empty_output_recovery_stage.txt"),
            Map.entry("budgetLastMileStageInstruction", "prompts/agent/budget_last_mile_stage.txt"),
            Map.entry("toolCapabilityCatalog", "prompts/tools/tool_capability_catalog.json")
    );

    private static final String REQUIREMENTS_RESOURCE = "prompts/python/python_refine_requirements.txt";
    private static final String DATASET_SPECS_RESOURCE = "prompts/python/dataset_field_specs.json";
    private static final PromptAuthority SHARED = new PromptAuthority(PromptFileLoader::load, new ObjectMapper());

    private final Function<String, String> resourceLoader;
    private final ObjectMapper objectMapper;
    private volatile AuthoritySnapshot cached;

    private PromptAuthority(Function<String, String> resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    static PromptAuthority shared() {
        return SHARED;
    }

    /** 仅供同包测试注入受控 classpath 读取行为。 */
    static PromptAuthority forTesting(Function<String, String> resourceLoader) {
        return new PromptAuthority(resourceLoader, new ObjectMapper());
    }

    AgentLlmProperties.Prompts prompts() {
        return copyPrompts(snapshot().prompts());
    }

    String expectedText(String fieldName) {
        String expected = snapshot().texts().get(fieldName);
        if (expected == null) {
            throw new PromptConfigurationException("unknown_field", "未知 Prompt 字段 " + fieldName);
        }
        return expected;
    }

    List<String> expectedRequirements() {
        return new ArrayList<>(snapshot().prompts().getPythonRefineRequirements());
    }

    List<AgentLlmProperties.DatasetFieldSpec> expectedDatasetFieldSpecs() {
        return copyPrompts(snapshot().prompts()).getDatasetFieldSpecs();
    }

    String bundleDigest() {
        return digest(snapshot().texts());
    }

    String capabilityCatalogDigest() {
        return sha256(expectedText("toolCapabilityCatalog"));
    }

    void validateText(String fieldName, String text, String source) {
        if (text == null || text.isBlank()) {
            throw new PromptConfigurationException("blank", source + " 的 " + fieldName + " 为空");
        }
        if (looksLikeFileReference(text)) {
            throw new PromptConfigurationException(
                    "unresolved_file_reference", source + " 的 " + fieldName + " 仍是未解析的 file 引用");
        }
        if (!expectedText(fieldName).equals(text)) {
            throw new PromptConfigurationException(
                    "projection_mismatch", source + " 的 " + fieldName + " 与 classpath 权威正文不一致");
        }
    }

    void validateRequirements(List<String> requirements, String source) {
        if (requirements == null || requirements.isEmpty()) {
            throw new PromptConfigurationException("blank", source + " 的 pythonRefineRequirements 为空");
        }
        if (!expectedRequirements().equals(requirements)) {
            throw new PromptConfigurationException(
                    "projection_mismatch", source + " 的 pythonRefineRequirements 与 classpath 权威正文不一致");
        }
    }

    void validateDatasetFieldSpecs(List<AgentLlmProperties.DatasetFieldSpec> specs, String source) {
        if (specs == null || specs.isEmpty()) {
            throw new PromptConfigurationException("blank", source + " 的 datasetFieldSpecs 为空");
        }
        if (!objectMapper.valueToTree(expectedDatasetFieldSpecs()).equals(objectMapper.valueToTree(specs))) {
            throw new PromptConfigurationException(
                    "projection_mismatch", source + " 的 datasetFieldSpecs 与 classpath 权威正文不一致");
        }
    }

    /**
     * 校验 Spring 或热加载对象中已经出现的正文。未配置字段允许回退到权威正文；
     * 一旦提供覆盖，内容必须与权威正文相同。
     */
    void validateProjection(AgentLlmProperties.Prompts prompts, String source) {
        if (prompts == null) {
            return;
        }
        validateIfPresent("agentRunSystemPrompt", prompts.getAgentRunSystemPrompt(), source);
        validateIfPresent("followUpSummarySystemPrompt", prompts.getFollowUpSummarySystemPrompt(), source);
        validateIfPresent("todoPlannerSystemPromptTemplate", prompts.getTodoPlannerSystemPromptTemplate(), source);
        validateIfPresent("workflowFinalSystemPrompt", prompts.getWorkflowFinalSystemPrompt(), source);
        validateIfPresent("workflowTodoRecoverySystemPrompt", prompts.getWorkflowTodoRecoverySystemPrompt(), source);
        validateIfPresent("parallelPlannerSystemPromptTemplate", prompts.getParallelPlannerSystemPromptTemplate(), source);
        validateIfPresent("parallelFinalSystemPrompt", prompts.getParallelFinalSystemPrompt(), source);
        validateIfPresent("parallelPatchPlannerSystemPromptTemplate", prompts.getParallelPatchPlannerSystemPromptTemplate(), source);
        validateIfPresent("planJudgeSystemPromptTemplate", prompts.getPlanJudgeSystemPromptTemplate(), source);
        validateIfPresent("planJudgeRuntimeSystemPromptTemplate", prompts.getPlanJudgeRuntimeSystemPromptTemplate(), source);
        validateIfPresent("semanticJudgeSystemPromptTemplate", prompts.getSemanticJudgeSystemPromptTemplate(), source);
        validateIfPresent("subAgentPlannerSystemPromptTemplate", prompts.getSubAgentPlannerSystemPromptTemplate(), source);
        validateIfPresent("subAgentSummarySystemPrompt", prompts.getSubAgentSummarySystemPrompt(), source);
        validateIfPresent("pythonRefineSystemPrompt", prompts.getPythonRefineSystemPrompt(), source);
        validateIfPresent("pythonRefineOutputInstruction", prompts.getPythonRefineOutputInstruction(), source);
        validateIfPresent("financeMethodResolverSystemPrompt", prompts.getFinanceMethodResolverSystemPrompt(), source);
        validateIfPresent("orchestratorPlanningSystemPrompt", prompts.getOrchestratorPlanningSystemPrompt(), source);
        validateIfPresent("orchestratorSummarySystemPrompt", prompts.getOrchestratorSummarySystemPrompt(), source);
        validateIfPresent("dagReactSystemPrompt", prompts.getDagReactSystemPrompt(), source);
        validateIfPresent("dagModeGuidancePrompt", prompts.getDagModeGuidancePrompt(), source);
        validateIfPresent("planningStrategyStage", prompts.getPlanningStrategyStage(), source);
        validateIfPresent("planningTodosStage", prompts.getPlanningTodosStage(), source);
        validateIfPresent("dagRecoveryJudgeSystemPromptTemplate", prompts.getDagRecoveryJudgeSystemPromptTemplate(), source);
        validateIfPresent("planningAnalysisStage", prompts.getPlanningAnalysisStage(), source);
        validateIfPresent("planningStructuredStage", prompts.getPlanningStructuredStage(), source);
        validateIfPresent("planningLinearModeGuidance", prompts.getPlanningLinearModeGuidance(), source);
        validateIfPresent("planningDagModeGuidance", prompts.getPlanningDagModeGuidance(), source);
        validateIfPresent("planningLinearConstraint", prompts.getPlanningLinearConstraint(), source);
        validateIfPresent("pythonRepairStageInstruction", prompts.getPythonRepairStageInstruction(), source);
        validateIfPresent("emptyOutputRecoveryStageInstruction", prompts.getEmptyOutputRecoveryStageInstruction(), source);
        validateIfPresent("budgetLastMileStageInstruction", prompts.getBudgetLastMileStageInstruction(), source);
        validateIfPresent("toolCapabilityCatalog", prompts.getToolCapabilityCatalog(), source);
        if (prompts.getPythonRefineRequirements() != null && !prompts.getPythonRefineRequirements().isEmpty()) {
            validateRequirements(prompts.getPythonRefineRequirements(), source);
        }
        if (prompts.getDatasetFieldSpecs() != null && !prompts.getDatasetFieldSpecs().isEmpty()) {
            validateDatasetFieldSpecs(prompts.getDatasetFieldSpecs(), source);
        }
    }

    private void validateIfPresent(String fieldName, String value, String source) {
        if (value != null && !value.isBlank()) {
            validateText(fieldName, value, source);
        }
    }

    private AuthoritySnapshot snapshot() {
        AuthoritySnapshot current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = loadSnapshot();
            }
            return cached;
        }
    }

    private AuthoritySnapshot loadSnapshot() {
        Map<String, String> texts = new LinkedHashMap<>();
        TEXT_FIELDS.forEach((field, resource) -> texts.put(field, requireResource(resource)));

        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        prompts.setAgentRunSystemPrompt(texts.get("agentRunSystemPrompt"));
        prompts.setFollowUpSummarySystemPrompt(texts.get("followUpSummarySystemPrompt"));
        prompts.setTodoPlannerSystemPromptTemplate(texts.get("todoPlannerSystemPromptTemplate"));
        prompts.setWorkflowFinalSystemPrompt(texts.get("workflowFinalSystemPrompt"));
        prompts.setWorkflowTodoRecoverySystemPrompt(texts.get("workflowTodoRecoverySystemPrompt"));
        prompts.setParallelPlannerSystemPromptTemplate(texts.get("parallelPlannerSystemPromptTemplate"));
        prompts.setParallelFinalSystemPrompt(texts.get("parallelFinalSystemPrompt"));
        prompts.setParallelPatchPlannerSystemPromptTemplate(texts.get("parallelPatchPlannerSystemPromptTemplate"));
        prompts.setPlanJudgeSystemPromptTemplate(texts.get("planJudgeSystemPromptTemplate"));
        prompts.setPlanJudgeRuntimeSystemPromptTemplate(texts.get("planJudgeRuntimeSystemPromptTemplate"));
        prompts.setSemanticJudgeSystemPromptTemplate(texts.get("semanticJudgeSystemPromptTemplate"));
        prompts.setSubAgentPlannerSystemPromptTemplate(texts.get("subAgentPlannerSystemPromptTemplate"));
        prompts.setSubAgentSummarySystemPrompt(texts.get("subAgentSummarySystemPrompt"));
        prompts.setPythonRefineSystemPrompt(texts.get("pythonRefineSystemPrompt"));
        prompts.setPythonRefineOutputInstruction(texts.get("pythonRefineOutputInstruction"));
        prompts.setFinanceMethodResolverSystemPrompt(texts.get("financeMethodResolverSystemPrompt"));
        prompts.setOrchestratorPlanningSystemPrompt(texts.get("orchestratorPlanningSystemPrompt"));
        prompts.setOrchestratorSummarySystemPrompt(texts.get("orchestratorSummarySystemPrompt"));
        prompts.setDagReactSystemPrompt(texts.get("dagReactSystemPrompt"));
        prompts.setDagModeGuidancePrompt(texts.get("dagModeGuidancePrompt"));
        prompts.setPlanningStrategyStage(texts.get("planningStrategyStage"));
        prompts.setPlanningTodosStage(texts.get("planningTodosStage"));
        prompts.setDagRecoveryJudgeSystemPromptTemplate(texts.get("dagRecoveryJudgeSystemPromptTemplate"));
        prompts.setPlanningAnalysisStage(texts.get("planningAnalysisStage"));
        prompts.setPlanningStructuredStage(texts.get("planningStructuredStage"));
        prompts.setPlanningLinearModeGuidance(texts.get("planningLinearModeGuidance"));
        prompts.setPlanningDagModeGuidance(texts.get("planningDagModeGuidance"));
        prompts.setPlanningLinearConstraint(texts.get("planningLinearConstraint"));
        prompts.setPythonRepairStageInstruction(texts.get("pythonRepairStageInstruction"));
        prompts.setEmptyOutputRecoveryStageInstruction(texts.get("emptyOutputRecoveryStageInstruction"));
        prompts.setBudgetLastMileStageInstruction(texts.get("budgetLastMileStageInstruction"));
        prompts.setToolCapabilityCatalog(texts.get("toolCapabilityCatalog"));
        prompts.setPythonRefineRequirements(readRequirements(requireResource(REQUIREMENTS_RESOURCE)));
        prompts.setDatasetFieldSpecs(readDatasetSpecs(requireResource(DATASET_SPECS_RESOURCE)));
        return new AuthoritySnapshot(Map.copyOf(texts), prompts);
    }

    private String requireResource(String resource) {
        String text = resourceLoader.apply(resource);
        if (text == null || text.isBlank()) {
            throw new PromptConfigurationException(
                    "authority_missing_or_blank", "classpath 权威资源不可用: " + resource);
        }
        if (looksLikeFileReference(text)) {
            throw new PromptConfigurationException(
                    "authority_unresolved_file_reference", "classpath 权威资源仍是未解析的 file 引用: " + resource);
        }
        return text;
    }

    private List<String> readRequirements(String text) {
        List<String> requirements = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (requirements.isEmpty()) {
            throw new PromptConfigurationException("authority_blank", REQUIREMENTS_RESOURCE + " 没有有效条目");
        }
        return requirements;
    }

    private List<AgentLlmProperties.DatasetFieldSpec> readDatasetSpecs(String text) {
        try {
            List<AgentLlmProperties.DatasetFieldSpec> specs = objectMapper.readValue(
                    text, new TypeReference<List<AgentLlmProperties.DatasetFieldSpec>>() { });
            if (specs == null || specs.isEmpty()) {
                throw new PromptConfigurationException("authority_blank", DATASET_SPECS_RESOURCE + " 没有字段定义");
            }
            return specs;
        } catch (PromptConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new PromptConfigurationException(
                    "authority_parse_failed", DATASET_SPECS_RESOURCE + " 不是合法字段定义: " + e.getMessage());
        }
    }

    private boolean looksLikeFileReference(String text) {
        String value = text.trim();
        return value.startsWith("file:") || value.startsWith("file://") || value.startsWith("@file:");
    }

    private AgentLlmProperties.Prompts copyPrompts(AgentLlmProperties.Prompts source) {
        AgentLlmProperties.Prompts copy = new AgentLlmProperties.Prompts();
        copy.setAgentRunSystemPrompt(source.getAgentRunSystemPrompt());
        copy.setFollowUpSummarySystemPrompt(source.getFollowUpSummarySystemPrompt());
        copy.setTodoPlannerSystemPromptTemplate(source.getTodoPlannerSystemPromptTemplate());
        copy.setWorkflowFinalSystemPrompt(source.getWorkflowFinalSystemPrompt());
        copy.setWorkflowTodoRecoverySystemPrompt(source.getWorkflowTodoRecoverySystemPrompt());
        copy.setParallelPlannerSystemPromptTemplate(source.getParallelPlannerSystemPromptTemplate());
        copy.setParallelFinalSystemPrompt(source.getParallelFinalSystemPrompt());
        copy.setParallelPatchPlannerSystemPromptTemplate(source.getParallelPatchPlannerSystemPromptTemplate());
        copy.setPlanJudgeSystemPromptTemplate(source.getPlanJudgeSystemPromptTemplate());
        copy.setPlanJudgeRuntimeSystemPromptTemplate(source.getPlanJudgeRuntimeSystemPromptTemplate());
        copy.setSemanticJudgeSystemPromptTemplate(source.getSemanticJudgeSystemPromptTemplate());
        copy.setSubAgentPlannerSystemPromptTemplate(source.getSubAgentPlannerSystemPromptTemplate());
        copy.setSubAgentSummarySystemPrompt(source.getSubAgentSummarySystemPrompt());
        copy.setPythonRefineSystemPrompt(source.getPythonRefineSystemPrompt());
        copy.setPythonRefineRequirements(new ArrayList<>(source.getPythonRefineRequirements()));
        copy.setPythonRefineOutputInstruction(source.getPythonRefineOutputInstruction());
        copy.setDatasetFieldSpecs(objectMapper.convertValue(
                source.getDatasetFieldSpecs(), new TypeReference<List<AgentLlmProperties.DatasetFieldSpec>>() { }));
        copy.setFinanceMethodResolverSystemPrompt(source.getFinanceMethodResolverSystemPrompt());
        copy.setOrchestratorPlanningSystemPrompt(source.getOrchestratorPlanningSystemPrompt());
        copy.setOrchestratorSummarySystemPrompt(source.getOrchestratorSummarySystemPrompt());
        copy.setDagReactSystemPrompt(source.getDagReactSystemPrompt());
        copy.setDagModeGuidancePrompt(source.getDagModeGuidancePrompt());
        copy.setPlanningStrategyStage(source.getPlanningStrategyStage());
        copy.setPlanningTodosStage(source.getPlanningTodosStage());
        copy.setDagRecoveryJudgeSystemPromptTemplate(source.getDagRecoveryJudgeSystemPromptTemplate());
        copy.setPlanningAnalysisStage(source.getPlanningAnalysisStage());
        copy.setPlanningStructuredStage(source.getPlanningStructuredStage());
        copy.setPlanningLinearModeGuidance(source.getPlanningLinearModeGuidance());
        copy.setPlanningDagModeGuidance(source.getPlanningDagModeGuidance());
        copy.setPlanningLinearConstraint(source.getPlanningLinearConstraint());
        copy.setPythonRepairStageInstruction(source.getPythonRepairStageInstruction());
        copy.setEmptyOutputRecoveryStageInstruction(source.getEmptyOutputRecoveryStageInstruction());
        copy.setBudgetLastMileStageInstruction(source.getBudgetLastMileStageInstruction());
        copy.setToolCapabilityCatalog(source.getToolCapabilityCatalog());
        return copy;
    }

    private String digest(Map<String, String> texts) {
        StringBuilder canonical = new StringBuilder();
        texts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey()).append('\n')
                        .append(entry.getValue()).append('\n'));
        canonical.append(REQUIREMENTS_RESOURCE).append('\n')
                .append(requireResource(REQUIREMENTS_RESOURCE)).append('\n')
                .append(DATASET_SPECS_RESOURCE).append('\n')
                .append(requireResource(DATASET_SPECS_RESOURCE));
        return sha256(canonical.toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new PromptConfigurationException("digest_failed", "Prompt 权威正文摘要计算失败");
        }
    }

    private record AuthoritySnapshot(Map<String, String> texts, AgentLlmProperties.Prompts prompts) {
    }
}
