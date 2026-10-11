package world.willfrog.sandbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
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
import world.willfrog.alphafrogmicro.agent.idl.AgentRunPersistentResourceEligibilityService;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityRequest;
import world.willfrog.alphafrogmicro.agent.idl.CheckRunPersistentResourceEligibilityResponse;
import world.willfrog.alphafrogmicro.agent.idl.RunPersistentResourceEligibility;
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
    // Short-query serves getTaskStatus, getTaskByOperationId and cancelTask.
    // Every call site MUST bind the correct bean via @Qualifier proof.
    private final RestTemplate longHttpClient;
    private final RestTemplate shortHttpClient;
    private final ObjectMapper objectMapper;

    /**
     * Run 持久资源资格回查：acquireWorkspace 下发 Python 前的前置裁决。
     * 提供方在 agentLangchain（group=langchain）；泳道标签由公共消费方
     * 过滤器沿入境调用自动传播，这里不做第二重路由判断。
     */
    @DubboReference(group = "langchain", check = false)
    private AgentRunPersistentResourceEligibilityService runEligibilityService;

    @Value("${sandbox.service.url}")
    private String sandboxUrl;

    // 260809-26Q3-stage1-w3 D13 MUST-FIX 3 (Cindy 91490076 #3 + 6a6e6158): platform max
    // task timeout. Gateway rejects createTask requests whose effective timeout exceeds
    // this value as local INVALID_ARGUMENT. MUST stay lock-step with Python-side
    // max_task_timeout_seconds (ccqwen 5c543fea).
    // Initializer mirrors @Value default so unit tests (which bypass Spring's @Value
    // resolution via ReflectionTestUtils) start from a valid 30min/5min floor; Spring
    // overwrites this when the context loads the resolved externalized value.
    @Value("${sandbox.service.max-task-timeout-millis:1800000}")
    private long maxTaskTimeoutMillis = 1800000L;

    @Value("${sandbox.service.queue-prepare-margin-millis:300000}")
    private long queuePrepareMarginMillis = 300000L;

    /**
     * D14 (Q-14): production create requires a non-blank operationId and
     * grouped canonical identity. Default false = fail-closed. Set true only
     * for explicitly annotated non-production fixtures (no idempotent recovery;
     * "resourceClass-only" transitional clients must not hit production).
     */
    @Value("${sandbox.gateway.allow-create-without-operation-id:false}")
    private boolean allowCreateWithoutOperationId = false;

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
        // 260809-26Q3-stage1-w3 D13 MUST-FIX 3 + round-2 #2 (Cindy 91490076 #3 +
        // 6a6e6158 + 1b29792d #2 + codex 3d78edba/aa8987d1): effective timeout
        // validation BEFORE any downstream call. Effective = max of legacy
        // `timeoutSeconds * 1000` (conservative ceil) and canonical `timeoutMillis`.
        // Two reject branches, both local INVALID_ARGUMENT with downstream_http_status absent:
        //   - computeEffectiveTimeoutMillis returns -1: some field is NaN/Infinity/negative
        //     (must NOT be silently numericized as valid-unset; must NOT be silently dropped)
        //   - effective > max: requested task timeout exceeds platform cap
        // Margin is NOT applied here — business task limit is bound by max alone
        // (Cindy 6a6e6158: threshold = `effective > max`, NOT `> max + margin`).
        long effectiveTimeoutMillis = computeEffectiveTimeoutMillis(request);
        if (effectiveTimeoutMillis < 0) {
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)
                    .build();
            String text = "createTask rejected: timeoutSeconds/timeoutMillis is NaN/Infinity/negative"
                    + " (seconds=" + request.getTimeoutSeconds()
                    + ", millis=" + request.getTimeoutMillis() + ")";
            log.warn("sandbox.createTask.localRejectTimeoutInvalid: taskId=*, seconds={}, millis={}, totalDurationMs={}",
                    request.getTimeoutSeconds(), request.getTimeoutMillis(),
                    System.currentTimeMillis() - startMs);
            return ExecuteResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        }
        if (effectiveTimeoutMillis > maxTaskTimeoutMillis) {
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)
                    .build();
            String text = "createTask rejected: effective timeout " + effectiveTimeoutMillis
                    + "ms exceeds platform max " + maxTaskTimeoutMillis + "ms";
            log.warn("sandbox.createTask.localRejectTimeoutOverMax: taskId=*, effectiveMs={}, maxMs={}, totalDurationMs={}",
                    effectiveTimeoutMillis, maxTaskTimeoutMillis, System.currentTimeMillis() - startMs);
            return ExecuteResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        }
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
             * D14 (Q-14): production rejects blank operationId before HTTP.
             * Non-empty is judged AFTER trim; empty / all-whitespace are rejected.
             * Never invent a key. The transitional "resourceClass only" path is
             * non-production-only behind
             * sandbox.gateway.allow-create-without-operation-id=true.
             * That switch only admits keyless creates — keyed creates still
             * forward the full canonical group and keep Python-side validation.
             *
             * proto3 标量没有 presence；因此只有 operationId 非空时才把 canonical 数值零值也
             * 写入 HTTP DTO。
             */
            String operationId = request.getOperationId() == null
                    ? ""
                    : request.getOperationId().trim();
            boolean canonicalCreate = !operationId.isEmpty();
            if (!canonicalCreate) {
                if (!allowCreateWithoutOperationId) {
                    SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)
                            .build();
                    // Stable caller-facing text: no config-key inventory.
                    String text = "createTask rejected: operationId is required "
                            + "(D14 production refuse create without idempotency key; "
                            + "resourceClass-only transitional clients are non-production only)";
                    log.warn("sandbox.createTask.localRejectMissingOperationId: resourceClass={}, "
                                    + "totalDurationMs={}, nonProductionSwitch=sandbox.gateway."
                                    + "allow-create-without-operation-id",
                            request.getResourceClass(),
                            System.currentTimeMillis() - startMs);
                    return ExecuteResponse.newBuilder()
                            .setError(text)
                            .setErrorDetail(detail)
                            .build();
                }
                log.warn("sandbox.createTask.allowWithoutOperationId: "
                        + "allow-create-without-operation-id=true "
                        + "(NON-PRODUCTION: no idempotent recovery; must also enable "
                        + "companion Java/Python switches as a group)");
            }
            if (canonicalCreate) {
                // D14 MUST-FIX: keyed create must carry a complete canonical identity
                // group BEFORE HTTP. Do not invent defaults or recompute fingerprint;
                // incomplete half-sets are local INVALID_ARGUMENT.
                String defect = findCanonicalCreateDefect(request);
                if (defect != null) {
                    SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)
                            .build();
                    String text = "createTask rejected: incomplete canonical identity "
                            + "for keyed create";
                    log.warn("sandbox.createTask.localRejectIncompleteCanonical: "
                                    + "operationId={}, missingOrInvalidField={}, totalDurationMs={}",
                            operationId, defect, System.currentTimeMillis() - startMs);
                    return ExecuteResponse.newBuilder()
                            .setError(text)
                            .setErrorDetail(detail)
                            .build();
                }
                httpRequest.setResource_class(request.getResourceClass());
                httpRequest.setEstimated_rows(request.getEstimatedRows());
                httpRequest.setEstimated_bytes(request.getEstimatedBytes());
                httpRequest.setFile_count(request.getFileCount());
                httpRequest.setCapacity_units(request.getCapacityUnits());
                httpRequest.setOperation_id(operationId);
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
                // Non-production transitional clients only (gate above).
                httpRequest.setResource_class(request.getResourceClass());
            }
            // Persistent-workspace identity group: all three fields or none.
            // A partial group is a caller defect — rejected locally with
            // INVALID_ARGUMENT before any HTTP round trip (the sandbox
            // enforces the same rule; failing earlier keeps the request
            // from consuming downstream capacity). proto3 scalars have no
            // presence, so blank-ness is the unset proxy.
            String runId = request.getRunId() == null ? "" : request.getRunId().trim();
            String workspaceId = request.getWorkspaceId() == null ? "" : request.getWorkspaceId().trim();
            String workspaceGeneration =
                    request.getWorkspaceGeneration() == null ? "" : request.getWorkspaceGeneration().trim();
            boolean anyIdentity = !runId.isEmpty() || !workspaceId.isEmpty() || !workspaceGeneration.isEmpty();
            boolean allIdentity = !runId.isEmpty() && !workspaceId.isEmpty() && !workspaceGeneration.isEmpty();
            if (anyIdentity && !allIdentity) {
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)
                        .build();
                String text = "createTask rejected: runId, workspaceId and workspaceGeneration"
                        + " must be provided together or all absent";
                log.warn("sandbox.createTask.localRejectPartialWorkspaceIdentity: totalDurationMs={}",
                        System.currentTimeMillis() - startMs);
                return ExecuteResponse.newBuilder()
                        .setError(text)
                        .setErrorDetail(detail)
                        .build();
            }
            if (allIdentity) {
                httpRequest.setRun_id(runId);
                httpRequest.setWorkspace_id(workspaceId);
                httpRequest.setWorkspace_generation(workspaceGeneration);
            }

            String endpoint = sandboxUrl + "/tasks";
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpCreateTaskResponse> response = longHttpClient.postForEntity(
                    endpoint, httpRequest, HttpCreateTaskResponse.class);
            int downstreamStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - httpStart;
            log.info("sandbox.http: endpoint=POST {}, httpStatus={}, durationMs={}",
                    endpoint, downstreamStatus, durationMs);
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 5 (Cindy 91490076 #5): final telemetry
            // AFTER body shape classification, not before.

            if (response.getBody() != null) {
                log.info("sandbox.createTask.result: taskId={}, status={}, totalDurationMs={}",
                        response.getBody().getTask_id(), response.getBody().getStatus(),
                        System.currentTimeMillis() - startMs);
                String workspaceResultRaw = response.getBody().getWorkspace_result();
                boolean hasWorkspaceResultRaw = workspaceResultRaw != null
                        && !workspaceResultRaw.isBlank();
                WorkspaceResult workspaceResult = parseWorkspaceResult(workspaceResultRaw);
                boolean hasTaskId = response.getBody().getTask_id() != null
                        && !response.getBody().getTask_id().isBlank();
                // Exactly ONE business form is legal on a 200 body: either a
                // task reference (taskId present, verdict absent) or a pure
                // workspace refusal (verdict present, taskId absent). An
                // unknown verdict value, both forms at once, or neither form
                // is a corrupted downstream protocol and must fail closed —
                // never read as success and never surfaced as "task created
                // AND refused".
                String malformedReason = null;
                if (hasWorkspaceResultRaw && workspaceResult == null) {
                    malformedReason = "unknown workspaceResult value: " + workspaceResultRaw;
                } else if (hasTaskId && workspaceResult != null) {
                    malformedReason = "both a task reference and a workspace refusal present";
                } else if (!hasTaskId && !hasWorkspaceResultRaw) {
                    malformedReason = "no task reference and no workspace verdict";
                }
                if (malformedReason != null) {
                    log.warn("sandbox.createTask.malformedResponse: reason={}, taskId={}, httpStatus={}",
                            malformedReason, response.getBody().getTask_id(), downstreamStatus);
                    SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .setDownstreamHttpStatus(downstreamStatus)
                            .build();
                    emitSandboxHttp("POST", endpoint, downstreamStatus, durationMs, "ERROR",
                            "CREATE_TASK_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
                    return ExecuteResponse.newBuilder()
                            .setError("Malformed create response from sandbox (" + malformedReason + ")")
                            .setErrorDetail(detail)
                            .build();
                }
                ExecuteResponse.Builder builder = ExecuteResponse.newBuilder()
                        .setTaskId(response.getBody().getTask_id() == null ? "" : response.getBody().getTask_id())
                        .setStatus(response.getBody().getStatus() == null ? "" : response.getBody().getStatus());
                if (response.getBody().getExisting() != null) {
                    builder.setExisting(response.getBody().getExisting());
                }
                if (response.getBody().getRequest_fingerprint() != null) {
                    builder.setRequestFingerprint(response.getBody().getRequest_fingerprint());
                }
                if (workspaceResult != null) {
                    builder.setWorkspaceResult(workspaceResult);
                }
                emitSandboxHttp("POST", endpoint, downstreamStatus, durationMs, "OK", null);
                return builder.build();
            } else {
                log.warn("sandbox.createTask.emptyBody: totalDurationMs={}, httpStatus={}",
                        System.currentTimeMillis() - startMs, downstreamStatus);
                // Empty body received from downstream (HTTP success but no payload).
                // D13 §4.2 + Cindy 91490076 #4: not a categorizable downstream rejection —
                // UNSPECIFIED with the ACTUAL downstream status (could be 200/201/202/204).
                // Parent `error` non-blank per D13 red line 4/5.
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(downstreamStatus)
                        .build();
                emitSandboxHttp("POST", endpoint, downstreamStatus, durationMs, "ERROR",
                        "CREATE_TASK_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
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
            String text = nonBlankOr(e, "createTask transport failure");
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
            String text = nonBlankOr(e, "createTask failed");
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

    /**
     * D14: keyed create local completeness check. Returns the first missing/invalid
     * field name for operator logs, or null when the full identity group is present.
     * Does not invent defaults or recompute fingerprints.
     *
     * <p>capacityUnits stay frozen by resource class (STANDARD=1, HEAVY=3).
     * memoryLimitBytes only requires a positive value here — the actual configured
     * STANDARD/HEAVY memory bytes live in Java DataAnalysisCapacityProperties and
     * Python AF_SANDBOX_*_MEMORY_BYTES; Gateway must not hardcode a third copy.
     *
     * <p>The five SHA-256 identity fields are syntax-checked the same way as Python
     * {@code normalize_sha256}: optional {@code sha256:} prefix + 64 hex digits.
     * Gateway does not recompute fingerprint or verify codeHash against code bytes.
     */
    private static final java.util.regex.Pattern SHA256_DIGEST =
            java.util.regex.Pattern.compile("^(?:sha256:)?([0-9a-fA-F]{64})$");

    static String findCanonicalCreateDefect(ExecuteRequest request) {
        String resourceClass = request.getResourceClass() == null
                ? ""
                : request.getResourceClass().trim();
        if (!"STANDARD".equals(resourceClass) && !"HEAVY".equals(resourceClass)) {
            return "resourceClass";
        }
        int expectedUnits = "HEAVY".equals(resourceClass) ? 3 : 1;
        if (request.getCapacityUnits() != expectedUnits) {
            return "capacityUnits";
        }
        if (request.getMemoryLimitBytes() <= 0L) {
            return "memoryLimitBytes";
        }
        if (request.getTimeoutMillis() <= 0L) {
            return "timeoutMillis";
        }
        if (!isSha256Digest(request.getRequestFingerprint())) {
            return "requestFingerprint";
        }
        if (isBlank(request.getCanonicalSpecSchemaVersion())
                || !"sandbox_create_v1".equals(request.getCanonicalSpecSchemaVersion().trim())) {
            return "canonicalSpecSchemaVersion";
        }
        if (isBlank(request.getRuntimeEnvironmentVersion())) {
            return "runtimeEnvironmentVersion";
        }
        if (!isSha256Digest(request.getCodeHash())) {
            return "codeHash";
        }
        if (!isSha256Digest(request.getImmutableDatasetSnapshotDigest())) {
            return "immutableDatasetSnapshotDigest";
        }
        if (!isSha256Digest(request.getLibrariesDigest())) {
            return "librariesDigest";
        }
        if (!isSha256Digest(request.getSandboxOptionsDigest())) {
            return "sandboxOptionsDigest";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isSha256Digest(String value) {
        if (value == null) {
            return false;
        }
        return SHA256_DIGEST.matcher(value.trim()).matches();
    }

    /**
     * 260809-26Q3-stage1-w3 D13 MUST-FIX 2c (Cindy 91490076 #2): null-OR-blank fallback
     * helper. Frozen contract: every failure path MUST keep parent `error` non-blank;
     * a blank exception message must NOT be propagated as the error text.
     */
    static String nonBlankOr(Throwable e, String fallback) {
        if (e == null) return fallback;
        return nonBlankOr(e.getMessage(), fallback);
    }

    static String nonBlankOr(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        return text;
    }

    /**
     * 260809-26Q3-stage1-w3 D13 MUST-FIX 3 (Cindy 91490076 #3 + 6a6e6158 + 1b29792d #2 +
     * codex 3d78edba/aa8987d1): effective task timeout归一化 for local-reject validation.
     *
     * Return contract:
     *   -1                  : signal that some timeout field is NaN/Infinity/negative —
     *                         caller MUST local-reject as INVALID_ARGUMENT (downstream_http_status
     *                         absent). NaN/Infinity不可数值化为有效未设置；负值不可静默丢弃。
     *   0                   : neither field set (proto3 default 0 = absent), no local reject;
     *                         sandbox-side enforcement is ccqwen's slice.
     *   positive long       : conservative ceiling of max(timeoutSeconds * 1000, timeoutMillis),
     *                         caller compares against `maxTaskTimeoutMillis`.
     *
     * Precision: uses `Math.ceil` (conservative upper bound) so fractional seconds like
     * 1800.0009s (real effective 1800000.9ms > 1800000ms max) round UP to 1800001ms and
     * trigger local reject, instead of being truncated to 1800000ms and slipping through.
     *
     * Overflow-safe: if `seconds * 1000.0` >= Long.MAX_VALUE, clamps to Long.MAX_VALUE so
     * the request is rejected without computing a wrapped negative sum.
     */
    static long computeEffectiveTimeoutMillis(ExecuteRequest request) {
        double seconds = request.getTimeoutSeconds();
        long millis = request.getTimeoutMillis();

        boolean secondsInvalid = !Double.isFinite(seconds) || seconds < 0;
        boolean millisInvalid = millis < 0;
        if (secondsInvalid || millisInvalid) {
            return -1L;
        }

        long fromSeconds = 0L;
        if (seconds > 0) {
            double secondsToMillis = seconds * 1000.0;
            if (secondsToMillis >= Long.MAX_VALUE) {
                fromSeconds = Long.MAX_VALUE;
            } else {
                fromSeconds = (long) Math.ceil(secondsToMillis);
            }
        }
        long fromMillis = millis > 0 ? millis : 0L;
        return Math.max(fromSeconds, fromMillis);
    }

    @Override
    public GetTaskByOperationIdResponse getTaskByOperationId(GetTaskByOperationIdRequest request) {
        long startMs = System.currentTimeMillis();
        String operationId = request.getOperationId();
        if (operationId == null || operationId.isBlank()) {
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 2a (Cindy 91490076 #2): Gateway-local
            // input reject MUST dual-write typed detail. category=INVALID_ARGUMENT,
            // downstream_http_status absent (no downstream call made).
            SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                    .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT)
                    .build();
            return GetTaskByOperationIdResponse.newBuilder()
                    .setFound(false)
                    .setError("operationId is required")
                    .setErrorDetail(detail)
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
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 5 (Cindy 91490076 #5): do NOT emit OK
            // telemetry before body validation; final emit happens after body shape is
            // classified (success / authoritative absence / typed failure).
            int downstreamStatus = response.getStatusCode().value();
            HttpOperationLookupResponse body = response.getBody();
            if (body == null) {
                // HTTP success but empty body. Not authoritative absence (sandbox did not
                // return a business negative; payload was malformed). D13 fail-closed red
                // line 6 + Cindy 91490076 #4: use ACTUAL downstream status (could be
                // 200/201/202/204), not hardcoded 200.
                long durationMs = System.currentTimeMillis() - httpStart;
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(downstreamStatus)
                        .build();
                emitSandboxHttp("GET", endpoint, downstreamStatus, durationMs, "ERROR",
                        "OPERATION_LOOKUP_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
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
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 2b + 4 (Cindy 91490076 #2/#4):
            // - If body has non-blank error (whether found=true OR found=false), that is a
            //   sandbox-side signal that the lookup encountered an issue. Surface it as
            //   present error_detail so consumer fail-closed (D13 §4.4 row 4: any non-blank
            //   body error ≠ authoritative absent, even when found=true).
            // - downstream_http_status uses ACTUAL response status, not hardcoded 200.
            // - Authoritative absence = found=false + error blank + error_detail absent
            //   (the only path that MAY release PREPARING).
            boolean bodyErrorPresent = body.getError() != null && !body.getError().isBlank();
            if (bodyErrorPresent) {
                builder.setError(body.getError());
                builder.setErrorDetail(SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(downstreamStatus)
                        .build());
            }
            long durationMs = System.currentTimeMillis() - httpStart;
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 5 (Cindy 91490076 #5): final telemetry
            // after body classification. Authoritative absence + found=true both emit OK;
            // body error emits ERROR + UNSPECIFIED category.
            String telemetryStatus = bodyErrorPresent ? "ERROR" : "OK";
            String telemetryCategory = bodyErrorPresent
                    ? "OPERATION_LOOKUP_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED" : null;
            emitSandboxHttp("GET", endpoint, downstreamStatus, durationMs, telemetryStatus, telemetryCategory);
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
            String text = nonBlankOr(e, "operation lookup transport failure");
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
            String text = nonBlankOr(e, "operation lookup failed");
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

    private static WorkspaceResult parseWorkspaceResult(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return WorkspaceResult.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 资格回查裁决：null = 明确允许，调用方继续下发 Python；非 null 为
     * 已成形的响应（明确拒绝走业务结果，其余一律失败关闭）。资格拒绝与
     * 不可用都属于未发起下游 HTTP 的本地裁决，不写 sandbox_http 记录
     * （与本地参数拒绝同一口径）。资格查询与 Python 侧创建之间的并发
     * 删除由沙箱本地封口在同一状态锁内仲裁，网关不做第二重判断。
     */
    private AcquireWorkspaceResponse runEligibilityGate(String runId, String endpoint) {
        CheckRunPersistentResourceEligibilityResponse response;
        try {
            response = runEligibilityService.checkEligibility(
                    CheckRunPersistentResourceEligibilityRequest.newBuilder()
                            .setRunId(runId)
                            .build());
        } catch (Exception e) {
            // 资格服务不可达/超时：失败关闭，不下发。
            log.warn("sandbox.acquireWorkspace.eligibilityUnavailable: runId={}, error={}",
                    runId, e.getMessage());
            return workspaceOpFailure(
                    "acquireWorkspace rejected: run eligibility check unavailable",
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                    null, endpoint, 0, false)
                    .toAcquireResponse();
        }
        RunPersistentResourceEligibility verdict = response == null
                ? RunPersistentResourceEligibility.UNRECOGNIZED
                : response.getEligibility();
        switch (verdict) {
            case RUN_PERSISTENT_RESOURCE_ELIGIBILITY_ALLOWED:
                return null;
            case RUN_PERSISTENT_RESOURCE_ELIGIBILITY_REFUSED:
                // 明确拒绝：业务结果通道（WORKSPACE_RUN_INELIGIBLE），
                // 不携带工作区，也不是传输/系统故障。
                log.info("sandbox.acquireWorkspace.eligibilityRefused: runId={}", runId);
                return AcquireWorkspaceResponse.newBuilder()
                        .setWorkspaceResult(WorkspaceResult.WORKSPACE_RUN_INELIGIBLE)
                        .build();
            default:
                // 暂不可用/空响应/未知枚举：失败关闭，不下发。
                log.warn("sandbox.acquireWorkspace.eligibilityUndecided: runId={}, verdict={}",
                        runId, verdict);
                return workspaceOpFailure(
                        "acquireWorkspace rejected: run eligibility temporarily unavailable",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE,
                        null, endpoint, 0, false)
                        .toAcquireResponse();
        }
    }

    /**
     * 取得或创建工作区：网关先回查该 Run 的持久资源资格，仅明确允许才
     * 下发 Python。业务结果（取得成功/身份冲突/部署错配/资格拒绝）经
     * workspace 与 workspaceResult 字段原样保留；请求缺陷与传输/系统
     * 故障经 error + errorDetail 分类返回，绝不借开关布尔值绕过或退回
     * 临时盘。
     */
    public AcquireWorkspaceResponse acquireWorkspace(AcquireWorkspaceRequest request) {
        String endpoint = sandboxUrl + "/workspaces/acquire";
        String defect = acquireRequestDefect(request);
        if (defect != null) {
            // Local rejection: no downstream request was made, so no record.
            return workspaceOpFailure("acquireWorkspace rejected: " + defect,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    null, endpoint, 0, false)
                    .toAcquireResponse();
        }
        AcquireWorkspaceResponse eligibilityVerdict =
                runEligibilityGate(request.getRunId().trim(), endpoint);
        if (eligibilityVerdict != null) {
            return eligibilityVerdict;
        }
        HttpAcquireWorkspaceRequest body = new HttpAcquireWorkspaceRequest();
        body.setRun_id(request.getRunId().trim());
        if (request.hasKnownWorkspaceId()) {
            body.setKnown_workspace_id(request.getKnownWorkspaceId().trim());
            body.setKnown_workspace_generation(request.getKnownWorkspaceGeneration().trim());
        }
        long startMs = System.currentTimeMillis();
        try {
            ResponseEntity<HttpAcquireWorkspaceResponse> response = longHttpClient.postForEntity(
                    endpoint, body, HttpAcquireWorkspaceResponse.class);
            int httpStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - startMs;
            HttpAcquireWorkspaceResponse result = response.getBody();
            if (httpStatus != 200 || result == null) {
                // workspaceOpFailure is the single telemetry emission site for
                // workspace-op failures that made a real HTTP call; emitting
                // here as well would write two error records per request.
                return workspaceOpFailure("Invalid acquire response from sandbox",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toAcquireResponse();
            }
            String workspaceResultRaw = result.getWorkspace_result();
            boolean hasVerdictRaw = workspaceResultRaw != null && !workspaceResultRaw.isBlank();
            WorkspaceResult verdict = parseWorkspaceResult(workspaceResultRaw);
            boolean hasWorkspace = result.getWorkspace() != null;
            // Exactly ONE business form is legal on a 200 body: a workspace
            // (any durable status, the honest truth) XOR a machine refusal.
            // Both at once, an unknown verdict value, or neither form is a
            // corrupted downstream protocol: fail closed with a system
            // failure instead of silently dropping one side of the body.
            String malformedReason = null;
            if (hasVerdictRaw && verdict == null) {
                malformedReason = "unknown workspaceResult value: " + workspaceResultRaw;
            } else if (hasWorkspace && verdict != null) {
                malformedReason = "both workspace and workspaceResult present";
            } else if (!hasWorkspace && !hasVerdictRaw) {
                malformedReason = "no workspace and no workspaceResult";
            }
            if (malformedReason != null) {
                log.warn("sandbox.acquireWorkspace.malformedResponse: reason={}, httpStatus={}",
                        malformedReason, httpStatus);
                // Same single-emission contract: workspaceOpFailure writes the
                // one ERROR record for this request.
                return workspaceOpFailure("Malformed acquire response from sandbox (" + malformedReason + ")",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toAcquireResponse();
            }
            emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "OK", null);
            AcquireWorkspaceResponse.Builder mapped = AcquireWorkspaceResponse.newBuilder();
            if (hasWorkspace) {
                WorkspaceInfo.Builder info = WorkspaceInfo.newBuilder()
                        .setWorkspaceId(nullSafe(result.getWorkspace().getWorkspace_id()))
                        .setWorkspaceGeneration(nullSafe(result.getWorkspace().getWorkspace_generation()))
                        .setStatus(nullSafe(result.getWorkspace().getStatus()))
                        .setOwnedByRunId(nullSafe(result.getWorkspace().getOwned_by_run_id()));
                mapped.setWorkspace(info);
            } else {
                mapped.setWorkspaceResult(verdict);
            }
            log.info("sandbox.acquireWorkspace: runId={}, workspaceId={}, result={}",
                    request.getRunId(),
                    result.getWorkspace() == null ? null : result.getWorkspace().getWorkspace_id(),
                    verdict == null ? "ACQUIRED" : verdict);
            return mapped.build();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return workspaceOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT, "acquireWorkspace")
                    .toAcquireResponse();
        } catch (HttpClientErrorException.Conflict e) {
            return workspaceOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT, "acquireWorkspace")
                    .toAcquireResponse();
        } catch (HttpClientErrorException e) {
            return workspaceOpHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 404
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED, "acquireWorkspace")
                    .toAcquireResponse();
        } catch (HttpServerErrorException e) {
            return workspaceOpHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE, "acquireWorkspace")
                    .toAcquireResponse();
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "ACQUIRE_" + detail.getCategory());
            return acquireFailure(nonBlankOr(e, "acquireWorkspace transport failure"), detail);
        } catch (Exception e) {
            log.error("sandbox.acquireWorkspace.failed: error={}", e.getMessage(), e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "ACQUIRE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
            return acquireFailure(nonBlankOr(e, "acquireWorkspace failed"),
                    SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .build());
        }
    }

    /**
     * 显式删除（测试/运维入口）：两阶段转发。deleted=true 仅在
     * 删盘确认与不可复活审计落库后；失败保留 retryableFailure=true 的可
     * 重试语义。删除中的并发取得竞争由沙箱侧状态锁仲裁，网关不重试。
     */
    public DeleteWorkspaceResponse deleteWorkspace(DeleteWorkspaceRequest request) {
        String endpoint = sandboxUrl + "/workspaces/delete";
        if (request == null || request.getWorkspaceId() == null || request.getWorkspaceId().isBlank()
                || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            return workspaceOpFailure("deleteWorkspace rejected: workspaceId and idempotencyKey are required",
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    null, endpoint, 0, false)
                    .toDeleteResponse();
        }
        HttpDeleteWorkspaceRequest body = new HttpDeleteWorkspaceRequest();
        body.setWorkspace_id(request.getWorkspaceId().trim());
        body.setIdempotency_key(request.getIdempotencyKey().trim());
        if (request.hasExpectedLastActiveAt()) {
            body.setExpected_last_active_at(request.getExpectedLastActiveAt().trim());
        }
        long startMs = System.currentTimeMillis();
        try {
            ResponseEntity<HttpDeleteWorkspaceResponse> response = longHttpClient.postForEntity(
                    endpoint, body, HttpDeleteWorkspaceResponse.class);
            int httpStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - startMs;
            HttpDeleteWorkspaceResponse result = response.getBody();
            if (httpStatus != 200 || result == null) {
                emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "ERROR",
                        "DELETE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
                return deleteFailure("Invalid delete response from sandbox",
                        SandboxErrorDetail.newBuilder()
                                .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                                .setDownstreamHttpStatus(httpStatus)
                                .build());
            }
            boolean deleted = Boolean.TRUE.equals(result.getDeleted());
            boolean retryable = Boolean.TRUE.equals(result.getRetryable_failure());
            // 核验顺序：outcome 有值时先按机器三分类核验——
            // REVOKED_NEW_ACTIVITY 对应 deleted=false + retryableFailure=
            // false，是合法形态，不得落入旧互斥布尔校验；两布尔还必须与
            // 分类的对应关系一致。缺 outcome（旧部署）才回落互斥布尔校验：
            // 两布尔相等即判响应损坏，失败关闭。
            String outcomeRaw = result.getOutcome();
            WorkspaceDeleteOutcome outcome = null;
            String malformedReason = null;
            if (outcomeRaw != null && !outcomeRaw.isBlank()) {
                try {
                    outcome = WorkspaceDeleteOutcome.valueOf(outcomeRaw.trim());
                } catch (IllegalArgumentException e) {
                    malformedReason = "unknown outcome value: " + outcomeRaw;
                }
                if (outcome == WorkspaceDeleteOutcome.WORKSPACE_DELETE_OUTCOME_UNSPECIFIED) {
                    malformedReason = "unspecified outcome value";
                }
                if (malformedReason == null) {
                    boolean expectDeleted =
                            outcome == WorkspaceDeleteOutcome.WORKSPACE_DELETE_DELETED;
                    boolean expectRetryable =
                            outcome == WorkspaceDeleteOutcome.WORKSPACE_DELETE_TEMPORARILY_UNAVAILABLE;
                    if (deleted != expectDeleted || retryable != expectRetryable) {
                        malformedReason = "outcome " + outcomeRaw
                                + " inconsistent with deleted=" + deleted
                                + ", retryableFailure=" + retryable;
                    }
                }
            } else if (deleted == retryable) {
                malformedReason = "deleted and retryableFailure must differ";
            }
            if (malformedReason != null) {
                log.warn("sandbox.deleteWorkspace.malformedResponse: reason={}, httpStatus={}",
                        malformedReason, httpStatus);
                emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "ERROR",
                        "DELETE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
                return deleteFailure("Malformed delete response from sandbox (" + malformedReason + ")",
                        SandboxErrorDetail.newBuilder()
                                .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                                .setDownstreamHttpStatus(httpStatus)
                                .build());
            }
            emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "OK", null);
            log.info("sandbox.deleteWorkspace: workspaceId={}, deleted={}, retryableFailure={}, outcome={}",
                    request.getWorkspaceId(), deleted, retryable, outcome);
            DeleteWorkspaceResponse.Builder mapped = DeleteWorkspaceResponse.newBuilder()
                    .setDeleted(deleted)
                    .setRetryableFailure(retryable);
            if (outcome != null) {
                mapped.setOutcome(outcome);
            }
            return mapped.build();
        } catch (HttpClientErrorException.NotFound e) {
            // Business not-found from the sandbox (unknown workspace id).
            return deleteFailure("workspace not found",
                    SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND)
                            .setDownstreamHttpStatus(404)
                            .build());
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return workspaceOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT, "deleteWorkspace")
                    .toDeleteResponse();
        } catch (HttpClientErrorException.Conflict e) {
            return workspaceOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT, "deleteWorkspace")
                    .toDeleteResponse();
        } catch (HttpClientErrorException e) {
            return workspaceOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED, "deleteWorkspace")
                    .toDeleteResponse();
        } catch (HttpServerErrorException e) {
            return workspaceOpHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE, "deleteWorkspace")
                    .toDeleteResponse();
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "DELETE_" + detail.getCategory());
            return deleteFailure(nonBlankOr(e, "deleteWorkspace transport failure"), detail);
        } catch (Exception e) {
            log.error("sandbox.deleteWorkspace.failed: error={}", e.getMessage(), e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "DELETE_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
            return deleteFailure(nonBlankOr(e, "deleteWorkspace failed"),
                    SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .build());
        }
    }

    /**
     * 幂等封口：清理协调器在删除前先把清理意图落盘。200 应答的合法形态
     * 只有一种：sealed=true（workspace 按 Run 是否有盘可选携带）；其余
     * 一律按损坏应答失败关闭。
     */
    @Override
    public SealWorkspaceResponse sealWorkspace(SealWorkspaceRequest request) {
        String endpoint = sandboxUrl + "/workspaces/seal";
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()
                || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            return expiryOpFailure("sealWorkspace rejected: runId and idempotencyKey are required",
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    null, endpoint, 0, false)
                    .toSealResponse();
        }
        HttpSealWorkspaceRequest body = new HttpSealWorkspaceRequest();
        body.setRun_id(request.getRunId().trim());
        body.setIdempotency_key(request.getIdempotencyKey().trim());
        long startMs = System.currentTimeMillis();
        try {
            ResponseEntity<HttpSealWorkspaceResponse> response = shortHttpClient.postForEntity(
                    endpoint, body, HttpSealWorkspaceResponse.class);
            int httpStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - startMs;
            HttpSealWorkspaceResponse result = response.getBody();
            if (httpStatus != 200 || result == null) {
                return expiryOpFailure("Invalid seal response from sandbox",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toSealResponse();
            }
            if (!Boolean.TRUE.equals(result.getSealed())) {
                log.warn("sandbox.sealWorkspace.malformedResponse: sealed={}, httpStatus={}",
                        result.getSealed(), httpStatus);
                return expiryOpFailure("Malformed seal response from sandbox (sealed must be true)",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toSealResponse();
            }
            emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "OK", null);
            SealWorkspaceResponse.Builder mapped = SealWorkspaceResponse.newBuilder()
                    .setSealed(true);
            if (result.getWorkspace() != null) {
                mapped.setWorkspace(mapWorkspaceInfo(result.getWorkspace()));
            }
            log.info("sandbox.sealWorkspace: runId={}, workspaceId={}",
                    request.getRunId(),
                    result.getWorkspace() == null ? null : result.getWorkspace().getWorkspace_id());
            return mapped.build();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return expiryOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT, "sealWorkspace")
                    .toSealResponse();
        } catch (HttpClientErrorException e) {
            return expiryOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED, "sealWorkspace")
                    .toSealResponse();
        } catch (HttpServerErrorException e) {
            return expiryOpHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE, "sealWorkspace")
                    .toSealResponse();
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "SEAL_" + detail.getCategory());
            return SealWorkspaceResponse.newBuilder()
                    .setError(nonBlankOr(e, "sealWorkspace transport failure"))
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.sealWorkspace.failed: error={}", e.getMessage(), e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "SEAL_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
            return SealWorkspaceResponse.newBuilder()
                    .setError(nonBlankOr(e, "sealWorkspace failed"))
                    .setErrorDetail(SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .build())
                    .build();
        }
    }

    /**
     * 按 Run 只读查盘的三态转发。合法形态：outcome 必须是
     * NOT_FOUND/FOUND/FOUND_DELETED 之一；NOT_FOUND 不得携带 workspace；
     * FOUND_DELETED 必带 workspace（旧身份核对）；FOUND 可带可不带
     * （无盘封口即不带）。其余一律失败关闭，绝不让调用方把损坏应答读成
     * 「查无此盘」。
     */
    @Override
    public QueryWorkspaceResponse queryWorkspace(QueryWorkspaceRequest request) {
        String endpoint = sandboxUrl + "/workspaces/query";
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()) {
            return expiryOpFailure("queryWorkspace rejected: runId is required",
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    null, endpoint, 0, false)
                    .toQueryResponse();
        }
        HttpQueryWorkspaceRequest body = new HttpQueryWorkspaceRequest();
        body.setRun_id(request.getRunId().trim());
        long startMs = System.currentTimeMillis();
        try {
            ResponseEntity<HttpQueryWorkspaceResponse> response = shortHttpClient.postForEntity(
                    endpoint, body, HttpQueryWorkspaceResponse.class);
            int httpStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - startMs;
            HttpQueryWorkspaceResponse result = response.getBody();
            if (httpStatus != 200 || result == null) {
                return expiryOpFailure("Invalid query response from sandbox",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toQueryResponse();
            }
            String outcomeRaw = result.getOutcome();
            WorkspaceQueryOutcome outcome = null;
            String malformedReason = null;
            if (outcomeRaw == null || outcomeRaw.isBlank()) {
                malformedReason = "missing outcome";
            } else {
                try {
                    outcome = WorkspaceQueryOutcome.valueOf(outcomeRaw.trim());
                } catch (IllegalArgumentException e) {
                    malformedReason = "unknown outcome value: " + outcomeRaw;
                }
                if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_OUTCOME_UNSPECIFIED) {
                    malformedReason = "unspecified outcome value";
                }
            }
            boolean hasWorkspace = result.getWorkspace() != null;
            if (malformedReason == null) {
                if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_NOT_FOUND && hasWorkspace) {
                    malformedReason = "NOT_FOUND must not carry workspace";
                } else if (outcome == WorkspaceQueryOutcome.WORKSPACE_QUERY_FOUND_DELETED
                        && !hasWorkspace) {
                    malformedReason = "FOUND_DELETED must carry workspace";
                }
            }
            if (malformedReason != null) {
                log.warn("sandbox.queryWorkspace.malformedResponse: reason={}, httpStatus={}",
                        malformedReason, httpStatus);
                return expiryOpFailure("Malformed query response from sandbox (" + malformedReason + ")",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toQueryResponse();
            }
            emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "OK", null);
            QueryWorkspaceResponse.Builder mapped = QueryWorkspaceResponse.newBuilder()
                    .setOutcome(outcome);
            if (hasWorkspace) {
                mapped.setWorkspace(mapWorkspaceInfo(result.getWorkspace()));
            }
            if (result.getLast_active_at() != null && !result.getLast_active_at().isBlank()) {
                mapped.setLastActiveAt(result.getLast_active_at());
            }
            if (result.getDeleted_at() != null && !result.getDeleted_at().isBlank()) {
                mapped.setDeletedAt(result.getDeleted_at());
            }
            log.info("sandbox.queryWorkspace: runId={}, outcome={}, hasWorkspace={}",
                    request.getRunId(), outcome, hasWorkspace);
            return mapped.build();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return expiryOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT, "queryWorkspace")
                    .toQueryResponse();
        } catch (HttpClientErrorException e) {
            return expiryOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED, "queryWorkspace")
                    .toQueryResponse();
        } catch (HttpServerErrorException e) {
            return expiryOpHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE, "queryWorkspace")
                    .toQueryResponse();
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "QUERY_" + detail.getCategory());
            return QueryWorkspaceResponse.newBuilder()
                    .setError(nonBlankOr(e, "queryWorkspace transport failure"))
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.queryWorkspace.failed: error={}", e.getMessage(), e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "QUERY_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
            return QueryWorkspaceResponse.newBuilder()
                    .setError(nonBlankOr(e, "queryWorkspace failed"))
                    .setErrorDetail(SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .build())
                    .build();
        }
    }

    /**
     * 到期候选分页转发：本地先校验 pageSize>0（服务端只承诺封顶，不替
     * 调用方修正非正值）；页内行与继续令牌原样透传。
     */
    @Override
    public ListWorkspaceExpiryCandidatesResponse listWorkspaceExpiryCandidates(
            ListWorkspaceExpiryCandidatesRequest request) {
        String endpoint = sandboxUrl + "/workspaces/expiry-candidates";
        if (request == null || request.getPageSize() <= 0) {
            return expiryOpFailure("listWorkspaceExpiryCandidates rejected: pageSize must be positive",
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    null, endpoint, 0, false)
                    .toCandidatesResponse();
        }
        HttpExpiryCandidatesRequest body = new HttpExpiryCandidatesRequest();
        body.setPage_size(request.getPageSize());
        body.setPage_token(request.getPageToken() == null ? "" : request.getPageToken());
        long startMs = System.currentTimeMillis();
        try {
            ResponseEntity<HttpExpiryCandidatesResponse> response = shortHttpClient.postForEntity(
                    endpoint, body, HttpExpiryCandidatesResponse.class);
            int httpStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - startMs;
            HttpExpiryCandidatesResponse result = response.getBody();
            if (httpStatus != 200 || result == null) {
                return expiryOpFailure("Invalid expiry-candidates response from sandbox",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                        httpStatus, endpoint, durationMs, true)
                        .toCandidatesResponse();
            }
            emitSandboxHttp("POST", endpoint, httpStatus, durationMs, "OK", null);
            ListWorkspaceExpiryCandidatesResponse.Builder mapped =
                    ListWorkspaceExpiryCandidatesResponse.newBuilder()
                            .setNextPageToken(nullSafe(result.getNext_page_token()));
            if (result.getCandidates() != null) {
                for (HttpExpiryCandidate row : result.getCandidates()) {
                    mapped.addCandidates(WorkspaceExpiryCandidate.newBuilder()
                            .setRunId(nullSafe(row.getRun_id()))
                            .setWorkspaceId(nullSafe(row.getWorkspace_id()))
                            .setWorkspaceGeneration(nullSafe(row.getWorkspace_generation()))
                            .setStatus(nullSafe(row.getStatus()))
                            .setLastActiveAt(nullSafe(row.getLast_active_at())));
                }
            }
            log.info("sandbox.listWorkspaceExpiryCandidates: pageSize={}, rows={}, hasNext={}",
                    request.getPageSize(),
                    result.getCandidates() == null ? 0 : result.getCandidates().size(),
                    result.getNext_page_token() != null && !result.getNext_page_token().isBlank());
            return mapped.build();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return expiryOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT,
                    "listWorkspaceExpiryCandidates")
                    .toCandidatesResponse();
        } catch (HttpClientErrorException e) {
            return expiryOpHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED,
                    "listWorkspaceExpiryCandidates")
                    .toCandidatesResponse();
        } catch (HttpServerErrorException e) {
            return expiryOpHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE,
                    "listWorkspaceExpiryCandidates")
                    .toCandidatesResponse();
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "EXPIRY_CANDIDATES_" + detail.getCategory());
            return ListWorkspaceExpiryCandidatesResponse.newBuilder()
                    .setError(nonBlankOr(e, "listWorkspaceExpiryCandidates transport failure"))
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.listWorkspaceExpiryCandidates.failed: error={}", e.getMessage(), e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "EXPIRY_CANDIDATES_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
            return ListWorkspaceExpiryCandidatesResponse.newBuilder()
                    .setError(nonBlankOr(e, "listWorkspaceExpiryCandidates failed"))
                    .setErrorDetail(SandboxErrorDetail.newBuilder()
                            .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                            .build())
                    .build();
        }
    }

    private static WorkspaceInfo mapWorkspaceInfo(HttpWorkspaceInfo info) {
        return WorkspaceInfo.newBuilder()
                .setWorkspaceId(nullSafe(info.getWorkspace_id()))
                .setWorkspaceGeneration(nullSafe(info.getWorkspace_generation()))
                .setStatus(nullSafe(info.getStatus()))
                .setOwnedByRunId(nullSafe(info.getOwned_by_run_id()))
                .build();
    }

    /** Shared failure carrier for the seal/query/expiry-candidates RPCs. */
    private static final class ExpiryOpFailure {
        final String error;
        final SandboxErrorDetail detail;

        ExpiryOpFailure(String error, SandboxErrorDetail detail) {
            this.error = error;
            this.detail = detail;
        }

        SealWorkspaceResponse toSealResponse() {
            return SealWorkspaceResponse.newBuilder().setError(error).setErrorDetail(detail).build();
        }

        QueryWorkspaceResponse toQueryResponse() {
            return QueryWorkspaceResponse.newBuilder().setError(error).setErrorDetail(detail).build();
        }

        ListWorkspaceExpiryCandidatesResponse toCandidatesResponse() {
            return ListWorkspaceExpiryCandidatesResponse.newBuilder()
                    .setError(error).setErrorDetail(detail).build();
        }
    }

    /**
     * Failure builder and the single telemetry emission site for the
     * seal/query/expiry-candidates RPCs; same one-record contract as
     * workspaceOpFailure (local rejections pass httpAttempted = false).
     */
    private ExpiryOpFailure expiryOpFailure(
            String error, SandboxHttpErrorCategory category, Integer downstreamStatus,
            String endpoint, long durationMs, boolean httpAttempted) {
        if (httpAttempted) {
            emitSandboxHttp("POST", endpoint, downstreamStatus == null ? -1 : downstreamStatus,
                    durationMs, "ERROR", "WORKSPACE_EXPIRY_" + category);
        }
        SandboxErrorDetail.Builder detail = SandboxErrorDetail.newBuilder().setCategory(category);
        if (downstreamStatus != null) {
            detail.setDownstreamHttpStatus(downstreamStatus);
        }
        return new ExpiryOpFailure(error, detail.build());
    }

    private ExpiryOpFailure expiryOpHttpFailure(
            RuntimeException e, String endpoint, long startMs,
            SandboxHttpErrorCategory category, String opName) {
        int status = extractDownstreamHttpStatus(e);
        emitSandboxHttp("POST", endpoint, status, System.currentTimeMillis() - startMs,
                "ERROR", opName.toUpperCase() + "_" + category);
        return new ExpiryOpFailure(nonBlankOr(e, opName + " failed"),
                SandboxErrorDetail.newBuilder().setCategory(category)
                        .setDownstreamHttpStatus(status).build());
    }

    private static String acquireRequestDefect(AcquireWorkspaceRequest request) {
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()) {
            return "runId is required";
        }
        boolean hasId = request.hasKnownWorkspaceId();
        boolean hasGeneration = request.hasKnownWorkspaceGeneration();
        if (hasId != hasGeneration) {
            return "knownWorkspaceId and knownWorkspaceGeneration must be provided together or all absent";
        }
        return null;
    }

    /** Shared failure carrier for the two workspace RPCs (acquire/delete shapes). */
    private static final class WorkspaceOpFailure {
        final String error;
        final SandboxErrorDetail detail;

        WorkspaceOpFailure(String error, SandboxErrorDetail detail) {
            this.error = error;
            this.detail = detail;
        }

        AcquireWorkspaceResponse toAcquireResponse() {
            return AcquireWorkspaceResponse.newBuilder().setError(error).setErrorDetail(detail).build();
        }

        DeleteWorkspaceResponse toDeleteResponse() {
            return DeleteWorkspaceResponse.newBuilder().setError(error).setErrorDetail(detail).build();
        }
    }

    private AcquireWorkspaceResponse acquireFailure(String error, SandboxErrorDetail detail) {
        return AcquireWorkspaceResponse.newBuilder().setError(error).setErrorDetail(detail).build();
    }

    private DeleteWorkspaceResponse deleteFailure(String error, SandboxErrorDetail detail) {
        return DeleteWorkspaceResponse.newBuilder().setError(error).setErrorDetail(detail).build();
    }

    /**
     * Shared failure carrier builder and the single telemetry emission site
     * for the two workspace RPCs. The one-record decision rests on whether a
     * downstream HTTP request was actually attempted, never on the measured
     * duration: a real exchange can complete inside one millisecond tick, so
     * gating on {@code durationMs > 0} would silently drop the one record a
     * fast failed call still owes. Local rejections pass
     * {@code httpAttempted = false} and write no record.
     */
    WorkspaceOpFailure workspaceOpFailure(
            String error, SandboxHttpErrorCategory category, Integer downstreamStatus,
            String endpoint, long durationMs, boolean httpAttempted) {
        if (httpAttempted) {
            emitSandboxHttp("POST", endpoint, downstreamStatus == null ? -1 : downstreamStatus,
                    durationMs, "ERROR", "WORKSPACE_" + category);
        }
        SandboxErrorDetail.Builder detail = SandboxErrorDetail.newBuilder().setCategory(category);
        if (downstreamStatus != null) {
            detail.setDownstreamHttpStatus(downstreamStatus);
        }
        return new WorkspaceOpFailure(error, detail.build());
    }

    private WorkspaceOpFailure workspaceOpHttpFailure(
            RuntimeException e, String endpoint, long startMs,
            SandboxHttpErrorCategory category, String opName) {
        int status = extractDownstreamHttpStatus(e);
        emitSandboxHttp("POST", endpoint, status, System.currentTimeMillis() - startMs,
                "ERROR", opName.toUpperCase() + "_" + category);
        return new WorkspaceOpFailure(nonBlankOr(e, opName + " failed"),
                SandboxErrorDetail.newBuilder().setCategory(category)
                        .setDownstreamHttpStatus(status).build());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public CancelTaskResponse cancelTask(CancelTaskRequest request) {
        String endpoint = sandboxUrl + "/tasks/cancel";
        String defect = cancelRequestDefect(request);
        if (defect != null) {
            return cancelFailure(defect,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT, null);
        }
        HttpCancelTaskRequest body = new HttpCancelTaskRequest();
        body.setCancel_request_id(request.getCancelRequestId().trim());
        body.setReason(request.getReason());
        if (request.hasByTaskId()) {
            HttpTaskIdCancelTarget target = new HttpTaskIdCancelTarget();
            target.setTask_id(request.getByTaskId().getTaskId().trim());
            body.setBy_task_id(target);
        } else {
            HttpOperationCancelTarget target = new HttpOperationCancelTarget();
            target.setOperation_id(request.getByOperation().getOperationId().trim());
            target.setRequest_fingerprint(request.getByOperation().getRequestFingerprint().trim());
            body.setBy_operation(target);
        }

        long startMs = System.currentTimeMillis();
        try {
            ResponseEntity<HttpCancelTaskResponse> response = shortHttpClient.postForEntity(
                    endpoint, body, HttpCancelTaskResponse.class);
            int httpStatus = response.getStatusCode().value();
            HttpCancelTaskResponse result = response.getBody();
            CancelOutcome outcome = result == null ? null : parseCancelOutcome(result.getOutcome());
            if (httpStatus != 200 || result == null || result.getError() != null && !result.getError().isBlank()
                    || !validCancelResult(outcome, result)) {
                emitSandboxHttp("POST", endpoint, httpStatus, System.currentTimeMillis() - startMs,
                        "ERROR", "CANCEL_TASK_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
                return cancelFailure("Invalid cancel response from sandbox",
                        SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED, httpStatus);
            }
            emitSandboxHttp("POST", endpoint, httpStatus, System.currentTimeMillis() - startMs,
                    "OK", null);
            CancelTaskResponse.Builder mapped = CancelTaskResponse.newBuilder()
                    .setOutcome(outcome);
            if (result.getTask_id() != null) mapped.setTaskId(result.getTask_id());
            if (result.getStatus() != null) mapped.setStatus(result.getStatus());
            return mapped.build();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.UnprocessableEntity e) {
            return cancelHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_INVALID_ARGUMENT);
        } catch (HttpClientErrorException.Conflict e) {
            return cancelHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_CONFLICT);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return cancelHttpFailure(e, endpoint, startMs,
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE);
        } catch (HttpClientErrorException e) {
            return cancelHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 404
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED);
        } catch (HttpServerErrorException e) {
            return cancelHttpFailure(e, endpoint, startMs, e.getStatusCode().value() == 503
                    ? SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_OVERLOADED_OR_UNAVAILABLE
                    : SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_DOWNSTREAM_FAILURE);
        } catch (ResourceAccessException e) {
            SandboxErrorDetail detail = buildTransportErrorDetail(e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "CANCEL_TASK_" + detail.getCategory());
            return CancelTaskResponse.newBuilder().setOutcome(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED)
                    .setError(nonBlankOr(e, "cancelTask transport failure"))
                    .setErrorDetail(detail).build();
        } catch (Exception e) {
            log.error("sandbox.cancelTask.failed: reason={}", e.getMessage(), e);
            emitSandboxHttp("POST", endpoint, -1, System.currentTimeMillis() - startMs,
                    "ERROR", "CANCEL_TASK_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
            return cancelFailure(nonBlankOr(e, "cancelTask failed"),
                    SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED, null);
        }
    }

    private static String cancelRequestDefect(CancelTaskRequest request) {
        if (request == null || request.getCancelRequestId().isBlank()) return "cancelRequestId is required";
        if (request.hasByTaskId()) {
            return request.getByTaskId().getTaskId().isBlank() ? "taskId is required" : null;
        }
        if (request.hasByOperation()) {
            String operationId = request.getByOperation().getOperationId().trim();
            String fingerprint = request.getByOperation().getRequestFingerprint().trim();
            if (!operationId.matches("[^:\\s]+:[^:\\s]+:[1-9][0-9]*"))
                return "operationId must identify one attempt";
            if (!fingerprint.matches("sha256:[0-9a-f]{64}"))
                return "requestFingerprint must be lowercase sha256";
            return null;
        }
        return "cancel target is required";
    }

    private static CancelOutcome parseCancelOutcome(String value) {
        if (value == null) return null;
        try {
            CancelOutcome outcome = CancelOutcome.valueOf(value);
            return outcome == CancelOutcome.UNRECOGNIZED ? null : outcome;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean validCancelResult(CancelOutcome outcome, HttpCancelTaskResponse result) {
        if (outcome == null || outcome == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED) return false;
        String taskId = result.getTask_id();
        String status = result.getStatus();
        if (outcome == CancelOutcome.NOT_FOUND) {
            return (taskId == null || taskId.isBlank()) && (status == null || status.isBlank());
        }
        if (taskId == null || taskId.isBlank()) return false;
        return switch (outcome) {
            // The cancel request keeps its first outcome on replay while current task status advances.
            case CANCEL_INTENT_RECORDED -> "QUEUED".equals(status) || "RUNNING".equals(status)
                    || "SUCCEEDED".equals(status) || "FAILED".equals(status)
                    || "CANCELED".equals(status);
            case CANCELED -> "CANCELED".equals(status);
            case ALREADY_TERMINAL -> "SUCCEEDED".equals(status) || "FAILED".equals(status)
                    || "CANCELED".equals(status);
            default -> false;
        };
    }

    private CancelTaskResponse cancelHttpFailure(RuntimeException e, String endpoint, long startMs,
                                                 SandboxHttpErrorCategory category) {
        int httpStatus = extractDownstreamHttpStatus(e);
        emitSandboxHttp("POST", endpoint, httpStatus, System.currentTimeMillis() - startMs,
                "ERROR", "CANCEL_TASK_" + category.name());
        return cancelFailure(extractDownstreamErrorText(e, "cancelTask rejected by sandbox"),
                category, httpStatus);
    }

    private static CancelTaskResponse cancelFailure(String error, SandboxHttpErrorCategory category,
                                                    Integer httpStatus) {
        SandboxErrorDetail.Builder detail = SandboxErrorDetail.newBuilder().setCategory(category);
        if (httpStatus != null) detail.setDownstreamHttpStatus(httpStatus);
        return CancelTaskResponse.newBuilder().setOutcome(CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED)
                .setError(nonBlankOr(error, "cancelTask failed"))
                .setErrorDetail(detail.build()).build();
    }

    /**
     * 260809-26Q3-stage1-w3 D15 §4.3.1: encode taskId as a single path segment.
     * Mirrors the getTaskByOperationId URL construction pattern (lines ~432-436)
     * to prevent route injection / misrouting when taskId contains '/', '%',
     * spaces, or non-ASCII characters. Returns URI for direct use with
     * RestTemplate#getForEntity(URI, Class).
     *
     * Red line D15 §6.5: status AND result AND telemetry endpoints all use
     * path-segment encoding — bare `sandboxUrl + "/tasks/" + taskId` is forbidden.
     */
    private URI buildTaskStatusEndpoint(String taskId) {
        return UriComponentsBuilder.fromHttpUrl(sandboxUrl)
                .pathSegment("tasks", taskId)
                .build()
                .encode()
                .toUri();
    }

    /**
     * 260809-26Q3-stage1-w3 D15 §4.3.1: encode taskId + "/result" suffix using
     * path-segment encoding. Same rationale as {@link #buildTaskStatusEndpoint}:
     * result endpoint is also a taskId-bearing path and must use the same
     * encoding level as operationId / status lookups.
     */
    private URI buildTaskResultEndpoint(String taskId) {
        return UriComponentsBuilder.fromHttpUrl(sandboxUrl)
                .pathSegment("tasks", taskId, "result")
                .build()
                .encode()
                .toUri();
    }

    @Override
    public TaskStatusResponse getTaskStatus(GetTaskStatusRequest request) {
        long startMs = System.currentTimeMillis();
        log.info("sandbox.getTaskStatus: taskId={}", request.getTaskId());
        // 260809-26Q3-stage1-w3 D15 §4.3.1: encode taskId as single path segment.
        // Declared before try so catch blocks can reference the same encoded form
        // for telemetry (D15 red line 6: status AND telemetry一致 encoded).
        URI endpointUri = buildTaskStatusEndpoint(request.getTaskId());
        String endpoint = endpointUri.toASCIIString();
        try {
            long httpStart = System.currentTimeMillis();
            ResponseEntity<HttpTask> response = shortHttpClient.getForEntity(endpointUri, HttpTask.class);
            log.info("sandbox.http: endpoint=GET {}, httpStatus={}, durationMs={}",
                    endpoint, response.getStatusCode().value(), System.currentTimeMillis() - httpStart);
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 5 (Cindy 91490076 #5): do NOT emit OK
            // telemetry before body validation; final emit happens after body shape is
            // classified (success / typed failure).
            int downstreamStatus = response.getStatusCode().value();
            long durationMs = System.currentTimeMillis() - httpStart;

            if (response.getBody() != null) {
                HttpTask task = response.getBody();
                TaskStatusResponse.Builder builder = TaskStatusResponse.newBuilder()
                        .setTaskId(task.getTask_id())
                        .setStatus(task.getStatus());
                if (task.getStarted_at() != null) builder.setStartedAt(task.getStarted_at());
                if (task.getFinished_at() != null) builder.setFinishedAt(task.getFinished_at());
                if (task.getError() != null) builder.setError(task.getError());
                emitSandboxHttp("GET", endpoint, downstreamStatus, durationMs, "OK", null);
                log.info("sandbox.getTaskStatus.result: taskId={}, status={}, totalDurationMs={}",
                        task.getTask_id(), task.getStatus(), System.currentTimeMillis() - startMs);
                return builder.build();
            } else {
                log.warn("sandbox.getTaskStatus.emptyBody: taskId={}, httpStatus={}, totalDurationMs={}",
                        request.getTaskId(), downstreamStatus, System.currentTimeMillis() - startMs);
                // 260809-26Q3-stage1-w3 D13 MUST-FIX 4 (Cindy 91490076 #4): HTTP success but
                // empty body — malformed response. Use ACTUAL downstream status (could be
                // 200/201/202/204), not hardcoded 200.
                SandboxErrorDetail detail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(downstreamStatus)
                        .build();
                emitSandboxHttp("GET", endpoint, downstreamStatus, durationMs, "ERROR",
                        "GET_STATUS_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
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
            // 260809-26Q3-stage1-w3 D13 MUST-FIX 5 (Cindy 91490076 #5): 404 is a typed
            // failure (task resource doesn't exist) — emit ERROR + frozen category, not OK.
            emitSandboxHttp("GET", endpoint, 404,
                    System.currentTimeMillis() - startMs, "ERROR",
                    "GET_STATUS_SANDBOX_HTTP_ERROR_CATEGORY_NOT_FOUND");
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
            String text = nonBlankOr(e, "getTaskStatus transport failure");
            log.warn("sandbox.getTaskStatus.transportFailure: taskId={}, category={}, totalDurationMs={}, error={}",
                    request.getTaskId(), detail.getCategory().name(),
                    System.currentTimeMillis() - startMs, text, e);
            emitSandboxHttp("GET", endpoint, -1,
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
            emitSandboxHttp("GET", endpoint, -1,
                    System.currentTimeMillis() - startMs, "ERROR", "GET_STATUS_FAILED");
            String text = nonBlankOr(e, "getTaskStatus failed");
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
        // 260809-26Q3-stage1-w3 D15 §4.3.1: helper-side URL also encoded.
        emitSandboxHttp("GET", buildTaskStatusEndpoint(taskId).toASCIIString(), statusCode,
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
        // 260809-26Q3-stage1-w3 D15 §4.3.1: encode taskId + "result" as path segments.
        // Declared before try so all catch blocks reference the same encoded form for
        // telemetry (D15 red line 6: status AND result AND telemetry一致 encoded).
        URI endpointUri = buildTaskResultEndpoint(request.getTaskId());
        String endpoint = endpointUri.toASCIIString();
        try {
            // Check status first to ensure we don't hit 409. Status lookup uses the short
            // HTTP client (handled inside getTaskStatus). The result fetch below uses the
            // long HTTP client since it can wait for downstream task completion.
            TaskStatusResponse status = getTaskStatus(GetTaskStatusRequest.newBuilder().setTaskId(request.getTaskId()).build());
            if (isResultBearingTerminal(status.getStatus())) {
                long httpStart = System.currentTimeMillis();
                ResponseEntity<HttpExecuteResult> response = longHttpClient.getForEntity(endpointUri, HttpExecuteResult.class);
                int downstreamStatus = response.getStatusCode().value();
                long resultDurationMs = System.currentTimeMillis() - httpStart;
                log.info("sandbox.http: endpoint=GET {}, httpStatus={}, durationMs={}",
                        endpoint, downstreamStatus, resultDurationMs);
                // 260809-26Q3-stage1-w3 D13 MUST-FIX 5 (Cindy 91490076 #5): final telemetry
                // AFTER body shape classification, not before.
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
                    emitSandboxHttp("GET", endpoint, downstreamStatus, resultDurationMs, "OK", null);
                    return builder.build();
                }
                // 260809-26Q3-stage1-w3 D13 MUST-FIX round-2 #1 (Cindy 1b29792d #1):
                // terminal status + /result 2xx empty body — MUST NOT fall through to
                // not-ready branch (which would lose typed detail). Return typed failure
                // preserving taskId/status, dual-write non-blank error + UNSPECIFIED detail
                // with ACTUAL downstream status, emit single final ERROR event.
                SandboxErrorDetail emptyDetail = SandboxErrorDetail.newBuilder()
                        .setCategory(SandboxHttpErrorCategory.SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED)
                        .setDownstreamHttpStatus(downstreamStatus)
                        .build();
                emitSandboxHttp("GET", endpoint, downstreamStatus, resultDurationMs, "ERROR",
                        "GET_RESULT_SANDBOX_HTTP_ERROR_CATEGORY_UNSPECIFIED");
                log.warn("sandbox.getTaskResult.terminalEmptyBody: taskId={}, status={}, httpStatus={}, totalDurationMs={}",
                        request.getTaskId(), status.getStatus(), downstreamStatus,
                        System.currentTimeMillis() - startMs);
                return TaskResultResponse.newBuilder()
                        .setTaskId(request.getTaskId())
                        .setStatus(status.getStatus())
                        .setError("Result body empty (downstream returned "
                                + downstreamStatus + " for terminal task)")
                        .setErrorDetail(emptyDetail)
                        .build();
            }

            // 260809-26Q3-stage1-w3 D13 MUST-FIX 1 (Cindy 91490076 #1): preserve typed
            // failure from status pre-check. If status.hasErrorDetail() is true, the status
            // lookup itself hit a typed failure (5xx/timeout/transport). Propagate fail-closed
            // — do NOT access the result endpoint, do NOT rewrite to "Result not available".
            if (status.hasErrorDetail()) {
                log.warn("sandbox.getTaskResult.statusPrecheckFailure: taskId={}, category={}, totalDurationMs={}",
                        request.getTaskId(), status.getErrorDetail().getCategory().name(),
                        System.currentTimeMillis() - startMs);
                TaskResultResponse.Builder builder = TaskResultResponse.newBuilder()
                        .setTaskId(request.getTaskId())
                        .setStatus(status.getStatus());
                String statusError = status.getError();
                builder.setError((statusError == null || statusError.isBlank())
                        ? "Result not available (status pre-check failed)"
                        : statusError);
                builder.setErrorDetail(status.getErrorDetail());
                return builder.build();
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
            String text = nonBlankOr(e, "getTaskResult transport failure");
            log.warn("sandbox.getTaskResult.transportFailure: taskId={}, category={}, totalDurationMs={}, error={}",
                    request.getTaskId(), detail.getCategory().name(),
                    System.currentTimeMillis() - startMs, text, e);
            emitSandboxHttp("GET", endpoint, -1,
                    System.currentTimeMillis() - startMs, "ERROR",
                    "GET_RESULT_" + detail.getCategory());
            return TaskResultResponse.newBuilder()
                    .setError(text)
                    .setErrorDetail(detail)
                    .build();
        } catch (Exception e) {
            log.error("sandbox.getTaskResult.failed: taskId={}, totalDurationMs={}, error={}",
                    request.getTaskId(), System.currentTimeMillis() - startMs, e.getMessage(), e);
            emitSandboxHttp("GET", endpoint, -1,
                    System.currentTimeMillis() - startMs, "ERROR", "GET_RESULT_FAILED");
            String text = nonBlankOr(e, "getTaskResult failed");
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
        // 260809-26Q3-stage1-w3 D15 §4.3.1: helper-side URL also encoded.
        emitSandboxHttp("GET", buildTaskResultEndpoint(taskId).toASCIIString(), statusCode,
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
        // Persistent-workspace identity group: forwarded as a whole or not at
        // all (a partial group is rejected locally before HTTP).
        private String run_id;
        private String workspace_id;
        private String workspace_generation;
    }

    @Data
    static class HttpCreateTaskResponse {
        private String task_id;
        private String status;
        private Boolean existing;
        private String request_fingerprint;
        // Present iff the workspace refused the create: no task was created,
        // no admission was taken. Value vocabulary matches WorkspaceResult.
        private String workspace_result;
    }

    @Data
    static class HttpAcquireWorkspaceRequest {
        private String run_id;
        private String known_workspace_id;
        private String known_workspace_generation;
    }

    @Data
    static class HttpWorkspaceInfo {
        private String workspace_id;
        private String workspace_generation;
        private String status;
        private String owned_by_run_id;
    }

    @Data
    static class HttpAcquireWorkspaceResponse {
        private HttpWorkspaceInfo workspace;
        private String workspace_result;
    }

    @Data
    static class HttpDeleteWorkspaceRequest {
        private String workspace_id;
        private String idempotency_key;
        // 自动条件删除携带的扫描观测时刻；手动路径不携带。
        private String expected_last_active_at;
    }

    @Data
    static class HttpDeleteWorkspaceResponse {
        private Boolean deleted;
        private Boolean retryable_failure;
        // 机器三分类（WorkspaceDeleteOutcome 的字符串形）；旧部署缺失。
        private String outcome;
    }

    @Data
    static class HttpSealWorkspaceRequest {
        private String run_id;
        private String idempotency_key;
    }

    @Data
    static class HttpSealWorkspaceResponse {
        private Boolean sealed;
        private HttpWorkspaceInfo workspace;
    }

    @Data
    static class HttpQueryWorkspaceRequest {
        private String run_id;
    }

    @Data
    static class HttpQueryWorkspaceResponse {
        private String outcome;
        private HttpWorkspaceInfo workspace;
        private String last_active_at;
        private String deleted_at;
    }

    @Data
    static class HttpExpiryCandidatesRequest {
        private Integer page_size;
        private String page_token;
    }

    @Data
    static class HttpExpiryCandidate {
        private String run_id;
        private String workspace_id;
        private String workspace_generation;
        private String status;
        private String last_active_at;
    }

    @Data
    static class HttpExpiryCandidatesResponse {
        private List<HttpExpiryCandidate> candidates;
        private String next_page_token;
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
    static class HttpCancelTaskRequest {
        private HttpTaskIdCancelTarget by_task_id;
        private HttpOperationCancelTarget by_operation;
        private String cancel_request_id;
        private String reason;
    }

    @Data
    static class HttpTaskIdCancelTarget {
        private String task_id;
    }

    @Data
    static class HttpOperationCancelTarget {
        private String operation_id;
        private String request_fingerprint;
    }

    @Data
    static class HttpCancelTaskResponse {
        private String outcome;
        private String task_id;
        private String status;
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
