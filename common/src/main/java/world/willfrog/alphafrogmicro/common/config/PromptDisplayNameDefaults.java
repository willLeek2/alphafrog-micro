package world.willfrog.alphafrogmicro.common.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词字段名到给人看的中文名的默认映射，供管理后台展示。
 *
 * <p>读取时补上默认值、不写回已保存的文档：配置快照（agent-llm 类型）里 prompts.displayNames
 * 写了的条目优先，没写的条目用本表，两处都没有时管理后台回退显示字段名。本表随激活配置查询响应
 * 的兄弟字段 promptDisplayNameDefaults 一并返回，不写入快照正文，也不参与副本内容摘要，
 * 运行中的 Agent 不读取它。</p>
 *
 * <p>命名口径：同一内容的「文件路径」与「内联正文」两个变体成对出现时，文件侧名称带（文件）、
 * 正文侧带（正文）后缀；不成对的字段不带后缀。新增提示词字段时应同步补默认名，
 * 键与 AgentLlmProperties.Prompts 的字段一一对应（完整性由测试保证），顺序同字段声明顺序。</p>
 */
public final class PromptDisplayNameDefaults {

    /** 提示词字段名到默认中文名的映射，不可修改。 */
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("agentRunSystemPrompt", "对话系统提示词");
        defaults.put("followUpSummarySystemPrompt", "追问摘要提示词");
        defaults.put("todoPlannerSystemPromptTemplate", "待办规划提示词");
        defaults.put("workflowFinalSystemPrompt", "工作流收尾提示词");
        defaults.put("workflowTodoRecoverySystemPrompt", "待办恢复提示词");
        defaults.put("parallelPlannerSystemPromptTemplate", "并行规划提示词");
        defaults.put("parallelFinalSystemPrompt", "并行收尾提示词");
        defaults.put("parallelPatchPlannerSystemPromptTemplate", "并行修补提示词");
        defaults.put("planJudgeSystemPromptTemplate", "计划评判提示词");
        defaults.put("planJudgeRuntimeSystemPromptTemplate", "运行时计划评判");
        defaults.put("semanticJudgeSystemPromptTemplate", "语义评判提示词");
        defaults.put("subAgentPlannerSystemPromptTemplate", "子代理规划提示词");
        defaults.put("subAgentSummarySystemPrompt", "子代理摘要提示词");
        defaults.put("pythonRefineSystemPrompt", "代码修订提示词");
        defaults.put("pythonRefineRequirementsFile", "代码修订要求（文件）");
        defaults.put("pythonRefineRequirements", "代码修订要求列表");
        defaults.put("pythonRefineOutputInstruction", "代码输出说明");
        defaults.put("datasetFieldSpecs", "数据集字段规格列表");
        defaults.put("datasetFieldSpecsFile", "数据集字段规格（文件）");
        defaults.put("financeMethodResolverSystemPrompt", "金融方法解析提示词（正文）");
        defaults.put("financeMethodResolverSystemPromptFile", "金融方法解析提示词（文件）");
        defaults.put("orchestratorPlanningSystemPrompt", "编排规划提示词");
        defaults.put("orchestratorSummarySystemPrompt", "编排摘要提示词");
        defaults.put("dagReactSystemPrompt", "DAG 执行提示词（正文）");
        defaults.put("dagReactSystemPromptFile", "DAG 执行提示词（文件）");
        defaults.put("dagModeGuidancePrompt", "DAG 模式说明（正文）");
        defaults.put("dagModeGuidancePromptFile", "DAG 模式说明（文件）");
        defaults.put("planningStrategyStageFile", "规划策略阶段（文件）");
        defaults.put("planningStrategyStage", "规划策略阶段（正文）");
        defaults.put("planningTodosStageFile", "任务拆解阶段（文件）");
        defaults.put("planningTodosStage", "任务拆解阶段（正文）");
        defaults.put("planningAnalysisStage", "规划分析说明");
        defaults.put("planningStructuredStage", "结构化输出说明");
        defaults.put("planningLinearModeGuidance", "线性模式说明");
        defaults.put("planningDagModeGuidance", "DAG 规划说明");
        defaults.put("planningLinearConstraint", "强制线性约束");
        defaults.put("pythonRepairStageInstruction", "代码修复阶段");
        defaults.put("emptyOutputRecoveryStageInstruction", "空输出恢复说明");
        defaults.put("budgetLastMileStageInstruction", "预算收尾说明");
        defaults.put("todoRetryContextInstruction", "待办重试说明");
        defaults.put("pythonRepairContextInstruction", "代码修复上下文");
        defaults.put("toolSummarySystemPrompt", "工具摘要系统提示词");
        defaults.put("toolSummaryUserPromptTemplate", "工具摘要用户模板");
        defaults.put("toolCapabilityCatalog", "工具能力目录");
        defaults.put("dagRecoveryJudgeSystemPromptFile", "DAG 恢复评判提示词（文件）");
        defaults.put("dagRecoveryJudgeSystemPromptTemplate", "DAG 恢复评判提示词（正文）");
        DEFAULTS = Collections.unmodifiableMap(defaults);
    }

    private PromptDisplayNameDefaults() {
    }
}
