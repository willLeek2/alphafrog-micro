package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import world.willfrog.agent.platform.debug.DebugObservabilityJsonlAppender;
import world.willfrog.agent.platform.debug.DebugObservabilityRpcKeys;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@DubboService
@Slf4j
public class PythonSandboxGatewayServiceImpl extends DubboPythonSandboxServiceTriple.PythonSandboxServiceImplBase {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${sandbox.service.url}")
    private String sandboxUrl;

    public PythonSandboxGatewayServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecuteResponse createTask(ExecuteRequest request) {
        long startMs = System.currentTimeMillis();
        // 260605-2 §3: gateway-side observability — log entry + RestTemplate duration.
        // We log code length (not content) and counts to keep INFO lines bounded and avoid PII/code leakage.
        int codeLen = request.getCode() == null ? 0 : request.getCode().length();
        // 260623-harness-optimization-02: pathsDatasetCsv / pathManifestCsv 由 Java 端 AgentRunDatasetRegistry
        // 生成（含 /__AF_INPUT__/ placeholder + NONE marker），透传到 sandbox runner 做 placeholder 替换 + NONE 物化。
        int pathsCsvLen = request.getPathsDatasetCsv() == null ? 0 : request.getPathsDatasetCsv().length();
        int manifestCsvLen = request.getPathManifestCsv() == null ? 0 : request.getPathManifestCsv().length();
        log.info("sandbox.createTask: datasetId={}, datasetIds={}, timeoutSeconds={}, filesCount={}, "
                        + "librariesCount={}, codeLen={}, pathsCsvLen={}, manifestCsvLen={}",
                request.getDatasetId(), request.getDatasetIdsList(),
                request.getTimeoutSeconds(), request.getFilesCount(),
                request.getLibrariesCount(), codeLen, pathsCsvLen, manifestCsvLen);
        try {
            HttpExecuteRequest httpRequest = new HttpExecuteRequest();
            httpRequest.setDataset_id(request.getDatasetId());
            httpRequest.setDataset_ids(request.getDatasetIdsList());
            httpRequest.setCode(request.getCode());
            httpRequest.setFiles(request.getFilesList());
            httpRequest.setLibraries(request.getLibrariesList());
            if (request.getTimeoutSeconds() > 0) {
                httpRequest.setTimeout_seconds(request.getTimeoutSeconds());
            }
            // CSV 透传：可能为空字符串（agent run 没有 manifest 时 pathManifestCsv 为空），
            // Python 端必须能区分 "未传" 和 "传了空字符串"，所以这里 setXxx 始终调用（默认值 "" 即可）。
            httpRequest.setPaths_dataset_csv(request.getPathsDatasetCsv());
            httpRequest.setPath_manifest_csv(request.getPathManifestCsv());
            /*
             * data-intense canonical create 字段必须作为一个整体透传。旧实现只传了代码、数据集、
             * libraries 和 timeoutSeconds，导致 Python 侧退回默认 STANDARD 资源配置，同时完全
             * 收不到 operationId/requestFingerprint，createTask 的幂等索引形同虚设。
             *
             * proto3 标量没有 presence；因此只有 operationId 非空时才把 canonical 数值零值也
             * 写入 HTTP DTO。旧客户端没有 operationId 时继续沿用 Python 默认值，避免把空字符串
             * resource_class 或 0 memory_limit_bytes 发送给 Pydantic 后被 422 拒绝。
             */
            boolean canonicalCreate = request.getOperationId() != null
                    && !request.getOperationId().isBlank();
            if (canonicalCreate) {
                httpRequest.setResource_class(request.getResourceClass());
                httpRequest.setEstimated_rows(request.getEstimatedRows());
                httpRequest.setEstimated_bytes(request.getEstimatedBytes());
                httpRequest.setFile_count(request.getFileCount());
                httpRequest.setCapacity_units(request.getCapacityUnits());
                httpRequest.setOperation_id(request.getOperationId());
                httpRequest.setRequest_fingerprint(request.getRequestFingerprint());
                httpRequest.setMemory_limit_bytes(request.getMemoryLimitBytes());
                httpRequest.setTimeout_millis(request.getTimeoutMillis());
                httpRequest.setRuntime_environment_version(request.getRuntimeEnvironmentVersion());
                httpRequest.setCanonical_spec_schema_version(request.getCanonicalSpecSchemaVersion());
                httpRequest.setCode_hash(request.getCodeHash());
                httpRequest.setImmutable_dataset_snapshot_digest(
                        request.getImmutableDatasetSnapshotDigest());
                httpRequest.setLibraries_digest(request.getLibrariesDigest());
                httpRequest.setSandbox_options_digest(request.getSandboxOptionsDigest());
            } else if (request.getResourceClass() != null && !request.getResourceClass().isBlank()) {
                // 兼容尚未启用 canonical identity、但已经声明资源档位的过渡客户端。
                httpRequest.setResource_class(request.getResourceClass());
            }

            String endpoint = sandboxUrl + "/tasks";
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpCreateTaskResponse> response = restTemplate.postForEntity(
                    endpoint, httpRequest, HttpCreateTaskResponse.class);
            log.info("sandbox.http: endpoint=POST {}, httpStatus={}, durationMs={}",
                    endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);
            emitSandboxHttp("POST", endpoint, response.getStatusCode().value(),
                    System.currentTimeMillis() - httpStart, "OK", null);

            if (response.getBody() != null) {
                log.info("sandbox.createTask.result: taskId={}, status={}, totalDurationMs={}",
                        response.getBody().getTask_id(), response.getBody().getStatus(),
                        System.currentTimeMillis() - startMs);
                ExecuteResponse.Builder builder = ExecuteResponse.newBuilder()
                        .setTaskId(response.getBody().getTask_id())
                        .setStatus(response.getBody().getStatus());
                if (response.getBody().getExisting() != null) {
                    builder.setExisting(response.getBody().getExisting());
                }
                if (response.getBody().getRequest_fingerprint() != null) {
                    builder.setRequestFingerprint(response.getBody().getRequest_fingerprint());
                }
                return builder.build();
            } else {
                log.warn("sandbox.createTask.emptyBody: totalDurationMs={}", System.currentTimeMillis() - startMs);
                return ExecuteResponse.newBuilder().setError("Empty response from sandbox").build();
            }
        } catch (Exception e) {
            log.error("sandbox.createTask.failed: totalDurationMs={}, error={}",
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("POST", sandboxUrl + "/tasks", -1,
                    System.currentTimeMillis() - startMs, "ERROR", "CREATE_TASK_FAILED");
            return ExecuteResponse.newBuilder().setError(e.getMessage()).build();
        }
    }

    @Override
    public GetTaskByOperationIdResponse getTaskByOperationId(GetTaskByOperationIdRequest request) {
        long startMs = System.currentTimeMillis();
        String operationId = request.getOperationId();
        if (operationId == null || operationId.isBlank()) {
            return GetTaskByOperationIdResponse.newBuilder()
                    .setFound(false)
                    .setError("operationId is required")
                    .build();
        }
        try {
            /*
             * operationId 是 createTask 不确定结果恢复的唯一幂等索引。该查询必须桥接到
             * Python `/operations/{operation_id}`；若 Gateway 省略本 RPC，Java 在 create
             * 超时后既无法确认“已创建”也无法安全释放 PREPARING reservation。
             */
            // operationId 是单个 path segment；统一编码，避免斜杠、空格或 Unicode 改变路由含义。
            URI endpointUri = UriComponentsBuilder.fromHttpUrl(sandboxUrl)
                    .pathSegment("operations", operationId)
                    .build()
                    .encode()
                    .toUri();
            String endpoint = endpointUri.toASCIIString();
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpOperationLookupResponse> response = restTemplate.getForEntity(
                    endpointUri, HttpOperationLookupResponse.class);
            emitSandboxHttp("GET", endpoint, response.getStatusCode().value(),
                    System.currentTimeMillis() - httpStart, "OK", null);
            HttpOperationLookupResponse body = response.getBody();
            if (body == null) {
                return GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("Empty response from sandbox")
                        .build();
            }
            GetTaskByOperationIdResponse.Builder builder = GetTaskByOperationIdResponse.newBuilder()
                    .setFound(body.isFound());
            if (body.getTask_id() != null) builder.setTaskId(body.getTask_id());
            if (body.getStatus() != null) builder.setStatus(body.getStatus());
            if (body.getRequest_fingerprint() != null) {
                builder.setRequestFingerprint(body.getRequest_fingerprint());
            }
            if (body.getError() != null) builder.setError(body.getError());
            log.info("sandbox.getTaskByOperationId.result: operationId={}, found={}, taskId={}, "
                            + "totalDurationMs={}",
                    operationId, body.isFound(), body.getTask_id(),
                    System.currentTimeMillis() - startMs);
            return builder.build();
        } catch (Exception e) {
            log.error("sandbox.getTaskByOperationId.failed: operationId={}, totalDurationMs={}, error={}",
                    operationId, System.currentTimeMillis() - startMs, e.getMessage(), e);
            return GetTaskByOperationIdResponse.newBuilder()
                    .setFound(false)
                    .setError(e.getMessage() == null ? "operation lookup failed" : e.getMessage())
                    .build();
        }
    }

    @Override
    public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskStatus: taskId={}", request.getTaskId());
        try {
            String endpoint = sandboxUrl + "/tasks/" + request.getTaskId();
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpTask> response = restTemplate.getForEntity(endpoint, HttpTask.class);
            log.info("sandbox.http: endpoint=GET {}, httpStatus={}, durationMs={}",
                    endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);
            emitSandboxHttp("GET", endpoint, response.getStatusCode().value(),
                    System.currentTimeMillis() - httpStart, "OK", null);

            if (response.getBody() != null) {
                HttpTask task = response.getBody();
                TaskStatusResponse.Builder builder = TaskStatusResponse.newBuilder()
                        .setTaskId(task.getTask_id())
                        .setStatus(task.getStatus());
                if (task.getStarted_at() != null) builder.setStartedAt(task.getStarted_at());
                if (task.getFinished_at() != null) builder.setFinishedAt(task.getFinished_at());
                if (task.getError() != null) builder.setError(task.getError());
                log.info("sandbox.getTaskStatus.result: taskId={}, status={}, totalDurationMs={}",
                        task.getTask_id(), task.getStatus(), System.currentTimeMillis() - startMs);
                return builder.build();
            } else {
                log.warn("sandbox.getTaskStatus.emptyBody: taskId={}, totalDurationMs={}",
                        request.getTaskId(), System.currentTimeMillis() - startMs);
                return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError("Task not found").build();
            }
        } catch (HttpClientErrorException.NotFound e) {
            log.info("sandbox.getTaskStatus.notFound: taskId={}, totalDurationMs={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId(), 404,
                    System.currentTimeMillis() - startMs, "OK", "NOT_FOUND");
            return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError("Task not found").build();
        } catch (Exception e) {
            log.error("sandbox.getTaskStatus.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId(), -1,
                    System.currentTimeMillis() - startMs, "ERROR", "GET_STATUS_FAILED");
            return TaskStatusResponse.newBuilder().setStatus("UNKNOWN").setError(e.getMessage()).build();
        }
    }

