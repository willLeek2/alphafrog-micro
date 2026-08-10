package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.prompt.DefaultPromptVariantSelector;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.prompt.PromptSelectionContext;
import world.willfrog.agent.platform.prompt.PromptVariantSelector;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Agent 提示词中心 —— 整个 agent 系统所有 LLM 调用的 prompt 都在这里组装，
 * 是面试理解 agent 全流程最关键的三个文件之一
 * （另两个是 {@code LangchainLinearRunPipelineImpl} 和 {@code LangchainAiPlanner}）。
 *
 * <h2>这个类解决什么问题</h2>
 * <p>agent 在不同阶段（planning 规划 / execution 执行 / final answer 最终回答 / recovery 恢复 / judge 判定）
 * 需要给 LLM 发送不同的 system prompt 和 user message。
 * 所有 prompt 的权威加载与投影校验、时间基准注入、阶段指令拼装、工具能力说明生成都集中在这里，避免散落在各处导致不一致。</p>
 *
 * <h2>Prompt 的三层结构</h2>
 * <ol>
 *   <li><b>时间基准前缀</b>（harness，由 {@link #composeSystemPrompt(String)} 动态注入）：
 *       Run 内根据建 Run 时冻结的 reference date 计算当前年月日 + 相对年份映射规则；
 *       非 Run 的轻量调用才使用 {@code LocalDate.now()}。
 *       例如 "当前时间：2026年05月25日（星期一，2026年5月25日）...说去年，指2025年"。
 *       这是防止 LLM 把"去年"推理成错误年份的兜底机制。</li>
 *   <li><b>全局 agent 指令</b>（global，来自 {@code agentRunSystemPrompt}）：
 *       所有阶段共享的基础角色定义和约束（如"你是专业金融分析代理"、禁止猜测代码、必须用具体年份等）。
 *       在 System Message 中保持不变，利于 OpenAI 兼容 API 的 KV 前缀缓存（prompt caching）。</li>
 *   <li><b>阶段专属指令</b>（stage-specific，来自各 {@code stageInstruction()} 方法）：
 *       注入到 User Message 而非 System Message，
 *       引导 LLM 在当前阶段完成特定任务（如 strategy 统筹规划、todos 任务拆解、final answer 汇总）。
 *       放在 User Message 的设计意图是保持 System Prompt 稳定以提升缓存命中率。</li>
 * </ol>
 *
 * <h2>Prompt 权威源与投影</h2>
 * <ol>
 *   <li><b>唯一权威正文</b>：{@code agentPlatformShared} classpath {@code prompts/}。</li>
 *   <li><b>Nacos / 外置文件</b>：仍可热加载，但只作为权威正文的部署投影；内容不一致时拒绝刷新。</li>
 *   <li><b>application-agent-llm-prompts.yml</b>：不再维护第二份内联正文。</li>
 * </ol>
 * 所有关键正文在追加时间前缀或阶段标记前完成加载和非空校验；缺失时抛出稳定配置异常，
 * 不会用日期前缀把空 Prompt 伪装成可用消息。
 *
 * <h2>与规划（planning）和执行（execution）的协作关系</h2>
 * <ul>
 *   <li>{@link #reactSystemPrompt()} → 用于两阶段 planning 的 System Message，
 *       {@link world.willfrog.agentlangchain.planning.LangchainAiPlanner} 调用。</li>
 *   <li>{@link #dagReactSystemPrompt()} → 用于 DAG 节点执行（单 todo 的 ReAct 循环）的 System Message，
 *       {@code LangchainTodoNodeExecutor} 调用。</li>
 *   <li>{@link #planningStrategyStageInstruction(String, int, int)} → 规划第一阶段（统筹）的 User Message 指令，
 *       含 {@link #renderToolCapabilities(Collection)} 从权威目录渲染的实际工具能力清单。</li>
 *   <li>{@link #planningTodosStageInstruction(String, String, String, int)} → 规划第二阶段（任务拆解）的 User Message 指令。</li>
 *   <li>{@link #composeSystemPrompt(String)} → 所有 System Prompt 的统一入口，注入时间基准 + 全局指令。</li>
 * </ul>
 *
 * <h2>Sub-Agent 模型选择</h2>
 * <p>{@link #selectSubAgentModelName(String, String)} 根据任务目标的文本长度和关键词做启发式复杂度估算
 * （{@link Complexity#LOW}/{@link Complexity#MEDIUM}/{@link Complexity#HIGH}），
 * 然后从配置中选择对应档位的模型名，高复杂度用强模型（如 GPT-5.4），低复杂度用便宜模型（如 Qwen 122B），
 * 兼顾效果和成本。</p>
 *
 * <h2>面试可能被问到的点</h2>
 * <ul>
 *   <li>"System Prompt 为什么按天变化？不会破坏 KV 缓存吗？"
 *       → 时间基准确实会让 system prompt 每天不同，降低跨日缓存命中率。但我们优先保证时间推理正确
 *       （否则 LLM 会把"去年"算成 2024 而非 2025），缓存损失可接受。</li>
 *   <li>"prompt 怎么发布更新？"
 *       → 先修改 shared classpath 权威正文，再生成/同步外置投影并发布新构建；
 *       Nacos 热加载只接受逐字一致的投影，漂移候选会保留旧快照并产生失败指标。</li>
 *   <li>"checkParallelLimits 为什么放在 prompt 里引导而不是硬编码？"
 *       → 批量上限是运行时配置（可热改），硬编码在 prompt 里会导致配置改了但 LLM 仍按旧数字发请求。
 *       引导 LLM 先调 checkParallelLimits 获取当前上限，配置变更时 prompt 无需改动。</li>
 * </ul>
 *
 * @see AgentLlmLocalConfigLoader 热加载配置加载器
 * @see AgentLlmProperties 配置结构定义
 */
@Component
@Slf4j
public class AgentPromptService {

    /** 中文日期格式化器，"yyyy年MM月dd日"，如 "2026年05月25日"。
     *  用于 {@link #dynamicContextPrefix()} 和 {@link #composeSystemPrompt(String)} 中的日期展示。 */
    private static final DateTimeFormatter CN_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    /** Spring Boot 其他 LLM 配置；其中 prompts 若非空，只能是 classpath 权威正文的投影。 */
    private final AgentLlmProperties properties;
    /** Nacos 热加载配置（agent-llm.local.json），10s 轮询。
     *  Prompt 部分只用于校验部署投影；其他运行时配置仍按各自契约热更新。 */
    private final AgentLlmLocalConfigLoader localConfigLoader;
    private final PromptAuthority promptAuthority;
    private final PromptVariantSelector promptVariantSelector;
    private static final ObjectMapper CATALOG_MAPPER = new ObjectMapper();
    private volatile Map<String, String> toolCapabilityCatalog;

    @Autowired
    public AgentPromptService(AgentLlmProperties properties,
                              AgentLlmLocalConfigLoader localConfigLoader,
                              PromptVariantSelector promptVariantSelector) {
        this(properties, localConfigLoader, PromptAuthority.shared(), promptVariantSelector);
    }

    public AgentPromptService(AgentLlmProperties properties,
                              AgentLlmLocalConfigLoader localConfigLoader) {
        this(properties, localConfigLoader, PromptAuthority.shared(), new DefaultPromptVariantSelector());
    }

    AgentPromptService(AgentLlmProperties properties,
                       AgentLlmLocalConfigLoader localConfigLoader,
                       PromptAuthority promptAuthority) {
        this(properties, localConfigLoader, promptAuthority, new DefaultPromptVariantSelector());
    }

    AgentPromptService(AgentLlmProperties properties,
                       AgentLlmLocalConfigLoader localConfigLoader,
                       PromptAuthority promptAuthority,
                       PromptVariantSelector promptVariantSelector) {
        this.properties = properties;
        this.localConfigLoader = localConfigLoader;
        this.promptAuthority = promptAuthority;
        this.promptVariantSelector = promptVariantSelector;
    }

    /** Run 创建时唯一允许调用的版本选择入口。 */
    public PromptRunSelection snapshotPromptSelection(String runId, String userId, String experimentContext) {
        PromptVariantSelector.SelectedVariant selected = promptVariantSelector.select(
                new PromptSelectionContext(runId, userId, experimentContext));
        if (!DefaultPromptVariantSelector.BUNDLE_VERSION.equals(selected.bundleVersion())
                || !DefaultPromptVariantSelector.VARIANT.equals(selected.variant())) {
            throw new PromptConfigurationException(
                    "unsupported_prompt_variant",
                    "当前构建未登记 Prompt 版本 " + selected.bundleVersion() + "/" + selected.variant());
        }
        return new PromptRunSelection(
                PromptRunSelection.SCHEMA_VERSION,
                selected.bundleVersion(),
                selected.variant(),
                promptAuthority.bundleDigest(),
                promptAuthority.capabilityCatalogDigest(),
                LocalDate.now());
    }

    /** 在消费正文前校验持久化选择仍对应当前不可变权威包。 */
    public void validatePromptSelection(PromptRunSelection selection) {
        if (selection == null) {
            return;
        }
        if (!DefaultPromptVariantSelector.BUNDLE_VERSION.equals(selection.bundleVersion())
                || !DefaultPromptVariantSelector.VARIANT.equals(selection.variant())
                || !promptAuthority.bundleDigest().equals(selection.bundleDigest())
                || !promptAuthority.capabilityCatalogDigest().equals(selection.capabilityCatalogDigest())) {
            throw new PromptConfigurationException(
                    "prompt_selection_mismatch",
                    "Run 冻结的 Prompt 版本或摘要与当前权威资源不一致");
        }
    }

    /**
     * 返回 Agent Run 入口的完整 System Prompt（含时间基准 + 全局指令）。
     * 主要供完整 Agent Run 入口使用；DAG 节点执行倾向使用 {@link #reactSystemPrompt()}
     * 配合 stage instruction 注入到 User Message。
     *
     * @return 时间基准 + agent_run_system.txt 全局指令的拼接结果
     */
    public String agentRunSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), ""));
    }

    /**
     * Todo Planner 的历史 System 出口。D02 后只返回稳定层；模板仍加载并渲染以保持配置失败可见，
     * 新消费方必须把相应阶段正文放入 User Message。
     *
     * <p>模板中的 {@code {{toolWhitelist}}} 和 {@code {{maxTodos}}} 占位符会被替换为实际值。
     * 模板正文来自 shared classpath 权威文件；Nacos / 外置文件只能提供逐字一致的投影。
     * 最终由 {@link #composeSystemPrompt(String)} 返回稳定时间/全局层，不再把渲染正文并入 System。</p>
     *
     * @param toolWhitelist 可用工具名列表，逗号分隔，如 "searchIndex,getIndexDaily,executePython"
     * @param maxTodos      总步骤数上限
     * @return 仅含时间基准、数据时效和全局指令的稳定 System Prompt
     */
    public String todoPlannerSystemPrompt(String toolWhitelist, int maxTodos) {
        String template = currentPrompts().getTodoPlannerSystemPromptTemplate();
        String specific = render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
        return composeSystemPrompt(specific);
    }

    /**
     * 返回需要由调用方注入到 User Message 的动态上下文前缀。
     *
     * <p>生成 "今天是2026年05月25日。" 格式的日期提示，作为 User Message 的<b>第二时间锚点</b>。
     * 虽然 {@link #composeSystemPrompt(String)} 已经注入了时间基准，
     * 但在 User Message 中再重复一次可以增强 LLM 对当前日期的感知。</p>
     *
     * <p>典型用法：</p>
     * <pre>{@code
     * String systemPrompt = promptService.reactSystemPrompt();
     * String dynamicPrefix = promptService.dynamicContextPrefix();
     * // User Message = 日期前缀 + 阶段指令 + 用户目标
     * String userMsg = dynamicPrefix + "\n" + stageInstruction + "\n\n用户需求：" + userGoal;
     * }</pre>
     *
     * @return "今天是yyyy年MM月dd日。" 格式的日期字符串
     */
    public String dynamicContextPrefix() {
        PromptRunSelection selection = AgentContext.getPromptRunSelection();
        validatePromptSelection(selection);
        LocalDate date = selection == null ? LocalDate.now() : selection.referenceDate();
        return "今天是" + date.format(CN_DATE_FORMATTER) + "。";
    }

    /**
     * 工作流最终回答阶段的 System Prompt。
     * 兼容 {@code workflowFinalSystemPrompt} 与历史的 {@code parallelFinalSystemPrompt} 命名。
     *
     * @return 含时间基准的 final answer 阶段 System Prompt
     */
    public String workflowFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowFinalSystemPrompt(),
                currentPrompts().getParallelFinalSystemPrompt(),
                ""
        ));
    }

    /**
     * Todo 失败恢复阶段的 System Prompt。
     * 当某个 Todo 执行失败后，LLM 根据此 prompt 生成可重试的恢复参数（如换关键词、调整日期范围等）。
     *
     * @return 含时间基准的 recovery 阶段 System Prompt
     */
    public String workflowTodoRecoverySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        ));
    }

    /**
     * 并行规划器 System Prompt（便捷重载）。
     * candidateIndex 和 candidateCount 均默认 1（即单候选方案）。
     * 当需要生成多个候选 Plan 时使用带 candidate 参数的完整重载版本。
     *
     * @param toolWhitelist    可用工具白名单
     * @param maxTasks         总任务数上限
     * @param maxSubSteps      单任务子步骤上限
     * @param maxParallelTasks 并行任务数上限
     * @param maxSubAgents     Sub-Agent（子代理）数量上限
     */
    public String parallelPlannerSystemPrompt(String toolWhitelist,
                                              int maxTasks,
                                              int maxSubSteps,
                                              int maxParallelTasks,
                                              int maxSubAgents) {
        return parallelPlannerSystemPrompt(toolWhitelist, maxTasks, maxSubSteps, maxParallelTasks, maxSubAgents, 1, 1);
    }

    /**
     * 并行规划器 System Prompt（完整版，带候选方案配置）。
     *
     * <p>当一次 agent run 需要同时生成多个并行候选方案（如 multi-plan 模式）时，
     * {@code candidateIndex} / {@code candidateCount} 用于告诉 LLM "你是第几个候选"，
     * 促使不同候选生成有差异的 Plan 而非相互重复。</p>
     *
     * @param toolWhitelist    可用工具白名单
     * @param maxTasks         总任务数上限
     * @param maxSubSteps      单任务子步骤上限
     * @param maxParallelTasks 并行任务数上限
     * @param maxSubAgents     Sub-Agent 数量上限
     * @param candidateIndex   当前候选序号（从 1 开始），至少为 1
     * @param candidateCount   候选方案总数，至少为 1
     */
    public String parallelPlannerSystemPrompt(String toolWhitelist,
                                              int maxTasks,
                                              int maxSubSteps,
                                              int maxParallelTasks,
                                              int maxSubAgents,
                                              int candidateIndex,
                                              int candidateCount) {
        String template = firstNonBlank(currentPrompts().getParallelPlannerSystemPromptTemplate(),
                "");
        String specific = render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTasks", String.valueOf(maxTasks),
                "maxSubSteps", String.valueOf(maxSubSteps),
                "maxParallelTasks", String.valueOf(maxParallelTasks),
                "maxSubAgents", String.valueOf(maxSubAgents),
                // candidateIndex/candidateCount 至少为 1，避免渲染出 "第 0/0 个候选" 这种无意义文案
                "candidateIndex", String.valueOf(Math.max(candidateIndex, 1)),
                "candidateCount", String.valueOf(Math.max(candidateCount, 1))
        ));
        return composeSystemPrompt(specific);
    }

    /**
     * 并行执行结束后的最终汇总（final answer）System Prompt。
     *
     * <p>当一次 run 同时生成多个候选方案并执行完毕后，用此 prompt 引导 LLM
     * 比较各方案结果并输出最终答案。</p>
     */
    public String parallelFinalSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelFinalSystemPrompt(), ""));
    }

    /** 并行 Plan 补丁（patch）阶段的 System Prompt，用于对已有 Plan 做增量修正。 */
    public String parallelPatchPlannerSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getParallelPatchPlannerSystemPromptTemplate(), ""));
    }

    /** Plan Judge（计划判定器，静态版本）的 System Prompt。
     *  用于让 LLM 判断一个 Plan 是否可执行或需要修正。 */
    public String planJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPlanJudgeSystemPromptTemplate(), ""));
    }

    /**
     * Plan Judge 的运行时版本 System Prompt。
     *
     * <p>优先使用 {@code planJudgeRuntimeSystemPromptTemplate}（更适合运行时动态判定场景）；
     * 如果未配置则 fallback 到静态版本 {@link #planJudgeSystemPrompt()}，保证向后兼容。</p>
     *
     * @return 运行时或静态 Plan Judge System Prompt
     */
    public String planJudgeRuntimeSystemPrompt() {
        String runtimePrompt = firstNonBlank(currentPrompts().getPlanJudgeRuntimeSystemPromptTemplate(), "");
        if (!runtimePrompt.isEmpty()) {
            return composeSystemPrompt(runtimePrompt);
        }
        return planJudgeSystemPrompt();
    }

    /** 语义判断器（SemanticJudge）的 System Prompt。
     *  用于让 LLM 对执行结果做语义级别的是否满足用户意图判定。 */
    public String semanticJudgeSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSemanticJudgeSystemPromptTemplate(), ""));
    }

    /**
     * Sub-Agent（子代理）内部规划器的 System Prompt。
     * Sub-Agent 是主 Agent 在执行阶段派生的独立 LLM 任务，有自己的工具集和步骤限制。
     *
     * @param tools    分配给 Sub-Agent 的可用工具列表，逗号分隔
     * @param maxSteps Sub-Agent 内部最大步骤数
     * @return 含时间基准的 Sub-Agent Planner System Prompt
     */
    public String subAgentPlannerSystemPrompt(String tools, int maxSteps) {
        String template = firstNonBlank(currentPrompts().getSubAgentPlannerSystemPromptTemplate(),
                "");
        String specific = render(template, Map.of(
                "tools", safe(tools),
                "maxSteps", String.valueOf(maxSteps)
        ));
        return composeSystemPrompt(specific);
    }

    /** Sub-Agent 完成任务后的内容总结 System Prompt。 */
    public String subAgentSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getSubAgentSummarySystemPrompt(), ""));
    }

    /**
     * Python 代码自动修正（refine）阶段的 System Prompt。
     *
     * <p>当 executePython 返回错误（如语法错误、依赖缺失、数据字段不对）时，
     * 用此 prompt 引导 LLM 基于错误信息和原始代码生成修正版本。</p>
     */
    public String pythonRefineSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getPythonRefineSystemPrompt(), ""));
    }

    /**
     * Python refine（代码修正）阶段的硬性需求清单（如必须使用 pandas、必须遍历 sandbox 挂载目录等）。
     * 以条目列表形式注入到 User Message 中，避免 LLM 在修复代码时偏离规范。
     *
     * @return 不可变的需求列表（配置为空时返回空列表）
     */
    public List<String> pythonRefineRequirements() {
        List<String> requirements = currentPrompts().getPythonRefineRequirements();
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : requirements) {
            if (item != null && !item.trim().isEmpty()) {
                out.add(item.trim());
            }
        }
        return out;
    }

    /** Python refine 阶段的输出格式指令（如"输出 markdown 表格"等）。 */
    public String pythonRefineOutputInstruction() {
        return firstNonBlank(currentPrompts().getPythonRefineOutputInstruction());
    }

    /**
     * Finance MethodSpec resolver 的专用 system prompt 组装。
     *
     * <p>由 shared classpath 权威模板 + 构建期生成的紧凑目录组成。模板中保留
     * {@code {{RESOLVER_CATALOG}}} 占位符，由调用方传入当前目录文本后替换。</p>
     *
     * @param resolverCatalog 运行时紧凑目录文本（含 methodId/version/specDigest/aliases 等）
     * @return 替换占位符后的完整 system prompt
     */
    public String financeMethodResolverSystemPrompt(String resolverCatalog) {
        return render(financeMethodResolverSystemPromptTemplate(), Map.of("RESOLVER_CATALOG", safe(resolverCatalog)));
    }

    /**
     * 返回占位符替换前的 resolver system prompt 权威模板。
     *
     * <p>调用方（如 resolver 轻量模型服务）据此计算实际模板的版本摘要，
     * 保证持久化的 promptVersion 是真实使用模板的事实而非 classpath 默认。</p>
     *
     * <p>local {@code *File} 字段只保存投影路径；loader 校验成功后把正文写入对应正文属性。
     * 路径本身不会再被当作模型指令。</p>
     */
    public String financeMethodResolverSystemPromptTemplate() {
        return currentPrompts().getFinanceMethodResolverSystemPrompt();
    }

    /**
     * 从配置的 {@code datasetFieldSpecs} 列表渲染为如下格式：
     * <pre>
     * - trade_date | 含义: 交易日期 | 类型: string | 格式: YYYYMMDD
     * - close      | 含义: 收盘价   | 类型: double | 格式: 保留两位小数
     * </pre>
     *
     * @return 多行字段说明文本，无配置时返回空串
     */
    public String pythonRefineDatasetFieldGuide() {
        List<AgentLlmProperties.DatasetFieldSpec> fields = currentPrompts().getDatasetFieldSpecs();
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (AgentLlmProperties.DatasetFieldSpec field : fields) {
            if (field == null) {
                continue;
            }
            String name = safe(field.getName());
            if (name.isBlank()) {
                continue;
            }
            String line = "- " + name
                    + " | 含义: " + firstNonBlank(field.getMeaning(), "未说明")
                    + " | 类型: " + firstNonBlank(field.getDataType(), "未说明")
                    + " | 格式: " + firstNonBlank(field.getDataFormat(), "未说明");
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    /** Orchestrator（任务编排器）规划阶段的 System Prompt。
     *  用于多 Agent 协作场景下主控 Agent 的规划。 */
    public String orchestratorPlanningSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorPlanningSystemPrompt(), ""));
    }

    /** Orchestrator 总结阶段的 System Prompt。 */
    public String orchestratorSummarySystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getOrchestratorSummarySystemPrompt(), ""));
    }

    /**
     * DAG ReAct 执行阶段的 System Prompt —— 单个 todo 节点在 DAG 模式下执行时使用。
     *
     * <p><b>业务定位</b>：这是执行阶段（而非规划阶段）的 System Prompt。
     * 每个 DAG todo 节点在执行时会构造以这个 prompt 为 System Message 的 LC4j AiServices 对话，
     * LLM 在 ReAct（思考-行动-观察）循环中逐步调用工具完成任务。
     * 它回答的是"作为一个被分配了具体任务的节点，你该怎么一步一步完成它"，
     * 与 {@link #reactSystemPrompt()} 回答的"全局角色是什么"是不同的层次。</p>
     *
     * <p><b>加载契约</b>：正文来自 shared classpath 权威文件
     * {@code prompts/todo/dag_react_system.txt}。Nacos 的直接正文或 {@code *File}
     * 只作为逐字一致的部署投影；不再构成多级竞争 fallback。</p>
     *
     * <p><b>与 planning 一致</b>：通过 {@link #composeSystemPrompt(String)} 注入时间基准前缀与全局 agent_run 指令，
     * 保证执行阶段也能正确推理"去年是2025年"。这是 2026-05-25 commit 691cd51 修复的 harness bug——
     * 此前 dagReactSystemPrompt 没有走 composeSystemPrompt，导致执行阶段 LLM 缺少时间上下文。</p>
     *
     * @return 含时间基准 + 全局指令 + DAG 执行专用指令的 System Prompt
     * @see #reactSystemPrompt() planning 阶段的 System Prompt
     */
    public String dagReactSystemPrompt() {
        return reactSystemPrompt();
    }

    /** DAG/Todo 执行阶段正文，只能进入 User Message。 */
    public String dagReactStageInstruction(String renderedToolCapabilities) {
        return currentPrompts().getDagReactSystemPrompt()
                + "\n\n## 本次 Run 实际开放的工具\n"
                + safe(renderedToolCapabilities);
    }

    /**
     * DAG 模式引导提示（用于 planning 阶段）。
     * 告诉规划器当前使用 DAG 模式，需要通过 dependsOn（依赖声明）表达任务之间的依赖关系。
     *
     * <p>正文来自 shared classpath 权威文件，运行时投影必须逐字一致。</p>
     */
    public String dagModeGuidancePrompt() {
        return currentPrompts().getDagModeGuidancePrompt();
    }

    /**
     * DAG recovery judge System Prompt。
     *
     * <p>正文来自 shared classpath 权威文件
     * {@code prompts/judge/dag_recovery_judge_system.txt}；运行时投影必须逐字一致。</p>
     *
     * <p>通过 {@link #composeSystemPrompt(String)} 注入时间基准前缀与全局 agent_run 指令。</p>
     */
    public String dagRecoveryJudgeSystemPrompt() {
        return reactSystemPrompt();
    }

    /** DAG recovery judge 阶段正文，只能进入 User Message。 */
    public String dagRecoveryJudgeStageInstruction() {
        return "[Stage: DAG_RECOVERY_JUDGE]\n"
                + currentPrompts().getDagRecoveryJudgeSystemPromptTemplate();
    }

    /**
     * 返回配置的最大并行 Sub-Agent 数量。
     *
     * <p>从 {@code agent.llm.runtime.subAgent.maxCount} 读取，支持热加载。
     * 默认值 3。用于限制一次 agent run 中同时运行的 Sub-Agent 数量上限。</p>
     *
     * @return 最大 Sub-Agent 并行数（≥1）
     */
    public int maxSubAgentCount() {
        try {
            AgentLlmProperties.SubAgent subAgent = currentSubAgentConfig();
            if (subAgent != null && subAgent.getMaxCount() != null && subAgent.getMaxCount() > 0) {
                return subAgent.getMaxCount();
            }
        } catch (Exception e) {
            log.warn("Failed to read maxSubAgentCount from config, using default 3: {}", e.getMessage());
        }
        return 3;
    }

    /** 返回单个子代理允许的最大逻辑步骤数；缺省为 6。 */
    public int maxSubAgentSteps() {
        try {
            AgentLlmProperties.SubAgent subAgent = currentSubAgentConfig();
            if (subAgent != null && subAgent.getMaxSteps() != null && subAgent.getMaxSteps() > 0) {
                return subAgent.getMaxSteps();
            }
        } catch (Exception e) {
            log.warn("Failed to read maxSubAgentSteps from config, using default 6: {}", e.getMessage());
        }
        return 6;
    }

    /** 子代理能力是否开启；缺省关闭以避免残缺配置意外暴露执行入口。 */
    public boolean subAgentEnabled() {
        AgentLlmProperties.SubAgent subAgent = currentSubAgentConfig();
        return subAgent != null && Boolean.TRUE.equals(subAgent.getEnabled());
    }

    /**
     * Sub-Agent 的 LLM endpoint 名称。
     * 为空时表示沿用主 Agent 的 endpoint（即不单独指定）。
     *
     * @return endpoint 名称或空串
     */
    public String subAgentEndpointName() {
        AgentLlmProperties.SubAgent cfg = currentSubAgentConfig();
        return cfg == null ? "" : firstNonBlank(cfg.getEndpointName(), "");
    }

    /**
     * 根据任务目标复杂度选择 Sub-Agent 的模型名称。
     *
     * <p><b>为什么需要这个</b>：Sub-Agent 处理的任务复杂度差异很大——
     * 简单的如"搜一个股票代码"用便宜的模型（如 Qwen 122B），
     * 复杂的如"分析多资产回测结果"用强模型（如 GPT-5.4），
     * 通过启发式估算自动选择可以兼顾效果和成本。</p>
     *
     * <p>选择流程：</p>
     * <ol>
     *   <li>{@link #estimateComplexity(String, String)} 根据文本长度 + 关键词特征估算 LOW / MEDIUM / HIGH</li>
     *   <li>按估算结果取对应级别的模型名</li>
     *   <li>若对应级别未配置，按 HIGH → MEDIUM → LOW → fallback（兜底模型）顺序逐级回退，确保最终返回非空</li>
     * </ol>
     *
     * @param goal    Sub-Agent 的任务目标描述
     * @param context 补充上下文（如依赖 todo 的输出摘要）
     * @return 选定的模型名称（可能为空串表示沿用主 Agent 模型）
     */
    public String selectSubAgentModelName(String goal, String context) {
        AgentLlmProperties.SubAgent cfg = currentSubAgentConfig();
        if (cfg == null) {
            return "";
        }
        String low = firstNonBlank(cfg.getLowComplexityModelName(), "");
        String medium = firstNonBlank(cfg.getMediumComplexityModelName(), "");
        String high = firstNonBlank(cfg.getHighComplexityModelName(), "");
        String fallback = firstNonBlank(cfg.getModelName(), "");

        Complexity complexity = estimateComplexity(goal, context);
        return switch (complexity) {
            // HIGH: 优先 high → 回退 medium → low → fallback（一次性找最强可用模型）
            case HIGH -> firstNonBlank(high, medium, low, fallback);
            // MEDIUM: 优先 medium → 回退 high（贵但强）→ low → fallback
            case MEDIUM -> firstNonBlank(medium, high, low, fallback);
            // LOW: 优先 low（便宜）→ 回退 medium → high → fallback
            case LOW -> firstNonBlank(low, medium, high, fallback);
        };
    }


    // ─────────────────────────────────────────────────────────────
    // ReAct 统一 System Prompt + 各阶段 Stage Instruction（#28 重构）
    //
    // 设计意图：System Prompt 在整个 Agent Run 生命周期中保持不变（只含时间基准 + 全局角色定义），
    // 各阶段的差异指令通过 stageInstruction() 注入到 User Message。
    // 这样做的好处：System Prompt 内容稳定 → OpenAI 兼容 API 的自动 KV 前缀缓存命中率更高 →
    // 减少 LLM 调用延迟和 token 消耗。
    // ─────────────────────────────────────────────────────────────

    /**
     * 统一的 ReAct System Prompt —— planning / execution / final answer 等所有阶段的共享前缀。
     *
     * <p>内容结构：时间基准 + {@code agentRunSystemPrompt}（全局 agent 角色定义 + 通用约束）。</p>
     *
     * <p>与旧版 {@link #agentRunSystemPrompt()} 的区别：这个方法<b>不加阶段专属 prompt</b>，
     * 阶段差异由调用方通过 stage instruction 注入到 User Message。
     * 这样 System Prompt 在所有阶段中完全相同，利于 LLM 的 prompt 前缀缓存。</p>
     *
     * <p><b>使用方</b>：{@code LangchainAiPlanner.planTwoStageStructured()} 在 strategy 和 todos
     * 两个规划阶段都使用同一个 reactSystemPrompt 作为 System Message。</p>
     *
     * @return "时间基准 + 全局 agent 指令" 的拼接结果
     * @see #dagReactSystemPrompt() DAG 节点执行的 System Prompt
     */
    public String reactSystemPrompt() {
        return composeSystemPrompt(firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), ""));
    }

    /**
     * 规划分析阶段指令（已废弃）。
     *
     * @param toolWhitelist 可用工具白名单
     * @param maxTodos      最大 todo 步骤数
     * @return 含 [Stage: PLANNING_ANALYSIS] 标记的 User Message 指令
     * @deprecated 使用 {@link #planningStrategyStageInstruction(String, int, int)} 作为第一阶段替代
     */
    @Deprecated
    public String planningAnalysisStageInstruction(String toolWhitelist, int maxTodos) {
        String template = currentPrompts().getPlanningAnalysisStage();
        return render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
    }

    /**
     * 第一阶段：统筹规划（strategy）阶段指令 —— 注入到 User Message。
     *
     * <p><b>在两阶段规划中的角色</b>：这是第一个 LLM 调用的 User Message 中的阶段专属指令。
     * LLM 拿到 System Prompt（时间基准 + 全局指令）+ 这个指令 + 用户原始需求后，
     * 输出一个 {@code overallPlan}，包含 {@code mode}（LINEAR 还是 DAG）
     * 和 {@code detail}（用自然语言描述的高层执行策略，如"先搜 ETF 代码，再批量拉日线，最后 Python 算收益率"）。
     * 这个 detail 会在第二阶段注入到 todo 拆解的指令中，作为上下文传递给 LLM。</p>
     *
     * <p>模板来自 shared classpath 权威文件 {@code prompts/todo/planning_strategy_stage.txt}；
     * Nacos 只允许提供逐字一致的部署投影。
     * 模板中可引用 {@code {{toolCapabilities}}} 占位符，
     * 实际值由 {@link #renderToolCapabilities(Collection)} 从权威目录按本次实际工具集生成。</p>
     *
     * @param toolWhitelist   可用工具白名单，逗号分隔
     * @param maxTodos        最大 todo 步骤数
     * @param maxDetailLength strategy detail 文本的最大长度（超过会被截断或要求 LLM 压缩）
     * @return 含工具能力清单的 strategy 阶段 User Message 指令
     */
    public String planningStrategyStageInstruction(String toolWhitelist, int maxTodos, int maxDetailLength) {
        return planningStrategyStageInstruction(
                toolWhitelist, maxTodos, maxDetailLength,
                renderToolCapabilities(splitToolNames(toolWhitelist)));
    }

    public String planningStrategyStageInstruction(String toolWhitelist,
                                                   int maxTodos,
                                                   int maxDetailLength,
                                                   String renderedToolCapabilities) {
        String template = currentPrompts().getPlanningStrategyStage();
        return render(template, Map.of(
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos),
                "strategyMaxDetailLength", String.valueOf(maxDetailLength),
                "toolCapabilities", safe(renderedToolCapabilities)
        ));
    }

    /**
     * 构建工具能力说明清单 —— 告诉规划/执行阶段的 LLM 本次实际开放的工具能做什么。
     *
     * <p><b>设计要点（2026-05-25 PL-A 重构）</b>：</p>
     * <ul>
     *   <li>不再硬编码"单次最多 2 个"或"默认最多 3 个"——
     *       统一引导 LLM 先调用 {@code checkParallelLimits} 工具获取当前生效的批量上限。
     *       这样运行时改了 Nacos 配置后无需改 prompt。</li>
     *   <li>{@code checkParallelLimits} 排在第一位，确保 LLM 优先看到。
     *       同时给出 fallback 规则："如果工具列表中未见 checkParallelLimits 或调用失败，默认不批量"。</li>
     *   <li>每个支持批量的工具（如 getIndexDaily、searchAssetInfo 等）的描述中注明
     *       "具体上限先调用 checkParallelLimits 查询"。</li>
     *   <li>未知或缺说明的工具直接失败，禁止无描述工具静默进入 Prompt。</li>
     * </ul>
     *
     * @param toolNames 本次实际可用的工具名（生产调用先由 D05 registry 校验 membership）
     * @return 以换行分隔的工具能力说明文本，直接注入到 strategy stage instruction 中
     */
    public String renderToolCapabilities(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "";
        }
        Map<String, String> catalog = toolCapabilityCatalog();
        List<String> ordered = new ArrayList<>();
        if (toolNames.contains("checkParallelLimits")) {
            ordered.add("checkParallelLimits");
        }
        toolNames.stream()
                .filter(name -> name != null && !name.isBlank() && !"checkParallelLimits".equals(name))
                .distinct()
                .sorted()
                .forEach(ordered::add);
        List<String> capabilities = new ArrayList<>();
        for (String name : ordered) {
            String description = catalog.get(name);
            if (description == null || description.isBlank()) {
                throw new PromptConfigurationException(
                        "tool_capability_missing", "工具 " + name + " 缺少权威能力说明");
            }
            capabilities.add("- " + name + ": " + description);
        }
        return String.join("\n", capabilities);
    }

    /**
     * 第二阶段：任务拆解（todos）阶段指令 —— 注入到 User Message。
     *
     * <p><b>在两阶段规划中的角色</b>：第一个 LLM 调用产出了 strategy（模式 + 高层策略），
     * 第二个 LLM 调用负责把策略具体化为可执行的 todo list。
     * 这个方法的返回值就是第二个 LLM 调用的 User Message 中的阶段专属指令。</p>
     *
     * <p>注入的关键上下文：</p>
     * <ul>
     *   <li>{@code mode} — "LINEAR" 或 "DAG"，影响 LLM 是否填写 dependsOn 字段</li>
     *   <li>{@code detail} — strategy 阶段产出的高层策略文本，帮助 LLM 理解整体意图</li>
     *   <li>{@code modeGuidance} — 根据 mode 动态生成：DAG 模式提示填写 dependsOn，LINEAR 模式提示按 sequence 顺序</li>
     * </ul>
     *
     * <p>模板来自 shared classpath 权威文件 {@code prompts/todo/planning_todos_stage.txt}；
     * Nacos 只允许提供逐字一致的部署投影。</p>
     *
     * @param mode         执行模式 "LINEAR" 或 "DAG"
     * @param detail       strategy 阶段产出的高层策略文本
     * @param toolWhitelist 可用工具白名单
     * @param maxTodos     最大 todo 步骤数
     * @return 含 mode guidance 的 todos 阶段 User Message 指令
     */
    public String planningTodosStageInstruction(String mode, String detail,
                                                  String toolWhitelist, int maxTodos) {
        String template = currentPrompts().getPlanningTodosStage();
        String modeGuidance = "DAG".equalsIgnoreCase(mode)
                ? currentPrompts().getPlanningDagModeGuidance()
                : currentPrompts().getPlanningLinearModeGuidance();

        return render(template, Map.of(
                "mode", safe(mode),
                "detail", safe(detail),
                "modeGuidance", modeGuidance,
                "toolWhitelist", safe(toolWhitelist),
                "maxTodos", String.valueOf(maxTodos)
        ));
    }

    /**
     * 规划结构化转换阶段指令 —— 引导 LLM 将自然语言分析转化为简化的 Todo JSON。
     *
     * <p>ReAct 模式下，Todo 只需包含简短描述（description），
     * 具体的工具调用（toolName / params）由执行阶段的 LLM 在 ReAct 循环中自主决策，
     * 规划阶段不预先绑定工具参数。这样每个 todo 执行时拥有最大的灵活性。</p>
     *
     * @return 含 JSON 格式示例和注意事项的 structured 阶段指令
     */
    public String planningStructuredStageInstruction() {
        return currentPrompts().getPlanningStructuredStage();
    }

    public String planningLinearConstraint() {
        return currentPrompts().getPlanningLinearConstraint();
    }

    public String pythonRepairStageInstruction() {
        List<String> requirements = pythonRefineRequirements();
        String renderedRequirements = requirements == null ? "" : requirements.stream()
                .map(item -> "- " + item)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return render(currentPrompts().getPythonRepairStageInstruction(),
                Map.of("requirements", renderedRequirements));
    }

    public String emptyOutputRecoveryStageInstruction() {
        return currentPrompts().getEmptyOutputRecoveryStageInstruction();
    }

    public String budgetLastMileStageInstruction(String dimension,
                                                  long actual,
                                                  long limit,
                                                  long ratioPct) {
        return render(currentPrompts().getBudgetLastMileStageInstruction(), Map.of(
                "dimension", safe(dimension),
                "actual", String.valueOf(actual),
                "limit", String.valueOf(limit),
                "ratioPct", String.valueOf(ratioPct)));
    }

    /**
     * Recovery（恢复）阶段指令 —— 当某个 Todo 执行失败后，引导 LLM 生成替代方案。
     *
     * <p>例如：用不同的搜索关键词重试、缩小时间范围、换一个工具等。
     * 注入到 User Message 中，System Prompt 仍然保持为 {@link #reactSystemPrompt()}。</p>
     *
     * @return 带 [Stage: TODO_RECOVERY] 标记的 recovery 指令
     */
    public String recoveryStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getWorkflowTodoRecoverySystemPrompt(),
                ""
        );
        return "[Stage: TODO_RECOVERY]\n" + specific;
    }

    /**
     * Final Answer（最终回答）阶段指令 —— 所有 todo 执行完毕后，
     * 引导 LLM 汇总中间结果，生成面向用户的最终回答。
     *
     * @return 带 [Stage: FINAL_ANSWER] 标记的 final answer 指令
     */
    public String finalAnswerStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getWorkflowFinalSystemPrompt(),
                currentPrompts().getParallelFinalSystemPrompt(),
                ""
        );
        return "[Stage: FINAL_ANSWER]\n" + specific;
    }

    /**
     * Plan Judge（计划判定）阶段指令 —— 引导 LLM 对执行结果做"是否失败、需要重试"的判定。
     *
     * <p>在 ReAct 累积模式下，Judge 不另起独立的 System Prompt，
     * 而是共享主流程的 System Prompt（{@link #reactSystemPrompt()}），
     * 只通过此阶段指令注入到对话上下文来切换任务。</p>
     *
     * @return 带 [Stage: PLAN_JUDGE] 标记的 judge 指令
     */
    public String planJudgeStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getPlanJudgeRuntimeSystemPromptTemplate(),
                currentPrompts().getPlanJudgeSystemPromptTemplate(),
                ""
        );
        return "[Stage: PLAN_JUDGE]\n" + specific;
    }

    /**
     * Patch Planner（计划补丁）阶段指令 —— 引导 LLM 对执行中出现问题的 Plan 做增量修正，
     * 而非全盘推翻重来。
     *
     * <p>与 {@link #planJudgeStageInstruction()} 一样共享主流程的 System Prompt。</p>
     *
     * @return 带 [Stage: PATCH_PLAN] 标记的 patch 指令
     */
    public String patchPlannerStageInstruction() {
        String specific = firstNonBlank(
                currentPrompts().getParallelPatchPlannerSystemPromptTemplate(),
                ""
        );
        return "[Stage: PATCH_PLAN]\n" + specific;
    }

    /**
     * 组装完整的 System Prompt：<b>时间基准 + 全局 agent 指令 + 可选的阶段专属 prompt</b>。
     *
     * <p>这是整个 agent 系统最重要的 prompt 组装方法，所有 System Prompt 最终都经过这里。
     * 面试时如果能讲清楚这个三段式结构及其设计意图，基本就掌握了 agent 的 prompt 架构。</p>
     *
     * <h3>三段式结构</h3>
     * <ol>
     *   <li><b>时间基准（动态）</b>：由 {@code LocalDate.now()} 计算，包含
     *       <ul>
     *         <li>当前精确时间："2026年05月25日（星期一，2026年5月25日）"</li>
     *         <li>相对年份映射："用户说2026年，指2026年；说去年，指2025年；说今年，指2026年..."</li>
     *         <li>输出约束："所有输出内容须标明具体公历年份，禁止使用仅含「去年」「明年」等表述"</li>
     *       </ul>
     *       <b>面试要点</b>：为什么每天动态计算而不是写死？因为 agent 长期运行，
     *       去年/今年的含义随时间变化。写死会导致跨年后"去年"指代错误。</li>
     *   <li><b>全局 agent 指令（静态）</b>：来自 {@code agentRunSystemPrompt}
     *       （通常是 {@code agent_run_system.txt}），包含角色定义、通用约束等。
     *       这部分内容跨阶段保持不变，利于 OpenAI 兼容 API 的自动 KV 前缀缓存。</li>
     *   <li><b>阶段专属 prompt</b>：由调用方通过 {@code *StageInstruction()} 注入 User Message；
     *       兼容参数不再进入 System。</li>
     * </ol>
     *
     * <h3>与 {@link #dynamicContextPrefix()} 的关系</h3>
     * <p>{@code dynamicContextPrefix()} 返回 "今天是2026年05月25日。" 并注入到 User Message。
     * 这与本方法在 System Prompt 中注入的时间基准形成<b>双重时间锚点</b>：
     * System Prompt 一次 + User Message 一次，增强 LLM 的时间感知。</p>
     *
     * @param ignoredSpecificPrompt 历史兼容参数；D02 后不再写入 System
     * @return 稳定时间基准、数据时效与全局指令组成的 System Prompt
     */
    private String composeSystemPrompt(String ignoredSpecificPrompt) {
        String global = firstNonBlank(currentPrompts().getAgentRunSystemPrompt(), "");

        List<String> parts = new ArrayList<>();
        PromptRunSelection selection = AgentContext.getPromptRunSelection();
        validatePromptSelection(selection);
        // Run 内使用冻结日期；非 Run 的轻量/测试调用才使用当前日期。
        LocalDate today = selection == null ? LocalDate.now() : selection.referenceDate();
        int thisYear = today.getYear();
        int lastYear = thisYear - 1;
        int yearBeforeLast = thisYear - 2;
        parts.add(String.format(
                "当前时间：%s（%s，%d年%d月%d日）。所有涉及日期、时间、年份的推理必须以当前时间为基准。"
                + "例如：用户说%d年，指%d年；说去年，指%d年；说今年，指%d年；"
                + "说上一年，指%d年；说再上一年，指%d年。"
                + "所有输出内容须标明具体公历年份，禁止使用仅含「去年」「明年」「上一年」「再上一年」等未标明具体年份的表述。",
                today.format(CN_DATE_FORMATTER),
                today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE),
                thisYear, today.getMonthValue(), today.getDayOfMonth(),
                thisYear, thisYear,
                lastYear, thisYear,
                lastYear, yearBeforeLast));
        String dataFreshnessPrompt = dataFreshnessPrompt();
        if (!dataFreshnessPrompt.isBlank()) {
            parts.add(dataFreshnessPrompt);
        }
        // 第二段：全局 agent 角色定义（跨阶段共享，利于 KV 缓存）
        if (!global.isBlank()) {
            parts.add(global);
        }
        return String.join("\n", parts).trim();
    }

    private List<String> splitToolNames(String toolWhitelist) {
        if (toolWhitelist == null || toolWhitelist.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(toolWhitelist.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .toList();
    }

    private Map<String, String> toolCapabilityCatalog() {
        Map<String, String> current = toolCapabilityCatalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (toolCapabilityCatalog == null) {
                try {
                    Map<String, String> parsed = CATALOG_MAPPER.readValue(
                            currentPrompts().getToolCapabilityCatalog(),
                            new TypeReference<LinkedHashMap<String, String>>() { });
                    if (parsed == null || parsed.isEmpty()) {
                        throw new PromptConfigurationException(
                                "tool_capability_catalog_blank", "工具能力权威目录为空");
                    }
                    toolCapabilityCatalog = Map.copyOf(parsed);
                } catch (PromptConfigurationException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PromptConfigurationException(
                            "tool_capability_catalog_invalid", "工具能力权威目录不是合法 JSON");
                }
            }
            return toolCapabilityCatalog;
        }
    }

    /**
     * 数据时效 Prompt 注入。
     *
     * <p>优先级：AgentContext 快照（run 启动时从 ext.data_freshness 反序列化）> Nacos 热加载 > 静态配置。
     * ext 快照由 {@code AgentEventService.createRun()} 写入、{@code LangchainLinearRunPipelineImpl.executeRun()}
     * 入口从 ext 解析回 ThreadLocal。快照为 null 时 fallback 到 {@link #currentDataFreshness()}。</p>
     */
    private String dataFreshnessPrompt() {
        // 优先使用 run 启动时冻结的快照（AgentContext），保证同一 run 内 dataFreshness 语义一致
        AgentLlmProperties.DataFreshness freshness = AgentContext.getDataFreshness();
        if (freshness == null) {
            freshness = currentDataFreshness();
        }
        if (freshness == null) {
            return "";
        }
        String startDate = firstNonBlank(freshness.getStartDate());
        String endDate = firstNonBlank(freshness.getEndDate());
        String asOfDate = firstNonBlank(freshness.getAsOfDate());
        String description = firstNonBlank(freshness.getDescription());
        if (startDate.isEmpty() && endDate.isEmpty() && asOfDate.isEmpty() && description.isEmpty()) {
            return "";
        }

        List<String> clauses = new ArrayList<>();
        if (!startDate.isEmpty() || !endDate.isEmpty()) {
            clauses.add("范围：" + firstNonBlank(startDate, "未指定") + " 至 " + firstNonBlank(endDate, "未指定"));
        }
        if (!asOfDate.isEmpty()) {
            clauses.add("as-of 日期：" + asOfDate);
        }
        if (!description.isEmpty()) {
            clauses.add("说明：" + description);
        }
        return "当前已爬取/可用市场数据时效范围（部署者在 agent-llm 配置中指定，业务逻辑默认该日期正确）："
                + String.join("；", clauses)
                + "。涉及行情、基金、指数、ETF 等本地数据查询时，应将该范围视为当前数据覆盖边界，不要自行推断或改写该范围。";
    }

    /**
     * 返回 classpath 权威 Prompt，并验证运行时出现的静态/热加载投影。
     *
     * <p>本地配置仍可只列出部分字段；未列出的字段直接使用权威正文。列出的正文必须与权威源
     * 逐字一致，否则 loader 拒绝候选快照。这里再做一道消费前校验，防止测试替身或未来调用
     * 绕过加载器。</p>
     *
     * @return 权威 Prompt 的防御性副本（永不为 null）
     */
    private AgentLlmProperties.Prompts currentPrompts() {
        promptAuthority.validateProjection(properties.getPrompts(), "Spring prompt projection");
        AgentLlmLocalConfigLoader.LocalConfigSnapshot localSnapshot = localConfigLoader.currentSnapshot();
        if (localSnapshot != null
                && localSnapshot.config() != null
                && localSnapshot.topLevelSections().contains("prompts")) {
            promptAuthority.validateProjection(
                    localSnapshot.config().getPrompts(), "local prompt projection");
        }
        return promptAuthority.prompts();
    }

    private AgentLlmProperties.DataFreshness currentDataFreshness() {
        AgentLlmProperties.DataFreshness base = properties.getDataFreshness();
        AgentLlmProperties.DataFreshness local = localConfigLoader.current()
                .map(AgentLlmProperties::getDataFreshness)
                .orElse(null);
        if (local == null) {
            return base;
        }
        AgentLlmProperties.DataFreshness merged = new AgentLlmProperties.DataFreshness();
        merged.setStartDate(firstNonBlank(local.getStartDate(), base == null ? null : base.getStartDate()));
        merged.setEndDate(firstNonBlank(local.getEndDate(), base == null ? null : base.getEndDate()));
        merged.setAsOfDate(firstNonBlank(local.getAsOfDate(), base == null ? null : base.getAsOfDate()));
        merged.setDescription(firstNonBlank(local.getDescription(), base == null ? null : base.getDescription()));
        return merged;
    }

    /**
     * 公开的快照方法：返回当前生效的合并后 DataFreshness 的防御性副本。
     * 调用方（如 {@code AgentEventService.createRun()}）在 run 创建时调用一次，
     * 写入 ext JSON 作为该 run 的不可变快照，保证 run 生命周期内数据时效语义稳定。
     *
     * @return 合并后的 DataFreshness 副本，不会随后续 Nacos 热加载变化
     */
    public AgentLlmProperties.DataFreshness snapshotDataFreshness() {
        return currentDataFreshness();
    }

    /**
     * 合并热加载与静态的 SubAgent（子代理）配置。
     *
     * <p>SubAgent 运行参数仍按字段级合并：本地非空则覆盖静态。
     * 支持的字段包括：enabled（是否启用）、complexityThreshold（复杂度阈值）、
     * maxSteps（最大步骤数）、maxCount（最大并行数）、endpointName（LLM 端点）、
     * modelName 及各复杂度等级的模型名。</p>
     *
     * @return 合并后的 SubAgent 配置，静态配置全部为空时返回 null
     */
    private AgentLlmProperties.SubAgent currentSubAgentConfig() {
        AgentLlmProperties.SubAgent base = properties.getRuntime() == null
                ? null
                : properties.getRuntime().getSubAgent();
        AgentLlmProperties.SubAgent local = localConfigLoader.current()
                .map(AgentLlmProperties::getRuntime)
                .map(AgentLlmProperties.Runtime::getSubAgent)
                .orElse(null);
        if (local == null) {
            return base;
        }
        AgentLlmProperties.SubAgent merged = new AgentLlmProperties.SubAgent();
        merged.setEnabled(firstNonNull(local.getEnabled(), base == null ? null : base.getEnabled()));
        merged.setComplexityThreshold(firstNonBlank(local.getComplexityThreshold(), base == null ? null : base.getComplexityThreshold()));
        merged.setMaxSteps(firstNonNull(local.getMaxSteps(), base == null ? null : base.getMaxSteps()));
        merged.setMaxCount(firstNonNull(local.getMaxCount(), base == null ? null : base.getMaxCount()));
        merged.setEndpointName(firstNonBlank(local.getEndpointName(), base == null ? null : base.getEndpointName()));
        merged.setModelName(firstNonBlank(local.getModelName(), base == null ? null : base.getModelName()));
        merged.setLowComplexityModelName(firstNonBlank(local.getLowComplexityModelName(), base == null ? null : base.getLowComplexityModelName()));
        merged.setMediumComplexityModelName(firstNonBlank(local.getMediumComplexityModelName(), base == null ? null : base.getMediumComplexityModelName()));
        merged.setHighComplexityModelName(firstNonBlank(local.getHighComplexityModelName(), base == null ? null : base.getHighComplexityModelName()));
        return merged;
    }

    /**
     * 基于启发式特征估算 Sub-Agent 任务的复杂度，用于自动选择合适的模型档位。
     *
     * <p><b>评分规则</b>（每命中一项 +1 分）：</p>
     * <ul>
     *   <li>文本长度 ＞ 180：score +1（描述越长通常越复杂）</li>
     *   <li>文本长度 ＞ 360：score 再 +1（超长文本累计 +2）</li>
     *   <li>包含"并行 / parallel / 多个 / multi"等并行关键词：score +1</li>
     *   <li>包含"组合 / 回测 / 夏普 / 最大回撤"等量化分析关键词：score +1</li>
     *   <li>包含"并且 / 同时 / 另外 / 此外"等多任务连接词：score +1</li>
     * </ul>
     *
     * <p>注意：goal 和 context 合并后计算长度和关键词匹配，
     * 因此短 goal + 长 context 的场景也可能被判为高复杂度。</p>
     *
     * <p><b>阈值</b>：</p>
     * <ul>
     *   <li>score ≥ 4 → {@link Complexity#HIGH}（高强度，如多资产回测 + 市场分析）→ 选最强模型（如 GPT-5.4）</li>
     *   <li>score ≥ 2 → {@link Complexity#MEDIUM}（中等，如单资产数据查询 + 简单计算）→ 选中档模型（如 Kimi K2.5）</li>
     *   <li>score ＜ 2 → {@link Complexity#LOW}（简单，如搜索一个代码）→ 选便宜模型（如 Qwen 122B）</li>
     * </ul>
     *
     * <p><b>面试要点</b>：这是一个简单但有效的启发式方法。不需要真正"理解"任务语义，
     * 只需要看文本长度和关键词就能做出合理的复杂度判断。比硬编码所有 Sub-Agent 用同一个模型更经济。</p>
     *
     * @param goal    Sub-Agent 的任务目标文本
     * @param context 补充上下文文本
     * @return 复杂度枚举值
     */
    private Complexity estimateComplexity(String goal, String context) {
        String text = (safe(goal) + "\n" + safe(context)).toLowerCase(Locale.ROOT);
        int score = 0;
        if (text.length() > 180) {
            score++;
        }
        if (text.length() > 360) {
            score++;
        }
        if (text.contains("并行") || text.contains("parallel") || text.contains("多个") || text.contains("multi")) {
            score++;
        }
        if (text.contains("组合") || text.contains("回测") || text.contains("夏普") || text.contains("最大回撤")) {
            score++;
        }
        if (text.contains("并且") || text.contains("同时") || text.contains("另外") || text.contains("此外")) {
            score++;
        }
        if (score >= 4) {
            return Complexity.HIGH;
        }
        if (score >= 2) {
            return Complexity.MEDIUM;
        }
        return Complexity.LOW;
    }

    /**
     * 返回 first 非空时取 first，否则取 second。
     * 用于配置字段级合并时热加载值覆盖静态值。
     */
    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /** Sub-Agent 任务复杂度三档，驱动模型自动选择。 */
    private enum Complexity {
        /** 简单任务——如搜索单个代码、获取单只股票信息，用最便宜的模型即可 */
        LOW,
        /** 中等任务——如批量数据查询 + 简单计算，用中档模型 */
        MEDIUM,
        /** 高强度任务——如多资产回测 + 市场分析 + Python 计算，需要最强模型 */
        HIGH
    }

    /**
     * 简易模板渲染：把 {@code {{key}}} 占位符替换为 vars 中的实际值。
     *
     * <p>例如模板 "可用工具: {{toolWhitelist}}" + vars {toolWhitelist: "searchIndex,getIndexDaily"}
     * → "可用工具: searchIndex,getIndexDaily"。</p>
     *
     * <p>不支持循环、条件等高级模板语法——prompt 模板只需简单的占位符替换。</p>
     *
     * @param template 含 {{key}} 占位符的模板字符串，为 null 时返回空串
     * @param vars     占位符到实际值的映射
     * @return 替换后的文本
     */
    private String render(String template, Map<String, String> vars) {
        String out = safe(template);
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return out;
    }

    /**
     * 取第一个非空白（非 null 且 trim 后非空）的字符串值。
     * 全部为空时返回空串。可用于配置字段多级 fallback 等场景。
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 空安全工具方法：null 转空串，避免 NullPointerException。 */
    private String safe(String text) {
        return text == null ? "" : text;
    }
}
