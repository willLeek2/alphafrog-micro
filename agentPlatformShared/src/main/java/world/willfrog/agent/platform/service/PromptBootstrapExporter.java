package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptBodyType;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptTemplateRenderer;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVariableDeclaration;

import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只读导出Authority登记内容及导入草案，不创建远程版本或切换消费者。 */
public final class PromptBootstrapExporter {
    private static final String REQUIREMENTS = "prompts/python/python_refine_requirements.txt";
    private static final String DATASET_SPECS = "prompts/python/dataset_field_specs.json";
    private static final String TOOL_INDEX = "prompts/tools/tool_description_index.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 这些键来自相应消费方法实际传给render/replace的变量，不从正文猜测。 */
    private static final Map<String, Set<String>> RENDER_VARIABLES = Map.ofEntries(
            Map.entry("followUpSummarySystemPrompt", Set.of("maxChars")),
            Map.entry("todoPlannerSystemPromptTemplate", Set.of("toolWhitelist", "maxTodos")),
            Map.entry("parallelPlannerSystemPromptTemplate", Set.of("toolWhitelist", "maxTasks", "maxSubSteps",
                    "maxParallelTasks", "maxSubAgents", "candidateIndex", "candidateCount")),
            Map.entry("subAgentPlannerSystemPromptTemplate", Set.of("tools", "maxSteps")),
            Map.entry("financeMethodResolverSystemPrompt", Set.of("RESOLVER_CATALOG")),
            Map.entry("planningAnalysisStage", Set.of("toolWhitelist", "maxTodos")),
            Map.entry("planningStrategyStage", Set.of("toolWhitelist", "maxTodos", "strategyMaxDetailLength", "toolCapabilities")),
            Map.entry("planningTodosStage", Set.of("mode", "detail", "modeGuidance", "toolWhitelist", "maxTodos")),
            Map.entry("pythonRepairStageInstruction", Set.of("requirements")),
            Map.entry("todoRetryContextInstruction", Set.of("toolName", "toolSafety", "failureCategory", "failureSummary", "previousArguments")),
            Map.entry("pythonRepairContextInstruction", Set.of("repairAttempt", "terminalStatus", "exitReason",
                    "errorCode", "retryable", "stdoutPreview", "stderrPreview", "failedCodePreview")),
            Map.entry("toolSummaryUserPromptTemplate", Set.of("todoGoal", "toolName", "rawOutput")),
            Map.entry("budgetLastMileStageInstruction", Set.of("dimension", "actual", "limit", "ratioPct")));

    /** 仅表示未登记字段自身的业务render键，不表示下游SDK或嵌入位置不会继续渲染。 */
    private static final Set<String> RAW_FIELDS = Set.of(
            "agentRunSystemPrompt", "workflowFinalSystemPrompt", "workflowTodoRecoverySystemPrompt",
            "parallelFinalSystemPrompt", "parallelPatchPlannerSystemPromptTemplate",
            "planJudgeSystemPromptTemplate", "planJudgeRuntimeSystemPromptTemplate", "semanticJudgeSystemPromptTemplate",
            "subAgentSummarySystemPrompt", "pythonRefineSystemPrompt", "pythonRefineOutputInstruction",
            "orchestratorPlanningSystemPrompt", "orchestratorSummarySystemPrompt", "dagReactSystemPrompt",
            "dagModeGuidancePrompt", "dagRecoveryJudgeSystemPromptTemplate", "planningStructuredStage",
            "planningLinearModeGuidance", "planningDagModeGuidance", "planningLinearConstraint",
            "emptyOutputRecoveryStageInstruction", "toolSummarySystemPrompt");

    private static final Map<String, String> DOWNSTREAM_TEMPLATES = Map.of(
            "agentRunSystemPrompt", "reactSystemPrompt进入LC4j systemMessageProvider，prepareSystemMessage会执行PromptTemplate.apply",
            "planningLinearModeGuidance", "作为modeGuidance进入planningTodosStageInstruction，既有逐项String.replace可能再次处理插入内容",
            "planningDagModeGuidance", "作为modeGuidance进入planningTodosStageInstruction，既有逐项String.replace可能再次处理插入内容");