    @Override
    public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskResult: taskId={}", request.getTaskId());
        try {
            // Check status first to ensure we don't hit 409
            TaskStatusResponse status = getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId(request.getTaskId()).build());
            if (isResultBearingTerminal(status.getStatus())) {
                String endpoint = sandboxUrl + "/tasks/" + request.getTaskId() + "/result";
                long httpStart = System.currentTimeMillis();
                ResponseEntity<HttpExecuteResult> response = restTemplate.getForEntity(endpoint, HttpExecuteResult.class);
                log.info("sandbox.http: endpoint=GET {}, httpStatus={}, durationMs={}",
                        endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);
                emitSandboxHttp("GET", endpoint, response.getStatusCode().value(),
                        System.currentTimeMillis() - httpStart, "OK", null);
                if (response.getBody() != null) {
                    HttpExecuteResult res = response.getBody();
                    int stdoutLen = res.getStdout() == null ? 0 : res.getStdout().length();
                    int stderrLen = res.getStderr() == null ? 0 : res.getStderr().length();
                    Map<String, Object> timingFields = extractTimingFields(res);
                    log.info("sandbox.getTaskResult.terminal: taskId={}, status={}, exitCode={}, stdoutLen={}, stderrLen={}, "
                                    + "envLoadMs={}, codeExecMs={}, artifactCollectMs={}, totalDurationMs={}",
                            request.getTaskId(), status.getStatus(), res.getExit_code(), stdoutLen, stderrLen,
                            timingFields.getOrDefault("env_load_ms", "-"),
                            timingFields.getOrDefault("code_exec_ms", "-"),
                            timingFields.getOrDefault("artifact_collect_ms", "-"),
                            System.currentTimeMillis() - startMs);
                    emitSandboxResultTiming(request.getTaskId(), res, System.currentTimeMillis() - httpStart);
                    TaskResultResponse.Builder builder = TaskResultResponse.newBuilder()
                            .setTaskId(request.getTaskId())
                            .setStatus(status.getStatus())
                            .setExitCode(res.getExit_code())
                            .setStdout(res.getStdout() != null ? res.getStdout() : "")
                            .setStderr(res.getStderr() != null ? res.getStderr() : "")
                            .setDatasetDir(res.getDataset_dir() != null ? res.getDataset_dir() : "");
                    if (status.getError() != null && !status.getError().isBlank()) {
                        builder.setError(status.getError());
                    }
                    if (res.getRetryable() != null) {
                        builder.setRetryable(res.getRetryable());
                    }
                    SandboxResourceUsage usage = toProtoUsage(res.getResource_usage());
                    if (usage != null) {
                        builder.setResourceUsage(usage);
                    }
                    return builder.build();
                }
            }

