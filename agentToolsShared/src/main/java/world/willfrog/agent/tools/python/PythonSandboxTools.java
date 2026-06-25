package world.willfrog.agent.tools.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.debug.DebugObservabilityRpcKeys;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.util.PromptFileLoader;
import world.willfrog.agent.workflow.AgentRunDatasetCsvWriter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

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

@Component
@Slf4j
public class PythonSandboxTools {

    private static final int POLL_INTERVAL_MS = 1000;

    /**
     * 260623-harness-optimization-02: TOOL_DESCRIPTION 文案现在通过 classpath 文件 {@link #TOOL_DESCRIPTION_PATH} 维护（03 owner）。
     * Java {@code @Tool} annotation 必须是 compile-time 常量，所以下面用 {@link #TOOL_DESCRIPTION_SHORT} 作为
     * 注入到 LLM 的简短提示语。文件加载的完整长文案通过 {@link #loadToolDescription()} 暴露给其他调用方
     * （例如 ToolRouter 在做工具描述拼接时），文件不存在时静默回落 {@link #FALLBACK_TOOL_DESCRIPTION}。
     * 部署阶段 NPE 风险面由 fallback 兜底。
     */
    private static final String TOOL_DESCRIPTION_PATH = "prompts/python/execute_python_tool_description.txt";

    private static final String TOOL_DESCRIPTION_SHORT =
            "Execute Python code in a secure sandbox. Inputs: code (required); at least one of "
            + "dataset_ids / manifest_ids (comma-separated agent run-level numbers from listMyData); "
            + "libraries (comma-separated, e.g. 'numpy,pandas'); timeout_seconds (default 30). "
            + "Sandbox input: paths_dataset.csv + path_manifest.csv; use "
            + "`from af_dataset_loader import load_manifest, load_datasets`. "
            + "Runtime preinstalled: numpy==2.4.1, pandas==2.3.3, matplotlib==3.10.8, scipy==1.17.0. "
            + "See loadToolDescription() for full docs (load failure falls back to a hardcoded equivalent).";

    private static final String FALLBACK_TOOL_DESCRIPTION = "Execute Python code in a secure sandbox. REQUIRED: code and at least one of "
            + "dataset_ids / manifest_ids. IDs are agent run-level numbers from listMyData, not raw datasetId strings or paths. "
            + "dataset_ids and manifest_ids may be comma-separated numbers (e.g. '1,3'); prefer manifest_ids for grouped data. "
            + "Sandbox injects /sandbox/paths_dataset.csv and /sandbox/path_manifest.csv with real task-local paths. "
            + "In Python, use from af_dataset_loader import load_manifest, load_datasets; load_manifest('1') returns "
            + "DatasetLoadResult with frame / failed_members / skipped_members; load_datasets('1') returns dict[from_ts_code, DataFrame]. "
            + "For multiple datasets/manifests, load one run-level number at a time in helper code and merge results. "
            + "Do not construct /sandbox/input/<dataset_id>/ or /sandbox/runs/<oldTaskId>/ paths. "
            + "OPTIONAL: libraries (comma-separated, e.g. 'numpy,pandas'), timeout_seconds. "
            + "Runtime preinstalled: numpy==2.4.1, pandas==2.3.3, matplotlib==3.10.8, scipy==1.17.0. "
            + "Service stack: fastapi==0.128.0, uvicorn[standard]==0.40.0, pydantic==2.12.5, llm-sandbox[docker]==0.3.33. "
            + "Please prioritize using the preinstalled runtime libraries to reduce latency.";

    /**
     * 暴露给 ToolRouter / 同包注入 / 单元测试的完整描述加载入口。
     * 先尝试 classpath 文件 {@link #TOOL_DESCRIPTION_PATH}，缺失 / 空白时回落到 {@link #FALLBACK_TOOL_DESCRIPTION}。
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
     * 260623-harness-optimization-02: 订阅 DatasetPersistedEvent 并提供 run 级别编号转译。
     * 可选注入（{@code required=false}），便于纯单元测试启动。
     */
    @Autowired(required = false)
    private AgentRunDatasetRegistry agentRunDatasetRegistry;

