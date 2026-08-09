package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.alphafrogmicro.common.config.ConfigLoadStateReporter;
import world.willfrog.alphafrogmicro.common.utils.PlaceholderResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 本地 LLM 配置热加载器 —— Nacos 推送的 {@code agent-llm.local.json} 通过此组件加载到内存。
 *
 * <h2>加载机制</h2>
 * <ol>
 *   <li>启动时通过 {@link #load()} 首次加载配置文件（{@code agent.llm.config-file} 指定的路径）</li>
 *   <li>每 10s（可配）通过 {@code @Scheduled} 轮询文件最后修改时间，
 *       有变化时重新解析 JSON → 替换内存中的 {@link AgentLlmProperties} 实例</li>
 *   <li>Nacos→文件→轮询→解析→原子替换，全程不需要重启服务</li>
 * </ol>
 *
 * <h2>{@code file:} 前缀解析</h2>
 * <p>配置中像 {@code "agentRunSystemPrompt": "file:prompts/agent/agent_run_system.txt"} 的字段，
 * 会在首次加载和每次重载时解析为文件内容并内联到配置对象中。
 * 引用的 prompt 文件也参与变更检测——prompt 文件改了同样触发重载。</p>
 *
 * <h2>面试常考点</h2>
 * <ul>
 *   <li>"配置怎么热更新？"→ Nacos 写文件 → 10s 轮询 → 检测配置/Prompt 文件修改时间 → 原子替换</li>
 *   <li>"为什么不用 Redis pub/sub？"→ 文件轮询更简单，Nacos 本身负责把配置分发到文件，
 *       不需要额外引入消息通道</li>
 *   <li>"热加载失败怎么办？"→ 沿用上一次成功加载的配置，不影响正在运行的 agent</li>
 * </ul>
 *
 * @see AgentPromptService#currentPrompts() 消费方
 * @see AgentLlmProperties 配置结构定义
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLlmLocalConfigLoader {

    private final ObjectMapper objectMapper;

    @Value("${agent.llm.config-file:}")
    private String configFile;

    @Value("${agent.llm.prompt-base-dir:}")
    private String promptBaseDir;

    @Value("${spring.application.name:agent-platform}")
    private String serviceName;

    @Value("${spring.application.instance-id:${HOSTNAME:unknown}}")
    private String instanceId;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private volatile String loadedConfigPath = "";
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private volatile byte[] loadedConfigBytes = new byte[0];
    private volatile Map<String, Long> loadedPromptFileModifiedTimes = new LinkedHashMap<>();
    /**
     * 本地配置 + 显式顶层节的原子快照：单次 volatile 读同时拿到两者，
     * 避免 refresh 窗口内旧 config 与新 sections 错配。
     */
    private volatile LocalConfigSnapshot localSnapshot = LocalConfigSnapshot.empty();
    private final Object reloadLock = new Object();
    private final AtomicLong promptReloadFailureCount = new AtomicLong();

    private static final String FILE_PREFIX = "file:";
    private static final String FILE_PREFIX_ALT = "file://";
    private static final String FILE_PREFIX_AT = "@file:";

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${agent.llm.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    private void reloadIfNeeded(boolean force) {
        String file = configFile == null ? "" : configFile.trim();
        if (file.isEmpty()) {
            if (force) {
                log.info("agent.llm.config-file is empty, skip local llm config loading");
            }
            clearLocalConfigIfPresent("agent.llm.config-file is empty");
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                if (force && this.localSnapshot.config() == null) {
                    log.info("Local llm config file not found, skip: {}", path);
                } else if (this.localSnapshot.config() != null) {
                    markPromptReloadFailure("config_file_missing");
                    log.error("Local llm config file disappeared; retaining last valid snapshot: {}", path);
                }
                return;
            }
            try {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                String normalizedPath = path.toString();
                boolean unchanged = normalizedPath.equals(loadedConfigPath) && currentModified == loadedConfigLastModified;
                if (!force && unchanged && !promptFilesChanged()) {
                    reportState(loadedConfigBytes);
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] bytes = in.readAllBytes();
                    JsonNode tree = objectMapper.readTree(bytes);
                    tree = preprocessAliasTree(tree);
                    java.util.Set<String> topLevelSections = new java.util.LinkedHashSet<>();
                    if (tree.isObject()) {
                        tree.fieldNames().forEachRemaining(topLevelSections::add);
                    }
                    Set<String> explicitPromptFields = explicitPromptFields(tree);
                    AgentLlmProperties parsed = objectMapper.treeToValue(tree, AgentLlmProperties.class);
                    PlaceholderResolver.resolve(parsed);
                    AgentLlmProperties sanitized = sanitize(parsed);
                    Map<String, Long> promptFileTimes = resolvePromptFiles(
                            sanitized, resolvePromptBaseDir(path), explicitPromptFields);
                    this.localSnapshot = new LocalConfigSnapshot(
                            sanitized, java.util.Collections.unmodifiableSet(topLevelSections));
                    this.loadedConfigPath = normalizedPath;
                    this.loadedConfigLastModified = currentModified;
                    this.loadedConfigBytes = bytes;
                    this.loadedPromptFileModifiedTimes = promptFileTimes;
                    reportState(bytes);
                    // 计算从 endpoints 中收集的模型数量
                    int endpointModels = 0;
                    if (sanitized.getEndpoints() != null) {
                        for (AgentLlmProperties.Endpoint endpoint : sanitized.getEndpoints().values()) {
                            if (endpoint != null && endpoint.getModels() != null) {
                                endpointModels += endpoint.getModels().size();
                            }
                        }
                    }
                    log.info("Loaded local llm config from {} (endpoints={}, topLevelModels={}, endpointModels={})",
                            path,
                            sanitized.getEndpoints().size(),
                            sanitized.getModels().size(),
                            endpointModels);
                }
            } catch (PromptConfigurationException e) {
                markPromptReloadFailure(e.reason());
                log.error("Rejected local llm prompt projection from {}; retaining last valid snapshot: {}",
                        path, e.getMessage());
            } catch (IOException e) {
                markPromptReloadFailure("config_read_or_parse_failed");
                log.error("Failed to load local llm config from {}", path, e);
            }
        }
    }

    private Set<String> explicitPromptFields(JsonNode tree) {
        if (tree == null || !tree.isObject() || !tree.path("prompts").isObject()) {
            return Set.of();
        }
        Set<String> fields = new java.util.LinkedHashSet<>();
        tree.path("prompts").fieldNames().forEachRemaining(fields::add);
        return java.util.Collections.unmodifiableSet(fields);
    }

    private void markPromptReloadFailure(String reason) {
        promptReloadFailureCount.incrementAndGet();
        if (meterRegistry != null) {
            Counter.builder("agent.prompt.config.reload.failures")
                    .description("被拒绝的 Prompt 配置刷新次数")
                    .tag("reason", hasText(reason) ? reason : "unknown")
                    .register(meterRegistry)
                    .increment();
        }
    }

    long promptReloadFailureCount() {
        return promptReloadFailureCount.get();
    }

    private void reportState(byte[] contentBytes) {
        ConfigLoadStateReporter.report(redisTemplate, serviceName, instanceId,
                "agent-llm.json", loadedConfigPath, contentBytes);
    }

    /**
     * 在 Jackson 反序列化前预处理 JSON 树，支持跨层别名映射。
     *
     * <p>T6 canonical 字段与历史/兼容性别名的映射关系如下（canonical 优先）：</p>
     * <ul>
     *   <li>{@code "tool"} 别名 → canonical {@code "tools"}</li>
     *   <li>{@code "agent.llm.request"} 别名 → canonical {@code "runtime.request"}</li>
     * </ul>
     *
     * <p>当 canonical 字段已存在时，alias 只被移除而不会被覆盖，确保配置语义以 canonical 为准。
     * 例如同时出现 {@code "agent.llm.request.maxRetries": 2} 和
     * {@code "runtime.request.maxRetries": 3} 时，最终生效值为 {@code 3}。</p>
     */
    private JsonNode preprocessAliasTree(JsonNode root) {
        if (root == null || !root.isObject()) {
            return root;
        }
        ObjectNode obj = (ObjectNode) root;

        // tool -> tools
        if (obj.has("tool") && !obj.has("tools")) {
            obj.set("tools", obj.remove("tool"));
        }

        // agent.llm.request -> runtime.request
        JsonNode agentNode = obj.path("agent");
        if (agentNode.isObject()) {
            ObjectNode agentObj = (ObjectNode) agentNode;
            JsonNode llmNode = agentObj.path("llm");
            if (llmNode.isObject()) {
                ObjectNode llmObj = (ObjectNode) llmNode;
                if (llmObj.has("request")) {
                    if (!runtimeHasRequest(obj)) {
                        ObjectNode runtimeObj = obj.has("runtime") && obj.path("runtime").isObject()
                                ? (ObjectNode) obj.path("runtime")
                                : obj.putObject("runtime");
                        runtimeObj.set("request", llmObj.path("request"));
                    }
                    // 无论是否发生移动，agent.llm 都不是合法路径，移除以避免 Jackson 未知字段失败
                    agentObj.remove("llm");
                }
            }
        }

        return obj;
    }

    private boolean runtimeHasRequest(ObjectNode root) {
        JsonNode runtime = root.path("runtime");
        return runtime.isObject() && runtime.has("request");
    }

    /**
     * 获取当前内存中已加载的 LLM 配置（热加载生效后的最新版本）。
     * 如果配置文件不存在或尚未加载成功，返回 {@code Optional.empty()}，
     * 调用方会 fallback 到 Spring Boot 静态配置（{@code application.yml}）。
     *
     * @return 当前生效的配置，可能为 empty
     */
    public Optional<AgentLlmProperties> current() {
        return Optional.ofNullable(localSnapshot.config());
    }

    /**
     * 当前本地配置的原子快照（config 与显式顶层节同源、单次 volatile 读取）。
     * 需要同时判断节存在性与读取配置的调用方必须用它，而不是拼接多个 accessor。
     */
    public LocalConfigSnapshot currentSnapshot() {
        return localSnapshot;
    }

    /**
     * 当前本地配置 JSON 是否显式包含指定顶层节（别名预处理后）。
     *
     * <p>用于区分"本地文件没有配置该节"与"该节字段恰好等于默认值"：只有前者才应回退到
     * Spring/Nacos 静态配置，后者必须尊重本地显式取值（例如显式 {@code "enabled": false}）。</p>
     */
    public boolean currentHasTopLevelSection(String section) {
        return section != null && localSnapshot.topLevelSections().contains(section);
    }

    /** 本地配置与显式顶层节的不可变快照（单 volatile 承载，保证读取原子性）。 */
    public record LocalConfigSnapshot(AgentLlmProperties config, java.util.Set<String> topLevelSections) {
        static LocalConfigSnapshot empty() {
            return new LocalConfigSnapshot(null, java.util.Set.of());
        }
    }

    private boolean promptFilesChanged() {
        if (loadedPromptFileModifiedTimes == null || loadedPromptFileModifiedTimes.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Long> entry : loadedPromptFileModifiedTimes.entrySet()) {
            Path filePath = Paths.get(entry.getKey());
            if (!Files.exists(filePath)) {
                return true;
            }
            try {
                long lastModified = Files.getLastModifiedTime(filePath).toMillis();
                if (lastModified != entry.getValue()) {
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    private void clearLocalConfigIfPresent(String reason) {
        synchronized (reloadLock) {
            if (this.localSnapshot.config() != null) {
                this.localSnapshot = LocalConfigSnapshot.empty();
                this.loadedConfigPath = "";
                this.loadedConfigLastModified = Long.MIN_VALUE;
                this.loadedPromptFileModifiedTimes = new LinkedHashMap<>();
                log.warn("Local llm config cleared: {}", reason);
            }
        }
    }

    private Path resolvePromptBaseDir(Path configPath) {
        if (hasText(promptBaseDir)) {
            return Paths.get(promptBaseDir).toAbsolutePath().normalize();
        }
        return configPath == null ? null : configPath.getParent();
    }

    private Map<String, Long> resolvePromptFiles(AgentLlmProperties cfg,
                                                  Path baseDir,
                                                  Set<String> explicitFields) {
        Map<String, Long> fileTimes = new LinkedHashMap<>();
        if (cfg == null || cfg.getPrompts() == null || baseDir == null || explicitFields.isEmpty()) {
            return fileTimes;
        }
        AgentLlmProperties.Prompts prompts = cfg.getPrompts();
        PromptAuthority authority = PromptAuthority.shared();

        resolveDirect("agentRunSystemPrompt", prompts::getAgentRunSystemPrompt,
                prompts::setAgentRunSystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("todoPlannerSystemPromptTemplate", prompts::getTodoPlannerSystemPromptTemplate,
                prompts::setTodoPlannerSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("workflowFinalSystemPrompt", prompts::getWorkflowFinalSystemPrompt,
                prompts::setWorkflowFinalSystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("workflowTodoRecoverySystemPrompt", prompts::getWorkflowTodoRecoverySystemPrompt,
                prompts::setWorkflowTodoRecoverySystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("parallelPlannerSystemPromptTemplate", prompts::getParallelPlannerSystemPromptTemplate,
                prompts::setParallelPlannerSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("parallelFinalSystemPrompt", prompts::getParallelFinalSystemPrompt,
                prompts::setParallelFinalSystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("parallelPatchPlannerSystemPromptTemplate", prompts::getParallelPatchPlannerSystemPromptTemplate,
                prompts::setParallelPatchPlannerSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planJudgeSystemPromptTemplate", prompts::getPlanJudgeSystemPromptTemplate,
                prompts::setPlanJudgeSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planJudgeRuntimeSystemPromptTemplate", prompts::getPlanJudgeRuntimeSystemPromptTemplate,
                prompts::setPlanJudgeRuntimeSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("semanticJudgeSystemPromptTemplate", prompts::getSemanticJudgeSystemPromptTemplate,
                prompts::setSemanticJudgeSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("subAgentPlannerSystemPromptTemplate", prompts::getSubAgentPlannerSystemPromptTemplate,
                prompts::setSubAgentPlannerSystemPromptTemplate, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("subAgentSummarySystemPrompt", prompts::getSubAgentSummarySystemPrompt,
                prompts::setSubAgentSummarySystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("pythonRefineSystemPrompt", prompts::getPythonRefineSystemPrompt,
                prompts::setPythonRefineSystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("pythonRefineOutputInstruction", prompts::getPythonRefineOutputInstruction,
                prompts::setPythonRefineOutputInstruction, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("orchestratorPlanningSystemPrompt", prompts::getOrchestratorPlanningSystemPrompt,
                prompts::setOrchestratorPlanningSystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("orchestratorSummarySystemPrompt", prompts::getOrchestratorSummarySystemPrompt,
                prompts::setOrchestratorSummarySystemPrompt, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planningAnalysisStage", prompts::getPlanningAnalysisStage,
                prompts::setPlanningAnalysisStage, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planningStructuredStage", prompts::getPlanningStructuredStage,
                prompts::setPlanningStructuredStage, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planningLinearModeGuidance", prompts::getPlanningLinearModeGuidance,
                prompts::setPlanningLinearModeGuidance, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planningDagModeGuidance", prompts::getPlanningDagModeGuidance,
                prompts::setPlanningDagModeGuidance, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("planningLinearConstraint", prompts::getPlanningLinearConstraint,
                prompts::setPlanningLinearConstraint, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("pythonRepairStageInstruction", prompts::getPythonRepairStageInstruction,
                prompts::setPythonRepairStageInstruction, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("emptyOutputRecoveryStageInstruction", prompts::getEmptyOutputRecoveryStageInstruction,
                prompts::setEmptyOutputRecoveryStageInstruction, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("budgetLastMileStageInstruction", prompts::getBudgetLastMileStageInstruction,
                prompts::setBudgetLastMileStageInstruction, explicitFields, baseDir, fileTimes, authority);
        resolveDirect("toolCapabilityCatalog", prompts::getToolCapabilityCatalog,
                prompts::setToolCapabilityCatalog, explicitFields, baseDir, fileTimes, authority);

        resolvePaired("dagModeGuidancePrompt", prompts::getDagModeGuidancePrompt,
                prompts::setDagModeGuidancePrompt, "dagModeGuidancePromptFile",
                prompts::getDagModeGuidancePromptFile, explicitFields, baseDir, fileTimes, authority);
        resolvePaired("dagReactSystemPrompt", prompts::getDagReactSystemPrompt,
                prompts::setDagReactSystemPrompt, "dagReactSystemPromptFile",
                prompts::getDagReactSystemPromptFile, explicitFields, baseDir, fileTimes, authority);
        resolvePaired("dagRecoveryJudgeSystemPromptTemplate", prompts::getDagRecoveryJudgeSystemPromptTemplate,
                prompts::setDagRecoveryJudgeSystemPromptTemplate, "dagRecoveryJudgeSystemPromptFile",
                prompts::getDagRecoveryJudgeSystemPromptFile, explicitFields, baseDir, fileTimes, authority);
        resolvePaired("financeMethodResolverSystemPrompt", prompts::getFinanceMethodResolverSystemPrompt,
                prompts::setFinanceMethodResolverSystemPrompt, "financeMethodResolverSystemPromptFile",
                prompts::getFinanceMethodResolverSystemPromptFile, explicitFields, baseDir, fileTimes, authority);
        resolvePaired("planningStrategyStage", prompts::getPlanningStrategyStage,
                prompts::setPlanningStrategyStage, "planningStrategyStageFile",
                prompts::getPlanningStrategyStageFile, explicitFields, baseDir, fileTimes, authority);
        resolvePaired("planningTodosStage", prompts::getPlanningTodosStage,
                prompts::setPlanningTodosStage, "planningTodosStageFile",
                prompts::getPlanningTodosStageFile, explicitFields, baseDir, fileTimes, authority);

        resolveRequirements(prompts, explicitFields, baseDir, fileTimes, authority);
        resolveDatasetSpecs(prompts, explicitFields, baseDir, fileTimes, authority);

        return fileTimes;
    }

    private void resolveDirect(String fieldName,
                               Supplier<String> getter,
                               Consumer<String> setter,
                               Set<String> explicitFields,
                               Path baseDir,
                               Map<String, Long> fileTimes,
                               PromptAuthority authority) {
        if (!explicitFields.contains(fieldName)) {
            return;
        }
        String resolved = resolvePromptTextRequired(fieldName, getter.get(), baseDir, fileTimes);
        authority.validateText(fieldName, resolved, "local prompt projection");
        setter.accept(resolved);
    }

    private void resolvePaired(String bodyField,
                               Supplier<String> bodyGetter,
                               Consumer<String> bodySetter,
                               String fileField,
                               Supplier<String> fileGetter,
                               Set<String> explicitFields,
                               Path baseDir,
                               Map<String, Long> fileTimes,
                               PromptAuthority authority) {
        boolean hasBody = explicitFields.contains(bodyField) && hasText(bodyGetter.get());
        boolean hasFile = explicitFields.contains(fileField) && hasText(fileGetter.get());
        if (hasBody && hasFile) {
            throw new PromptConfigurationException(
                    "ambiguous_body_and_file", bodyField + " 与 " + fileField + " 不能同时配置非空值");
        }
        if (hasBody) {
            String resolved = resolvePromptTextRequired(bodyField, bodyGetter.get(), baseDir, fileTimes);
            authority.validateText(bodyField, resolved, "local prompt projection");
            bodySetter.accept(resolved);
            return;
        }
        if (hasFile) {
            String resolved = readPromptFileRequired(fileField, fileGetter.get(), baseDir, fileTimes);
            authority.validateText(bodyField, resolved, "local prompt projection");
            bodySetter.accept(resolved);
            return;
        }
        if (explicitFields.contains(bodyField)) {
            resolvePromptTextRequired(bodyField, bodyGetter.get(), baseDir, fileTimes);
        }
        if (explicitFields.contains(fileField)) {
            readPromptFileRequired(fileField, fileGetter.get(), baseDir, fileTimes);
        }
    }

    private String resolvePromptTextRequired(String fieldName,
                                             String value,
                                             Path baseDir,
                                             Map<String, Long> fileTimes) {
        if (!hasText(value)) {
            throw new PromptConfigurationException("blank", fieldName + " 为空");
        }
        String raw = value.trim();
        String pathRef = stripFilePrefix(raw);
        if (pathRef == null) {
            return value;
        }
        return readPromptFileRequired(fieldName, pathRef, baseDir, fileTimes);
    }

    private String readPromptFileRequired(String fieldName,
                                          String fileRef,
                                          Path baseDir,
                                          Map<String, Long> fileTimes) {
        Path filePath = resolveFilePath(stripFilePrefixOrSelf(fileRef), baseDir);
        if (filePath == null) {
            throw new PromptConfigurationException("file_path_blank", fieldName + " 的文件路径为空");
        }
        try {
            recordFileModifiedTime(filePath, fileTimes);
            String text = Files.readString(filePath, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new PromptConfigurationException("file_blank", fieldName + " 的文件内容为空: " + filePath);
            }
            return text;
        } catch (PromptConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new PromptConfigurationException(
                    "file_read_failed", fieldName + " 的文件读取失败: " + filePath + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void resolveRequirements(AgentLlmProperties.Prompts prompts,
                                     Set<String> explicitFields,
                                     Path baseDir,
                                     Map<String, Long> fileTimes,
                                     PromptAuthority authority) {
        boolean hasBody = explicitFields.contains("pythonRefineRequirements")
                && prompts.getPythonRefineRequirements() != null
                && !prompts.getPythonRefineRequirements().isEmpty();
        boolean hasFile = explicitFields.contains("pythonRefineRequirementsFile")
                && hasText(prompts.getPythonRefineRequirementsFile());
        if (hasBody && hasFile) {
            throw new PromptConfigurationException(
                    "ambiguous_body_and_file", "pythonRefineRequirements 与 pythonRefineRequirementsFile 不能同时配置");
        }
        if (hasBody) {
            authority.validateRequirements(prompts.getPythonRefineRequirements(), "local prompt projection");
        } else if (hasFile) {
            List<String> requirements = readPromptLinesRequired(
                    "pythonRefineRequirementsFile", prompts.getPythonRefineRequirementsFile(), baseDir, fileTimes);
            authority.validateRequirements(requirements, "local prompt projection");
            prompts.setPythonRefineRequirements(requirements);
        } else if (explicitFields.contains("pythonRefineRequirements")) {
            authority.validateRequirements(prompts.getPythonRefineRequirements(), "local prompt projection");
        } else if (explicitFields.contains("pythonRefineRequirementsFile")) {
            readPromptLinesRequired(
                    "pythonRefineRequirementsFile", prompts.getPythonRefineRequirementsFile(), baseDir, fileTimes);
        }
    }

    private List<String> readPromptLinesRequired(String fieldName,
                                                 String fileRef,
                                                 Path baseDir,
                                                 Map<String, Long> fileTimes) {
        Path filePath = resolveFilePath(stripFilePrefixOrSelf(fileRef), baseDir);
        if (filePath == null) {
            throw new PromptConfigurationException("file_path_blank", fieldName + " 的文件路径为空");
        }
        try {
            recordFileModifiedTime(filePath, fileTimes);
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                out.add(trimmed);
            }
            if (out.isEmpty()) {
                throw new PromptConfigurationException("file_blank", fieldName + " 的文件没有有效条目: " + filePath);
            }
            return out;
        } catch (PromptConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new PromptConfigurationException(
                    "file_read_failed", fieldName + " 的文件读取失败: " + filePath + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void resolveDatasetSpecs(AgentLlmProperties.Prompts prompts,
                                     Set<String> explicitFields,
                                     Path baseDir,
                                     Map<String, Long> fileTimes,
                                     PromptAuthority authority) {
        boolean hasBody = explicitFields.contains("datasetFieldSpecs")
                && prompts.getDatasetFieldSpecs() != null
                && !prompts.getDatasetFieldSpecs().isEmpty();
        boolean hasFile = explicitFields.contains("datasetFieldSpecsFile")
                && hasText(prompts.getDatasetFieldSpecsFile());
        if (hasBody && hasFile) {
            throw new PromptConfigurationException(
                    "ambiguous_body_and_file", "datasetFieldSpecs 与 datasetFieldSpecsFile 不能同时配置");
        }
        if (hasBody) {
            authority.validateDatasetFieldSpecs(prompts.getDatasetFieldSpecs(), "local prompt projection");
        } else if (hasFile) {
            List<AgentLlmProperties.DatasetFieldSpec> specs = readDatasetFieldSpecsRequired(
                    "datasetFieldSpecsFile", prompts.getDatasetFieldSpecsFile(), baseDir, fileTimes);
            authority.validateDatasetFieldSpecs(specs, "local prompt projection");
            prompts.setDatasetFieldSpecs(specs);
        } else if (explicitFields.contains("datasetFieldSpecs")) {
            authority.validateDatasetFieldSpecs(prompts.getDatasetFieldSpecs(), "local prompt projection");
        } else if (explicitFields.contains("datasetFieldSpecsFile")) {
            readDatasetFieldSpecsRequired(
                    "datasetFieldSpecsFile", prompts.getDatasetFieldSpecsFile(), baseDir, fileTimes);
        }
    }

    private List<AgentLlmProperties.DatasetFieldSpec> readDatasetFieldSpecsRequired(String fieldName,
                                                                                     String fileRef,
                                                                                     Path baseDir,
                                                                                     Map<String, Long> fileTimes) {
        Path filePath = resolveFilePath(stripFilePrefixOrSelf(fileRef), baseDir);
        if (filePath == null) {
            throw new PromptConfigurationException("file_path_blank", fieldName + " 的文件路径为空");
        }
        try (InputStream in = Files.newInputStream(filePath)) {
            recordFileModifiedTime(filePath, fileTimes);
            List<AgentLlmProperties.DatasetFieldSpec> specs = objectMapper.readValue(
                    in, new TypeReference<List<AgentLlmProperties.DatasetFieldSpec>>() { });
            if (specs == null || specs.isEmpty()) {
                throw new PromptConfigurationException("file_blank", fieldName + " 的文件没有字段定义: " + filePath);
            }
            return specs;
        } catch (PromptConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new PromptConfigurationException(
                    "file_read_failed", fieldName + " 的文件读取失败: " + filePath + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private void recordFileModifiedTime(Path filePath, Map<String, Long> fileTimes) {
        try {
            fileTimes.put(filePath.toString(), Files.getLastModifiedTime(filePath).toMillis());
        } catch (Exception e) {
            fileTimes.put(filePath.toString(), -1L);
        }
    }

    private Path resolveFilePath(String pathRef, Path baseDir) {
        if (!hasText(pathRef)) {
            return null;
        }
        Path path = Paths.get(pathRef.trim());
        if (!path.isAbsolute()) {
            path = baseDir.resolve(pathRef.trim());
        }
        return path.toAbsolutePath().normalize();
    }

    private String stripFilePrefix(String text) {
        if (!hasText(text)) {
            return null;
        }
        String raw = text.trim();
        if (raw.startsWith(FILE_PREFIX_ALT)) {
            return raw.substring(FILE_PREFIX_ALT.length()).trim();
        }
        if (raw.startsWith(FILE_PREFIX)) {
            return raw.substring(FILE_PREFIX.length()).trim();
        }
        if (raw.startsWith(FILE_PREFIX_AT)) {
            return raw.substring(FILE_PREFIX_AT.length()).trim();
        }
        return null;
    }

    private String stripFilePrefixOrSelf(String text) {
        String stripped = stripFilePrefix(text);
        return stripped == null ? text : stripped;
    }

    private boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private AgentLlmProperties sanitize(AgentLlmProperties input) {
        AgentLlmProperties cfg = input == null ? new AgentLlmProperties() : input;
        if (cfg.getEndpoints() == null) {
            cfg.setEndpoints(null);
        } else {
            for (AgentLlmProperties.Endpoint endpoint : cfg.getEndpoints().values()) {
                if (endpoint == null) {
                    continue;
                }
                if (endpoint.getModels() == null) {
                    endpoint.setModels(null);
                    continue;
                }
                for (AgentLlmProperties.ModelMetadata metadata : endpoint.getModels().values()) {
                    if (metadata == null) {
                        continue;
                    }
                    if (metadata.getFeatures() == null) {
                        metadata.setFeatures(null);
                    }
                    if (metadata.getValidProviders() == null) {
                        metadata.setValidProviders(null);
                    }
                }
            }
        }
        if (cfg.getModels() == null) {
            cfg.setModels(null);
        }
        if (cfg.getPrompts() == null) {
            cfg.setPrompts(null);
        }
        if (cfg.getRuntime() == null) {
            cfg.setRuntime(null);
        } else {
            sanitizeRuntime(cfg.getRuntime());
        }
        if (cfg.getRuntime() != null && cfg.getRuntime().getMultiTurn() == null) {
            cfg.getRuntime().setMultiTurn(null);
        }
        if (cfg.getRuntime().getMultiTurn().getCompression() == null) {
            cfg.getRuntime().getMultiTurn().setCompression(null);
        } else if (cfg.getRuntime().getMultiTurn().getCompression().getSummaryProviderOrder() == null) {
            cfg.getRuntime().getMultiTurn().getCompression().setSummaryProviderOrder(null);
        }
        if (cfg.getRuntime().getResume() == null) {
            cfg.getRuntime().setResume(null);
        }
        if (cfg.getRuntime().getCache() == null) {
            cfg.getRuntime().setCache(null);
        }
        if (cfg.getRuntime().getPlanning() == null) {
            cfg.getRuntime().setPlanning(null);
        }
        if (cfg.getRuntime().getJudge() == null) {
            cfg.getRuntime().setJudge(null);
        }
        if (cfg.getRuntime().getRunBudget() == null) {
            cfg.getRuntime().setRunBudget(null);
        }
        if (cfg.getRuntime().getJudge().getRoutes() == null) {
            cfg.getRuntime().getJudge().setRoutes(null);
        } else {
            for (AgentLlmProperties.JudgeRoute route : cfg.getRuntime().getJudge().getRoutes()) {
                if (route != null && route.getModels() == null) {
                    route.setModels(null);
                }
            }
        }
        if (cfg.getPrompts().getPythonRefineRequirements() == null) {
            cfg.getPrompts().setPythonRefineRequirements(null);
        }
        if (cfg.getPrompts().getDatasetFieldSpecs() == null) {
            cfg.getPrompts().setDatasetFieldSpecs(null);
        }
        if (cfg.getAgent() == null) {
            cfg.setAgent(null);
        } else {
            sanitizeAgent(cfg.getAgent());
        }
        if (cfg.getTools() == null) {
            cfg.setTools(null);
        } else {
            sanitizeTools(cfg.getTools());
        }
        if (cfg.getObservability() == null) {
            cfg.setObservability(null);
        }
        if (cfg.getObservability() != null && cfg.getObservability().getOpenrouter() == null) {
            cfg.getObservability().setOpenrouter(null);
        }
        if (cfg.getObservability() != null && cfg.getObservability().getOpenrouter() != null 
                && cfg.getObservability().getOpenrouter().getCostEnrichment() == null) {
            cfg.getObservability().getOpenrouter().setCostEnrichment(null);
        }
        if (cfg.getDebug() == null) {
            cfg.setDebug(null);
        }
        if (cfg.getOpenrouter() == null) {
            cfg.setOpenrouter(null);
        }
        if (cfg.getExecutor() == null) {
            cfg.setExecutor(null);
        } else if (cfg.getExecutor().getParallel() == null) {
            cfg.getExecutor().setParallel(null);
        }
        if (cfg.getEventStore() == null) {
            cfg.setEventStore(null);
        }
        return cfg;
    }

    private void sanitizeRuntime(AgentLlmProperties.Runtime runtime) {
        if (runtime.getExecution() != null && runtime.getExecution().getAdjFactorEnabled() == null) {
            runtime.getExecution().setAdjFactorEnabled(false);
        }
        if (runtime.getRequest() == null) {
            runtime.setRequest(null);
        } else {
            AgentLlmProperties.Request request = runtime.getRequest();
            if (request.getMaxRetries() == null) {
                request.setMaxRetries(3);
            }
            if (request.getRetry() == null) {
                request.setRetry(null);
            } else {
                AgentLlmProperties.Retry retry = request.getRetry();
                if (retry.getBackoffType() == null) {
                    retry.setBackoffType("exponential");
                }
                if (retry.getBaseDelayMs() == null) {
                    retry.setBaseDelayMs(1000L);
                }
                if (retry.getMaxDelayMs() == null) {
                    retry.setMaxDelayMs(4000L);
                }
                if (retry.getJitterMs() == null) {
                    retry.setJitterMs(100L);
                }
            }
        }
        if (runtime.getParallel() == null || runtime.getParallel().getToolWeightedLimit() == null) {
            return;
        }
        AgentLlmProperties.ToolWeightedLimit limit = runtime.getParallel().getToolWeightedLimit();
        if (limit.getTools() == null) {
            return;
        }
        for (AgentLlmProperties.ToolWeightEntry entry : limit.getTools().values()) {
            if (entry != null && entry.getRequiresAdjFactorEnabled() == null) {
                entry.setRequiresAdjFactorEnabled(false);
            }
        }
    }

    private void sanitizeAgent(AgentLlmProperties.Agent agent) {
        if (agent.getWorkspace() == null) {
            agent.setWorkspace(null);
        } else {
            AgentLlmProperties.Workspace workspace = agent.getWorkspace();
            if (workspace.getDump() == null) {
                workspace.setDump(null);
            } else {
                AgentLlmProperties.Dump dump = workspace.getDump();
                if (dump.getTtlHours() == null) {
                    dump.setTtlHours(12);
                }
            }
        }
        if (agent.getDataset() == null) {
            agent.setDataset(null);
        } else {
            AgentLlmProperties.Dataset dataset = agent.getDataset();
            if (dataset.getTtlHours() == null) {
                dataset.setTtlHours(12);
            }
        }
    }

    private void sanitizeTools(AgentLlmProperties.Tools tools) {
        if (tools.getResult() == null) {
            tools.setResult(null);
        } else {
            AgentLlmProperties.ToolResult result = tools.getResult();
            if (result.getMaxStringLength() == null) {
                result.setMaxStringLength(2000);
            }
        }
        if (tools.getSummary() == null) {
            tools.setSummary(null);
        } else {
            AgentLlmProperties.ToolSummary summary = tools.getSummary();
            if (summary.getMaxRetries() == null) {
                summary.setMaxRetries(3);
            }
        }
        if (tools.getReread() == null) {
            tools.setReread(null);
        } else {
            AgentLlmProperties.ToolReread reread = tools.getReread();
            if (reread.getMaxLimit() == null) {
                reread.setMaxLimit(2000);
            }
        }
        if (tools.getRawRef() == null) {
            tools.setRawRef(null);
        } else {
            AgentLlmProperties.ToolRawRef rawRef = tools.getRawRef();
            if (rawRef.getTtlHours() == null) {
                rawRef.setTtlHours(12);
            }
        }
    }
}