            log.info("sandbox.getTaskResult.notReady: taskId={}, status={}, totalDurationMs={}",
                    request.getTaskId(), status.getStatus(), System.currentTimeMillis() - startMs);
            return TaskResultResponse.newBuilder()
                    .setTaskId(request.getTaskId())
                    .setStatus(status.getStatus())
                    .setError("Result not available (Task " + status.getStatus() + ")")
                    .build();

        } catch (Exception e) {
            log.error("sandbox.getTaskResult.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId() + "/result", -1,
                    System.currentTimeMillis() - startMs, "ERROR", "GET_RESULT_FAILED");
            return TaskResultResponse.newBuilder().setError(e.getMessage()).build();
        }
    }

    private static boolean isResultBearingTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }

    static SandboxResourceUsage toProtoUsage(HttpSandboxResourceUsage usage) {
        if (usage == null) {
            return null;
        }
        SandboxResourceUsage.Builder builder = SandboxResourceUsage.newBuilder();
        if (usage.getResource_class() != null) builder.setResourceClass(usage.getResource_class());
        if (usage.getCpu_millis() != null) builder.setCpuMillis(usage.getCpu_millis());
        if (usage.getMemory_peak_bytes() != null) builder.setMemoryPeakBytes(usage.getMemory_peak_bytes());
        if (usage.getMemory_byte_millis() != null) builder.setMemoryByteMillis(usage.getMemory_byte_millis());
        if (usage.getLogical_bytes_scanned() != null) builder.setLogicalBytesScanned(usage.getLogical_bytes_scanned());
        if (usage.getArtifact_bytes_written() != null) builder.setArtifactBytesWritten(usage.getArtifact_bytes_written());
        if (usage.getTemporary_bytes_written() != null) builder.setTemporaryBytesWritten(usage.getTemporary_bytes_written());
        if (usage.getQueue_wait_millis() != null) builder.setQueueWaitMillis(usage.getQueue_wait_millis());
        if (usage.getPrepare_millis() != null) builder.setPrepareMillis(usage.getPrepare_millis());
        if (usage.getExecution_wall_millis() != null) builder.setExecutionWallMillis(usage.getExecution_wall_millis());
        if (usage.getCleanup_millis() != null) builder.setCleanupMillis(usage.getCleanup_millis());
        if (usage.getDataset_open_count() != null) builder.setDatasetOpenCount(usage.getDataset_open_count());
        if (usage.getExit_reason() != null) builder.setExitReason(usage.getExit_reason());
        builder.setOomKilled(Boolean.TRUE.equals(usage.getOom_killed()));
        builder.setTimedOut(Boolean.TRUE.equals(usage.getTimed_out()));
        builder.setAttributionComplete(Boolean.TRUE.equals(usage.getAttribution_complete()));
        if (usage.getSampling_interval_millis() != null) {
            builder.setSamplingIntervalMillis(usage.getSampling_interval_millis());
        }
        if (usage.getMissing_fields() != null) {
            builder.addAllMissingFields(usage.getMissing_fields());
        }
        return builder.build();
    }

    private void emitSandboxResultTiming(String taskId, HttpExecuteResult result, long durationMs) {
        Map<String, Object> timingFields = extractTimingFields(result);
        if (timingFields.isEmpty()) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("eventType", "sandbox_result_timing");
        fields.put("taskId", taskId);
        fields.put("durationMs", durationMs);
        fields.put("exitCode", result == null ? -1 : result.getExit_code());
        fields.putAll(timingFields);
        emitSandboxDebug(fields);
    }

    static Map<String, Object> extractTimingFields(HttpExecuteResult result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (result == null || result.getArtifacts() == null) {
            return fields;
        }
        Object rawTimings = result.getArtifacts().get("timings");
        if (!(rawTimings instanceof Map<?, ?> timings)) {
            return fields;
        }

        putTimingAlias(fields, timings, "env_load_ms", "workspace_prepare_ms");
        putTimingAlias(fields, timings, "code_exec_ms", "script_run_ms");
        putTimingAlias(fields, timings, "artifact_collect_ms", "workspace_cleanup_ms");

        putTiming(fields, timings, "queue_wait_ms");
        putTiming(fields, timings, "container_create_ms");
        putTiming(fields, timings, "workspace_prepare_ms");
        putTiming(fields, timings, "script_run_ms");
        putTiming(fields, timings, "workspace_cleanup_ms");
        putTiming(fields, timings, "total_runner_ms");
        putTiming(fields, timings, "total_duration_ms");
        return fields;
    }

    private static void putTimingAlias(Map<String, Object> fields, Map<?, ?> timings, String preferred, String fallback) {
        Object value = timingValue(timings, preferred);
        if (value == null) {
            value = timingValue(timings, fallback);
        }
        if (value != null) {
            fields.put(preferred, value);
        }
    }

    private static void putTiming(Map<String, Object> fields, Map<?, ?> timings, String key) {
        Object value = timingValue(timings, key);
        if (value != null) {
            fields.put(key, value);
        }
    }

    private static Object timingValue(Map<?, ?> timings, String key) {
        Object value = timings.get(key);
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignoredLong) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignoredDouble) {
                    return null;
                }
            }
        }
        return null;
    }

    private void emitSandboxHttp(String method,
                                 String endpoint,
                                 int httpStatus,
                                 long durationMs,
                                 String status,
                                 String errorCategory) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("eventType", "sandbox_http");
        fields.put("method", method);
        fields.put("endpoint", endpoint);
        fields.put("httpStatus", httpStatus);
        fields.put("durationMs", durationMs);
        fields.put("status", status);
        if (errorCategory != null && !errorCategory.isBlank()) {
            fields.put("errorCategory", errorCategory);
        }
        emitSandboxDebug(fields);
    }

    private void emitSandboxDebug(Map<String, Object> fields) {
        try {
            RpcContext context = RpcContext.getServiceContext();
            if (context == null) {
                return;
            }
            String sessionDir = context.getAttachment(DebugObservabilityRpcKeys.SESSION_DIR);
            if (sessionDir == null || sessionDir.isBlank()) {
                return;
            }
            DebugObservabilityJsonlAppender.append(
                    Path.of(sessionDir),
                    context.getAttachment(DebugObservabilityRpcKeys.RUN_ID),
                    context.getAttachment(DebugObservabilityRpcKeys.SESSION_ID),
                    "pythonSandboxGatewayService",
                    objectMapper,
                    fields
            );
        } catch (Exception ignored) {
            // debug path must not affect gateway RPC
        }
    }

    // Inner DTOs for JSON mapping
    @Data
    static class HttpExecuteRequest {
        private String dataset_id;
        private List<String> dataset_ids;
        private String code;
        private List<String> files;
        private List<String> libraries;
        private Double timeout_seconds;
        // 260623-harness-optimization-02: agent run 级 dataset / manifest CSV 注入。
        // Java 端 AgentRunDatasetRegistry 生成（含 /__AF_INPUT__/ placeholder + NONE marker），
        // Python 端在 _prepare_task_workspace 替换 placeholder 并把 CSVs 落到 workdir。
        private String paths_dataset_csv;
        private String path_manifest_csv;
        // 以下字段共同组成 canonical create contract；必须成组透传，不能只传其中一部分。
        private String resource_class;
        private Long estimated_rows;
        private Long estimated_bytes;
        private Integer file_count;
        private Integer capacity_units;
        private String operation_id;
        private String request_fingerprint;
        private Long memory_limit_bytes;
        private Long timeout_millis;
        private String runtime_environment_version;
        private String canonical_spec_schema_version;
        private String code_hash;
        private String immutable_dataset_snapshot_digest;
        private String libraries_digest;
        private String sandbox_options_digest;
    }

    @Data
    static class HttpCreateTaskResponse {
        private String task_id;
        private String status;
        private Boolean existing;
        private String request_fingerprint;
    }

    @Data
    static class HttpOperationLookupResponse {
        private boolean found;
        private String task_id;
        private String status;
        private String request_fingerprint;
        private String error;
    }

    @Data
    static class HttpTask {
        private String task_id;
        private String status;
        private String error;
        private String started_at;
        private String finished_at;
    }
    
    @Data
    static class HttpExecuteResult {
        private int exit_code;
        private String stdout;
        private String stderr;
        private String dataset_dir;
        private Map<String, Object> artifacts;
        private HttpSandboxResourceUsage resource_usage;
        private Boolean retryable;
    }

    @Data
    static class HttpSandboxResourceUsage {
        private String resource_class;
        private Long cpu_millis;
        private Long memory_peak_bytes;
        private Long memory_byte_millis;
        private Long logical_bytes_scanned;
        private Long artifact_bytes_written;
        private Long temporary_bytes_written;
        private Long queue_wait_millis;
        private Long prepare_millis;
        private Long execution_wall_millis;
        private Long cleanup_millis;
        private Integer dataset_open_count;
        private String exit_reason;
        private Boolean oom_killed;
        private Boolean timed_out;
        private Boolean attribution_complete;
        private Long sampling_interval_millis;
        private List<String> missing_fields;
    }
}