    private static final Set<String> NUMERIC_VARIABLES = Set.of("maxChars", "maxTodos", "maxTasks", "maxSubSteps",
            "maxParallelTasks", "maxSubAgents", "candidateIndex", "candidateCount", "maxSteps",
            "strategyMaxDetailLength", "actual", "limit", "ratioPct");

    private PromptBootstrapExporter() {
    }

    public static Manifest exportCurrentProcess() {
        return export(PromptAuthority.shared());
    }

    static Manifest export(PromptAuthority authority) {
        CapturedAuthority captured = authority.captureForExport();
        return new Manifest(1,
                describe(captured, SnapshotKind.CLASSPATH_BASELINE),
                describe(captured, SnapshotKind.PROCESS_EFFECTIVE), coverageGaps());
    }

    private static Snapshot describe(CapturedAuthority captured, SnapshotKind kind) {
        boolean effective = kind == SnapshotKind.PROCESS_EFFECTIVE;
        Map<String, String> texts = effective ? captured.effectiveTexts() : captured.baselineTexts();
        Map<String, String> tools = effective ? captured.effectiveTools() : captured.baselineTools();
        List<String> requirements = effective ? captured.effectiveRequirements() : captured.baselineRequirements();
        String datasetFacts = effective ? captured.effectiveDatasetFactsJson() : captured.baselineDatasetFactsJson();
        List<ResourceDocument> resources = new ArrayList<>();
        List<FragmentDraft> fragments = new ArrayList<>();
        texts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String field = entry.getKey();
            String path = captured.textResources().get(field);
            ResourceKind resourceKind = field.equals("toolCapabilityCatalog")
                    ? ResourceKind.MIXED_TOOL_CAPABILITIES
                    : RAW_FIELDS.contains(field) || RENDER_VARIABLES.containsKey(field)
                    ? ResourceKind.INSTRUCTION_TEXT : ResourceKind.UNCLASSIFIED;
            resources.add(resource(field, path, kind, resourceKind, entry.getValue()));
            if (resourceKind == ResourceKind.MIXED_TOOL_CAPABILITIES) {
                addCapabilities(field, path, entry.getValue(), fragments);
            } else {
                fragments.add(textDraft(field, path, entry.getValue()));
            }
        });
        tools.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String path = entry.getKey().equals("executePython")
                    ? "prompts/python/execute_python_tool_description.txt"
                    : "prompts/tools/descriptions/" + entry.getKey() + ".txt";
            resources.add(resource("toolDescriptions." + entry.getKey(), path, kind,
                    ResourceKind.INSTRUCTION_TEXT, entry.getValue()));
            fragments.add(fixed("tool.description." + entry.getKey(), "TOOL_DESCRIPTION", path,
                    "toolDescriptions." + entry.getKey(), entry.getValue(),
                    "PromptAuthority.requireToolDescription；当前消费者剥离末尾LF，导出保留原始正文"));
        });
        captured.auxiliaryClasspathResources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ResourceKind resourceKind = entry.getKey().equals(REQUIREMENTS) ? ResourceKind.INSTRUCTION_LIST
                    : entry.getKey().equals(DATASET_SPECS) ? ResourceKind.STRUCTURAL_FACTS : ResourceKind.REGISTRATION_INDEX;
            resources.add(resource(entry.getKey(), entry.getKey(), SnapshotKind.CLASSPATH_BASELINE, resourceKind, entry.getValue()));
        });
        for (int i = 0; i < requirements.size(); i++) {
            fragments.add(fixed("pythonRefineRequirements." + i, "PYTHON_REQUIREMENT", REQUIREMENTS,
                    "prompts.pythonRefineRequirements[" + i + "]", requirements.get(i),
                    "快照已解析条目；原读取会trim并滤去空行和注释，原资源另行保留"));
        }
        return new Snapshot(kind, authorityDigest(texts, tools, captured.auxiliaryClasspathResources()),
                "sha256:" + sha256(texts.get("toolCapabilityCatalog")), resources, fragments, datasetFacts);
    }

    private static ResourceDocument resource(String field, String path, SnapshotKind origin,
                                              ResourceKind kind, String body) {
        return new ResourceDocument(field, path, origin, kind, body, sha256(body));
    }

    private static FragmentDraft textDraft(String field, String path, String body) {
        String downstream = DOWNSTREAM_TEMPLATES.get(field);
        if (downstream != null) {
            return downstreamDraft(field, field, path, field, body, downstream);
        }
        if (RAW_FIELDS.contains(field)) {
            return fixed(field, field, path, field, body,
                    "未登记字段自身的业务render键；部分历史specific参数被composeSystemPrompt忽略；只导出登记资源，不据此断言实际进入System");
        }
        Set<String> supplied = RENDER_VARIABLES.get(field);
        if (supplied == null) {
            return new FragmentDraft(field, "UNCONFIRMED_PURPOSE", path, field, body, sha256(body),
                    null, List.of(), false, List.of("尚未核对该字段的消费方法和变量"), "未确认消费位置");
        }
        String evidence = field.equals("followUpSummarySystemPrompt")
                ? "AgentContextCompressor.buildSummarySystemPrompt实际replace键；当前该入口读取classpath基线"
                : "AgentPromptService对应方法实际传入render的键";
        Map<String, VariableDraft> variables = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        int cursor = 0;
        int opening;
        while ((opening = body.indexOf("{{", cursor)) >= 0) {
            int closing = body.indexOf("}}", opening + 2);
            if (closing < 0) {
                pending.add("模板含未闭合的双花括号，需核对原消费行为");
                break;
            }
            String name = body.substring(opening + 2, closing);
            if (supplied.contains(name)) {
                variables.putIfAbsent(name, new VariableDraft(name, true,
                        !NUMERIC_VARIABLES.contains(name), null, VariableStatus.CONFIRMED_RENDER_KEY, evidence));
            } else if (name.equals("...")) {
                variables.putIfAbsent(name, new VariableDraft(name, false, false, "{{...}}",
                        VariableStatus.LITERAL_DEFAULT, "原文用于说明非标准语法，实际render没有此键；默认逐字保留"));
            } else {
                variables.putIfAbsent(name, new VariableDraft(name, null, null, null,
                        VariableStatus.PENDING, "实际消费方法未提供此键，尚不能确认是变量还是字面示例"));
                pending.add("待确认双花括号内容：" + name);
            }
            cursor = closing + 2;
        }
        if (pending.isEmpty()) {
            try {
                PromptTemplateRenderer.validate(PromptBodyType.TEMPLATE, body, variables.values().stream()
                        .map(v -> new PromptVariableDeclaration(v.name(), v.required(), v.allowEmpty(), v.defaultValue()))
                        .toList());
            } catch (IllegalArgumentException | ModelSendBlockedException invalid) {
                pending.add("模板草案未通过声明一致性校验，需人工确认");
            }
        }
        return new FragmentDraft(field, field, path, field, body, sha256(body),
                PromptBodyType.TEMPLATE, List.copyOf(variables.values()), pending.isEmpty(), pending, evidence);
    }

    private static FragmentDraft fixed(String fragment, String purpose, String path, String location,
                                        String body, String consumer) {
        return new FragmentDraft(fragment, purpose, path, location, body, sha256(body),
                PromptBodyType.FIXED, List.of(), true, List.of(), consumer);
    }

    private static FragmentDraft downstreamDraft(String fragment, String purpose, String path, String location,
                                                  String body, String consumer) {
        if (!body.contains("{{")) {
            return fixed(fragment, purpose, path, location, body, consumer + "；本版没有双花括号标记");
        }
        Map<String, VariableDraft> variables = new LinkedHashMap<>();
        int cursor = 0;
        int opening;
        while ((opening = body.indexOf("{{", cursor)) >= 0) {
            int closing = body.indexOf("}}", opening + 2);
            if (closing < 0) {
                break;
            }
            String name = body.substring(opening + 2, closing);
            variables.putIfAbsent(name, new VariableDraft(name, null, null, null, VariableStatus.PENDING,
                    "下游模板消费尚未确认真实接口变量或替换顺序，不生成必需项或字面默认"));
            cursor = closing + 2;
        }
        return new FragmentDraft(fragment, purpose, path, location, body, sha256(body), null,
                List.copyOf(variables.values()), false,
                List.of("正文会继续参与下游模板消费；双花括号规则待确认，禁止直接导入"), consumer);
    }

    private static void addCapabilities(String field, String path, String body, List<FragmentDraft> fragments) {
        try {
            JsonNode catalog = JSON.readTree(body);
            if (catalog == null || !catalog.isObject()) {
                throw new IllegalArgumentException("能力目录不是对象");
            }
            catalog.fields().forEachRemaining(entry -> {
                String pointer = "/" + entry.getKey().replace("~", "~0").replace("/", "~1");
                if (entry.getValue().isTextual() && !entry.getValue().textValue().isBlank()) {
                    fragments.add(downstreamDraft("tool.capability." + entry.getKey(), "TOOL_CAPABILITY", path,
                            pointer, entry.getValue().textValue(),
                            "renderToolCapabilities的自然语言值作为toolCapabilities进入strategy模板；既有逐项String.replace可能再次处理插入内容"));
                } else {
                    String raw = entry.getValue().toString();
                    fragments.add(new FragmentDraft("tool.capability." + entry.getKey(), "UNCONFIRMED_PURPOSE",
                            path, pointer, raw, sha256(raw), null, List.of(), false,
                            List.of("能力项不是非空说明文本；需核对消费转换，不能把结构值直接作为指令导入"),
                            "AgentPromptService.toolCapabilityCatalog"));
                }
            });
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            fragments.add(new FragmentDraft(field, "UNCONFIRMED_PURPOSE", path, field, body, sha256(body),
                    null, List.of(), false, List.of("能力目录无法按对象分类，需核对原消费转换"),
                    "AgentPromptService.toolCapabilityCatalog"));
        }
    }

    /** 与Authority现有指纹使用相同规则，但只使用此次已经捕获的数据。 */
    private static String authorityDigest(Map<String, String> texts, Map<String, String> tools,
                                           Map<String, String> auxiliary) {
        StringBuilder canonical = new StringBuilder();
        texts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                canonical.append(entry.getKey()).append('\n').append(entry.getValue()).append('\n'));
        canonical.append(REQUIREMENTS).append('\n').append(auxiliary.get(REQUIREMENTS)).append('\n')
                .append(DATASET_SPECS).append('\n').append(auxiliary.get(DATASET_SPECS));
        tools.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                canonical.append("toolDescription:").append(entry.getKey()).append('\n')
                        .append(entry.getValue()).append('\n'));
        return "sha256:" + sha256(canonical.toString());
    }

    private static String sha256(String body) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(body));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encoded);
            return HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("待导出正文无法无损编码成UTF-8");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256", impossible);
        }
    }

    private static List<CoverageGap> coverageGaps() {
        return List.of(
                new CoverageGap("GUIDES", List.of("agent_guides/python_sandbox.md", "agent_guides/dataset_manifest.md",
                        "agent_guides/execute_python_tips.md", "agent_guides/advanced_market_data.md"),
                        "四份随包指南及/data/agent_guides覆盖未登记在Authority，需要另外捕获实际读取来源"),
                new CoverageGap("JAVA_INSTRUCTIONS", List.of("AgentPromptService.composeSystemPrompt",
                        "AgentPromptService.firstNonBlank", "AgentPromptService.pythonRefineDatasetFieldGuide",
                        "GlobalUserProfileInjector", "ToolRouterToolExecutor", "StructuredPlanningSupport", "ToolCatalogBuilder"),
                        "Java包装、纠正建议及参数说明不在本次资源快照内；清单不宣称穷尽散落指令"),
                new CoverageGap("UNREGISTERED_RESOURCES", List.of("prompts/search/market_news_query.txt",
                        "prompts/todo/dag_react_system_default.txt"), "这两个随包资源未进入Authority登记，未作为本次快照正文导出"),
                new CoverageGap("BASELINE_CONSUMER", List.of("AgentContextCompressor.buildSummarySystemPrompt"),
                        "该入口当前直接读取classpath基线，Authority进程生效副本不等于所有消费者已经使用该副本"));
    }

    public enum SnapshotKind { CLASSPATH_BASELINE, PROCESS_EFFECTIVE }
    public enum ResourceKind { INSTRUCTION_TEXT, INSTRUCTION_LIST, MIXED_TOOL_CAPABILITIES, STRUCTURAL_FACTS, REGISTRATION_INDEX, UNCLASSIFIED }
    public enum VariableStatus { CONFIRMED_RENDER_KEY, LITERAL_DEFAULT, PENDING }

    public record Manifest(int schemaVersion, Snapshot classpathBaseline, Snapshot processEffective,
                            List<CoverageGap> coverageGaps) {
        public Manifest { coverageGaps = List.copyOf(coverageGaps); }
    }

    public record Snapshot(SnapshotKind kind, String authorityBundleDigest, String capabilityCatalogDigest,
                            List<ResourceDocument> resources, List<FragmentDraft> fragments,
                            String capturedDatasetFactsJson) {
        public Snapshot { resources = List.copyOf(resources); fragments = List.copyOf(fragments); }
    }

    /** resourcePath是原登记资源定位；进程覆盖的文件路径并未由Authority保存，不在此推断。 */
    public record ResourceDocument(String authorityField, String resourcePath, SnapshotKind origin,
                                     ResourceKind kind, String rawBody, String utf8Sha256) {
        @Override public String toString() { return "ResourceDocument[resource=" + resourcePath + ", kind=" + kind + "]"; }
    }

    public record FragmentDraft(String fragment, String purpose, String resourcePath, String sourceLocation,
                                  String body, String utf8Sha256, PromptBodyType bodyType,
                                  List<VariableDraft> variables, boolean directImportAllowed,
                                  List<String> pendingReasons, String consumerEvidence) {
        public FragmentDraft { variables = List.copyOf(variables); pendingReasons = List.copyOf(pendingReasons); }
        @Override public String toString() { return "FragmentDraft[fragment=" + fragment + ", importAllowed=" + directImportAllowed + "]"; }
    }

    public record VariableDraft(String name, Boolean required, Boolean allowEmpty, String defaultValue,
                                  VariableStatus status, String evidence) { }

    public record CoverageGap(String scope, List<String> locations, String reason) {
        public CoverageGap { locations = List.copyOf(locations); }
    }

    record CapturedAuthority(Map<String, String> textResources, Map<String, String> effectiveTexts,
                               Map<String, String> effectiveTools, Map<String, String> baselineTexts,
                               Map<String, String> baselineTools, Map<String, String> auxiliaryClasspathResources,
                               List<String> effectiveRequirements, List<String> baselineRequirements,
                               String effectiveDatasetFactsJson, String baselineDatasetFactsJson) {
        CapturedAuthority {
            textResources = Map.copyOf(textResources);
            effectiveTexts = Map.copyOf(effectiveTexts);
            effectiveTools = Map.copyOf(effectiveTools);
            baselineTexts = Map.copyOf(baselineTexts);
            baselineTools = Map.copyOf(baselineTools);
            auxiliaryClasspathResources = Map.copyOf(auxiliaryClasspathResources);
            effectiveRequirements = List.copyOf(effectiveRequirements);
            baselineRequirements = List.copyOf(baselineRequirements);
        }
    }
}
