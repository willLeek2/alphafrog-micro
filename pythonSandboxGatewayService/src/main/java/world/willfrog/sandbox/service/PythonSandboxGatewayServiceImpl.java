package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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

    // 260809-26Q3-stage1-w3 D13: dual RestTemplate beans with explicit timeouts.
    // Long-path serves createTask + getTaskResult (downstream may run max task duration).
    // Short-query serves getTaskStatus + getTaskByOperationId (+ future D11 cancelTask).
    // Every call site MUST bind the correct bean via @Qualifier proof.
    private final RestTemplate longHttpClient;
    private final RestTemplate shortHttpClient;
    private final ObjectMapper objectMapper;

    @Value("${sandbox.service.url}")
    private String sandboxUrl;

    public PythonSandboxGatewayServiceImpl(
            @Qualifier("sandboxLongHttpClient") RestTemplate longHttpClient,
            @Qualifier("sandboxShortHttpClient") RestTemplate shortHttpClient,
            ObjectMapper objectMapper
    ) {
        this.longHttpClient = longHttpClient;
        this.shortHttpClient = shortHttpClient;
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
            ResponseEntity<HttpCreateTaskResponse> response = longHttpClient.postForEntity(
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
                // Empty body received from downstream (HTTP success but no payload).
                // D13 §4.2: not a categorizable downstream rejection — UNSPECIFIED with
                // downstream_http_status=200 (proxied response was 2xx). Parent `error`
                // non-blank per D13 red line 4/5.
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(200)
                        .build();
                return ExecuteResponse.newBuilder()
                        .setError("Empty response from sandbox")
                        .setErrorDetail(detail)
                        .build();
            }
        } catch (HttpClientErrorException.Conflict e) {
            return buildCreateTaskHttpFailureResponse(e, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                    "sandbox.createTask.conflict", startMs);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return buildCreateTaskHttpFailureResponse(e, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    "sandbox.createTask.invalidArgument", startMs);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return buildCreateTaskHttpFailureResponse(e, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                    "sandbox.createTask.overloaded", startMs);
        } catch (HttpClientErrorException e) {
            // 401/403/other 4xx not explicitly mapped above: do NOT default to INVALID_ARGUMENT
            // per Cindy 4b89c2d6 #4 (avoid over-categorizing auth/permission errors). Fall back
            // to UNSPECIFIED with actual downstream status preserved.
            return buildCreateTaskHttpFailureResponse(e, SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                    "sandbox.createTask.httpClientError", startMs);
        } catch (HttpServerErrorException e) {
            // 5xx received from downstream (proxy returned response body). Includes 504 — note
            // 504 is DOWNSTREAM_FAILURE not GATEWAY_TIMEOUT per Cindy 4b89c2d6 #4 (downstream
            // did respond). 503 specifically maps to OVERLOADED_OR_UNAVAILABLE.
            SandboxHttpErrorCategory category = e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE;
            return buildCreateTaskHttpFailureResponse(e, category, "sandbox.createTask.serverError", startMs);
        } catch (ResourceAccessException e) {
            // Transport-layer failure: timeout vs DNS/conn-refused/TLS/IO split per Cindy 313d871e #3.
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            String text = e.getMessage() == null ? "createTask transport failure" : e.getMessage();
            log.warn("sandbox.createTask.transportFailure: totalDurationMs={}, category={}, error={}",
                    System.currentTimeMillis() - startMs,
                    detail.getCategory().getNumber(), text, e);
            emitSandboxHttp("POST", sandboxUrl + "/tasks", -1,
                    System.currentTimeMillis() - startMs, "ERROR", "CREATE_TASK_" + detail.getCategory());
            return ExecuteResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.createTask.failed: totalDurationMs={}, error={}",
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("POST", sandboxUrl + "/tasks", -1,
                    System.currentTimeMillis() - startMs, "ERROR", "CREATE_TASK_FAILED");
            // Uncategorized exception (e.g., serialization bug). No downstream HTTP response
            // observed — downstream_http_status stays absent.
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                    .build();
            String text = e.getMessage() == null ? "createTask failed" : e.getMessage();
            return ExecuteResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        }
    }

    /**
     * 260809-26Q3-stage1-w3 D13: helper for createTask downstream-HTTP-reject branches.
     * Writes both legacy `error` text (non-blank per D13 red line 4) and typed `error_detail`
     * (category + actual downstream status). The legacy text MUST stay non-blank so old
     * consumers reading only `error` retain fail-closed behavior.
     */
    private ExecuteResponse buildCreateTaskHttpFailureResponse(
            RuntimeException e, SandboxHttpErrorCategory category, String logKey, long startMs
    ) {
        int statusCode = extractDownstreamHttpStatus(e);
        String text = extractDownstreamErrorText(e, "createTask rejected by sandbox");
        log.warn("sandbox.{}: httpStatus={}, category={}, totalDurationMs={}, error={}",
                logKey, statusCode, category.name(),
                System.currentTimeMillis() - startMs, text, e);
        emitSandboxHttp("POST", sandboxUrl + "/tasks", statusCode,
                System.currentTimeMillis() - startMs, "ERROR", "CREATE_TASK_" + category.name());
        SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                .setCategory(category)
                .setDownstreamHttpStatus(statusCode)
                .build();
        return ExecuteResponse.newBuilder()
                .setError(text)
                .setErrorDetail(detail)
                .build();
    }

    /**
     * D13: classify a ResourceAccessException (Spring's wrapper for transport-layer faults)
     * into GATEWAY_TIMEOUT vs TRANSPORT_FAILURE per Cindy 313d871e #3:
     *   - ConnectTimeoutException / SocketTimeoutException → GATEWAY_TIMEOUT
     *   - UnknownHostException / ConnectException (refused) / SSLException / other IO → TRANSPORT_FAILURE
     * downstream_http_status is absent on both (no HTTP response was received).
     */
    static SandboxErrorDetail buildTransportErrorDetail(ResourceAccessException e) {
        Throwable cause = unwrap(e);
        if (isTimeoutCause(cause)) {
            return SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_GATEWAY_TIMEOUT)
                    .build();
        }
        return SandboxErrorDetail.newBuilder()
                .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_TRANSPORT_FAILURE)
                .build();
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.getCause() == null || cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return cur != null ? cur : t;
    }

    private static boolean isTimeoutCause(Throwable cause) {
        if (cause == null) return false;
        String name = cause.getClass().getName();
        // Spring wraps JDK connect/read timeouts; both expose as SocketTimeoutException at
        // root, or as org.springframework.web.client.ResourceAccessException message hints.
        if (cause instanceof java.net.SocketTimeoutException) return true;
        // ConnectTimeoutException is a Spring internal class (package varies); match by name.
        if (name.endsWith("ConnectTimeoutException")) return true;
        // Fall back to message text for RestTemplate read/connect timeout wrappers.
        String msg = cause.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("read timed out") || lower.contains("connect timed out")) return true;
        }
        return false;
    }

    private static int extractDownstreamHttpStatus(Throwable e) {
        if (e instanceof HttpClientErrorException http4xx) {
            return http4xx.getStatusCode().value();
        }
        if (e instanceof HttpServerErrorException http5xx) {
            return http5xx.getStatusCode().value();
        }
        return 0; // absence signaled via proto3 optional; caller still writes detail.category
    }

    private static String extractDownstreamErrorText(Throwable e, String fallback) {
        if (e == null) return fallback;
        if (e.getMessage() != null && !e.getMessage().isBlank()) return e.getMessage();
        return fallback;
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
            ResponseEntity<HttpOperationLookupResponse> response = shortHttpClient.getForEntity(
                    endpointUri, HttpOperationLookupResponse.class);
            emitSandboxHttp("GET", endpoint, response.getStatusCode().value(),
                    System.currentTimeMillis() - httpStart, "OK", null);
            HttpOperationLookupResponse body = response.getBody();
            if (body == null) {
                // HTTP success but empty body. Not authoritative absence (sandbox did not
                // return a business negative; payload was malformed). D13 fail-closed red
                // line 6: only `found=false + error blank + error_detail absent` is absence.
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(200)
                        .build();
                return GetTaskByOperationIdResponse.newBuilder()
                        .setFound(false)
                        .setError("Empty response from sandbox")
                        .setErrorDetail(detail)
                        .build();
            }
            GetTaskByOperationIdResponse.Builder builder = GetTaskByOperationIdResponse.newBuilder()
                    .setFound(body.isFound());
            if (body.getTask_id() != null) builder.setTaskId(body.getTask_id());
            if (body.getStatus() != null) builder.setStatus(body.getStatus());
            if (body.getRequest_fingerprint() != null) {
                builder.setRequestFingerprint(body.getRequest_fingerprint());
            }
            // D13 fail-closed red line 6: authoritative absence = found=false + error blank
            // + error_detail absent. New producer MUST NOT write error_detail on this path
            // (preserve legacy presence=false signal). Any non-blank error from downstream
            // body is treated as a classifiable failure → add UNSPECIFIED error_detail so
            // downstream fails closed instead of guessing from text.
            if (body.getError() != null) builder.setError(body.getError());
            if (body.isFound() || (body.getError() != null && !body.getError().isBlank())) {
                // Either found=true (success path) OR sandbox returned a non-blank error
                // alongside found=false (sandbox-internal lookup failure). The latter MUST
                // be surfaced as a present error_detail so consumer fail-closed (D13 §4.4
                // row 4: operation lookup with non-blank error ≠ authoritative absent).
                if (!body.isFound() && body.getError() != null && !body.getError().isBlank()) {
                    builder.setErrorDetail(SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .setDownstreamHttpStatus(200)
                            .build());
                }
            }
            // else: found=false + blank error → authoritative absence; do NOT set error_detail.
            log.info("sandbox.getTaskByOperationId.result: operationId={}, found={}, taskId={}, "
                            + "totalDurationMs={}",
                    operationId, body.isFound(), body.getTask_id(),
                    System.currentTimeMillis() - startMs);
            return builder.build();
        } catch (HttpClientErrorException.Conflict e) {
            return buildOperationLookupFailureResponse(e, operationId,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                    "operationLookup.conflict", startMs);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return buildOperationLookupFailureResponse(e, operationId,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    "operationLookup.invalidArgument", startMs);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return buildOperationLookupFailureResponse(e, operationId,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                    "operationLookup.overloaded", startMs);
        } catch (HttpClientErrorException e) {
            // 401/403/other 4xx: UNSPECIFIED, not INVALID_ARGUMENT (Cindy 4b89c2d6 #4).
            // 404 here is NOT authoritative absence — getTaskByOperationId 404 only proves
            // the sandbox has no record for this operationId; per D13 v2 修订 3, ANY present
            // error_detail (including NOT_FOUND) is failure, fail-closed preserve PREPARING.
            SandboxHttpErrorCategory category = e.getStatusCode().value() == 404
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED;
            return buildOperationLookupFailureResponse(e, operationId, category,
                    "operationLookup.httpClientError", startMs);
        } catch (HttpServerErrorException e) {
            SandboxHttpErrorCategory category = e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE;
            return buildOperationLookupFailureResponse(e, operationId, category,
                    "operationLookup.serverError", startMs);
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            String text = e.getMessage() == null ? "operation lookup transport failure" : e.getMessage();
            log.warn("sandbox.operationLookup.transportFailure: operationId={}, category={}, totalDurationMs={}, error={}",
                    operationId, detail.getCategory().name(),
                    System.currentTimeMillis() - startMs, text, e);
            emitSandboxHttp("GET", sandboxUrl + "/operations/" + operationId, -1,
                    System.currentTimeMillis() - startMs, "ERROR",
                    "OPERATION_LOOKUP_" + detail.getCategory());
            return GetTaskByOperationIdResponse.newBuilder()
                    .setFound(false)
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.getTaskByOperationId.failed: operationId={}, totalDurationMs={}, error={}",
                    operationId, System.currentTimeMillis() - startMs, e.getMessage(), e);
            String text = e.getMessage() == null ? "operation lookup failed" : e.getMessage();
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                    .build();
            return GetTaskByOperationIdResponse.newBuilder()
                    .setFound(false)
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        }
    }

    private GetTaskByOperationIdResponse buildOperationLookupFailureResponse(
            RuntimeException e, String operationId, SandboxHttpErrorCategory category,
            String logKey, long startMs
    ) {
        int statusCode = extractDownstreamHttpStatus(e);
        String text = extractDownstreamErrorText(e, "operation lookup rejected by sandbox");
        log.warn("sandbox.{}: operationId={}, httpStatus={}, category={}, totalDurationMs={}, error={}",
                logKey, operationId, statusCode, category.name(),
                System.currentTimeMillis() - startMs, text, e);
        emitSandboxHttp("GET", sandboxUrl + "/operations/" + operationId, statusCode,
                System.currentTimeMillis() - startMs, "ERROR",
                "OPERATION_LOOKUP_" + category.name());
        SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                .setCategory(category)
                .setDownstreamHttpStatus(statusCode)
                .build();
        // found=false stays; per D13 v2 修订 3, present error_detail = failure (NOT authoritative
        // absence), so consumer MUST fail-closed regardless of found=false value.
        return GetTaskByOperationIdResponse.newBuilder()
                .setFound(false)
                .setError(text)
                .setErrorDetail(detail)
                .build();
    }

    @Override
    public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskStatus: taskId={}", request.getTaskId());
        try {
            // 260809-26Q3-stage1-w3 D15 (separate commit): taskId as single path segment.
            // For D13 we keep the raw concat shape; only switch bean to shortHttpClient.
            String endpoint = sandboxUrl + "/tasks/" + request.getTaskId();
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpTask> response = shortHttpClient.getForEntity(endpoint, HttpTask.class);
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
                // HTTP success but empty body — malformed response. Not a 404; categorize as
                // UNSPECIFIED with downstream_http_status=200 (proxied response was 2xx).
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(200)
                        .build();
                return TaskStatusResponse.newBuilder()
                        .setStatus("UNKNOWN")
                        .setError("Task not available (empty body)")
                        .setErrorDetail(detail)
                        .build();
            }
        } catch (HttpClientErrorException.NotFound e) {
            // 404 special-case preserved for backward compat with TaskStatusResponse.status="UNKNOWN".
            // D13 v2 修订 3: this is NOT authoritative absence for an operationId — only
            // getTaskByOperationId (with found=false + blank error + absent detail) can express
            // that. Here we surface NOT_FOUND + downstream_http_status=404 so downstream can
            // machine-recognize the difference between "task resource doesn't exist" and
            // "sandbox was unreachable".
            log.info("sandbox.getTaskStatus.notFound: taskId={}, totalDurationMs={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId(), 404,
                    System.currentTimeMillis() - startMs, "OK", "NOT_FOUND");
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND)
                    .setDownstreamHttpStatus(404)
                    .build();
            return TaskStatusResponse.newBuilder()
                    .setStatus("UNKNOWN")
                    .setError("Task not found")
                    .setErrorDetail(detail)
                    .build();
        } catch (HttpClientErrorException.TooManyRequests e) {
            return buildStatusFailureResponse(e, request.getTaskId(),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                    "getTaskStatus.overloaded", startMs);
        } catch (HttpClientErrorException e) {
            // 401/403/other 4xx: UNSPECIFIED, not INVALID_ARGUMENT (Cindy 4b89c2d6 #4).
            // BadRequest/UnprocessableEntity would normally be INVALID_ARGUMENT, but task
            // status lookups are GET-by-id; a 400 from this endpoint typically means malformed
            // taskId rather than invalid request body — still surface as INVALID_ARGUMENT
            // since that's the closest semantic match for the caller.
            SandboxHttpErrorCategory category = (e instanceof HttpClientErrorException.BadRequest
                    || e instanceof HttpClientErrorException.UnprocessableEntity)
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED;
            return buildStatusFailureResponse(e, request.getTaskId(), category,
                    "getTaskStatus.httpClientError", startMs);
        } catch (HttpServerErrorException e) {
            SandboxHttpErrorCategory category = e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE;
            return buildStatusFailureResponse(e, request.getTaskId(), category,
                    "getTaskStatus.serverError", startMs);
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            String text = e.getMessage() == null ? "getTaskStatus transport failure" : e.getMessage();
            log.warn("sandbox.getTaskStatus.transportFailure: taskId={}, category={}, totalDurationMs={}, error={}",
                    request.getTaskId(), detail.getCategory().name(),
                    System.currentTimeMillis() - startMs, text, e);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId(), -1,
                    System.currentTimeMillis() - startMs, "ERROR",
                    "GET_STATUS_" + detail.getCategory());
            return TaskStatusResponse.newBuilder()
                    .setStatus("UNKNOWN")
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.getTaskStatus.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId(), -1,
                    System.currentTimeMillis() - startMs, "ERROR", "GET_STATUS_FAILED");
            String text = e.getMessage() == null ? "getTaskStatus failed" : e.getMessage();
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                    .build();
            return TaskStatusResponse.newBuilder()
                    .setStatus("UNKNOWN")
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        }
    }

    private TaskStatusResponse buildStatusFailureResponse(
            RuntimeException e, String taskId, SandboxHttpErrorCategory category,
            String logKey, long startMs
    ) {
        int statusCode = extractDownstreamHttpStatus(e);
        String text = extractDownstreamErrorText(e, "getTaskStatus rejected by sandbox");
        log.warn("sandbox.{}: taskId={}, httpStatus={}, category={}, totalDurationMs={}, error={}",
                logKey, taskId, statusCode, category.name(),
                System.currentTimeMillis() - startMs, text, e);
        emitSandboxHttp("GET", sandboxUrl + "/tasks/" + taskId, statusCode,
                System.currentTimeMillis() - startMs, "ERROR",
                "GET_STATUS_" + category.name());
        SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                .setCategory(category)
                .setDownstreamHttpStatus(statusCode)
                .build();
        return TaskStatusResponse.newBuilder()
                .setStatus("UNKNOWN")
                .setError(text)
                .setErrorDetail(detail)
                .build();
    }

    @Override
    public TaskResultResponse getTaskResult(GetTaskResultRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskResult: taskId={}", request.getTaskId());
        try {
            // Check status first to ensure we don't hit 409. Status lookup uses the short
            // HTTP client (handled inside getTaskStatus). The result fetch below uses the
            // long HTTP client since it can wait for downstream task completion.
            TaskStatusResponse status = getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId(request.getTaskId()).build());
            if (isResultBearingTerminal(status.getStatus())) {
                String endpoint = sandboxUrl + "/tasks/" + request.getTaskId() + "/result";
                long httpStart = System.currentTimeMillis();
                ResponseEntity<HttpExecuteResult> response = longHttpClient.getForEntity(endpoint, HttpExecuteResult.class);
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
                    // 260808-finance-methodspec-v5 work package D: presence-aware mapping
                    // for v5 finance record channel + execution environment. Parent absence
                    // means old producer (no v5 protocol); parent presence means v5 enabled
                    // (including empty but complete batch). The mapping is one-directional:
                    // HTTP null parent -> proto no setFinanceRecordChannel/setExecutionEnvironment;
                    // HTTP present parent -> proto parent set with mapped fields.
                    FinanceRecordChannelMetadata financeChannel = toProtoFinanceRecordChannel(
                            res.getFinance_record_channel());
                    if (financeChannel != null) {
                        builder.setFinanceRecordChannel(financeChannel);
                    }
                    SandboxEnvironmentIdentity executionEnvironment = toProtoExecutionEnvironment(
                            res.getExecution_environment());
                    if (executionEnvironment != null) {
                        builder.setExecutionEnvironment(executionEnvironment);
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

        } catch (HttpClientErrorException.Conflict e) {
            return buildResultFailureResponse(e, request.getTaskId(),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT,
                    "getTaskResult.conflict", startMs);
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return buildResultFailureResponse(e, request.getTaskId(),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    "getTaskResult.invalidArgument", startMs);
        } catch (HttpClientErrorException.NotFound e) {
            return buildResultFailureResponse(e, request.getTaskId(),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND,
                    "getTaskResult.notFound", startMs);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return buildResultFailureResponse(e, request.getTaskId(),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                    "getTaskResult.overloaded", startMs);
        } catch (HttpClientErrorException e) {
            // 401/403/other 4xx: UNSPECIFIED, not INVALID_ARGUMENT (Cindy 4b89c2d6 #4).
            return buildResultFailureResponse(e, request.getTaskId(),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                    "getTaskResult.httpClientError", startMs);
        } catch (HttpServerErrorException e) {
            SandboxHttpErrorCategory category = e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE;
            return buildResultFailureResponse(e, request.getTaskId(), category,
                    "getTaskResult.serverError", startMs);
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            String text = e.getMessage() == null ? "getTaskResult transport failure" : e.getMessage();
            log.warn("sandbox.getTaskResult.transportFailure: taskId={}, category={}, totalDurationMs={}, error={}",
                    request.getTaskId(), detail.getCategory().name(),
                    System.currentTimeMillis() - startMs, text, e);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId() + "/result", -1,
                    System.currentTimeMillis() - startMs, "ERROR",
                    "GET_RESULT_" + detail.getCategory());
            return TaskResultResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.getTaskResult.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("GET", sandboxUrl + "/tasks/" + request.getTaskId() + "/result", -1,
                    System.currentTimeMillis() - startMs, "ERROR", "GET_RESULT_FAILED");
            String text = e.getMessage() == null ? "getTaskResult failed" : e.getMessage();
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                    .build();
            return TaskResultResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        }
    }

    private TaskResultResponse buildResultFailureResponse(
            RuntimeException e, String taskId, SandboxHttpErrorCategory category,
            String logKey, long startMs
    ) {
        int statusCode = extractDownstreamHttpStatus(e);
        String text = extractDownstreamErrorText(e, "getTaskResult rejected by sandbox");
        log.warn("sandbox.{}: taskId={}, httpStatus={}, category={}, totalDurationMs={}, error={}",
                logKey, taskId, statusCode, category.name(),
                System.currentTimeMillis() - startMs, text, e);
        emitSandboxHttp("GET", sandboxUrl + "/tasks/" + taskId + "/result", statusCode,
                System.currentTimeMillis() - startMs, "ERROR",
                "GET_RESULT_" + category.name());
        SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                .setCategory(category)
                .setDownstreamHttpStatus(statusCode)
                .build();
        return TaskResultResponse.newBuilder()
                .setError(text)
                .setErrorDetail(detail)
                .build();
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

    // 260808-finance-methodspec-v5 work package D: presence-aware mapping for
    // financeRecordChannel (proto field 10). Returns null when the HTTP parent
    // is absent, so the caller skips setFinanceRecordChannel and downstream
    // consumers see hasFinanceRecordChannel() == false (old producer).
    static FinanceRecordChannelMetadata toProtoFinanceRecordChannel(HttpFinanceRecordChannel channel) {
        if (channel == null) {
            return null;
        }
        FinanceRecordChannelMetadata.Builder builder = FinanceRecordChannelMetadata.newBuilder();
        if (channel.getEmitted_record_count() != null) {
            builder.setEmittedRecordCount(channel.getEmitted_record_count());
        }
        if (channel.getEmitted_record_bytes() != null) {
            builder.setEmittedRecordBytes(channel.getEmitted_record_bytes());
        }
        if (channel.getRecord_set_complete() != null) {
            builder.setRecordSetComplete(channel.getRecord_set_complete());
        }
        if (channel.getDrop_reason() != null) {
            builder.setDropReason(channel.getDrop_reason());
        }
        if (channel.getRecord_digest() != null) {
            builder.setRecordDigest(channel.getRecord_digest());
        }
        if (channel.getStdout_truncated() != null) {
            builder.setStdoutTruncated(channel.getStdout_truncated());
        }
        if (channel.getStderr_truncated() != null) {
            builder.setStderrTruncated(channel.getStderr_truncated());
        }
        return builder.build();
    }

    // 260808-finance-methodspec-v5 work package D: presence-aware mapping for
    // executionEnvironment (proto field 11). Returns null when the HTTP parent
    // is absent, so the caller skips setExecutionEnvironment and downstream
    // consumers see hasExecutionEnvironment() == false (old producer). The
    // packageApis repeated field uses no runtimeImageRef per FROZEN contract.
    static SandboxEnvironmentIdentity toProtoExecutionEnvironment(HttpExecutionEnvironment environment) {
        if (environment == null) {
            return null;
        }
        SandboxEnvironmentIdentity.Builder builder = SandboxEnvironmentIdentity.newBuilder();
        if (environment.getEnvironment_id() != null) {
            builder.setEnvironmentId(environment.getEnvironment_id());
        }
        if (environment.getImage_digest() != null) {
            builder.setImageDigest(environment.getImage_digest());
        }
        if (environment.getLibrary_set_digest() != null) {
            builder.setLibrarySetDigest(environment.getLibrary_set_digest());
        }
        if (environment.getPackage_apis() != null) {
            for (HttpSandboxPackageApi pkg : environment.getPackage_apis()) {
                if (pkg == null) {
                    continue;
                }
                SandboxPackageApi.Builder pkgBuilder = SandboxPackageApi.newBuilder();
                if (pkg.getName() != null) {
                    pkgBuilder.setName(pkg.getName());
                }
                if (pkg.getVersion() != null) {
                    pkgBuilder.setVersion(pkg.getVersion());
                }
                if (pkg.getApi_version() != null) {
                    pkgBuilder.setApiVersion(pkg.getApi_version());
                }
                builder.addPackageApis(pkgBuilder.build());
            }
        }
        if (environment.getInventory_complete() != null) {
            builder.setInventoryComplete(environment.getInventory_complete());
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
        // 260808-finance-methodspec-v5 work package D: v5 finance record channel +
        // execution environment parents. Null parent = old producer (no v5 protocol);
        // present parent (even with default-valued fields) = v5 enabled. The presence
        // check at consumer side is via hasFinanceRecordChannel()/hasExecutionEnvironment()
        // on the proto side, not on these fields.
        private HttpFinanceRecordChannel finance_record_channel;
        private HttpExecutionEnvironment execution_environment;
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

    // 260808-finance-methodspec-v5 work package D: HTTP-side mirror of
    // FinanceRecordChannelMetadata (proto field 10). Jackson binds snake_case JSON
    // from the sandbox Python side. Field order matches the proto schema.
    @Data
    static class HttpFinanceRecordChannel {
        private Integer emitted_record_count;
        private Long emitted_record_bytes;
        private Boolean record_set_complete;
        private String drop_reason;
        private String record_digest;
        private Boolean stdout_truncated;
        private Boolean stderr_truncated;
    }

    // 260808-finance-methodspec-v5 work package D: HTTP-side mirror of
    // SandboxEnvironmentIdentity (proto field 11). Jackson binds snake_case JSON
    // from the sandbox Python side. No runtimeImageRef per FROZEN contract;
    // that value is a Python-internal C/H task fact and stays off the wire.
    @Data
    static class HttpExecutionEnvironment {
        private String environment_id;
        private String image_digest;
        private String library_set_digest;
        private List<HttpSandboxPackageApi> package_apis;
        private Boolean inventory_complete;
    }

    @Data
    static class HttpSandboxPackageApi {
        private String name;
        private String version;
        private String api_version;
    }
}