    @Autowired(required = false)
    private DebugObservabilityService debugObservabilityService;

    public PythonSandboxTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(TOOL_DESCRIPTION_SHORT)
    public String executePython(String code, String dataset_ids, String manifest_ids, String libraries, Integer timeout_seconds) {
        return executePythonInternal(code, dataset_ids, manifest_ids, libraries, timeout_seconds);
    }

    /**
     * 4-arg backward-compat shim：把 dataset_ids + libraries 走 5-arg 入口（manifest_ids=null）。
     * 注意：5-arg 模式下 dataset_ids 走"dataset 空间"，不再"先 dataset 后 manifest"双重解析。
     * 业务路径走 5-arg（LLM-facing @Tool），4-arg 仅供其他 Java 模块或历史测试调用。
     */
    public String executePython(String code, String dataset_ids, String libraries, Integer timeout_seconds) {
        return executePythonInternal(code, dataset_ids, null, libraries, timeout_seconds);
    }

    private String executePythonInternal(String code, String dataset_ids, String manifest_ids, String libraries, Integer timeout_seconds) {
        long toolStartMs = System.currentTimeMillis();
        try {
            long prepareStartMs = System.currentTimeMillis();
            String[] parsedDatasetNumbers = parseDatasetIds(dataset_ids);
            String[] parsedManifestNumbers = parseDatasetIds(manifest_ids);
            if (parsedDatasetNumbers.length == 0 && parsedManifestNumbers.length == 0) {
                return fail("executePython", "MISSING_IDS",
                        "dataset_ids and manifest_ids are both empty; at least one is required",
                        Map.of());
            }

            // 260623-harness-optimization-02: dataset_ids / manifest_ids 都是 agent run 级别编号。
            // 走 registry 解析为 01 持久化条目（originalId / sortKey / fromTsCode）。
            // Q4 拍板：dataset 和 manifest 各自独立编号空间，独立解析。
            // Q12: 非法编号报错并列出合法编号列表。
            String runId = AgentContext.getRunId();
            AgentRunDatasetRegistry registry = this.agentRunDatasetRegistry;
            if (runId == null || runId.isBlank() || registry == null) {
                return fail("executePython", "RUN_LEVEL_IDS_UNAVAILABLE",
                        "Agent run-level dataset ids require an active run and AgentRunDatasetRegistry",
                        Map.of("runId", nvl(runId)));
            }

            AgentRunDatasetSnapshot snapshot = registry.snapshot(runId);
            List<Integer> legalDatasetNumbers = registry.listDatasetNumbers(runId);
            List<Integer> legalManifestNumbers = registry.listManifestNumbers(runId);

            // 独立解析 dataset 和 manifest 空间
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

            // 260623-harness-optimization-02 round 4 (Option A 拍板):
            // manifest-only 时 resolvedDatasets=[] → writePathsDatasetCsv 只出 header →
            // sandbox_runner 不落盘 /sandbox/paths_dataset.csv → loader 退 legacy → 找不到 member 文件。
            // 这里隐式 walk 每个 selected manifest 的 related_dataset_ids（run-level number 空间，
            // spec §A.4 + Q1 拍板），把 member dataset entry 加到 resolvedDatasets 进 subSnapshot。
            // mount 顺序：显式 dataset → 显式 manifest → 隐式 related dataset（保持 primaryOriginalId 是第一个显式选中项）。
            Map<Integer, AgentRunDatasetEntry> datasetByNumber = new HashMap<>();
            for (AgentRunDatasetEntry ds : snapshot.datasets()) {
                datasetByNumber.put(ds.number(), ds);
            }
            Set<Integer> explicitNumbers = new HashSet<>(resolvedDatasets.size() * 2);
            for (AgentRunDatasetEntry ds : resolvedDatasets) {
                explicitNumbers.add(ds.number());
            }
            List<AgentRunDatasetEntry> relatedDatasets = new ArrayList<>();
            for (AgentRunDatasetEntry mf : resolvedManifests) {
                for (String relatedNumberStr : mf.relatedDatasetIds()) {
                    int relatedNumber;
                    try {
                        relatedNumber = Integer.parseInt(relatedNumberStr);
                    } catch (NumberFormatException nfe) {
                        log.warn("Manifest related_dataset_id is not a run-level number: runId={} manifestId={} value={}",
                                runId, mf.originalId(), relatedNumberStr);
                        continue;
                    }
                    if (explicitNumbers.contains(relatedNumber)) {
                        continue;
                    }
                    AgentRunDatasetEntry relatedDs = datasetByNumber.get(relatedNumber);
                    if (relatedDs == null) {
                        log.warn("Manifest related_dataset_id not in registry: runId={} manifestId={} number={}",
                                runId, mf.originalId(), relatedNumber);
                        continue;
                    }
                    explicitNumbers.add(relatedNumber);
                    relatedDatasets.add(relatedDs);
                }
            }

            // 收集要 mount 的 originalIds（Python 端 sandbox_runner.py 用现有 datasetIds 字段做 mount）
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

            // 构造 snapshot 形态供 CSV writer：
            // - paths_dataset.csv 包含显式 dataset + manifest 隐式 member dataset
            //   （manifest-only 时也是数据行，sandbox 才落盘 /sandbox/paths_dataset.csv）
            // - path_manifest.csv 反映当前 run 全量 manifest，方便 sandbox 内 manifest cross-ref。
            // 这是 Q13 snapshot 行为的 Java 形态，round 4 把 manifest 隐式 member 算进 sub-snapshot。
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

            String primaryOriginalId = originalIdsToMount.get(0);
            log.info("Executing python task for run-level ids: primary={}, total={}, datasetCount={}, manifestCount={}, relatedDatasetCount={}",
                    primaryOriginalId, originalIdsToMount.size(), resolvedDatasets.size(), resolvedManifests.size(), relatedDatasets.size());

            ExecuteRequest.Builder requestBuilder = ExecuteRequest.newBuilder()
                    .setCode(nvl(code))
                    .setDatasetId(primaryOriginalId);

            for (String oid : originalIdsToMount) {
                requestBuilder.addDatasetIds(oid);
            }

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

            // 260623-harness-optimization-02: 注入 run-level CSV (Python 端 sandbox_runner.py 替换占位符并落盘)
            requestBuilder.setPathsDatasetCsv(pathsDatasetCsv);
            requestBuilder.setPathManifestCsv(pathManifestCsv);

            long createStartMs = System.currentTimeMillis();
            installDebugRpcAttachments();
            ExecuteResponse createResp = pythonSandboxService.createTask(requestBuilder.build());
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

            long maxWaitMs = timeout * 1000L + 5000;
            long startTime = System.currentTimeMillis();
            int pollIndex = 0;
            String lastRemoteStatus = "";
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                long pollStartMs = System.currentTimeMillis();
                TaskStatusResponse statusResp = getTaskStatus(taskId);
                String remoteStatus = statusResp == null ? "" : nvl(statusResp.getStatus());
                boolean statusChanged = !remoteStatus.equals(lastRemoteStatus);
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
        } catch (Exception e) {
            log.error("Execute python tool error", e);
            emitSandboxToolTotal(toolStartMs, "ERROR", "TOOL_ERROR");
            return fail("executePython", "TOOL_ERROR", "Python sandbox invocation error", Map.of("message", nvl(e.getMessage())));
        }
    }

