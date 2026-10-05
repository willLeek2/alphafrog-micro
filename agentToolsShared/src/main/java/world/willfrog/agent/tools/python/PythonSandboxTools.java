package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.tools.sandboxjob.SandboxJobAdapterRegistry;
import world.willfrog.agent.tools.sandboxjob.SandboxJobAdapters;
import world.willfrog.agent.tools.sandboxjob.SandboxJobObservability;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResponses;
import world.willfrog.agent.tools.sandboxjob.SandboxJobWaitPolicy;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;
import world.willfrog.agent.tools.sandboxjob.SandboxToolJobLifecycle;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionRequest;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionResult;
import world.willfrog.agent.platform.finance.FinanceRecordProcessingException;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.service.ToolDescriptionTexts;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.workflow.AgentRunDatasetCsvWriter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.agent.tools.dataset.DatasetEntryMetadataReader;
import world.willfrog.agent.tools.dataset.RunLevelIdResolver;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LangChain4j 工具：在隔离 Python 沙箱中执行代码，并把当前 agent 运行选中的 dataset / manifest 挂载进沙箱。
 * 调用方传入的是 {@code listMyData} 返回的 run 级局部编号，本类负责解析编号、生成路径映射 CSV、
 * 创建沙箱任务并轮询直至成功、失败或超时。
 */
@Component
@Slf4j
public class PythonSandboxTools {

    /** 轮询沙箱任务状态的间隔（毫秒）。 */
    private static final int POLL_INTERVAL_MS = 1000;
    /** lease 剩余一半时续租，避免每次 fast-path poll 都写 PostgreSQL。 */
    private static final long DAG_LEASE_RENEW_AHEAD_MILLIS =
            DagBlockingWorkerLease.LEASE_DURATION.toMillis() / 2L;
    /** schema 2 起保证 estimate 与 reservation 同源；旧错配兼容只能处理 schema 1。 */
    private static final int DATA_INTENSE_ANCHOR_SCHEMA_VERSION = 2;
    /**
     * 数据分析调用的尝试轮次。
     *
     * <p>新旧两条路径都从第一轮开始：同一段里重试换的是工具调用身份（模型给出的调用编号），
     * 身份变了外部作业也就换了，不需要靠轮次区分。</p>
     */
    private static final int DATA_ANALYSIS_ATTEMPT = 1;

    /**
     * 写给模型的完整说明只维护在 classpath 权威文件里。
     * {@code @Tool} 不再放正文：LangChain4j 反射只要方法签名，目录构建时再覆盖成权威正文。
     * 文件缺失或为空时加载直接失败，不再在 Java 里放第二份说明。
     */
    public static String loadToolDescription() {
        return ToolDescriptionTexts.require("executePython");
    }

    @DubboReference
    private PythonSandboxService pythonSandboxService;

    private final ObjectMapper objectMapper;

    /**
     * 订阅数据集持久化事件，并在单次 agent 运行内维护「局部编号 → 条目」映射。
     * 标记为可选注入（{@code required=false}），便于单元测试在不启动完整 Spring 上下文时运行。
     */
    @Autowired(required = false)
    private AgentRunDatasetRegistry agentRunDatasetRegistry;

    @Autowired(required = false)
    private DebugObservabilityService debugObservabilityService;

    @Autowired(required = false)
    private DataAnalysisCapacityService dataAnalysisCapacityService;

    @Autowired(required = false)
    private WaitGroupStore waitGroupStore;

    @Autowired(required = false)
    private DataAnalysisCapacityProperties dataAnalysisCapacityProperties;

    @Autowired(required = false)
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

    @Autowired(required = false)
    private ToolJobFaultInjector toolJobFaultInjector;

    @Autowired(required = false)
    private DataAnalysisTerminalRecorder dataAnalysisTerminalRecorder;

    @Autowired(required = false)
    private FinanceRecordChannelConfigLoader financeRecordChannelConfigLoader;

    @Autowired(required = false)
    private FinanceRecordChannelProcessor financeRecordChannelProcessor;

    @Autowired(required = false)
    private FinanceResultModelAdapter financeResultModelAdapter;

    private final FinanceToolResultFormatter financeToolResultFormatter;

    @Value("${agent.tool-job.fast-path-ms:1500}")
    private long fastPathMs = 1500L;

    @Value("${sandbox.runtime-environment-version:python-sandbox-v1}")
    private String runtimeEnvironmentVersion = "python-sandbox-v1";

    /**
     * 生产环境的安全开关，默认 false。容量组件接线不完整时直接拒绝创建，
     * 不允许悄悄降级到不带容量管理的老创建路径（老路径没有名额预留、
     * 没有 operationId，重复请求无法去重）。只允许在明确标注的非生产
     * 测试夹具里打开，绝不能指向生产网关。
     */
    @Value("${sandbox.create.allow-legacy-without-capacity:false}")
    private boolean allowLegacyWithoutCapacity = false;

    private final DatasetEntryMetadataReader metadataReader;

