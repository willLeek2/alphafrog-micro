package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionRequest;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionResult;
import world.willfrog.agent.platform.finance.FinanceRecordProcessingException;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.debug.DebugObservabilityRpcKeys;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.util.PromptFileLoader;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.workflow.AgentRunDatasetCsvWriter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.agent.tools.dataset.DatasetEntryMetadataReader;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
     * 工具说明正文维护在 classpath 文件 {@link #TOOL_DESCRIPTION_PATH} 中。
     * LangChain4j 的 {@code @Tool} 注解要求 description 为编译期常量，因此注解上只能放
     * {@link #TOOL_DESCRIPTION_SHORT} 这段简短提示；完整长文案由 {@link #loadToolDescription()} 在运行时加载，
     * 供 ToolRouter 拼接工具说明等场景使用。文件缺失或内容为空时，回落到 {@link #FALLBACK_TOOL_DESCRIPTION}，
     * 避免部署后因描述为空触发空指针。
     */
    private static final String TOOL_DESCRIPTION_PATH = "prompts/python/execute_python_tool_description.txt";

    private static final String TOOL_DESCRIPTION_SHORT =
            "Execute Python code in a secure sandbox. Inputs: code (required); at least one of "
            + "dataset_ids / manifest_ids (comma-separated agent run-level numbers from listMyData); "
            + "libraries (comma-separated, e.g. 'numpy,pandas'); timeout_seconds (default 30). "
            + "Sandbox input: paths_dataset.csv + path_manifest.csv; use "
            + "`from af_dataset_loader import load_manifest, load_datasets`. "
            + "Runtime preinstalled: numpy==2.4.1, pandas==2.3.3, matplotlib==3.10.8, scipy==1.17.0. "
            + "Finance questions can pass the raw natural-language expression to resolveFinanceMethods first. "
            + "If a candidate has unresolved boundaries, do not invent values; use compatible public libraries when available but not mandatory; "
            + "custom calculations must declare generic fields; pass the resolver root resolverToolCallId to report() or report_custom() "
            + "via the source_resolver_tool_call_id parameter. "
            + "See loadToolDescription() for full docs (load failure falls back to a hardcoded equivalent).";

    private static final String FALLBACK_TOOL_DESCRIPTION = "Execute Python code in a secure sandbox. REQUIRED: code and at least one of "
            + "dataset_ids / manifest_ids. IDs are agent run-level numbers from listMyData, not raw datasetId strings or paths. "
            + "dataset_ids and manifest_ids may be comma-separated numbers (e.g. '1,3'); prefer manifest_ids for grouped data. "
            + "Sandbox injects /sandbox/paths_dataset.csv and /sandbox/path_manifest.csv with real task-local paths. "
            + "In Python, use from af_dataset_loader import load_manifest, load_datasets; load_manifest('1') returns "
            + "DatasetLoadResult with frame / failed_members / skipped_members; load_datasets('1') returns dict[from_ts_code, DataFrame]. "
            + "For multiple datasets/manifests, load one run-level number at a time in helper code and merge results. "
            + "Do not construct /sandbox/input/<dataset_id>/ or /sandbox/runs/<oldTaskId>/ paths. "
            + "Finance questions can pass the raw natural-language expression to resolveFinanceMethods first. "
            + "If a candidate has unresolved boundaries, do not invent values; use compatible public libraries when available but not mandatory; "
            + "custom calculations must declare generic fields; pass the resolver root resolverToolCallId to report() or report_custom() "
            + "via the source_resolver_tool_call_id parameter. "
            + "OPTIONAL: libraries (comma-separated, e.g. 'numpy,pandas'), timeout_seconds. "
            + "Runtime preinstalled: numpy==2.4.1, pandas==2.3.3, matplotlib==3.10.8, scipy==1.17.0. "
            + "Service stack: fastapi==0.128.0, uvicorn[standard]==0.40.0, pydantic==2.12.5, llm-sandbox[docker]==0.3.33. "
            + "Please prioritize using the preinstalled runtime libraries to reduce latency.";

    /**
     * 加载完整工具说明，供 ToolRouter、同包代码与单元测试调用。
     * 优先读取 {@link #TOOL_DESCRIPTION_PATH}；读不到或内容为空白时，使用 {@link #FALLBACK_TOOL_DESCRIPTION}。
     */
    public static String loadToolDescription() {
        String loaded = PromptFileLoader.load(TOOL_DESCRIPTION_PATH);
        if (loaded == null || loaded.isBlank()) {
            log.warn("TOOL_DESCRIPTION classpath resource missing: {} — falling back to hardcoded text", TOOL_DESCRIPTION_PATH);
            return FALLBACK_TOOL_DESCRIPTION;
        }
        return loaded;
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
    private DataAnalysisCapacityProperties dataAnalysisCapacityProperties;

    @Autowired(required = false)
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

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

    /**
     * LangChain4j 暴露给 LLM 的五参数入口；参数名与工具描述中的字段一一对应。
     */
    @Tool(TOOL_DESCRIPTION_SHORT)
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
        } catch (ExternalToolJobPendingException pending) {
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

    private String executeDataIntense(
            String runId,
            AgentRunDatasetSnapshot datasetSnapshot,
            List<AgentRunDatasetEntry> datasets,
            List<AgentRunDatasetEntry> manifests,
            ExecuteRequest baseRequest,
            int timeoutSeconds,
            long toolStartMs) throws Exception {
        // 等待策略必须来自 executor 已冻结的 effective workflow；未知值不能猜成 LINEAR。
        Optional<PythonWaitPolicy> resolvedWaitPolicy =
                PythonWaitPolicy.fromWorkflow(AgentContext.getWorkflow());
        if (resolvedWaitPolicy.isEmpty()) {
            return fail("executePython", "WORKFLOW_MODE_UNAVAILABLE",
                    "executePython requires an effective workflow of linear or dag",
                    Map.of("workflow", nvl(AgentContext.getWorkflow())));
        }
        PythonWaitPolicy waitPolicy = resolvedWaitPolicy.get();

        // toolCallId 来自当前 Todo 的 AgentContext，是跨 worker 恢复的稳定逻辑调用身份。
        String toolCallId = AgentContext.getToolCallId();
        if (toolCallId == null || toolCallId.isBlank()) {
            return fail("executePython", "TOOL_JOB_IDENTITY_UNAVAILABLE",
                    "executePython requires a stable tool call id", Map.of("run_id", runId));
        }
        // 首次调用从 attempt=1 开始；后续重试必须使用新轮次。
        int attempt = 1;
        // operationId 由 runId/toolCallId/attempt 确定性派生，Sandbox create 可据此幂等查找。
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                runId, toolCallId, attempt);

        // estimate 同时用于准入、reservation 和终态 release proof，必须在分发前冻结。
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
                    return fail("executePython", "DATA_ANALYSIS_ESTIMATE_UNAVAILABLE",
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
            // 资源档位只在这里冻结一次；后续 reservation、Sandbox request 和终态证明都复用它。
            decision = dataAnalysisCapacityProperties.classify(
                    rows, bytes, heavyOperationHints);
            if (decision.outcome()
                    == DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.REJECTED) {
                return fail("executePython", "DATA_ANALYSIS_TASK_TOO_LARGE",
                        "Dataset estimate exceeds Sandbox hard limits",
                        Map.of("estimated_rows", rows, "estimated_bytes", bytes));
            }
            // 构造 immutable estimate，后续写入 anchor 并在 finalizer 再使用。
            estimate = new DataAnalysisEstimate(
                    rows, bytes, datasets.size(), 1.0d, manifestMembers, heavyOperationHints,
                    decision.resourceClass(), decision.capacityUnits());
        } catch (ArithmeticException overflow) {
            return fail("executePython", "DATA_ANALYSIS_TASK_TOO_LARGE",
                    "Dataset estimate overflowed admission counters", Map.of());
        }

        // 修复判重必须在容量 reserve 和 Sandbox create 之前完成，避免原样重放占用配额。
        long timeoutMillis = timeoutSeconds * 1000L;
        CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(
                CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                identity.operationId(),
                sha256(baseRequest.getCode()),
                datasetSnapshot.immutableDigest(),
                decision.resourceClass(),
                decision.memoryLimitBytes(),
                timeoutMillis,
                runtimeEnvironmentVersion,
                sha256(baseRequest.getLibrariesList().stream().sorted().collect(Collectors.joining("\n"))),
                sha256(""));
        String pythonRequestFingerprint = spec.repairRequestFingerprint();
        PythonRepairContext repairContext = AgentContext.getPythonRepairContext();
        if (repairContext != null && repairContext.hasFailed(pythonRequestFingerprint)) {
            return fail("executePython", "REPEATED_FAILED_PYTHON_ATTEMPT",
                    "The same Python code and effective parameters already failed in this Todo; "
                            + "change the code or meaningful parameters before retrying",
                    Map.of("python_repair_attempt", repairContext.repairAttempt(),
                            "request_fingerprint", pythonRequestFingerprint));
        }

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
        ExecuteRequest request = baseRequest.toBuilder()
                .setResourceClass(reservation.resourceClass().name())
                .setEstimatedRows(estimate.estimatedRows())
                .setEstimatedBytes(estimate.estimatedBytes())
                .setFileCount(estimate.fileCount())
                .setCapacityUnits(estimate.capacityUnits())
                .setOperationId(identity.operationId())
                .setRequestFingerprint(spec.requestFingerprint())
                .setMemoryLimitBytes(spec.memoryLimitBytes())
                .setTimeoutMillis(spec.timeoutMillis())
                .setRuntimeEnvironmentVersion(spec.runtimeEnvironmentVersion())
                .setCanonicalSpecSchemaVersion(spec.schemaVersion())
                .setCodeHash(spec.codeHash())
                .setImmutableDatasetSnapshotDigest(spec.immutableDatasetSnapshotDigest())
                .setLibrariesDigest(spec.librariesDigest())
                .setSandboxOptionsDigest(spec.sandboxOptionsDigest())
                .build();

        // 在调用 createTask 之前先构造完整 PREPARING anchor，覆盖 RPC 成败不确定窗口。
        ToolJobAnchor anchor = new ToolJobAnchor();
        // 版本 2 起，预估与名额预留必须同源、请求指纹必须一致；对不上就拒绝。旧数据只有版本 1 才能兼容处理。
        anchor.setSchemaVersion(DATA_INTENSE_ANCHOR_SCHEMA_VERSION);
        // 幂等操作身份与请求指纹用于启动恢复查询/重放。
        anchor.setOperationId(identity.operationId());
        anchor.setRequestFingerprint(spec.requestFingerprint());
        anchor.setPythonRequestFingerprint(pythonRequestFingerprint);
        // 新请求已经写了自己的数据库进度记录，上一轮终态后等待启动的修复阶段到此结束。
        anchor.setPythonRepairPending(false);
        anchor.setPythonRepairExhausted(false);
        if (repairContext != null) {
            anchor.setPythonRepairAttempt(repairContext.repairAttempt());
            anchor.setPythonFailedRequestFingerprints(repairContext.failedRequestFingerprints());
        }
        anchor.setCanonicalCreateSpecJson(objectMapper.writeValueAsString(spec));
        // createRequestJson 允许进程在 RPC 前后崩溃后重放同一 canonical 请求。
        anchor.setCreateRequestJson(JsonFormat.printer()
                .omittingInsignificantWhitespace().print(request));
        // PREPARING 表示容量已占用，但 Sandbox taskId 尚未确认附着。
        anchor.setAnchorState("PREPARING");
        anchor.setToolCallId(toolCallId);
        anchor.setAttempt(attempt);
        // 保存当前 Todo 位置，后续 pipeline 完整 checkpoint 会补充已完成前缀。
        anchor.setTodoId(AgentContext.getTodoId());
        anchor.setSequence(AgentContext.getTodoSequence() == null ? 0 : AgentContext.getTodoSequence());
        anchor.setRunDisposition(waitPolicy.runDisposition());
        anchor.setAutoResume(waitPolicy.autoResume());
        // reservation/estimate/dataset snapshot 都先写 anchor，确保旧 worker 退出前真相完整。
        anchor.setReservationJson(objectMapper.writeValueAsString(reservation));
        anchor.setEstimateJson(objectMapper.writeValueAsString(estimate));
        if (financeRecordChannelConfigLoader != null) {
            anchor.setFinanceRecordLimitsJson(financeRecordChannelConfigLoader.frozenSnapshotJson());
        }
        anchor.setDatasetSnapshotJson(objectMapper.writeValueAsString(datasetSnapshot));
        anchor.setDatasetSnapshotDigest(datasetSnapshot.immutableDigest());
        // timeoutAt 和 nextPollAt 都写进了数据库，重启后不重新计时。
        anchor.setTimeoutAt(Instant.now().plusMillis(timeoutMillis));
        anchor.setNextPollAt(Instant.now().plusMillis(POLL_INTERVAL_MS));
        if (!waitPolicy.durableSuspend()) {
            // ownerId 在 JVM 生命周期内稳定；租约从第一次数据库抢占前开始计时。
            anchor.setBlockingOwnerId(DagBlockingWorkerLease.processOwnerId());
            anchor.setBlockingLeaseUntil(DagBlockingWorkerLease.renewedUntil(Instant.now()));
        }

        // 必须先 CAS 占有 anchor，再调用有副作用的 createTask。恢复 worker 的第二次长工具
        // 不能走“空 anchor”路径：它必须用旧 LAUNCHING token/version 原子替换已消费 handoff。
        String resumeToken = AgentContext.getToolJobResumeToken();
        Long resumeLeaseVersion = AgentContext.getToolJobResumeLeaseVersion();
        boolean preparingPersisted;
        if (resumeToken != null && !resumeToken.isBlank()
                && resumeLeaseVersion != null && resumeLeaseVersion > 0) {
            preparingPersisted = pythonSandboxDispatchStore.persistPreparingFromResume(
                    runId, anchor, resumeToken, resumeLeaseVersion);
            if (preparingPersisted) {
                // 旧 handoff 已被这一版 PREPARING 消费；同一 worker 后续同步工具回到普通空-anchor CAS。
                AgentContext.clearToolJobResumeHandoff();
            }
        } else {
            preparingPersisted = pythonSandboxDispatchStore.persistPreparing(runId, anchor);
        }
        if (!preparingPersisted) {
            // 未取得 anchor owner 时释放尚未转交的容量。
            releasePreDispatch(reservation);
            // retryable=false：进度记录被别的流程占用时，同一 Run 内立刻重试必然再次失败
            // （曾有模型无停止信号连试 7 次烧完 480 秒的先例），所以直接告诉模型不可重试。
            return fail("executePython", "TOOL_JOB_ANCHOR_INVALID",
                    "Failed to persist PREPARING tool-job anchor",
                    Map.of("operation_id", identity.operationId(), "retryable", false));
        }

        /*
         * 从数据库里的 PREPARING（准备中）抢占成功开始，DAG 线程的任何异常退场都必须先移交
         * 负责者。局部路径负责更精确的 abort/poll 分类；这里的外层备用路径覆盖序列化、
         * 名额恢复、persistAttached 以及 create 身份不确定等未被局部 catch 的异常。
         */
        try {
            // createResp 可能来自首次 RPC，也可能来自 operationId 灾后查询。
            ExecuteResponse createResp;
            try {
                installDebugRpcAttachments();
                // Sandbox 必须按 operationId/requestFingerprint 幂等创建。
                createResp = pythonSandboxService.createTask(request);
            } catch (Exception createFailure) {
                try {
                    // RPC 异常不代表服务端未创建；先按 operationId 查找，禁止立即重建第二任务。
                    GetTaskByOperationIdResponse lookup = pythonSandboxService.getTaskByOperationId(
                            GetTaskByOperationIdRequest.newBuilder()
                                    .setOperationId(identity.operationId()).build());
                    if (lookup != null && lookup.getFound()
                            && !lookup.getTaskId().isBlank()
                            && !lookup.getRequestFingerprint().isBlank()
                            && spec.requestFingerprint().equals(lookup.getRequestFingerprint())) {
                        // canonical operation 必须返回完全相同且非空的 fingerprint，才能附着已有任务。
                        createResp = ExecuteResponse.newBuilder()
                                .setTaskId(lookup.getTaskId())
                                .setRequestFingerprint(spec.requestFingerprint())
                                .build();
                    } else if (lookup != null && !lookup.getFound()
                            && lookup.getError().isBlank()) {
                        /*
                         * 只有权威响应“未找到且无查询错误”才能证明 create 未发生。Gateway transport/
                         * 5xx/解析异常会返回 found=false + error；该状态不具备否定证明，必须保留
                         * PREPARING，避免真实 Sandbox task 已创建却被 Java 释放容量并清 anchor。
                         */
                        if (!waitPolicy.durableSuspend()) {
                            if (!abortDagBlockingPreparing(runId, anchor, reservation)) {
                                return dagBlockingLeaseLost(
                                        null, toolStartMs,
                                        "durable PREPARING abort was rejected after authoritative create result");
                            }
                            return fail("executePython", "CREATE_TASK_FAILED",
                                    "Sandbox create failed and the operation was authoritatively absent",
                                    Map.of("operation_id", identity.operationId(),
                                            "message", nvl(createFailure.getMessage())));
                        }
                        if (releasePreDispatch(reservation)) {
                            pythonSandboxDispatchStore.clearActive(runId, identity.operationId());
                        }
                        throw createFailure;
                    } else {
                        // 查询也无法证明结果时保留 PREPARING，交给 startup recovery 决定，不能猜测释放。
                        throw new IllegalStateException(
                                "createTask outcome is ambiguous; PREPARING anchor retained", createFailure);
                    }
                } catch (Exception lookupFailure) {
                    if (lookupFailure != createFailure) {
                        createFailure.addSuppressed(lookupFailure);
                    }
                    throw createFailure;
                }
            }
            // create 响应必须包含无错误的 taskId，否则在确认释放成功后清 active anchor。
            if (createResp == null || createResp.getError() != null && !createResp.getError().isEmpty()
                    || createResp.getTaskId() == null || createResp.getTaskId().isBlank()) {
                if (!waitPolicy.durableSuspend()) {
                    if (!abortDagBlockingPreparing(runId, anchor, reservation)) {
                        return dagBlockingLeaseLost(
                                null, toolStartMs,
                                "durable PREPARING abort was rejected after invalid create response");
                    }
                } else if (releasePreDispatch(reservation)) {
                    pythonSandboxDispatchStore.clearActive(runId, identity.operationId());
                }
                return fail("executePython", "CREATE_TASK_FAILED",
                        "Failed to create python sandbox task",
                        Map.of("message", createResp == null ? "empty response" : nvl(createResp.getError())));
            }
            String taskId = createResp.getTaskId();
            /*
             * create 响应里的 canonical fingerprint 是 taskId 的身份凭据，不是可选诊断字段。
             * 直接响应若为空或漂移，必须先用 operationId 做一次权威回读；只有同 taskId、精确且
             * 非空的 fingerprint 才允许 PREPARING→ATTACHED。查询错误、未找到、taskId 漂移
             * 都保留 PREPARING，严禁转普通 PENDING 后让 reconciler 消费未验证任务。
             */
            if (createResp.getRequestFingerprint().isBlank()
                    || !spec.requestFingerprint().equals(createResp.getRequestFingerprint())) {
                GetTaskByOperationIdResponse lookup = null;
                try {
                    lookup = pythonSandboxService.getTaskByOperationId(
                            GetTaskByOperationIdRequest.newBuilder()
                                    .setOperationId(identity.operationId()).build());
                } catch (Exception lookupFailure) {
                    log.error("Sandbox create identity lookup failed for run={}, operationId={}, taskId={}",
                            runId, identity.operationId(), taskId, lookupFailure);
                }
                boolean canonicalIdentityConfirmed = lookup != null
                        && lookup.getFound()
                        && taskId.equals(lookup.getTaskId())
                        && !lookup.getRequestFingerprint().isBlank()
                        && spec.requestFingerprint().equals(lookup.getRequestFingerprint());
                if (!canonicalIdentityConfirmed) {
                    log.error("Sandbox create identity unverified for run={}, operationId={}, taskId={}; "
                                    + "PREPARING anchor retained for fail-closed recovery",
                            runId, identity.operationId(), taskId);
                    throw new IllegalStateException(
                            "createTask identity is unverified; PREPARING anchor retained");
                }
            }

            // taskId 与 canonical fingerprint 同时确认后，才把 reservation 转为 TASK_ATTACHED。
            reservation = transitionReservation(reservation, DataAnalysisReservationState.TASK_ATTACHED, taskId);
            /*
             * 先生成完整 ATTACHED 快照，再改变本地 anchor；序列化失败时 outer fallback
             * 仍以 PREPARING 做 operationId recovery，成功后则 capacity 异常也携带 task proof。
             */
            String attachedReservationJson = objectMapper.writeValueAsString(reservation);
            anchor.setTaskId(taskId);
            anchor.setAnchorState("ATTACHED");
            anchor.setReservationJson(attachedReservationJson);
            // 容量账本必须接受同一 reservation 的附着状态，冲突时停止推进。
            if (dataAnalysisCapacityService.restoreReservation(reservation) == DataAnalysisRestoreOutcome.CONFLICT) {
                throw new IllegalStateException("capacity reservation attachment conflicted for task=" + taskId);
            }
            // taskId、ATTACHED 和名额状态一起写进数据库进度记录。
            if (!pythonSandboxDispatchStore.persistAttached(runId, anchor)) {
                if (!waitPolicy.durableSuspend()) {
                    return dagBlockingLeaseLost(
                            taskId, toolStartMs, "attach CAS rejected the live DAG owner");
                }
                throw new IllegalStateException("failed to persist attached Sandbox task");
            }

            // 两种策略先共享极短 fast-path；到期后 LINEAR 才让出 worker，DAG 改为阻塞轮询。
            long fastDeadline = System.currentTimeMillis() + Math.max(1L, fastPathMs);
            while (System.currentTimeMillis() < fastDeadline) {
                // 状态查询短而轻量，终态时再拉取结果体。
                TaskStatusResponse statusResp;
                try {
                    if (!waitPolicy.durableSuspend()
                            && !renewDagBlockingLease(runId, anchor, false)) {
                        return dagBlockingLeaseLost(
                                taskId, toolStartMs, "lease renewal was rejected before fast-path poll");
                    }
                    statusResp = getTaskStatus(taskId);
                } catch (Exception pollFailure) {
                    if (!waitPolicy.durableSuspend()) {
                        return promoteDagBlockingFailure(
                                runId,
                                anchor,
                                "DAG_BLOCKING_POLL_FAILED",
                                "DAG Sandbox status polling failed",
                                toolStartMs,
                                Map.of("task_id", taskId,
                                        "message", nvl(pollFailure.getMessage())));
                    }
                    throw pollFailure;
                }
                if (!waitPolicy.durableSuspend()) {
                    if (statusResp != null && !nvl(statusResp.getError()).isBlank()) {
                        return promoteDagBlockingFailure(
                                runId,
                                anchor,
                                "DAG_BLOCKING_POLL_FAILED",
                                "DAG Sandbox status polling failed",
                                toolStartMs,
                                Map.of("task_id", taskId,
                                        "message", nvl(statusResp.getError())));
                    }
                }
                String status = statusResp == null ? "" : nvl(statusResp.getStatus());
                if (isTerminal(status)) {
                    return finishTerminalByWaitPolicy(
                            runId, identity, estimate, reservation, anchor, status,
                            waitPolicy, toolStartMs);
                }
                // 每次最多睡 100ms，并且不越过 fastDeadline。
                try {
                    TimeUnit.MILLISECONDS.sleep(
                            Math.min(100L, Math.max(1L, fastDeadline - System.currentTimeMillis())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (!waitPolicy.durableSuspend()) {
                        return promoteDagBlockingFailure(
                                runId,
                                anchor,
                                "DAG_BLOCKING_INTERRUPTED",
                                "DAG Sandbox task polling was interrupted",
                                toolStartMs,
                                Map.of("task_id", taskId));
                    }
                    throw interrupted;
                }
            }
            // 线性模式在快路径后转后台等待；DAG 留在当前线程，禁止生成 WAITING_TOOL_JOB。
            if (waitPolicy.durableSuspend()) {
                return suspend(runId, anchor, reservation, taskId);
            }
            return pollDagBlocking(
                    runId, identity, estimate, reservation, anchor, toolStartMs);
        } catch (Exception lifecycleFailure) {
            if (!waitPolicy.durableSuspend()) {
                return promoteDagBlockingFailure(
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

    /**
     * 处理已经观察到的沙箱终态。线性模式如果同步走完终态流程失败，就把任务转成后台等待；
     * DAG 模式只能正常返回显式失败、保留数据库里的进度记录，交给恢复流程按「执行线程丢失」处理。
     */
    private String finishTerminalByWaitPolicy(
            String runId,
            DataAnalysisOperationIdentity identity,
            DataAnalysisEstimate estimate,
            DataAnalysisReservation reservation,
            ToolJobAnchor anchor,
            String status,
            PythonWaitPolicy waitPolicy,
            long toolStartMs) throws Exception {
        String taskId = anchor.getTaskId();
        // 终态结果必须同时证明 taskId、status、payload 完整性与 retryable 字段存在。
        if (!waitPolicy.durableSuspend()
                && !renewDagBlockingLease(runId, anchor, true)) {
            return dagBlockingLeaseLost(
                    taskId, toolStartMs, "lease renewal was rejected before terminal result fetch");
        }
        TaskResultResponse result;
        try {
            result = fetchTerminalResult(runId, taskId, status);
        } catch (Exception resultFailure) {
            if (!waitPolicy.durableSuspend()) {
                return promoteDagBlockingFailure(
                        runId,
                        anchor,
                        "DAG_BLOCKING_RESULT_FETCH_FAILED",
                        "DAG Sandbox terminal result fetch failed",
                        toolStartMs,
                        Map.of("task_id", taskId,
                                "status", status,
                                "message", nvl(resultFailure.getMessage())));
            }
            throw resultFailure;
        }
        if (!waitPolicy.durableSuspend()
                && !renewDagBlockingLease(runId, anchor, true)) {
            return dagBlockingLeaseLost(
                    taskId, toolStartMs, "lease renewal was rejected after terminal result fetch");
        }
        if (result != null && result.hasRetryable()) {
            try {
                String completed = completeSynchronously(
                        runId, identity, estimate, reservation, anchor, status, result);
                if (completed != null) {
                    emitSandboxToolTotal(toolStartMs, "OK", "");
                    return completed;
                }
            } catch (Exception terminalFailure) {
                log.warn("Synchronous terminal finalization incomplete: run={}, taskId={}, "
                                + "waitPolicy={}, error={}",
                        runId, taskId, waitPolicy, terminalFailure.getMessage());
            }
        }
        if (waitPolicy.durableSuspend()) {
            return suspend(runId, anchor, reservation, taskId);
        }
        return promoteDagBlockingFailure(
                runId,
                anchor,
                "DAG_BLOCKING_TERMINAL_INCOMPLETE",
                "DAG Sandbox task reached terminal state but durable finalization proof is incomplete",
                toolStartMs,
                Map.of("task_id", taskId, "status", status));
    }

    /**
     * DAG 节点在同一 worker 内阻塞轮询到 anchor 已冻结的 timeoutAt。
     * 本方法从不调用 transferToPending，也不抛 ExternalToolJobPendingException。
     */
    private String pollDagBlocking(
            String runId,
            DataAnalysisOperationIdentity identity,
            DataAnalysisEstimate estimate,
            DataAnalysisReservation reservation,
            ToolJobAnchor anchor,
            long toolStartMs) throws Exception {
        String taskId = anchor.getTaskId();
        Instant timeoutAt = anchor.getTimeoutAt();
        int pollIndex = 0;
        String lastRemoteStatus = "";
        while (true) {
            TaskStatusResponse statusResp;
            try {
                if (!renewDagBlockingLease(runId, anchor, false)) {
                    return dagBlockingLeaseLost(
                            taskId, toolStartMs, "lease renewal was rejected before blocking poll");
                }
                statusResp = getTaskStatus(taskId);
            } catch (Exception pollFailure) {
                log.warn("DAG blocking poll failed: run={}, taskId={}, error={}",
                        runId, taskId, pollFailure.getMessage());
                return promoteDagBlockingFailure(
                        runId,
                        anchor,
                        "DAG_BLOCKING_POLL_FAILED",
                        "DAG Sandbox status polling failed",
                        toolStartMs,
                        Map.of("task_id", taskId, "message", nvl(pollFailure.getMessage())));
            }
            if (statusResp != null && !nvl(statusResp.getError()).isBlank()) {
                return promoteDagBlockingFailure(
                        runId,
                        anchor,
                        "DAG_BLOCKING_POLL_FAILED",
                        "DAG Sandbox status polling failed",
                        toolStartMs,
                        Map.of("task_id", taskId,
                                "message", nvl(statusResp.getError())));
            }
            String status = statusResp == null ? "" : nvl(statusResp.getStatus());
            if (pollIndex == 0 || !status.equals(lastRemoteStatus) || pollIndex % 5 == 0) {
                emitSandboxEvent("sandbox_poll", Map.of(
                        "status", "OK",
                        "pollIndex", pollIndex,
                        "remoteStatus", status,
                        "taskId", taskId,
                        "waitPolicy", PythonWaitPolicy.BLOCKING_POLL.name()));
            }
            lastRemoteStatus = status;
            pollIndex++;
            if (isTerminal(status)) {
                return finishTerminalByWaitPolicy(
                        runId, identity, estimate, reservation, anchor, status,
                        PythonWaitPolicy.BLOCKING_POLL, toolStartMs);
            }

            long remainingMillis = timeoutAt.toEpochMilli() - System.currentTimeMillis();
            if (remainingMillis <= 0L) {
                return promoteDagBlockingFailure(
                        runId,
                        anchor,
                        "DAG_BLOCKING_TIMEOUT",
                        "DAG Sandbox task did not reach terminal state before the frozen timeout",
                        toolStartMs,
                        Map.of("task_id", taskId, "timeout_at", timeoutAt.toString()));
            }
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(POLL_INTERVAL_MS, remainingMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return promoteDagBlockingFailure(
                        runId,
                        anchor,
                        "DAG_BLOCKING_INTERRUPTED",
                        "DAG Sandbox task polling was interrupted",
                        toolStartMs,
                        Map.of("task_id", taskId));
            }
        }
    }

    private boolean renewDagBlockingLease(
            String runId,
            ToolJobAnchor anchor,
            boolean force) {
        Instant expectedLeaseUntil = anchor.getBlockingLeaseUntil();
        Instant now = Instant.now();
        if (expectedLeaseUntil == null
                || anchor.getBlockingOwnerId() == null
                || anchor.getBlockingOwnerId().isBlank()) {
            return false;
        }
        if (!force && expectedLeaseUntil.isAfter(
                now.plusMillis(DAG_LEASE_RENEW_AHEAD_MILLIS))) {
            return true;
        }
        Instant renewedUntil = DagBlockingWorkerLease.renewedUntil(now);
        anchor.setBlockingLeaseUntil(renewedUntil);
        boolean renewed;
        try {
            renewed = pythonSandboxDispatchStore.renewDagBlockingLease(
                    runId, anchor, expectedLeaseUntil);
        } catch (Exception renewalFailure) {
            log.warn("DAG blocking lease renewal failed: run={}, taskId={}, owner={}, error={}",
                    runId, anchor.getTaskId(), anchor.getBlockingOwnerId(),
                    renewalFailure.getMessage());
            renewed = false;
        }
        if (!renewed) {
            // 续租失败后，旧线程不能拿着新租约继续写数据库。
            anchor.setBlockingLeaseUntil(expectedLeaseUntil);
        }
        return renewed;
    }

    private boolean abortDagBlockingPreparing(
            String runId,
            ToolJobAnchor anchor,
            DataAnalysisReservation preparingReservation) throws Exception {
        Instant expectedLeaseUntil = anchor.getBlockingLeaseUntil();
        if (!renewDagBlockingLease(runId, anchor, true)) {
            return false;
        }
        expectedLeaseUntil = anchor.getBlockingLeaseUntil();
        DataAnalysisReservation released = transitionReservation(
                preparingReservation,
                DataAnalysisReservationState.RELEASED,
                null);
        // 先生成 durable proof，再改变本地 anchor；序列化失败时 outer fallback 仍看见 PREPARING。
        String releasedReservationJson = objectMapper.writeValueAsString(released);
        String previousReservationJson = anchor.getReservationJson();
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setReservationJson(releasedReservationJson);

        boolean began;
        try {
            began = pythonSandboxDispatchStore.beginDagBlockingPreparingAbort(
                    runId, anchor, expectedLeaseUntil);
        } catch (Exception beginFailure) {
            /*
             * DB outcome 不确定：若 begin 未提交，outer fallback 需要用原 PREPARING 快照
             * 做 WORKER_LOST CAS；若已提交，DB 的 ABORTING disposition 会拒绝该 CAS，
             * durable abort intent 仍安全保留。
             */
            anchor.setAnchorState("PREPARING");
            anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_NO_RESUME);
            anchor.setReservationJson(previousReservationJson);
            throw beginFailure;
        }
        if (!began) {
            // takeover/过期/operation 漂移时绝不能触碰容量账本。
            return false;
        }

        if (!releasePreDispatch(preparingReservation, DataAnalysisReleaseReason.PREPARING_ABORTED)) {
            // durable ABORTING/RELEASED intent 留给恢复者重入；不能猜测清 anchor。
            return false;
        }
        return pythonSandboxDispatchStore.completeDagBlockingPreparingAbort(
                runId, anchor, expectedLeaseUntil);
    }

    private String promoteDagBlockingFailure(
            String runId,
            ToolJobAnchor anchor,
            String errorCode,
            String message,
            long toolStartMs,
            Map<String, Object> details) {
        Instant expectedLeaseUntil = anchor.getBlockingLeaseUntil();
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_WORKER_LOST);
        anchor.setAutoResume(false);
        anchor.setFinalizerError(errorCode);
        anchor.setNextPollAt(Instant.now());
        boolean promoted;
        try {
            promoted = pythonSandboxDispatchStore.promoteDagBlockingWorkerLost(
                    runId, anchor, expectedLeaseUntil);
        } catch (Exception promotionFailure) {
            log.warn("DAG blocking cleanup ownership transfer failed: run={}, taskId={}, "
                            + "owner={}, error={}",
                    runId, anchor.getTaskId(), anchor.getBlockingOwnerId(),
                    promotionFailure.getMessage());
            promoted = false;
        }
        if (!promoted) {
            return dagBlockingLeaseLost(
                    anchor.getTaskId(), toolStartMs,
                    "live-to-cleanup ownership CAS was rejected");
        }
        emitSandboxToolTotal(toolStartMs,
                "DAG_BLOCKING_TIMEOUT".equals(errorCode) ? "TIMEOUT" : "ERROR",
                errorCode);
        return fail("executePython", errorCode, message, details);
    }

    private String dagBlockingLeaseLost(
            String taskId,
            long toolStartMs,
            String reason) {
        emitSandboxToolTotal(toolStartMs, "ERROR", "DAG_BLOCKING_LEASE_LOST");
        return fail("executePython", "DAG_BLOCKING_LEASE_LOST",
                "DAG Sandbox worker lost its durable blocking lease",
                Map.of("task_id", nvl(taskId), "reason", nvl(reason)));
    }

    private String completeSynchronously(
            String runId,
            DataAnalysisOperationIdentity identity,
            DataAnalysisEstimate estimate,
            DataAnalysisReservation attached,
            ToolJobAnchor anchor,
            String status,
            TaskResultResponse result) throws Exception {
        DataAnalysisReservation confirmed = transitionReservation(
                attached, DataAnalysisReservationState.TERMINAL_CONFIRMED, attached.taskId());
        if (dataAnalysisCapacityService.restoreReservation(confirmed) == DataAnalysisRestoreOutcome.CONFLICT) {
            return null;
        }
        FinanceRecordExtractionResult financeResult = processFinanceResult(
                runId, identity, anchor, status, result);
        // Build the public allowlist before persisting ENVELOPE. A projection/serialization
        // failure must never leave a durable success preview behind.
        String output = formatResult(status, result, financeResult);
        String preview = output;
        String rawRef = blankToNull(result.getDatasetDir());
        String errorCode = blankToNull(result.getError());
        if (!"SUCCEEDED".equals(status) && errorCode == null) {
            errorCode = status;
        }
        Instant terminalAt = Instant.now();
        anchor.setAnchorState("TERMINAL");
        anchor.setTerminalStatus(status);
        anchor.setSandboxTerminalStatus(status);
        anchor.setTerminalResultPreview(preview);
        anchor.setTerminalRawRef(rawRef);
        anchor.setTerminalErrorCode(errorCode);
        anchor.setTerminalRetryable(result.getRetryable());
        anchor.setTerminalAt(terminalAt);
        anchor.setTerminalUsageJson(JsonFormat.printer()
                .omittingInsignificantWhitespace().print(result.getResourceUsage()));
        anchor.setReservationJson(objectMapper.writeValueAsString(confirmed));
        anchor.setFinalizerStep("ENVELOPE");
        if (!pythonSandboxDispatchStore.persistAttached(runId, anchor)) {
            return null;
        }
        DataAnalysisResourceUsage usage = toUsage(confirmed.resourceClass(), result);
        DataAnalysisTerminalEnvelope envelope = new DataAnalysisTerminalEnvelope(
                runId, identity.toolCallId(), identity.attempt(), identity.operationId(),
                attached.taskId(), status, "SUCCEEDED".equals(status), preview, rawRef,
                errorCode, "SUCCEEDED".equals(status) ? null : "sandbox " + status,
                result.getRetryable(), estimate, confirmed, usage, terminalAt, false);

        DataAnalysisReleaseOutcome released = dataAnalysisCapacityService.releaseReservation(
                new DataAnalysisReleaseRequest(confirmed,
                        new DataAnalysisReleaseProof.Terminal(envelope),
                        DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED));
        if (released != DataAnalysisReleaseOutcome.RELEASED
                && released != DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
            return null;
        }
        DataAnalysisReservation releasedReservation = transitionReservation(
                confirmed, DataAnalysisReservationState.RELEASED, confirmed.taskId());
        anchor.setReservationJson(objectMapper.writeValueAsString(releasedReservation));
        anchor.setFinalizerStep("RELEASE");
        if (!pythonSandboxDispatchStore.persistAttached(runId, anchor)) {
            return null;
        }
        DataAnalysisUpsertOutcome recorded = dataAnalysisTerminalRecorder.upsert(envelope);
        if (recorded != DataAnalysisUpsertOutcome.INSERTED
                && recorded != DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME) {
            return null;
        }
        anchor.setUsagePersisted(true);
        anchor.setFinalizerStep("USAGE");
        if (!pythonSandboxDispatchStore.persistAttached(runId, anchor)) {
            return null;
        }
        return output;
    }

    private String suspend(
            String runId,
            ToolJobAnchor anchor,
            DataAnalysisReservation current,
            String taskId) throws Exception {
        // 这个 Python 任务无法在短时间内完成。下面把它从当前线程移交给后台：先把占用的
        // 资源名额过户给后台任务（线程一旦释放，名额就没人管了），再把任务凭证
        // 和 Run 状态一起写进数据库，最后通知上层可以释放线程。
        // 先看数据库进度记录里有没有最新的资源占用信息，有就用它，防止拿调用栈里的旧数据覆盖新状态。
        if (anchor.getReservationJson() != null && !anchor.getReservationJson().isBlank()) {
            current = objectMapper.readValue(anchor.getReservationJson(), DataAnalysisReservation.class);
        }
        // 只有 TASK_ATTACHED（任务已交给沙箱）需要转成 PENDING_TRANSFERRED（待过户给后台）；
        // 更靠后的状态保持原样，让本方法可以安全重入。
        DataAnalysisReservation pending = current.state() == DataAnalysisReservationState.TASK_ATTACHED
                ? transitionReservation(current, DataAnalysisReservationState.PENDING_TRANSFERRED, taskId)
                : current;
        // 把占用的资源名额从当前线程过户给后台任务。
        if (current.state() == DataAnalysisReservationState.TASK_ATTACHED
                && dataAnalysisCapacityService.restoreReservation(pending) == DataAnalysisRestoreOutcome.CONFLICT) {
            // 过户冲突（另一个流程已经接管了这份名额）按失败处理：此时绝不能释放线程，
            // 否则任务和名额都无人负责。
            throw new IllegalStateException("capacity transfer to pending conflicted");
        }
        // 把后台状态和最新的名额记录写进内存凭证，并安排好第一次后台轮询时间（同时写数据库和 Redis 到期索引）。
        anchor.setAnchorState("PENDING");
        anchor.setReservationJson(objectMapper.writeValueAsString(pending));
        anchor.setNextPollAt(Instant.now().plusMillis(POLL_INTERVAL_MS));
        // 一条 SQL 同时写任务凭证、并把 Run 状态从执行中改为等待长工具，要么都成功、要么都不改。
        if (!pythonSandboxDispatchStore.transferToPending(runId, anchor)) {
            // 落库失败按失败处理：不抛挂起信号，防止上层释放线程。
            throw new IllegalStateException("durable transfer to WAITING_TOOL_JOB failed");
        }
        // 到这里后台任务、名额和 Run 状态都已写进数据库。最后抛出一个专门的挂起信号：
        // 上层看到它就知道任务已转后台、可以释放线程，随后把信号转换成正常的挂起结果。
        throw new ExternalToolJobPendingException(
                runId, anchor.getToolCallId(), anchor.getAttempt(),
                "Python Sandbox task continues in background: " + taskId);
    }

    private TaskResultResponse fetchTerminalResult(
            String runId,
            String taskId,
            String expectedStatus) {
        installDebugRpcAttachments();
        TaskResultResponse result = pythonSandboxService.getTaskResult(
                GetTaskResultRequest.newBuilder().setTaskId(taskId).build());
        return SandboxTerminalResultValidator.validate(
                taskId, runId, result, expectedStatus);
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }

    private DataAnalysisResourceUsage toUsage(
            DataAnalysisResourceClass resourceClass,
            TaskResultResponse result) throws Exception {
        if (!result.hasResourceUsage()) {
            return DataAnalysisResourceUsage.missing(resourceClass);
        }
        return SandboxResourceUsageParser.parse(
                objectMapper,
                resourceClass,
                JsonFormat.printer().omittingInsignificantWhitespace()
                        .print(result.getResourceUsage()));
    }

    private boolean releasePreDispatch(DataAnalysisReservation reservation) {
        return releasePreDispatch(reservation, DataAnalysisReleaseReason.CREATE_NOT_STARTED);
    }

    private boolean releasePreDispatch(
            DataAnalysisReservation reservation,
            DataAnalysisReleaseReason reason) {
        DataAnalysisReleaseOutcome outcome = dataAnalysisCapacityService.releaseReservation(
                new DataAnalysisReleaseRequest(
                reservation,
                new DataAnalysisReleaseProof.PreDispatchAbort(reservation.identity()),
                reason));
        return outcome == DataAnalysisReleaseOutcome.RELEASED
                || outcome == DataAnalysisReleaseOutcome.ALREADY_RELEASED;
    }

    private static DataAnalysisReservation transitionReservation(
            DataAnalysisReservation current,
            DataAnalysisReservationState state,
            String taskId) {
        return new DataAnalysisReservation(
                current.reservationId(), current.identity(), current.resourceClass(),
                current.capacityUnits(), state, taskId, current.acquiredAt());
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 向调试观测服务发送结构化事件。未启用 {@link DebugObservabilityService} 时不执行任何操作。
     *
     * @param eventType 事件类型，如 {@code sandbox_poll}、{@code sandbox_create_task}
     * @param fields 与 eventType 配套的键值对，方法内会追加 {@code eventType} 字段
     */
    private void emitSandboxEvent(String eventType, Map<String, Object> fields) {
        if (debugObservabilityService == null || !debugObservabilityService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(fields);
            payload.put("eventType", eventType);
            debugObservabilityService.emit(payload);
        } catch (Exception ignored) {
            // 调试观测路径上的异常不应影响工具主流程
        }
    }

    /**
     * 在发起 Dubbo 调用前，把调试会话 id、run id、会话目录写入 RpcContext attachment，
     * 以便沙箱服务侧把日志与产物归档到同一调试目录。
     */
    private void installDebugRpcAttachments() {
        if (debugObservabilityService == null || !debugObservabilityService.isEnabled()) {
            return;
        }
        try {
            String sessionId = AgentContext.getDebugObservabilitySessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            RpcContext.getClientAttachment().setAttachment(DebugObservabilityRpcKeys.SESSION_ID, sessionId);
            String runId = AgentContext.getRunId();
            if (runId != null && !runId.isBlank()) {
                RpcContext.getClientAttachment().setAttachment(DebugObservabilityRpcKeys.RUN_ID, runId);
            }
            String sessionDir = debugObservabilityService.sessionDirFor(sessionId);
            if (sessionDir != null) {
                RpcContext.getClientAttachment().setAttachment(DebugObservabilityRpcKeys.SESSION_DIR, sessionDir);
            }
        } catch (Exception ignored) {
            // 调试观测路径上的异常不应影响工具主流程
        }
    }

    /** 工具调用结束时发送汇总事件，附带总耗时、终态 status，以及可选的主机堆内存快照。 */
    private void emitSandboxToolTotal(long toolStartMs, String status, String errorCategory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", System.currentTimeMillis() - toolStartMs);
        payload.put("status", status);
        if (errorCategory != null && !errorCategory.isBlank()) {
            payload.put("errorCategory", errorCategory);
        }
        appendHostSnapshot(payload);
        emitSandboxEvent("sandbox_tool_total", payload);
    }

    /** 把当前 JVM 堆使用与 OS 负载写入 payload，供性能排查时对照沙箱耗时。 */
    private void appendHostSnapshot(Map<String, Object> payload) {
        try {
            Runtime runtime = Runtime.getRuntime();
            payload.put("heapFreeBytes", runtime.freeMemory());
            payload.put("heapTotalBytes", runtime.totalMemory());
            payload.put("heapMaxBytes", runtime.maxMemory());
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            payload.put("systemLoadAverage", osBean.getSystemLoadAverage());
            payload.put("availableProcessors", osBean.getAvailableProcessors());
        } catch (Exception ignored) {
            // 主机资源快照为可选项，采集失败时忽略
        }
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
        for (String token : tokens) {
            if (!allowEmptyTokens && (token == null || token.isBlank())) {
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(token);
            } catch (NumberFormatException nfe) {
                // 非整数 token 无法对应 run 级编号，记入 illegal 并继续处理后续 token。
                illegal.add(Map.of("input", token, "reason", "not_an_integer"));
                continue;
            }
            Optional<AgentRunDatasetEntry> hit = "manifest".equals(kind)
                    ? registry.findManifestByNumber(runId, number)
                    : registry.findDatasetByNumber(runId, number);
            if (hit.isPresent()) {
                resolved.add(hit.get());
            } else {
                // 整数合法但当前 run 的对应编号空间里不存在该编号。
                illegal.add(Map.of(
                        "input", token,
                        "reason", "no_" + kind + "_with_this_run_level_number"
                ));
            }
        }
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
        if (datasetIds == null) {
            return new String[0];
        }
        String trimmed = datasetIds.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        // 去掉 JSON 数组外层的方括号，后续仍按逗号拆分。
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .map(item -> {
                    String id = item;
                    // 去掉 JSON 字符串元素两侧的双引号。
                    if (id.startsWith("\"") && id.endsWith("\"") && id.length() >= 2) {
                        id = id.substring(1, id.length() - 1).trim();
                    }
                    return id;
                })
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * 把沙箱执行结果转为工具统一的 JSON 响应。
     * 进程 exit code 为 0 时走 {@link #ok}；非零时仍附带 stdout/stderr 到 {@code data}，但 {@code ok=false}，
     * 方便 LLM 读取输出内容的同时识别执行失败。
     */
    private String formatResult(
            String status,
            TaskResultResponse result,
            FinanceRecordExtractionResult financeResult) {
        String stdout = financeResult == null
                ? nvl(result.getStdout()) : financeResult.ordinaryStdout();

        if ("SUCCEEDED".equals(status) && result.getExitCode() == 0) {
            if (financeResult == null) {
                return formatter().formatSuccess(stdout, List.of(), List.of());
            }
            if (financeResultModelAdapter == null) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RESULT_PROJECTOR_UNAVAILABLE",
                        "Finance result projector is unavailable");
            }
            FinanceResultModelAdapter.ProjectionBatch projection =
                    financeResultModelAdapter.project(financeResult);
            return formatter().formatSuccess(
                    stdout, projection.results(), projection.notices());
        }

        String errorCode = "CANCELED".equals(status)
                ? "PYTHON_EXECUTION_CANCELED" : "PYTHON_EXECUTION_FAILED";
        String message = "CANCELED".equals(status)
                ? "Python 执行已取消" : "Python 执行失败";
        String action = result.getRetryable()
                ? "根据 stderr 修正代码或输入后重试"
                : "检查输入和资源限制；如问题持续，请联系管理员";
        return formatter().formatFailure(
                stdout,
                nvl(result.getStderr()),
                new FinanceToolResultFormatter.FailureDetail(
                        errorCode, message, result.getRetryable(), action));
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    /** 构造 {@code ok=false} 的标准 JSON 工具响应；{@code details} 供 LLM 或上层做结构化重试。 */
    private String fail(String tool, String code, String message, Map<String, Object> details) {
        return fail(tool, code, message, details, Map.of());
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", nvl(code));
        err.put("message", nvl(message));
        err.put("details", details == null ? Map.of() : details);
        payload.put("error", err);
        return writeJson(payload);
    }

    /** 序列化工具响应；序列化本身失败时返回最小可用的硬编码 JSON 错误串。 */
    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"ok\":false,\"tool\":\"executePython\",\"error\":{\"code\":\"JSON_SERIALIZE_ERROR\",\"message\":\"" + escapeJson(nvl(e.getMessage())) + "\"}}";
        }
    }

    private String nvl(String text) {
        return text == null ? "" : text;
    }

    private String escapeJson(String text) {
        return nvl(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 供单元测试与同包代码在构造完成后注入 registry；生产路径走 Spring {@code @Autowired(required=false)}。
     */
    void setAgentRunDatasetRegistry(AgentRunDatasetRegistry registry) {
        this.agentRunDatasetRegistry = registry;
    }
}