    private void emitSandboxEvent(String eventType, Map<String, Object> fields) {
        if (debugObservabilityService == null || !debugObservabilityService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(fields);
            payload.put("eventType", eventType);
            debugObservabilityService.emit(payload);
        } catch (Exception ignored) {
            // debug path must not affect tool execution
        }
    }

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
            // debug path must not affect tool execution
        }
    }

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
            // optional host snapshot
        }
    }

    /**
     * 把 caller 传的 token 列表解析为 resolved entries + illegal refs（Q4 拍板：dataset / manifest 各自独立空间）。
     *
     * @param tokens caller 传的 token 字符串（parseDatasetIds 后的结果，可能含非数字）
     * @param registry registry
     * @param runId agent run id
     * @param kind "dataset" 或 "manifest"，用于在 illegal ref 标 reason
     * @param resolved 成功解析的 entry 列表
     * @param illegal 解析失败的 ref 列表（input + reason）
     * @param allowEmptyTokens 是否允许空 token（实际两个空间都不允许，留 false）
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
                illegal.add(Map.of("input", token, "reason", "not_an_integer"));
                continue;
            }
            Optional<AgentRunDatasetEntry> hit = "manifest".equals(kind)
                    ? registry.findManifestByNumber(runId, number)
                    : registry.findDatasetByNumber(runId, number);
            if (hit.isPresent()) {
                resolved.add(hit.get());
            } else {
                illegal.add(Map.of(
                        "input", token,
                        "reason", "no_" + kind + "_with_this_run_level_number"
                ));
            }
        }
    }

    private TaskStatusResponse getTaskStatus(String taskId) {
        installDebugRpcAttachments();
        return pythonSandboxService.getTaskStatus(
                GetTaskStatusRequest.newBuilder().setTaskId(taskId).build()
        );
    }

    private String terminalOutput(String taskId, TaskStatusResponse statusResp) {
        String status = statusResp.getStatus();
        if ("SUCCEEDED".equals(status)) {
            long fetchStartMs = System.currentTimeMillis();
            installDebugRpcAttachments();
            TaskResultResponse result = pythonSandboxService.getTaskResult(
                    GetTaskResultRequest.newBuilder().setTaskId(taskId).build()
            );
            emitSandboxEvent("sandbox_fetch_result", Map.of(
                    "durationMs", System.currentTimeMillis() - fetchStartMs,
                    "status", "OK",
                    "taskId", taskId,
                    "exitCode", result == null ? -1 : result.getExitCode(),
                    "stdoutLen", result == null || result.getStdout() == null ? 0 : result.getStdout().length(),
                    "stderrLen", result == null || result.getStderr() == null ? 0 : result.getStderr().length()
            ));
            return formatResult(taskId, status, result);
        }
        if ("FAILED".equals(status)) {
            return fail("executePython", "TASK_FAILED", "Task failed", Map.of(
                    "task_id", taskId,
                    "status", status,
                    "message", nvl(statusResp.getError())
            ));
        }
        if ("CANCELED".equals(status)) {
            return fail("executePython", "TASK_CANCELED", "Task canceled", Map.of(
                    "task_id", taskId,
                    "status", status
            ));
        }
        if ("NOT_FOUND".equals(status)) {
            return fail("executePython", "TASK_NOT_FOUND", "Task not found", Map.of(
                    "task_id", taskId,
                    "status", status
            ));
        }
        return null;
    }

    private String[] parseDatasetIds(String datasetIds) {
        if (datasetIds == null) {
            return new String[0];
        }
        String trimmed = datasetIds.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return java.util.Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .map(item -> {
                    String id = item;
                    if (id.startsWith("\"") && id.endsWith("\"") && id.length() >= 2) {
                        id = id.substring(1, id.length() - 1).trim();
                    }
                    return id;
                })
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    private String formatResult(String taskId, String status, TaskResultResponse result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        data.put("status", status);
        data.put("exit_code", result.getExitCode());
        data.put("stdout", nvl(result.getStdout()));
        data.put("stderr", nvl(result.getStderr()));
        data.put("dataset_dir", nvl(result.getDatasetDir()));

        if (result.getExitCode() == 0) {
            return ok("executePython", data);
        }

        return fail("executePython", "NON_ZERO_EXIT", "Python execution finished with non-zero exit code", Map.of(
                "task_id", taskId,
                "status", status,
                "exit_code", result.getExitCode(),
                "stderr", nvl(result.getStderr())
        ), data);
    }

    private String ok(String tool, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("tool", tool);
        payload.put("data", data == null ? Map.of() : data);
        payload.put("error", null);
        return writeJson(payload);
    }

    private String fail(String tool, String code, String message, Map<String, Object> details) {
        return fail(tool, code, message, details, Map.of());
    }

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
     * 暴露给单测 / 同包注入：构造完成后 setter 注入 registry。
     * 业务路径走 Spring {@code @Autowired(required=false)}。
     */
    void setAgentRunDatasetRegistry(AgentRunDatasetRegistry registry) {
        this.agentRunDatasetRegistry = registry;
    }
}