    public PythonSandboxTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.metadataReader = new DatasetEntryMetadataReader(objectMapper);
        this.financeToolResultFormatter = new FinanceToolResultFormatter(objectMapper);
    }

    // ==================== 适配器装配（生命周期框架的四个出口） ====================

    private volatile SandboxJobObservability observability;
    private volatile SandboxJobAdapters sandboxJobAdapters;

    /** 观测出口惰性组装：optional 注入字段在构造后才就绪，首次使用时定格。 */
    private SandboxJobObservability observability() {
        SandboxJobObservability current = observability;
        if (current == null) {
            current = new SandboxJobObservability(debugObservabilityService);
            observability = current;
        }
        return current;
    }

    /** 适配器束惰性组装：请求/运行器/结果/计量四个出口，框架按工具名协作。 */
    private SandboxJobAdapters sandboxJobAdapters() {
        SandboxJobAdapters current = sandboxJobAdapters;
        if (current == null) {
            synchronized (this) {
                current = sandboxJobAdapters;
                if (current == null) {
                    current = new SandboxJobAdapters(
                            new PythonSandboxJobRequestAdapter(),
                            new PythonSandboxJobRunnerAdapter(pythonSandboxService, observability()),
                            new PythonSandboxJobResultAdapter(financeToolResultFormatter, financeResultModelAdapter),
                            new PythonSandboxJobMeteringAdapter(objectMapper));
                    sandboxJobAdapters = current;
                }
            }
        }
        return current;
    }

    private PythonSandboxJobRequestAdapter requestAdapter() {
        return (PythonSandboxJobRequestAdapter) sandboxJobAdapters().request();
    }

    private PythonSandboxJobRunnerAdapter runnerAdapter() {
        return (PythonSandboxJobRunnerAdapter) sandboxJobAdapters().runner();
    }

    private PythonSandboxJobResultAdapter resultAdapter() {
        return (PythonSandboxJobResultAdapter) sandboxJobAdapters().result();
    }

    private PythonSandboxJobMeteringAdapter meteringAdapter() {
        return (PythonSandboxJobMeteringAdapter) sandboxJobAdapters().metering();
    }

    /** Spring 装配完成后把适配器束登记进注册表；不走 Spring 的单元测试注册表保持为空。 */
    @PostConstruct
    void registerSandboxJobAdapters() {
        SandboxJobAdapterRegistry.registerEquivalent(sandboxJobAdapters());
    }

    /** 生命周期框架的共享依赖束：每次调用现组，字段的可空语义与既有 null 检查一致。 */
    private SandboxToolJobLifecycle.LifecycleDeps lifecycleDeps() {
        return new SandboxToolJobLifecycle.LifecycleDeps(
                pythonSandboxDispatchStore, dataAnalysisCapacityService, dataAnalysisTerminalRecorder,
                waitGroupStore, toolJobFaultInjector, observability(), objectMapper,
                POLL_INTERVAL_MS, DAG_LEASE_RENEW_AHEAD_MILLIS, fastPathMs);
    }

    /** finance 记录通道是 executePython 的自有终态副作用；框架在结果格式化前回调这里。 */
    private SandboxToolJobLifecycle.TerminalSideEffect terminalSideEffect() {
        return (runId, identity, anchor, view) -> processFinanceResult(
                runId, identity, anchor, view.statusName(), (TaskResultResponse) view.nativePayload());
    }

    /**
     * LangChain4j 暴露给 LLM 的五参数入口；参数名与权威说明中的字段一一对应。
     * 方法上的 {@code @Tool} 不再带正文，目录构建时会覆盖成权威文件。
     */
    @Tool
    public String executePython(String code, String dataset_ids, String manifest_ids, String libraries, Integer timeout_seconds) {
        return executePythonInternal(code, dataset_ids, manifest_ids, libraries, timeout_seconds);
    }

    /**
     * 四参数重载，供历史 Java 调用方与旧测试使用；内部转调五参数入口，{@code manifest_ids} 传 {@code null}。
     * 五参数入口才是面向 LLM 的 {@code @Tool} 路径：{@code dataset_ids} 与 {@code manifest_ids}
     * 分别在各自编号空间中解析，不会再按旧逻辑串行混查。
     */
    public String executePython(String code, String dataset_ids, String libraries, Integer timeout_seconds) {
        return executePythonInternal(code, dataset_ids, null, libraries, timeout_seconds);
    }

    /**
     * {@code executePython} 的实际执行体，整体分为六个阶段：
     * <ol>
     *   <li>解析并校验 {@code dataset_ids} / {@code manifest_ids} 入参</li>
     *   <li>借助 {@link AgentRunDatasetRegistry} 把 run 级局部编号解析为持久化条目</li>
     *   <li>根据已选 manifest 补全其成员 dataset（避免 paths CSV 只有表头）</li>
     *   <li>生成路径映射 CSV，并汇总待挂载的 {@code originalId} 列表</li>
     *   <li>组装 {@link ExecuteRequest}，经 Dubbo 创建沙箱任务</li>
     *   <li>轮询任务状态，直至成功、失败、取消、不存在或超时</li>
     * </ol>
     * 返回值始终是 JSON 字符串：{@code ok=true} 时 {@code data} 含 stdout/stderr 等字段；
     * {@code ok=false} 时 {@code error.code} 标识失败类型，{@code error.details} 附带结构化上下文。
     */
    private String executePythonInternal(String code, String dataset_ids, String manifest_ids, String libraries, Integer timeout_seconds) {
        long toolStartMs = System.currentTimeMillis();
        try {
            long prepareStartMs = System.currentTimeMillis();

            // --- 第一阶段：解析入参中的编号字符串 ---
            // LLM 可能传入逗号分隔数字，也可能传入 JSON 数组形态（如 "[1, 3]"），统一经 parseDatasetIds 规范化。
            String[] parsedDatasetNumbers = parseDatasetIds(dataset_ids);
            String[] parsedManifestNumbers = parseDatasetIds(manifest_ids);
            // 两个参数至少填一个；都为空则无法确定要挂载哪些数据，直接返回 MISSING_IDS。
            if (parsedDatasetNumbers.length == 0 && parsedManifestNumbers.length == 0) {
                return fail("executePython", "MISSING_IDS",
                        "dataset_ids and manifest_ids are both empty; at least one is required",
                        Map.of());
            }

            // --- 第二阶段：读取当前 agent 运行的 registry 快照 ---
            // dataset_ids 与 manifest_ids 均为当前 agent 运行内的局部编号（由 listMyData 分配），
            // 不是持久化层的 originalId，也不是磁盘路径。registry 负责把编号映射为
            // AgentRunDatasetEntry（含 originalId、sortKey、fromTsCode 等字段）。
            // dataset 与 manifest 使用两套独立编号空间，各自单独解析。
            String runId = AgentContext.getRunId();
            AgentRunDatasetRegistry registry = this.agentRunDatasetRegistry;
            // 编号解析依赖「正在进行的 run」以及已注入的 registry；单元测试或未在 run 内调用时会失败。
            if (runId == null || runId.isBlank() || registry == null) {
                return fail("executePython", "RUN_LEVEL_IDS_UNAVAILABLE",
                        "Agent run-level dataset ids require an active run and AgentRunDatasetRegistry",
                        Map.of("runId", nvl(runId)));
            }

            // snapshot 含本 run 已注册的全部 dataset / manifest，后续补全成员 dataset 时要查表。
            AgentRunDatasetSnapshot snapshot = registry.snapshot(runId);
            // 合法编号列表仅用于错误提示：解析失败时告诉 LLM 当前 run 里有哪些编号可选。
            List<Integer> legalDatasetNumbers = registry.listDatasetNumbers(runId);
            List<Integer> legalManifestNumbers = registry.listManifestNumbers(runId);

            // resolvedDatasets / resolvedManifests：调用方显式选中的条目，顺序与入参 token 顺序一致。
            // illegalDatasetRefs / illegalManifestRefs：无法解析的 token 及原因，供 fail 响应回填。
            List<AgentRunDatasetEntry> resolvedDatasets = new ArrayList<>();
            List<AgentRunDatasetEntry> resolvedManifests = new ArrayList<>();
            List<Map<String, Object>> illegalDatasetRefs = new ArrayList<>();
            List<Map<String, Object>> illegalManifestRefs = new ArrayList<>();

            resolveRunLevelNumbers(parsedDatasetNumbers, registry, runId, "dataset",
                    resolvedDatasets, illegalDatasetRefs, false);
            resolveRunLevelNumbers(parsedManifestNumbers, registry, runId, "manifest",
                    resolvedManifests, illegalManifestRefs, true);

            if (!illegalDatasetRefs.isEmpty() || !illegalManifestRefs.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("illegal_dataset_refs", illegalDatasetRefs);
                details.put("illegal_manifest_refs", illegalManifestRefs);
                details.put("legal_dataset_numbers", legalDatasetNumbers);
                details.put("legal_manifest_numbers", legalManifestNumbers);
                return fail("executePython", "ILLEGAL_RUN_LEVEL_IDS",
                        "Some dataset_ids / manifest_ids are not valid run-level numbers; pass values from the legal lists below",
                        details);
            }

            // --- 第三阶段：根据 manifest 补全成员 dataset ---
            // 若调用方只选了 manifest、未显式选 dataset，resolvedDatasets 会为空，
            // writePathsDatasetCsv 只会写出表头。sandbox_runner 因此不会落盘 /sandbox/paths_dataset.csv，
            // Python 侧 af_dataset_loader 会改走旧路径，最终找不到 manifest 成员对应的文件。
            // 此处遍历每个已选 manifest 的 related_dataset_ids（同样使用 run 级编号），
            // 将关联的成员 dataset 写入 relatedDatasets，再与显式选中项合并进 subSnapshot。
            // 挂载顺序为：显式 dataset → 显式 manifest → 由 manifest 推断出的关联 dataset；
            // primaryOriginalId 仍取第一个显式选中项，不受此处补全影响。
            Map<Integer, AgentRunDatasetEntry> datasetByNumber = new HashMap<>();
            for (AgentRunDatasetEntry ds : snapshot.datasets()) {
                datasetByNumber.put(ds.number(), ds);
            }
            // explicitNumbers 记录「已经纳入挂载计划」的 dataset 编号，避免同一成员被多个 manifest 重复添加。
            Set<Integer> explicitNumbers = new HashSet<>(resolvedDatasets.size() * 2);
            for (AgentRunDatasetEntry ds : resolvedDatasets) {
                explicitNumbers.add(ds.number());
            }
            List<AgentRunDatasetEntry> relatedDatasets = new ArrayList<>();
            List<Integer> manifestsWithoutResolvableMembers = new ArrayList<>();
            for (AgentRunDatasetEntry mf : resolvedManifests) {
                int resolvedMemberCount = 0;
                for (String relatedNumberStr : mf.relatedDatasetIds()) {
                    int relatedNumber;
                    try {
                        relatedNumber = Integer.parseInt(relatedNumberStr);
                    } catch (NumberFormatException nfe) {
                        // manifest 元数据异常：related_dataset_ids 里出现了非整数，跳过并打 warn，不阻断整个任务。
                        log.warn("Manifest related_dataset_id is not a run-level number: runId={} manifestId={} value={}",
                                runId, mf.originalId(), relatedNumberStr);
                        continue;
                    }
                    AgentRunDatasetEntry relatedDs = datasetByNumber.get(relatedNumber);
                    if (relatedDs == null) {
                        // manifest 引用了本 run 中不存在的 dataset 编号，同样跳过并打 warn。
                        log.warn("Manifest related_dataset_id not in registry: runId={} manifestId={} number={}",
                                runId, mf.originalId(), relatedNumber);
                        continue;
                    }
                    resolvedMemberCount++;
                    if (explicitNumbers.add(relatedNumber)) {
                        relatedDatasets.add(relatedDs);
                    }
                }
                if (resolvedMemberCount == 0) {
                    manifestsWithoutResolvableMembers.add(mf.number());
                }
            }
            if (!manifestsWithoutResolvableMembers.isEmpty()) {
                return fail("executePython", "MANIFEST_MEMBERS_UNAVAILABLE",
                        "Selected manifest has no resolvable dataset members in the current run",
                        Map.of(
                                "manifest_numbers", manifestsWithoutResolvableMembers,
                                "legal_dataset_numbers", legalDatasetNumbers));
            }

            // --- 第四阶段：汇总挂载列表并生成路径映射 CSV ---
            // originalIdsToMount 的顺序决定 Dubbo 请求里 datasetIds 的排列，也影响 primaryOriginalId 的选取。
            // Python 端 sandbox_runner 按该列表把持久化目录挂载进容器。
            List<String> originalIdsToMount = new ArrayList<>();
            for (AgentRunDatasetEntry ds : resolvedDatasets) {
                originalIdsToMount.add(ds.originalId());
            }
            for (AgentRunDatasetEntry mf : resolvedManifests) {
                originalIdsToMount.add(mf.originalId());
            }
            for (AgentRunDatasetEntry ds : relatedDatasets) {
                originalIdsToMount.add(ds.originalId());
            }
            if (originalIdsToMount.isEmpty()) {
                return fail("executePython", "EMPTY_RESOLVED_IDS",
                        "No dataset / manifest resolved from dataset_ids / manifest_ids", Map.of());
            }

            // subSnapshot 供 paths_dataset.csv 使用：行集 = 显式 dataset + 推断出的成员 dataset。
            // path_manifest.csv 则写入 snapshot 中的全量 manifest（不仅是调用方选中的那几个），
            // 方便沙箱内 Python 代码按 run 级编号交叉引用任意 manifest。
            List<AgentRunDatasetEntry> allDatasets = new ArrayList<>(resolvedDatasets);
            allDatasets.addAll(relatedDatasets);
            AgentRunDatasetSnapshot subSnapshot = new AgentRunDatasetSnapshot(allDatasets, resolvedManifests);
            String pathsDatasetCsv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(subSnapshot);
            String pathManifestCsv = AgentRunDatasetCsvWriter.writePathManifestCsv(snapshot);

            emitSandboxEvent("sandbox_prepare_registry", Map.of(
                    "durationMs", System.currentTimeMillis() - prepareStartMs,
                    "status", "OK",
                    "datasetCount", resolvedDatasets.size(),
                    "manifestCount", resolvedManifests.size(),
                    "relatedDatasetCount", relatedDatasets.size(),
                    "pathsCsvBytes", pathsDatasetCsv.length(),
                    "manifestCsvBytes", pathManifestCsv.length()
            ));

            // datasetId（单数）字段保留兼容旧接口，取挂载列表首项；完整列表通过 datasetIds（复数）重复字段传递。
            String primaryOriginalId = originalIdsToMount.get(0);
            log.info("Executing python task for run-level ids: primary={}, total={}, datasetCount={}, manifestCount={}, relatedDatasetCount={}",
                    primaryOriginalId, originalIdsToMount.size(), resolvedDatasets.size(), resolvedManifests.size(), relatedDatasets.size());

            // --- 第五阶段：组装 ExecuteRequest 并创建沙箱任务 ---
            ExecuteRequest.Builder requestBuilder = ExecuteRequest.newBuilder()
                    .setCode(nvl(code))
                    .setDatasetId(primaryOriginalId);

            for (String oid : originalIdsToMount) {
                requestBuilder.addDatasetIds(oid);
            }

            // libraries 为可选的额外 pip 包列表；沙箱镜像已预装 numpy/pandas 等，未指定则不再安装。
            if (libraries != null && !libraries.isBlank()) {
                for (String lib : libraries.split(",")) {
                    String normalized = lib == null ? "" : lib.trim();
                    if (!normalized.isBlank()) {
                        requestBuilder.addLibraries(normalized);
                    }
                }
            }

            int timeout = (timeout_seconds != null && timeout_seconds > 0) ? timeout_seconds : 30;
            requestBuilder.setTimeoutSeconds(timeout);

            // pathsDatasetCsv / pathManifestCsv 随请求下发，sandbox_runner 写入 /sandbox/ 下供 loader 读取。
            requestBuilder.setPathsDatasetCsv(pathsDatasetCsv);
            requestBuilder.setPathManifestCsv(pathManifestCsv);

            ExecuteRequest legacyRequest = requestBuilder.build();
            // 新调度器版本：这次调用是某个等待组里的一名成员，建好后台任务就交出去，不等结果。
            WaitGroupMemberExecutionContext.Snapshot waitGroupMember =
                    WaitGroupMemberExecutionContext.current();
            if (waitGroupMember != null) {
                return submitForWaitGroup(waitGroupMember, subSnapshot, allDatasets,
                        resolvedManifests, legacyRequest, timeout, toolStartMs);
            }
            if (dataIntenseWiringAvailable()) {
                return executeDataIntense(
                        runId, subSnapshot, allDatasets, resolvedManifests,
                        legacyRequest, timeout, toolStartMs);
            }

            // 生产环境不允许悄悄降级到不带容量管理的老创建路径：
            // 在调用网关之前就失败。对外的错误保持不变，接线细节只进运维日志。
            if (!allowLegacyWithoutCapacity) {
                log.error("sandbox.create.wiringIncomplete: production refuses "
                        + "Legacy create; capacityWiringPresent={}; "
                        + "nonProductionSwitch=sandbox.create.allow-legacy-without-capacity",
                        dataIntenseWiringAvailable());
                emitSandboxToolTotal(toolStartMs, "ERROR", "SANDBOX_CAPACITY_WIRING_INCOMPLETE");
                return fail(
                        "executePython",
                        "SANDBOX_CAPACITY_WIRING_INCOMPLETE",
                        "Python sandbox production wiring incomplete; "
                                + "refuses Legacy create without capacity reservation "
                                + "and operationId. Non-production fixtures must enable "
                                + "the documented Legacy compatibility switches as a group "
                                + "(no global capacity admission / no idempotent recovery).",
                        Map.of());
            }
            log.warn("sandbox.create.legacyWithoutCapacity: allow-legacy-without-capacity=true "
                    + "(NON-PRODUCTION: no global capacity admission, no operationId recovery; "
                    + "must also enable companion Gateway/Python switches as a group)");

            long createStartMs = System.currentTimeMillis();
            installDebugRpcAttachments();
            ExecuteResponse createResp = pythonSandboxService.createTask(legacyRequest);
            emitSandboxEvent("sandbox_create_task", Map.of(
                    "durationMs", System.currentTimeMillis() - createStartMs,
                    "status", createResp.getError() == null || createResp.getError().isEmpty() ? "OK" : "ERROR",
                    "errorCategory", createResp.getError() == null || createResp.getError().isEmpty() ? "" : "CREATE_TASK_FAILED",
                    "taskId", nvl(createResp.getTaskId()),
                    "datasetMountCount", originalIdsToMount.size()
            ));
            if (createResp.getError() != null && !createResp.getError().isEmpty()) {
                return fail("executePython", "CREATE_TASK_FAILED", "Failed to create python sandbox task", Map.of(
                        "message", createResp.getError(),
                        "dataset_id", primaryOriginalId
                ));
            }

            String taskId = createResp.getTaskId();
            log.info("Task created: {}", taskId);

            // --- 第六阶段：轮询任务直至终态或超时 ---
            // maxWaitMs 在声明的 timeout 基础上额外留 5 秒，覆盖沙箱侧排队与收尾延迟。
            long maxWaitMs = timeout * 1000L + 5000;
            long startTime = System.currentTimeMillis();
            int pollIndex = 0;
            String lastRemoteStatus = "";
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                long pollStartMs = System.currentTimeMillis();
                TaskStatusResponse statusResp = getTaskStatus(taskId);
                String remoteStatus = statusResp == null ? "" : nvl(statusResp.getStatus());
                boolean statusChanged = !remoteStatus.equals(lastRemoteStatus);
                // 调试事件采样：首次、状态变化、以及每 5 次轮询各打一条，避免日志过密。
                if (pollIndex == 0 || statusChanged || pollIndex % 5 == 0) {
                    emitSandboxEvent("sandbox_poll", Map.of(
                            "durationMs", System.currentTimeMillis() - pollStartMs,
                            "status", "OK",
                            "pollIndex", pollIndex,
                            "sinceCreateMs", System.currentTimeMillis() - startTime,
                            "remoteStatus", remoteStatus,
                            "taskId", taskId
                    ));
                }
                lastRemoteStatus = remoteStatus;
                pollIndex++;
                // terminalOutput 对终态返回 JSON 字符串，对 RUNNING 等中间态返回 null，继续轮询。
                String terminal = terminalOutput(taskId, statusResp);
                if (terminal != null) {
                    emitSandboxToolTotal(toolStartMs, "OK", "");
                    return terminal;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return fail("executePython", "INTERRUPTED", "Task polling interrupted", Map.of("task_id", taskId));
                }
            }

            emitSandboxToolTotal(toolStartMs, "TIMEOUT", "TIMEOUT");
            return fail("executePython", "TIMEOUT", "Sandbox task timed out after " + timeout + "s", Map.of("task_id", taskId));
        } catch (ToolJobInjectedInterruption interruption) {
            // 验收故障点表示当前 worker 必须立即退场。把它转换成普通工具失败会让模型
            // 继续生成并提交节点结果，反而覆盖数据库里等待恢复的 PREPARING/READY 锚点。
            throw interruption;
        } catch (ExternalToolJobPendingException pending) {
            throw pending;
        } catch (WaitGroupMemberPendingException pending) {
            // 等待成员的后台作业已经交出去了。把它转成工具失败文本会让模型以为这次调用结束，
            // 而库里还没有记下这个成员已经派发，所以原样上抛，由派发器写进成员行。
            throw pending;
        } catch (Exception e) {
            log.error("Execute python tool error", e);
            emitSandboxToolTotal(toolStartMs, "ERROR", "TOOL_ERROR");
            return fail("executePython", "TOOL_ERROR", "Python sandbox invocation error", Map.of("message", nvl(e.getMessage())));
        }
    }

    private boolean dataIntenseWiringAvailable() {
        return dataAnalysisCapacityService != null
                && dataAnalysisCapacityProperties != null
                && pythonSandboxDispatchStore != null
                && dataAnalysisTerminalRecorder != null;
    }

    // ==================== 两条路径共用的冻结事实 ====================

    /**
     * 一次数据分析调用在建沙箱任务之前冻结下来的事实：预估值、资源档位、canonical 请求规格与请求指纹。
     *
     * <p>旧路径把这些写进 Run 级的长工具进度记录，新调度器版本把它们写进成员行的派发证明。
     * 两边用的是同一份结果，所以只留一处算法。</p>
     */
    private record CapacityPlan(DataAnalysisEstimate estimate,
                                DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision decision,
                                CanonicalSandboxCreateSpec spec,
                                String pythonRequestFingerprint,
                                PythonRepairContext repairContext) {
    }

    /**
     * 「这次调用在冻结事实这一步就被拒绝」时抛的内部异常。
     *
     * <p>冻结这一步只有计算，不占名额、不碰沙箱，所以拒绝就是给模型一段可解释的失败文本。
     * 用异常传出来，是为了让两条路径共用同一段计算，又不必把失败文本从计算里层层返回。</p>
     */
    private static final class DataIntenseRefusal extends RuntimeException {

        private final String code;
        private final Map<String, Object> details;

        DataIntenseRefusal(String code, String message, Map<String, Object> details) {
            super(message);
            this.code = code;
            this.details = details == null ? Map.of() : details;
        }

        String code() {
            return code;
        }

        Map<String, Object> details() {
            return details;
        }
    }

    /**
     * 冻结这次调用的事实：数据集预估值、资源档位、canonical 请求规格与请求指纹。
     *
     * <p>只有计算，没有副作用。算不出来时抛 {@link DataIntenseRefusal}：元数据缺失、超过硬上限、
     * 计数溢出，以及同一份代码加同一组有效参数刚刚失败过。</p>
     */
    private CapacityPlan planCapacity(String operationId,
                                      AgentRunDatasetSnapshot datasetSnapshot,
                                      List<AgentRunDatasetEntry> datasets,
                                      List<AgentRunDatasetEntry> manifests,
                                      ExecuteRequest baseRequest,
                                      int timeoutSeconds) {
        DataAnalysisEstimate estimate;
        DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision decision;
        try {
            // 聚合所有输入数据集的行数与字节数，不能只看用户传入的逻辑数量。
            long rows = 0L;
            long bytes = 0L;
            for (AgentRunDatasetEntry dataset : datasets) {
                // 元数据不完整时直接拒绝，避免低估资源占用后把超出承载能力的任务放进沙箱。
                DatasetEntryMetadataReader.EntryMetadata metadata = metadataReader.read(dataset);
                if (metadata.rowCount() == null || metadata.bytes() == null) {
                    throw new DataIntenseRefusal("DATA_ANALYSIS_ESTIMATE_UNAVAILABLE",
                            "Dataset row/byte metadata is required before Sandbox admission",
                            Map.of("dataset_id", dataset.originalId(),
                                    "metadata_status", metadata.metadataStatus()));
                }
                rows = Math.addExact(rows, metadata.rowCount());
                bytes = Math.addExact(bytes, metadata.bytes());
            }
            int manifestMembers = manifests.stream()
                    .mapToInt(entry -> entry.relatedDatasetIds().size())
                    .sum();
            /*
             * heavyOperationHints 只能描述“代码将执行高成本操作”这一事实，例如全量排序、
             * 大规模 join 或模型训练；它不是依赖库列表。旧实现把 numpy/pandas 等 libraries
             * 直接塞进 hints，导致任何声明依赖库的小任务先被判为 HEAVY/3，随后容量服务又根据
             * 被清空的 hints 判成 STANDARD/1。estimate 与 reservation 的 class/units 因而漂移，
             * terminal envelope 无法通过一致性校验，容量也永远无法 RELEASE。
             *
             * 当前工具协议尚未提供可信的重操作提示，因此这里显式使用空列表。以后如果要增加
             * 静态代码分析或调用方声明，必须先得到同一个 immutable hints 列表，再同时用于
             * classify 和 DataAnalysisEstimate；严禁在两个阶段分别推断。
             */
            List<String> heavyOperationHints = List.of();
            // 资源档位只在这里冻结一次；后续名额预留、沙箱请求和终态证明都复用它。
            decision = dataAnalysisCapacityProperties.classify(rows, bytes, heavyOperationHints);
            if (decision.outcome()
                    == DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.REJECTED) {
                throw new DataIntenseRefusal("DATA_ANALYSIS_TASK_TOO_LARGE",
                        "Dataset estimate exceeds Sandbox hard limits",
                        Map.of("estimated_rows", rows, "estimated_bytes", bytes));
            }
            // 构造 immutable estimate，后续写入进度记录或派发证明，并在终态处理时再使用。
            estimate = new DataAnalysisEstimate(
                    rows, bytes, datasets.size(), 1.0d, manifestMembers, heavyOperationHints,
                    decision.resourceClass(), decision.capacityUnits());
        } catch (ArithmeticException overflow) {
            throw new DataIntenseRefusal("DATA_ANALYSIS_TASK_TOO_LARGE",
                    "Dataset estimate overflowed admission counters", Map.of());
        }

        long timeoutMillis = timeoutSeconds * 1000L;
        CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(
                CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                operationId,
                sha256(baseRequest.getCode()),
                datasetSnapshot.immutableDigest(),
                decision.resourceClass(),
                decision.memoryLimitBytes(),
                timeoutMillis,
                runtimeEnvironmentVersion,
                sha256(baseRequest.getLibrariesList().stream().sorted().collect(Collectors.joining("\n"))),
                sha256(""));
        // 修复判重必须在名额预留与 Sandbox create 之前完成，避免原样重放占用配额。
        String pythonRequestFingerprint = spec.repairRequestFingerprint();
        PythonRepairContext repairContext = AgentContext.getPythonRepairContext();
        if (repairContext != null && repairContext.hasFailed(pythonRequestFingerprint)) {
            throw new DataIntenseRefusal("REPEATED_FAILED_PYTHON_ATTEMPT",
                    "The same Python code and effective parameters already failed in this Todo; "
                            + "change the code or meaningful parameters before retrying",
                    Map.of("python_repair_attempt", repairContext.repairAttempt(),
                            "request_fingerprint", pythonRequestFingerprint));
        }
        return new CapacityPlan(estimate, decision, spec, pythonRequestFingerprint, repairContext);
    }

    // ==================== 等待成员：建后台任务并把派发证明交出去 ====================

    /**
     * 新调度器版本上的一次 {@code executePython}：把这次调用建成沙箱后台任务，然后立刻交出去。
     *
     * <p>名额预留之后的编排（创建证明落库、幂等创建、取消墓碑与派发证明移交）已下沉到
     * {@link SandboxToolJobLifecycle#dispatchWaitGroupMember}；这里只保留工具自有的
     * 接线检查、成员身份比对与容量事实冻结（planCapacity）。</p>
     */
    private String submitForWaitGroup(WaitGroupMemberExecutionContext.Snapshot member,
                                      AgentRunDatasetSnapshot datasetSnapshot,
                                      List<AgentRunDatasetEntry> datasets,
                                      List<AgentRunDatasetEntry> manifests,
                                      ExecuteRequest baseRequest,
                                      int timeoutSeconds,
                                      long toolStartMs) {
        // 容量账本是写派发证明的前提：没有它就没有名额凭证可写，也没有人能把名额还回去。
        if (!dataIntenseWiringAvailable()) {
            log.error("waitGroup.withoutCapacity: 成员调用缺少容量接线，拒绝建任务 member={}",
                    member.describe());
            emitSandboxToolTotal(toolStartMs, "ERROR", "SANDBOX_CAPACITY_WIRING_INCOMPLETE");
            return fail("executePython", "SANDBOX_CAPACITY_WIRING_INCOMPLETE",
                    "Python sandbox production wiring incomplete; a wait-group member "
                            + "requires capacity reservation and a durable dispatch proof",
                    Map.of());
        }
        // 外部作业身份由调度侧算好、整组落库时已经写进成员行。这里按同一份输入拼出身份对象，
        // 与成员行上的值不一致就什么都不做：建一个库里认不回来的后台任务，比不建更糟。
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                member.runId(), member.durableToolCallId(), DATA_ANALYSIS_ATTEMPT);
        if (!identity.operationId().equals(member.expectedOperationId())) {
            emitSandboxToolTotal(toolStartMs, "ERROR", "WAIT_GROUP_OPERATION_IDENTITY_MISMATCH");
            return fail("executePython", "WAIT_GROUP_OPERATION_IDENTITY_MISMATCH",
                    "The member's external operation identity does not match the persisted one",
                    Map.of("expected_operation_id", member.expectedOperationId(),
                            "derived_operation_id", identity.operationId()));
        }
        CapacityPlan plan;
        try {
            plan = planCapacity(identity.operationId(), datasetSnapshot, datasets, manifests,
                    baseRequest, timeoutSeconds);
        } catch (DataIntenseRefusal refusal) {
            return fail("executePython", refusal.code(), refusal.getMessage(), refusal.details());
        }
        return SandboxToolJobLifecycle.dispatchWaitGroupMember(
                lifecycleDeps(),
                new SandboxToolJobLifecycle.WaitGroupDispatchRequest<>(
                        member, identity, plan.spec(), plan.estimate(), baseRequest,
                        requestAdapter(), runnerAdapter(),
                        ToolJobAnchor.EXECUTE_PYTHON_TOOL, toolStartMs, null));
    }

    private String executeDataIntense(
            String runId,
            AgentRunDatasetSnapshot datasetSnapshot,
            List<AgentRunDatasetEntry> datasets,
            List<AgentRunDatasetEntry> manifests,
            ExecuteRequest baseRequest,
            int timeoutSeconds,
            long toolStartMs) throws Exception {
        // 等待策略必须来自 executor 已冻结的 effective workflow；未知值不能猜成 LINEAR。
        Optional<SandboxJobWaitPolicy> resolvedWaitPolicy =
                SandboxJobWaitPolicy.fromWorkflow(AgentContext.getWorkflow());
        if (resolvedWaitPolicy.isEmpty()) {
            return fail("executePython", "WORKFLOW_MODE_UNAVAILABLE",
                    "executePython requires an effective workflow of linear or dag",
                    Map.of("workflow", nvl(AgentContext.getWorkflow())));
        }
        SandboxJobWaitPolicy waitPolicy = resolvedWaitPolicy.get();

        // toolCallId 来自当前 Todo 的 AgentContext，是跨 worker 恢复的稳定逻辑调用身份。
        String toolCallId = AgentContext.getToolCallId();
        if (toolCallId == null || toolCallId.isBlank()) {
            return fail("executePython", "TOOL_JOB_IDENTITY_UNAVAILABLE",
                    "executePython requires a stable tool call id", Map.of("run_id", runId));
        }
        // operationId 由 runId/toolCallId/attempt 确定性派生，Sandbox create 可据此幂等查找。
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                runId, toolCallId, DATA_ANALYSIS_ATTEMPT);

        // 预估值、资源档位与 canonical 请求规格在分发之前一次冻结：准入、名额预留、
        // 沙箱请求与终态释放证明都用这同一份结果。
        CapacityPlan plan;
        try {
            plan = planCapacity(identity.operationId(), datasetSnapshot, datasets, manifests,
                    baseRequest, timeoutSeconds);
        } catch (DataIntenseRefusal refusal) {
            return fail("executePython", refusal.code(), refusal.getMessage(), refusal.details());
        }
        DataAnalysisEstimate estimate = plan.estimate();
        CanonicalSandboxCreateSpec spec = plan.spec();
        String pythonRequestFingerprint = plan.pythonRequestFingerprint();

        // reservation（资源名额凭证）拿到手之后，任何退出路径都必须把它释放掉，
        // 或者过户给后台任务继续管理，否则名额会一直占着。
        DataAnalysisReservation reservation;
        try {
            // reserve 在容量账本中创建 PREPARING 状态，可能因服务繁忙拒绝。
            reservation = dataAnalysisCapacityService.reserve(identity, estimate);
        } catch (CapacityAdmissionException admission) {
            String code = admission.reason() == CapacityAdmissionException.Reason.TASK_TOO_LARGE
                    ? "DATA_ANALYSIS_TASK_TOO_LARGE"
                    : "DATA_ANALYSIS_SERVER_BUSY";
            return fail("executePython", code, admission.getMessage(), Map.of("retryable",
                    admission.reason() != CapacityAdmissionException.Reason.TASK_TOO_LARGE));
        }

        // 把准入结果与 canonical identity 写入真正发送给 Sandbox 的请求。
        ExecuteRequest request = requestAdapter().enrichWithCapacity(baseRequest, reservation, estimate, spec);

        // 锚点组装与 PREPARING 抢占（四段持久第一段）已下沉到通用生命周期框架；
        // 这里只补 executePython 自有字段（修复计数、finance 通道冻结快照）。
        String canonicalSpecJson = objectMapper.writeValueAsString(spec);
        // createRequestJson 允许进程在 RPC 前后崩溃后重放同一 canonical 请求。
        String createRequestJson = JsonFormat.printer()
                .omittingInsignificantWhitespace().print(request);
        SandboxToolJobLifecycle.PrepareDispatchResult dispatch = SandboxToolJobLifecycle.prepareDispatch(
                pythonSandboxDispatchStore,
                new SandboxToolJobLifecycle.PrepareDispatchRequest(
                        runId,
                        ToolJobAnchor.EXECUTE_PYTHON_TOOL,
                        toolCallId,
                        DATA_ANALYSIS_ATTEMPT,
                        DATA_INTENSE_ANCHOR_SCHEMA_VERSION,
                        identity.operationId(),
                        spec.requestFingerprint(),
                        canonicalSpecJson,
                        createRequestJson,
                        waitPolicy.runDisposition(),
                        waitPolicy.autoResume(),
                        waitPolicy.durableSuspend(),
                        objectMapper.writeValueAsString(reservation),
                        objectMapper.writeValueAsString(estimate),
                        objectMapper.writeValueAsString(datasetSnapshot),
                        datasetSnapshot.immutableDigest(),
                        spec.timeoutMillis(),
                        POLL_INTERVAL_MS),
                extras -> {
                    extras.setPythonRequestFingerprint(pythonRequestFingerprint);
                    // 新请求已经写了自己的数据库进度记录，上一轮终态后等待启动的修复阶段到此结束。
                    extras.setPythonRepairPending(false);
                    extras.setPythonRepairExhausted(false);
                    if (plan.repairContext() != null) {
                        extras.setPythonRepairAttempt(plan.repairContext().repairAttempt());
                        extras.setPythonFailedRequestFingerprints(plan.repairContext().failedRequestFingerprints());
                    }
                    if (financeRecordChannelConfigLoader != null) {
                        extras.setFinanceRecordLimitsJson(financeRecordChannelConfigLoader.frozenSnapshotJson());
                    }
                });
        if (!dispatch.persisted()) {
            // 未取得 anchor owner 时释放尚未转交的容量。
            SandboxToolJobLifecycle.releasePreDispatch(lifecycleDeps(), reservation);
            // retryable=false：进度记录被别的流程占用时，同一 Run 内立刻重试必然再次失败
            // （曾有模型无停止信号连试 7 次烧完 480 秒的先例），所以直接告诉模型不可重试。
            return fail("executePython", "TOOL_JOB_ANCHOR_INVALID",
                    "Failed to persist PREPARING tool-job anchor",
                    Map.of("operation_id", identity.operationId(), "retryable", false));
        }
        ToolJobAnchor anchor = dispatch.anchor();
        hitFaultPoint(runId, ToolJobFaultInjector.BEFORE_SANDBOX_SUBMIT);

        /*
         * 从数据库里的 PREPARING（准备中）抢占成功开始，DAG 线程的任何异常退场都必须先移交
         * 负责者。局部路径负责更精确的 abort/poll 分类；这里的外层备用路径覆盖序列化、
         * 名额恢复、persistAttached 以及 create 身份不确定等未被局部 catch 的异常。
         *
         * 创建裁决、墓碑、ATTACHED 持久化与两种等待策略的轮询都已下沉到通用生命周期框架，
         * executePython 只提供四个适配器与 finance 终态副作用。
         */
        SandboxToolJobLifecycle.LifecycleDeps deps = lifecycleDeps();
        try {
            SandboxToolJobLifecycle.AttachOutcome attachOutcome = SandboxToolJobLifecycle.attachAfterCreate(
                    deps,
                    new SandboxToolJobLifecycle.AttachAfterCreateRequest<>(
                            runId, identity, spec, request, runnerAdapter(),
                            reservation, estimate, anchor,
                            waitPolicy.durableSuspend(), toolStartMs));
            if (attachOutcome instanceof SandboxToolJobLifecycle.AttachOutcome.FailureText failureText) {
                return failureText.text();
            }
            SandboxToolJobLifecycle.AttachOutcome.Attached attached =
                    (SandboxToolJobLifecycle.AttachOutcome.Attached) attachOutcome;
            String taskId = attached.taskId();
            reservation = attached.reservation();

            // 两种策略先共享极短 fast-path；到期后 LINEAR 才让出 worker，DAG 改为阻塞轮询。
            return SandboxToolJobLifecycle.pollFastPath(
                    deps,
                    new SandboxToolJobLifecycle.PollRequest(
                            runId, identity, estimate, reservation, anchor, taskId,
                            waitPolicy.durableSuspend(), toolStartMs,
                            runnerAdapter(), resultAdapter(), meteringAdapter(), terminalSideEffect()));
        } catch (Exception lifecycleFailure) {
            if (!waitPolicy.durableSuspend()) {
                return SandboxToolJobLifecycle.promoteDagBlockingFailure(
                        deps,
                        runId,
                        anchor,
                        "DAG_BLOCKING_LIFECYCLE_FAILED",
                        "DAG Sandbox lifecycle failed after durable ownership claim",
                        toolStartMs,
                        Map.of("operation_id", identity.operationId(),
                                "task_id", nvl(anchor.getTaskId()),
                                "message", nvl(lifecycleFailure.getMessage())));
            }
            throw lifecycleFailure;
        }
    }

    private void hitFaultPoint(String runId, String checkpoint) {
        if (toolJobFaultInjector != null) {
            toolJobFaultInjector.hit(runId, checkpoint);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** 观测事件整体委托给生命周期框架的观测出口，事件名与字段保持原样。 */
    private void emitSandboxEvent(String eventType, Map<String, Object> fields) {
        observability().emit(eventType, fields);
    }

    /** 在发起 Dubbo 调用前安装调试 attachment（委托框架观测出口）。 */
    private void installDebugRpcAttachments() {
        observability().installDebugRpcAttachments();
    }

    /** 工具调用结束时发送汇总事件（委托框架观测出口）。 */
    private void emitSandboxToolTotal(long toolStartMs, String status, String errorCategory) {
        observability().emitToolTotal(toolStartMs, status, errorCategory);
    }

    /**
     * 将调用方传入的编号 token 解析为 registry 条目；解析失败的 token 记入 illegal 列表。
     * dataset 与 manifest 使用独立编号空间，由 {@code kind} 参数决定查哪一侧。
     *
     * @param tokens 经 {@link #parseDatasetIds} 拆分后的字符串，可能含非数字
     * @param registry 当前 run 的数据集注册表
     * @param runId agent 运行 id
     * @param kind {@code "dataset"} 或 {@code "manifest"}，决定查询哪条编号空间，并写入 illegal 的 reason
     * @param resolved 解析成功的条目，按输入顺序追加
     * @param illegal 解析失败的引用，元素含 {@code input} 与 {@code reason}
     * @param allowEmptyTokens 是否跳过空 token；当前两个编号空间均传 {@code false}
     */
    private void resolveRunLevelNumbers(
            String[] tokens,
            AgentRunDatasetRegistry registry,
            String runId,
            String kind,
            List<AgentRunDatasetEntry> resolved,
            List<Map<String, Object>> illegal,
            boolean allowEmptyTokens) {
        // 解析归集语义与 executeQuery 共用 RunLevelIdResolver，这里只留委托。
        RunLevelIdResolver.resolveRunLevelNumbers(tokens, registry, runId, kind, resolved, illegal, allowEmptyTokens);
    }

    /** 查询沙箱任务当前状态；每次 Dubbo 调用前都会尝试安装调试 attachment。 */
    private TaskStatusResponse getTaskStatus(String taskId) {
        installDebugRpcAttachments();
        return pythonSandboxService.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build()
        );
    }

    /**
     * 根据远程任务状态决定是否已到达终态。
     * <ul>
     *   <li>{@code SUCCEEDED}：拉取 stdout/stderr，经 {@link #formatResult} 包装为 JSON 返回</li>
     *   <li>{@code FAILED} / {@code CANCELED} / {@code NOT_FOUND}：构造带 error.code 的失败 JSON</li>
     *   <li>其余状态（如 RUNNING）：返回 {@code null}，由轮询循环继续等待</li>
     * </ul>
     */
    private String terminalOutput(String taskId, TaskStatusResponse statusResp) {
        String status = statusResp.getStatus();
        if ("SUCCEEDED".equals(status)) {
            TaskResultResponse result = fetchTerminalResult(taskId);
            if (result == null) {
                return resultLostFailure();
            }
            if (result.hasFinanceRecordChannel()
                    || nvl(result.getStdout()).contains("__AF_FINANCE_RESULT_")) {
                return unavailableFinanceWiringFailure();
            }
            return formatResult(status, result, null);
        }
        if ("FAILED".equals(status)) {
            TaskResultResponse result = fetchTerminalResult(taskId);
            if (result != null) {
                if (result.hasFinanceRecordChannel()
                        || nvl(result.getStdout()).contains("__AF_FINANCE_RESULT_")) {
                    return unavailableFinanceWiringFailure();
                }
                return formatResult(status, result, null);
            }
            return formatter().formatFailure(
                    "", nvl(statusResp.getError()),
                    new FinanceToolResultFormatter.FailureDetail(
                            "PYTHON_EXECUTION_FAILED",
                            "Python 执行失败",
                            true,
                            "根据错误信息修正代码或输入后重试"));
        }
        if ("CANCELED".equals(status)) {
            TaskResultResponse result = fetchTerminalResult(taskId);
            if (result != null) {
                if (result.hasFinanceRecordChannel()
                        || nvl(result.getStdout()).contains("__AF_FINANCE_RESULT_")) {
                    return unavailableFinanceWiringFailure();
                }
                return formatResult(status, result, null);
            }
            return formatter().formatFailure(
                    "", "",
                    new FinanceToolResultFormatter.FailureDetail(
                            "PYTHON_EXECUTION_CANCELED",
                            "Python 执行已取消",
                            false,
                            "确认仍需计算后重新提交任务"));
        }
        if ("NOT_FOUND".equals(status)) {
            return resultLostFailure();
        }
        return null;
    }

    /**
     * Fetches the complete terminal payload for the legacy polling path.
     *
     * <p>Failed and canceled executions can still carry bounded stdout/stderr. They must use the
     * same public failure formatter as the durable path instead of losing those diagnostics. A
     * transient read failure is represented as an absent result so the caller can return a
     * deterministic result-lost/status-only failure without exposing RPC details.</p>
     */
    private TaskResultResponse fetchTerminalResult(String taskId) {
        long fetchStartMs = System.currentTimeMillis();
        TaskResultResponse result = null;
        String fetchStatus = "OK";
        try {
            installDebugRpcAttachments();
            result = pythonSandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
            if (result == null) {
                fetchStatus = "EMPTY";
            }
            return result;
        } catch (Exception exception) {
            fetchStatus = "ERROR";
            log.warn("Unable to fetch terminal sandbox result: taskId={}, error={}",
                    taskId, exception.getMessage());
            return null;
        } finally {
            emitSandboxEvent("sandbox_fetch_result", Map.of(
                    "durationMs", System.currentTimeMillis() - fetchStartMs,
                    "status", fetchStatus,
                    "taskId", nvl(taskId),
                    "exitCode", result == null ? -1 : result.getExitCode(),
                    "stdoutLen", result == null ? 0 : nvl(result.getStdout()).length(),
                    "stderrLen", result == null ? 0 : nvl(result.getStderr()).length()
            ));
        }
    }

    private String unavailableFinanceWiringFailure() {
        return formatter().formatFailure(
                "", "",
                new FinanceToolResultFormatter.FailureDetail(
                        "FINANCE_RECORD_DURABLE_WIRING_UNAVAILABLE",
                        "结构化金融结果暂时无法安全保存",
                        false,
                        "稍后重试，或改为普通文本输出"));
    }

    private String resultLostFailure() {
        return formatter().formatFailure(
                "", "",
                new FinanceToolResultFormatter.FailureDetail(
                        "PYTHON_RESULT_LOST",
                        "Python 执行结果已丢失",
                        false,
                        "重新提交计算任务"));
    }

    /**
     * 把 LLM 或 Java 调用方传入的编号字符串拆成 token 数组。
     * 兼容两种常见形态：逗号分隔的纯数字串，以及 JSON 数组字符串（含可选的双引号包裹）。
     * 会去重并保持首次出现顺序，避免重复挂载同一 dataset。
     */
    private String[] parseDatasetIds(String datasetIds) {
        // 拆分语义与 executeQuery 共用 RunLevelIdResolver.parseIds，这里只留委托。
        return RunLevelIdResolver.parseIds(datasetIds);
    }

    /**
     * 把一次已经确认终态的沙箱任务结果包成模型看到的那份 JSON。
     *
     * <p>同步执行与后台作业的结果接回必须写成同一个形状，格式化实现已收敛到
     * {@link PythonSandboxJobResultAdapter}（三条路径共用唯一出口）。后台作业只跑
     * {@code executePython}，没有 finance 记录通道那一路，所以按没有 finance 结果处理。</p>
     */
    public String formatTerminalResult(String status, TaskResultResponse result) {
        return formatResult(status, result, null);
    }

    /**
     * 把沙箱执行结果转为工具统一的 JSON 响应（委托结果适配器；proto 先映射成工具中立视图）。
     */
    private String formatResult(
            String status,
            TaskResultResponse result,
            FinanceRecordExtractionResult financeResult) {
        return resultAdapter().formatTerminalResult(
                runnerAdapter().toTerminalView(result, status), financeResult);
    }

    private FinanceRecordExtractionResult processFinanceResult(
            String runId,
            DataAnalysisOperationIdentity identity,
            ToolJobAnchor anchor,
            String status,
            TaskResultResponse result) {
        boolean hasFinancePayload = result.hasFinanceRecordChannel()
                || nvl(result.getStdout()).contains("__AF_FINANCE_RESULT_");
        if (!hasFinancePayload) {
            return null;
        }
        if (financeRecordChannelProcessor == null || financeRecordChannelConfigLoader == null) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_PROCESSOR_UNAVAILABLE",
                    "Finance record processor/config loader is unavailable");
        }

        if (anchor.getFinanceRecordLimitsJson() == null
                || anchor.getFinanceRecordLimitsJson().isBlank()) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_CONFIG_SNAPSHOT_MISSING",
                    "Finance record payload is present but the frozen configuration snapshot is missing");
        }
        FinanceRecordChannelConfigLoader.Snapshot frozen =
                financeRecordChannelConfigLoader.parseFrozenSnapshot(
                        anchor.getFinanceRecordLimitsJson());

        return financeRecordChannelProcessor.process(new FinanceRecordExtractionRequest(
                runId,
                AgentContext.getUserId(),
                anchor.getTodoId(),
                identity.toolCallId(),
                "sync",
                anchor.getTaskId(),
                status,
                result.getExitCode(),
                result.getStdout(),
                result.getStderr(),
                FinanceRecordProtoAdapter.channelMetadata(result),
                FinanceRecordProtoAdapter.executionEnvironment(result),
                frozen.targetEnvironment(),
                frozen.limits()));
    }

    private FinanceToolResultFormatter formatter() {
        return financeToolResultFormatter;
    }

    /** 构造 {@code ok=true} 的标准 JSON 工具响应。 */
    private String ok(String tool, Map<String, Object> data) {
        return SandboxJobResponses.ok(objectMapper, tool, data);
    }

    /** 构造 {@code ok=false} 的标准 JSON 工具响应；{@code details} 供 LLM 或上层做结构化重试。 */
    private String fail(String tool, String code, String message, Map<String, Object> details) {
        return SandboxJobResponses.fail(objectMapper, tool, code, message, details);
    }

    /**
     * 构造 {@code ok=false} 的 JSON 工具响应，可在失败时仍附带部分 {@code data}
     *（例如 exit code 非零但 stdout 有内容的场景）。
     */
    private String fail(String tool,
                        String code,
                        String message,
                        Map<String, Object> details,
                        Map<String, Object> data) {
        return SandboxJobResponses.fail(objectMapper, tool, code, message, details, data);
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    /**
     * 供单元测试与同包代码在构造完成后注入 registry；生产路径走 Spring {@code @Autowired(required=false)}。
     */
    void setAgentRunDatasetRegistry(AgentRunDatasetRegistry registry) {
        this.agentRunDatasetRegistry = registry;
    }
}
