package world.willfrog.agent.tools.dataanalysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.debug.DebugObservabilityService;
import world.willfrog.agent.platform.exception.RunStartedAtMissingException;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.service.AgentRunBudgetService;
import world.willfrog.agent.platform.service.ToolDescriptionTexts;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.tools.dataset.DatasetEntryMetadataReader;
import world.willfrog.agent.tools.dataset.RunLevelIdResolver;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.PythonSandboxJobMeteringAdapter;
import world.willfrog.agent.tools.python.PythonSandboxJobRunnerAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobAdapterRegistry;
import world.willfrog.agent.tools.sandboxjob.SandboxJobAdapters;
import world.willfrog.agent.tools.sandboxjob.SandboxJobObservability;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResponses;
import world.willfrog.agent.tools.sandboxjob.SandboxJobWaitPolicy;
import world.willfrog.agent.tools.sandboxjob.SandboxToolJobLifecycle;
import world.willfrog.agent.workflow.AgentRunDatasetCsvWriter;
import world.willfrog.agent.workflow.AgentRunDatasetEntry;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.agent.workflow.AgentRunDatasetSnapshot;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * LangChain4j 工具 executeQuery：模型只写 SQL，执行体是沙箱内平台固定的 DuckDB 运行器，
 * 数据集按 run 级编号只读挂载（视图名 t1、t2……），引擎约束由运行器强制执行。
 *
 * <p>本类只做 SQL 工具的自有语义：入参与档位解析、运行器渲染、容量事实冻结。
 * 生命周期编排（PREPARING 抢占、创建裁决、轮询、终态、挂起移交）全部走
 * {@link SandboxToolJobLifecycle} 框架，本类按四适配器契约提供请求/结果两个
 * 工具自有适配器，并复用 executePython 的沙箱 RPC 运行器适配器与计量适配器
 * （同一沙箱通道、同一用量模型）。等待组成员走 {@link SandboxToolJobLifecycle#dispatchWaitGroupMember}：
 * 建完后台任务就把线程交出去，不读 DAG 阻塞轮询。</p>
 */
@Component
@Slf4j
public class SqlQueryTools {

    /** 运行器脚本在 classpath 的位置；投递前替换 SPEC_B64 占位符。 */
    static final String RUNNER_RESOURCE = "/sandboxjob/duckdb_query_runner.py";
    /** 运行器脚本末尾的规格占位符（替换为 base64 编码的规格 JSON）。 */
    static final String SPEC_PLACEHOLDER = "__SPEC_B64_PLACEHOLDER__";
    /** 沙箱内输入数据的稳定符号链接，由 sandbox_runner 对每个任务建立。 */
    static final String SANDBOX_INPUT_ROOT = "/sandbox/input";
    /** DuckDB 外溢临时目录：一任务一容器，/tmp 可写且随容器销毁。 */
    static final String DUCKDB_TEMP_DIRECTORY = "/tmp/af_duckdb_tmp";
    /** 引擎线程数固定为 2：查询受语句超时与内存上限约束，线程不是产品档位维度。 */
    static final int DUCKDB_THREADS = 2;

    /**
     * 产品档位的语句超时与返回行数上限（方案固定值，模型不能自定义）。
     * 估计行数上限不在此列：它与容量账本阈值同源，渲染规格时从
     * {@link DataAnalysisCapacityProperties} 现取（标准档上限 / 单任务硬上限）。
     */
    record ProductTier(int statementTimeoutSeconds, int rowCap) {}

    static final Map<String, ProductTier> PRODUCT_TIERS = Map.of(
            "INTERACTIVE", new ProductTier(30, 100),
            "BACKGROUND", new ProductTier(120, 1000));
    /**
     * 语句超时到进程超时的固定缓冲，也是档位进程上限相对语句上限的差值。
     * INTERACTIVE 30+15=45，BACKGROUND 120+15=135；有效进程超时公式里的加数用同一个常量。
     */
    static final int TASK_OVERHEAD_SECONDS = 15;
    /**
     * 夹语句超时用的收尾余量：有效语句超时 = min(档位上限, 剩余秒 − 该值)。
     * 进程超时上限取整个剩余时间，不含这一项。与进程缓冲不是同一个数。
     */
    static final int WALL_CLOCK_TAIL_SECONDS = 5;

    private static final int POLL_INTERVAL_MS = 1000;
    /** lease 剩余一半时续租，避免每次 fast-path poll 都写 PostgreSQL。 */
    private static final long DAG_LEASE_RENEW_AHEAD_MILLIS =
            DagBlockingWorkerLease.LEASE_DURATION.toMillis() / 2L;
    /** schema 2 起保证 estimate 与 reservation 同源（与 executePython 的锚点版本一致）。 */
    private static final int ANCHOR_SCHEMA_VERSION = 2;
    /** 同一段里重试换的是工具调用身份，外部作业轮次恒从 1 开始（与 executePython 同一约定）。 */
    private static final int DATA_ANALYSIS_ATTEMPT = 1;

    /**
     * 写给模型的完整说明只维护在 classpath 权威文件里。
     * {@code @Tool} 不再放正文：LangChain4j 反射只要方法签名，目录构建时再覆盖成权威正文。
     */
    public static String loadToolDescription() {
        return ToolDescriptionTexts.require("executeQuery");
    }

    @DubboReference
    private PythonSandboxService pythonSandboxService;

    private final ObjectMapper objectMapper;
    private final DatasetEntryMetadataReader metadataReader;

    /** 订阅数据集持久化事件，并在单次 agent 运行内维护「局部编号 → 条目」映射。 */
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
    private AgentRunBudgetService agentRunBudgetService;

    @Autowired(required = false)
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

    @Autowired(required = false)
    private ToolJobFaultInjector toolJobFaultInjector;

    @Autowired(required = false)
    private DataAnalysisTerminalRecorder dataAnalysisTerminalRecorder;

    @Autowired(required = false)
    private FinanceRecordChannelConfigLoader financeRecordChannelConfigLoader;

    @Value("${agent.tool-job.fast-path-ms:1500}")
    private long fastPathMs = 1500L;

    @Value("${sandbox.runtime-environment-version:python-sandbox-v1}")
    private String runtimeEnvironmentVersion = "python-sandbox-v1";

    /** 运行器脚本原文，首次使用时从 classpath 读入并缓存；读不到直接类初始化失败。 */
    private static final String RUNNER_SOURCE = loadRunnerSource();

    private volatile SandboxJobObservability observability;
    private volatile SandboxJobAdapters sandboxJobAdapters;

    public SqlQueryTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.metadataReader = new DatasetEntryMetadataReader(objectMapper);
    }

    /**
     * LangChain4j 暴露给模型的入口：SQL 文本、run 级数据集编号、产品档位。
     * 方法名即工具名；说明正文在目录构建时覆盖成权威文件。
     */
    @Tool
    public String executeQuery(String sql, String dataset_ids, String product_tier) {
        return executeQueryInternal(sql, dataset_ids, product_tier);
    }

    private String executeQueryInternal(String sql, String datasetIds, String productTier) {
        long toolStartMs = System.currentTimeMillis();
        try {
            // --- 档位解析： blank 默认 INTERACTIVE；未知档位直接拒，带上合法名单。 ---
            String tier = productTier == null || productTier.isBlank()
                    ? "INTERACTIVE"
                    : productTier.trim().toUpperCase(Locale.ROOT);
            ProductTier tierLimits = PRODUCT_TIERS.get(tier);
            if (tierLimits == null) {
                return fail("UNKNOWN_TIER", "Unknown product_tier; use INTERACTIVE or BACKGROUND",
                        Map.of("legal_tiers", List.of("INTERACTIVE", "BACKGROUND"), "retryable", true));
            }
            if (sql == null || sql.isBlank()) {
                return fail("MISSING_SQL", "sql is required", Map.of("retryable", true));
            }

            // --- 入参编号解析：dataset_ids 是当前 Run 内的局部整数编号。 ---
            String[] parsedDatasetNumbers = RunLevelIdResolver.parseIds(datasetIds);
            if (parsedDatasetNumbers.length == 0) {
                return fail("MISSING_IDS", "dataset_ids is empty; at least one is required",
                        Map.of("retryable", true));
            }
            String runId = AgentContext.getRunId();
            AgentRunDatasetRegistry registry = this.agentRunDatasetRegistry;
            if (runId == null || runId.isBlank() || registry == null) {
                return fail("RUN_LEVEL_IDS_UNAVAILABLE",
                        "Run-level dataset ids require an active run and AgentRunDatasetRegistry",
                        Map.of("run_id", runId == null ? "" : runId));
            }
            List<AgentRunDatasetEntry> resolvedDatasets = new ArrayList<>();
            List<Map<String, Object>> illegalRefs = new ArrayList<>();
            RunLevelIdResolver.resolveRunLevelNumbers(parsedDatasetNumbers, registry, runId, "dataset",
                    resolvedDatasets, illegalRefs, false);
            if (!illegalRefs.isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("illegal_dataset_refs", illegalRefs);
                details.put("legal_dataset_numbers", registry.listDatasetNumbers(runId));
                details.put("retryable", true);
                return fail("ILLEGAL_RUN_LEVEL_IDS",
                        "Some dataset_ids are not valid run-level numbers; pass values from the legal list below",
                        details);
            }

            WaitGroupMemberExecutionContext.Snapshot waitGroupMember =
                    WaitGroupMemberExecutionContext.current();
            if (waitGroupMember != null) {
                return submitForWaitGroup(waitGroupMember, sql, tier, tierLimits,
                        resolvedDatasets, toolStartMs);
            }

            // 等待策略必须来自 executor 已冻结的 effective workflow；未知值不能猜成 LINEAR。
            Optional<SandboxJobWaitPolicy> resolvedWaitPolicy =
                    SandboxJobWaitPolicy.fromWorkflow(AgentContext.getWorkflow());
            if (resolvedWaitPolicy.isEmpty()) {
                return fail("WORKFLOW_MODE_UNAVAILABLE",
                        "executeQuery requires an effective workflow of linear or dag",
                        Map.of("workflow", AgentContext.getWorkflow() == null
                                ? "" : AgentContext.getWorkflow()));
            }
            SandboxJobWaitPolicy waitPolicy = resolvedWaitPolicy.get();

            // toolCallId 来自当前 Todo 的 AgentContext，是跨 worker 恢复的稳定逻辑调用身份。
            String toolCallId = AgentContext.getToolCallId();
            if (toolCallId == null || toolCallId.isBlank()) {
                return fail("TOOL_JOB_IDENTITY_UNAVAILABLE",
                        "executeQuery requires a stable tool call id", Map.of("run_id", runId));
            }
            DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                    runId, toolCallId, DATA_ANALYSIS_ATTEMPT);

            // 本工具没有不带容量管理的老创建路径：接线不完整时直接在调用网关之前失败。
            SandboxToolJobLifecycle.LifecycleDeps deps = lifecycleDeps();
            if (!deps.wiringAvailable() || dataAnalysisCapacityProperties == null) {
                log.error("sandbox.create.wiringIncomplete: executeQuery refuses create; "
                        + "capacity/dispatch/recorder wiring is incomplete");
                observability().emitToolTotal(toolStartMs, "ERROR", "SANDBOX_CAPACITY_WIRING_INCOMPLETE");
                return fail("SANDBOX_CAPACITY_WIRING_INCOMPLETE",
                        "Query sandbox wiring incomplete; refuses create without capacity reservation and operationId",
                        Map.of("retryable", false));
            }

            // --- 先分类后渲染：引擎内存上限取决于容量档位，渲染必须拿到冻结结果；
            // 而 canonical 规格的 codeHash 绑渲染后的脚本，所以规格在渲染之后才组装。 ---
            AgentRunDatasetSnapshot subSnapshot = new AgentRunDatasetSnapshot(resolvedDatasets, List.of());
            String pathsDatasetCsv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(subSnapshot);
            String pathManifestCsv = AgentRunDatasetCsvWriter.writePathManifestCsv(subSnapshot);
            long remainingWallClockMs;
            try {
                if (agentRunBudgetService == null) {
                    throw new RunStartedAtMissingException(runId);
                }
                remainingWallClockMs = agentRunBudgetService.remainingWallClockMs();
            } catch (RunStartedAtMissingException missingStartedAt) {
                return fail("RUN_STARTED_AT_MISSING", missingStartedAt.getMessage(),
                        Map.of("run_id", runId, "retryable", false));
            }
            QueryTimeouts timeouts;
            try {
                timeouts = clampQueryTimeouts(tierLimits.statementTimeoutSeconds(), remainingWallClockMs);
            } catch (DataIntenseRefusal wallClockRefusal) {
                return fail(wallClockRefusal.code(), wallClockRefusal.getMessage(), wallClockRefusal.details());
            }
            int statementTimeoutSeconds = timeouts.statementTimeoutSeconds();
            int taskTimeoutSeconds = timeouts.taskTimeoutSeconds();
            CapacityPlan plan;
            try {
                plan = planCapacity(tier, resolvedDatasets);
            } catch (DataIntenseRefusal refusal) {
                return fail(refusal.code(), refusal.getMessage(), refusal.details());
            }
            DataAnalysisEstimate estimate = plan.estimate();
            DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision decision = plan.decision();

            // --- 渲染固定运行器：模型 SQL 与挂载规格经 base64 内嵌，无引号转义问题。 ---
            String runnerCode;
            try {
                long estimatedRowCap = "BACKGROUND".equals(tier)
                        ? dataAnalysisCapacityProperties.getMaxRowsPerTask()
                        : dataAnalysisCapacityProperties.getStandardRowsMax();
                runnerCode = renderRunner(sql, tier,
                        new ProductTier(statementTimeoutSeconds, tierLimits.rowCap()),
                        resolvedDatasets,
                        decision.memoryLimitBytes(), estimatedRowCap);
            } catch (IllegalStateException renderFailure) {
                return fail("RUNNER_RENDER_FAILED", renderFailure.getMessage(), Map.of("retryable", false));
            }
            CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(
                    CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                    identity.operationId(),
                    sha256(runnerCode),
                    subSnapshot.immutableDigest(),
                    decision.resourceClass(),
                    decision.memoryLimitBytes(),
                    taskTimeoutSeconds * 1000L,
                    runtimeEnvironmentVersion,
                    sha256(""),
                    sha256(""));

            // reservation 拿到手之后，任何退出路径都必须释放它或过户给后台任务。
            DataAnalysisReservation reservation;
            try {
                reservation = dataAnalysisCapacityService.reserve(identity, estimate);
            } catch (CapacityAdmissionException admission) {
                String code = admission.reason() == CapacityAdmissionException.Reason.TASK_TOO_LARGE
                        ? "DATA_ANALYSIS_TASK_TOO_LARGE"
                        : "DATA_ANALYSIS_SERVER_BUSY";
                return fail(code, admission.getMessage(), Map.of("retryable",
                        admission.reason() != CapacityAdmissionException.Reason.TASK_TOO_LARGE));
            }

            ExecuteRequest baseRequest = buildBaseRequest(
                    runnerCode, resolvedDatasets, taskTimeoutSeconds, pathsDatasetCsv, pathManifestCsv);
            ExecuteRequest request = requestAdapter().enrichWithCapacity(baseRequest, reservation, estimate, spec);

            String canonicalSpecJson = objectMapper.writeValueAsString(spec);
            String createRequestJson = JsonFormat.printer()
                    .omittingInsignificantWhitespace().print(request);
            // executeQuery 没有修复计数；金融记录通道快照必须写：沙箱每个任务都会带通道元数据，
            // 终态对账缺这份冻结配置会永远停在 finance_snapshot_missing。
            SandboxToolJobLifecycle.PrepareDispatchResult dispatch;
            try {
                dispatch = SandboxToolJobLifecycle.prepareDispatch(
                    pythonSandboxDispatchStore,
                    new SandboxToolJobLifecycle.PrepareDispatchRequest(
                            runId,
                            ToolJobAnchor.EXECUTE_QUERY_TOOL,
                            toolCallId,
                            DATA_ANALYSIS_ATTEMPT,
                            ANCHOR_SCHEMA_VERSION,
                            identity.operationId(),
                            spec.requestFingerprint(),
                            canonicalSpecJson,
                            createRequestJson,
                            waitPolicy.runDisposition(),
                            waitPolicy.autoResume(),
                            waitPolicy.durableSuspend(),
                            objectMapper.writeValueAsString(reservation),
                            objectMapper.writeValueAsString(estimate),
                            objectMapper.writeValueAsString(subSnapshot),
                            subSnapshot.immutableDigest(),
                            spec.timeoutMillis(),
                            POLL_INTERVAL_MS),
                    this::writeQueryAnchorExtras);
            } catch (SessionQueryAdmissionException sessionBusy) {
                SandboxToolJobLifecycle.releasePreDispatch(deps, reservation);
                return fail(sessionBusy.code(), sessionBusy.getMessage(),
                        Map.of("retryable", sessionBusy.retryable()));
            }
            if (!dispatch.persisted()) {
                // 未取得 anchor owner 时释放尚未转交的容量；同一 Run 内立刻重试必然再次失败。
                SandboxToolJobLifecycle.releasePreDispatch(deps, reservation);
                return fail("TOOL_JOB_ANCHOR_INVALID",
                        "Failed to persist PREPARING tool-job anchor",
                        Map.of("operation_id", identity.operationId(), "retryable", false));
            }
            ToolJobAnchor anchor = dispatch.anchor();
            hitFaultPoint(runId, ToolJobFaultInjector.BEFORE_SANDBOX_SUBMIT);

            /*
             * 从 PREPARING 抢占成功开始，DAG 线程的任何异常退场都必须先移交负责者。
             * 创建裁决、墓碑、ATTACHED 持久化与两种等待策略的轮询全部在框架里，
             * executeQuery 只提供适配器；没有 finance 终态副作用，钩子传 null。
             */
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
                                runnerAdapter(), resultAdapter(), meteringAdapter(), null));
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
                                    "task_id", anchor.getTaskId() == null ? "" : anchor.getTaskId(),
                                    "message", lifecycleFailure.getMessage() == null
                                            ? "" : lifecycleFailure.getMessage()));
                }
                throw lifecycleFailure;
            }
        } catch (ToolJobInjectedInterruption interruption) {
            // 验收故障点表示当前 worker 必须立即退场，原样上抛。
            throw interruption;
        } catch (WaitGroupMemberPendingException pending) {
            // 等待组成员：后台任务、名额与派发证明均已落库，原样上抛由派发器写进成员行。
            throw pending;
        } catch (ExternalToolJobPendingException pending) {
            // 挂起信号：后台任务、名额与 Run 状态均已落库，原样上抛由上层释放线程。
            throw pending;
        } catch (Exception e) {
            log.error("Execute query tool error", e);
            observability().emitToolTotal(toolStartMs, "ERROR", "TOOL_ERROR");
            return fail("TOOL_ERROR", "Query sandbox invocation error",
                    Map.of("message", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    /**
     * 等待组成员：把这次查询建成沙箱后台任务，然后立刻把线程交出去。
     *
     * <p>名额预留之后的编排已下沉到 {@link SandboxToolJobLifecycle#dispatchWaitGroupMember}。
     * 这里只做查询工具自有的接线检查、成员身份比对、容量事实冻结，以及会话串行：
     * 先占 PREPARING 名额，再把真实预约 JSON 写进 Run 级锚点（同一事务按 userId 计数），
     * 然后把已占名额交给成员派发，禁止再写空对象 "{}"。成员路径不读 DAG 阻塞轮询。</p>
     */
    private String submitForWaitGroup(WaitGroupMemberExecutionContext.Snapshot member,
                                      String sql,
                                      String tier,
                                      ProductTier tierLimits,
                                      List<AgentRunDatasetEntry> datasets,
                                      long toolStartMs) throws Exception {
        SandboxToolJobLifecycle.LifecycleDeps deps = lifecycleDeps();
        if (!deps.wiringAvailable() || dataAnalysisCapacityProperties == null) {
            log.error("waitGroup.withoutCapacity: executeQuery 成员调用缺少容量接线，拒绝建任务 member={}",
                    member.describe());
            observability().emitToolTotal(toolStartMs, "ERROR", "SANDBOX_CAPACITY_WIRING_INCOMPLETE");
            return fail("SANDBOX_CAPACITY_WIRING_INCOMPLETE",
                    "Query sandbox wiring incomplete; a wait-group member "
                            + "requires capacity reservation and a durable dispatch proof",
                    Map.of("retryable", false));
        }
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(
                member.runId(), member.durableToolCallId(), DATA_ANALYSIS_ATTEMPT);
        if (!identity.operationId().equals(member.expectedOperationId())) {
            observability().emitToolTotal(toolStartMs, "ERROR", "WAIT_GROUP_OPERATION_IDENTITY_MISMATCH");
            return fail("WAIT_GROUP_OPERATION_IDENTITY_MISMATCH",
                    "The member's external operation identity does not match the persisted one",
                    Map.of("expected_operation_id", member.expectedOperationId(),
                            "derived_operation_id", identity.operationId(),
                            "retryable", false));
        }
        AgentRunDatasetSnapshot subSnapshot = new AgentRunDatasetSnapshot(datasets, List.of());
        String pathsDatasetCsv = AgentRunDatasetCsvWriter.writePathsDatasetCsv(subSnapshot);
        String pathManifestCsv = AgentRunDatasetCsvWriter.writePathManifestCsv(subSnapshot);
        long remainingWallClockMs;
        try {
            if (agentRunBudgetService == null) {
                throw new RunStartedAtMissingException(member.runId());
            }
            remainingWallClockMs = agentRunBudgetService.remainingWallClockMs();
        } catch (RunStartedAtMissingException missingStartedAt) {
            return fail("RUN_STARTED_AT_MISSING", missingStartedAt.getMessage(),
                    Map.of("run_id", member.runId(), "retryable", false));
        }
        QueryTimeouts timeouts;
        try {
            timeouts = clampQueryTimeouts(tierLimits.statementTimeoutSeconds(), remainingWallClockMs);
        } catch (DataIntenseRefusal wallClockRefusal) {
            return fail(wallClockRefusal.code(), wallClockRefusal.getMessage(), wallClockRefusal.details());
        }
        CapacityPlan plan;
        try {
            plan = planCapacity(tier, datasets);
        } catch (DataIntenseRefusal refusal) {
            return fail(refusal.code(), refusal.getMessage(), refusal.details());
        }
        String runnerCode;
        try {
            long estimatedRowCap = "BACKGROUND".equals(tier)
                    ? dataAnalysisCapacityProperties.getMaxRowsPerTask()
                    : dataAnalysisCapacityProperties.getStandardRowsMax();
            runnerCode = renderRunner(sql, tier,
                    new ProductTier(timeouts.statementTimeoutSeconds(), tierLimits.rowCap()),
                    datasets, plan.decision().memoryLimitBytes(), estimatedRowCap);
        } catch (IllegalStateException renderFailure) {
            return fail("RUNNER_RENDER_FAILED", renderFailure.getMessage(), Map.of("retryable", false));
        }
        CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(
                CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                identity.operationId(),
                sha256(runnerCode),
                subSnapshot.immutableDigest(),
                plan.decision().resourceClass(),
                plan.decision().memoryLimitBytes(),
                timeouts.taskTimeoutSeconds() * 1000L,
                runtimeEnvironmentVersion,
                sha256(""),
                sha256(""));
        ExecuteRequest baseRequest = buildBaseRequest(
                runnerCode, datasets, timeouts.taskTimeoutSeconds(), pathsDatasetCsv, pathManifestCsv);
        SandboxJobWaitPolicy sessionClaimPolicy = SandboxJobWaitPolicy.DURABLE_SUSPEND;
        String createRequestJson = JsonFormat.printer()
                .omittingInsignificantWhitespace().print(baseRequest);
        DataAnalysisReservation reservation;
        try {
            reservation = dataAnalysisCapacityService.reserve(identity, plan.estimate());
        } catch (CapacityAdmissionException admission) {
            String code = admission.reason() == CapacityAdmissionException.Reason.TASK_TOO_LARGE
                    ? "DATA_ANALYSIS_TASK_TOO_LARGE"
                    : "DATA_ANALYSIS_SERVER_BUSY";
            return fail(code, admission.getMessage(), Map.of("retryable",
                    admission.reason() != CapacityAdmissionException.Reason.TASK_TOO_LARGE));
        }
        boolean sessionClaimed = false;
        SandboxToolJobLifecycle.PrepareDispatchResult dispatch = null;
        try {
            dispatch = SandboxToolJobLifecycle.prepareDispatch(
                            pythonSandboxDispatchStore,
                            new SandboxToolJobLifecycle.PrepareDispatchRequest(
                                    member.runId(),
                                    ToolJobAnchor.EXECUTE_QUERY_TOOL,
                                    member.durableToolCallId(),
                                    DATA_ANALYSIS_ATTEMPT,
                                    ANCHOR_SCHEMA_VERSION,
                                    identity.operationId(),
                                    spec.requestFingerprint(),
                                    objectMapper.writeValueAsString(spec),
                                    createRequestJson,
                                    sessionClaimPolicy.runDisposition(),
                                    sessionClaimPolicy.autoResume(),
                                    sessionClaimPolicy.durableSuspend(),
                                    objectMapper.writeValueAsString(reservation),
                                    objectMapper.writeValueAsString(plan.estimate()),
                                    objectMapper.writeValueAsString(subSnapshot),
                                    subSnapshot.immutableDigest(),
                                    spec.timeoutMillis(),
                                    POLL_INTERVAL_MS),
                            this::writeQueryAnchorExtras);
            if (!dispatch.persisted()) {
                SandboxToolJobLifecycle.releasePreDispatch(deps, reservation);
                return fail("TOOL_JOB_ANCHOR_INVALID",
                        "Failed to persist PREPARING tool-job anchor",
                        Map.of("operation_id", identity.operationId(), "retryable", false));
            }
            sessionClaimed = true;
            String dispatched = SandboxToolJobLifecycle.dispatchWaitGroupMember(
                    deps,
                    new SandboxToolJobLifecycle.WaitGroupDispatchRequest<>(
                            member, identity, spec, plan.estimate(), baseRequest,
                            requestAdapter(), runnerAdapter(),
                            ToolJobAnchor.EXECUTE_QUERY_TOOL, toolStartMs, reservation));
            pythonSandboxDispatchStore.clearActive(member.runId(), identity.operationId());
            return dispatched;
        } catch (SessionQueryAdmissionException sessionBusy) {
            SandboxToolJobLifecycle.releasePreDispatch(deps, reservation);
            if (sessionClaimed) {
                pythonSandboxDispatchStore.clearActive(member.runId(), identity.operationId());
            }
            return fail(sessionBusy.code(), sessionBusy.getMessage(),
                    Map.of("retryable", sessionBusy.retryable()));
        } catch (WaitGroupMemberPendingException pending) {
            attachWaitGroupSessionAnchor(member.runId(), dispatch == null ? null : dispatch.anchor(), pending);
            throw pending;
        } catch (RuntimeException dispatchFailure) {
            if (sessionClaimed) {
                pythonSandboxDispatchStore.clearActive(member.runId(), identity.operationId());
            }
            throw dispatchFailure;
        }
    }

    /**
     * 把派发时的金融记录通道冻结快照写进锚点。沙箱有界包装器对每个任务都产出通道元数据，
     * 终态对账必须能还原这份快照；缺了会永远卡在 finance_snapshot_missing。
     */
    void writeQueryAnchorExtras(ToolJobAnchor extras) {
        if (extras == null || financeRecordChannelConfigLoader == null) {
            return;
        }
        extras.setFinanceRecordLimitsJson(financeRecordChannelConfigLoader.frozenSnapshotJson());
    }

    /**
     * 等待组成员建好沙箱任务后，把 Run 级会话锚点从 PREPARING 推进到 ATTACHED，
     * 写入与成员派发证明同一份 TASK_ATTACHED 预约。启动恢复才解析得出真实名额，
     * 不会把空对象 "{}" 当成预约、也不会把已建任务再当「创建中途崩溃」重放。
     * 任务编号尚未证实时保持 PREPARING，真实预约 JSON 已经在抢占锚点时写过。
     */
    private void attachWaitGroupSessionAnchor(String runId,
                                              ToolJobAnchor preparing,
                                              WaitGroupMemberPendingException pending) {
        if (pythonSandboxDispatchStore == null || preparing == null || pending == null) {
            return;
        }
        String taskId = pending.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        preparing.setTaskId(taskId);
        preparing.setAnchorState("ATTACHED");
        preparing.setReservationJson(pending.getProof().reservationJson());
        try {
            if (!pythonSandboxDispatchStore.persistAttached(runId, preparing)) {
                log.warn("waitGroup.sessionAnchorAttachFailed: executeQuery 成员已派发，但 Run 级锚点未能推进到 ATTACHED runId={} operationId={} taskId={}",
                        runId, pending.getOperationId(), taskId);
            }
        } catch (RuntimeException attachFailure) {
            log.warn("waitGroup.sessionAnchorAttachFailed: executeQuery 成员已派发，推进 ATTACHED 时出错 runId={} operationId={} taskId={}",
                    runId, pending.getOperationId(), taskId, attachFailure);
        }
    }

    /**
     * 按 Run 剩余墙钟夹语句超时与进程超时。档位进程上限与公式里「有效语句超时 + N 秒」
     * 共用 {@link #TASK_OVERHEAD_SECONDS}。
     */
    static QueryTimeouts clampQueryTimeouts(int statementCapSeconds, long remainingWallClockMs) {
        int processCapSeconds = statementCapSeconds + TASK_OVERHEAD_SECONDS;
        if (remainingWallClockMs == Long.MAX_VALUE) {
            return new QueryTimeouts(statementCapSeconds, processCapSeconds);
        }
        long remainingSeconds = remainingWallClockMs / 1000L;
        if (remainingSeconds <= WALL_CLOCK_TAIL_SECONDS) {
            throw new DataIntenseRefusal("RUN_WALL_CLOCK_INSUFFICIENT",
                    "remaining run wall clock is not enough to start executeQuery",
                    Map.of("remaining_ms", remainingWallClockMs,
                            "tail_seconds", WALL_CLOCK_TAIL_SECONDS,
                            "retryable", false));
        }
        // remainingSeconds > WALL_CLOCK_TAIL_SECONDS，差至少为 1；档位语句上限恒 > 0。
        int effectiveStatement = (int) Math.min(statementCapSeconds, remainingSeconds - WALL_CLOCK_TAIL_SECONDS);
        int effectiveProcess = (int) Math.min(processCapSeconds,
                Math.min((long) effectiveStatement + TASK_OVERHEAD_SECONDS, remainingSeconds));
        return new QueryTimeouts(effectiveStatement, effectiveProcess);
    }

    record QueryTimeouts(int statementTimeoutSeconds, int taskTimeoutSeconds) {}

    /**
     * 冻结这次调用的容量事实：数据集预估值与资源档位。
     *
     * <p>BACKGROUND 档按设计一律申请 HEAVY：给分类器传一条「产品档位即重操作」的
     * 不可变提示列表，分类与 estimate 使用同一份列表，杜绝两段各自推断漂移。
     * INTERACTIVE 档不传提示，由输入行数/字节数按阈值分类。canonical 规格要等
     * 运行器渲染完（codeHash 绑渲染后脚本）才组装，不在这里。</p>
     */
    private CapacityPlan planCapacity(String tier, List<AgentRunDatasetEntry> datasets) {
        DataAnalysisEstimate estimate;
        DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision decision;
        try {
            long rows = 0L;
            long bytes = 0L;
            for (AgentRunDatasetEntry dataset : datasets) {
                // 元数据不完整时直接拒绝，避免低估资源占用后把超出承载能力的任务放进沙箱。
                DatasetEntryMetadataReader.EntryMetadata metadata = metadataReader.read(dataset);
                if (metadata.rowCount() == null || metadata.bytes() == null) {
                    throw new DataIntenseRefusal("DATA_ANALYSIS_ESTIMATE_UNAVAILABLE",
                            "Dataset row/byte metadata is required before Sandbox admission",
                            Map.of("dataset_id", dataset.originalId(),
                                    "metadata_status", metadata.metadataStatus(),
                                    "retryable", false));
                }
                rows = Math.addExact(rows, metadata.rowCount());
                bytes = Math.addExact(bytes, metadata.bytes());
            }
            List<String> heavyOperationHints = "BACKGROUND".equals(tier)
                    ? List.of("PRODUCT_TIER_BACKGROUND")
                    : List.of();
            // 资源档位只在这里冻结一次；后续名额预留、沙箱请求和终态证明都复用它。
            decision = dataAnalysisCapacityProperties.classify(rows, bytes, heavyOperationHints);
            if (decision.outcome()
                    == DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision.Outcome.REJECTED) {
                throw new DataIntenseRefusal("DATA_ANALYSIS_TASK_TOO_LARGE",
                        "Dataset estimate exceeds Sandbox hard limits",
                        Map.of("estimated_rows", rows, "estimated_bytes", bytes, "retryable", false));
            }
            estimate = new DataAnalysisEstimate(
                    rows, bytes, datasets.size(), 1.0d, 0, heavyOperationHints,
                    decision.resourceClass(), decision.capacityUnits());
        } catch (ArithmeticException overflow) {
            throw new DataIntenseRefusal("DATA_ANALYSIS_TASK_TOO_LARGE",
                    "Dataset estimate overflowed admission counters", Map.of("retryable", false));
        }
        return new CapacityPlan(estimate, decision);
    }

    private record CapacityPlan(DataAnalysisEstimate estimate,
                                DataAnalysisCapacityProperties.DataAnalysisResourceClassDecision decision) {
    }

    /** 「冻结事实这一步就被拒绝」时抛的内部异常，与 executePython 的同名机制同语义。 */
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
     * 渲染固定运行器：把规格 JSON（SQL、档位、挂载清单、引擎上限）base64 后替换占位符。
     * 数据集在沙箱内的路径 = 兼容符号链接 /sandbox/input 下的 _run_dataset_<编号>/<文件名>，
     * 视图名是 run 级编号的确定性映射（t1、t2……），模型在工具说明里按此写 SQL。
     */
    String renderRunner(String sql, String tier, ProductTier tierLimits,
                        List<AgentRunDatasetEntry> datasets, long memoryLimitBytes,
                        long estimatedRowCap) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("sql", sql);
        spec.put("tier", tier);
        spec.put("mount_dir", SANDBOX_INPUT_ROOT);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("temp_directory", DUCKDB_TEMP_DIRECTORY);
        // 内存上限用容量分类的冻结值，换算成 DuckDB 配置串（配置均为整 MiB，整除无损）。
        limits.put("memory_limit", (memoryLimitBytes / (1024 * 1024)) + "MB");
        limits.put("threads", DUCKDB_THREADS);
        // 语句超时、返回行数上限、估计行数上限全部由这里下发，运行器不持有副本。
        limits.put("statement_timeout_s", tierLimits.statementTimeoutSeconds());
        limits.put("row_cap", tierLimits.rowCap());
        limits.put("estimated_row_cap", estimatedRowCap);
        spec.put("limits", limits);
        List<Map<String, Object>> mounts = new ArrayList<>();
        for (AgentRunDatasetEntry dataset : datasets) {
            Map<String, Object> mount = new LinkedHashMap<>();
            mount.put("alias", "t" + dataset.number());
            mount.put("path", SANDBOX_INPUT_ROOT + "/_run_dataset_" + dataset.number() + "/" + dataset.sortKey());
            mount.put("format", dataset.sortKey() != null
                    && dataset.sortKey().toLowerCase(Locale.ROOT).endsWith(".parquet") ? "parquet" : "csv");
            mounts.add(mount);
        }
        spec.put("datasets", mounts);
        String specJson;
        try {
            specJson = objectMapper.writeValueAsString(spec);
        } catch (Exception serializeFailure) {
            throw new IllegalStateException("query runner spec could not be serialized");
        }
        String encoded = Base64.getEncoder().encodeToString(specJson.getBytes(StandardCharsets.UTF_8));
        String rendered = RUNNER_SOURCE.replace(SPEC_PLACEHOLDER, encoded);
        if (rendered.equals(RUNNER_SOURCE)) {
            throw new IllegalStateException("query runner placeholder missing from runner source");
        }
        return rendered;
    }

    /** 组装发往沙箱的创建请求：数据集复数列表 + 路径映射 CSV（沙箱据此落盘输入文件）。 */
    private ExecuteRequest buildBaseRequest(String runnerCode,
                                            List<AgentRunDatasetEntry> datasets,
                                            int taskTimeoutSeconds,
                                            String pathsDatasetCsv,
                                            String pathManifestCsv) {
        ExecuteRequest.Builder builder = ExecuteRequest.newBuilder()
                .setCode(runnerCode)
                .setDatasetId(datasets.get(0).originalId())
                .setTimeoutSeconds(taskTimeoutSeconds)
                .setPathsDatasetCsv(pathsDatasetCsv)
                .setPathManifestCsv(pathManifestCsv);
        for (AgentRunDatasetEntry dataset : datasets) {
            builder.addDatasetIds(dataset.originalId());
        }
        return builder.build();
    }

    // ==================== 适配器装配（生命周期框架的四个出口） ====================

    private SandboxJobObservability observability() {
        SandboxJobObservability local = observability;
        if (local == null) {
            synchronized (this) {
                local = observability;
                if (local == null) {
                    local = new SandboxJobObservability(debugObservabilityService);
                    observability = local;
                }
            }
        }
        return local;
    }

    private SandboxJobAdapters sandboxJobAdapters() {
        SandboxJobAdapters local = sandboxJobAdapters;
        if (local == null) {
            synchronized (this) {
                local = sandboxJobAdapters;
                if (local == null) {
                    // 运行器与计量适配器与 executePython 共用同一实现类：
                    // 同一沙箱 RPC 通道、同一容量用量模型，两处工具语义都不需要分叉。
                    local = new SandboxJobAdapters(
                            new SqlQueryJobRequestAdapter(),
                            new PythonSandboxJobRunnerAdapter(pythonSandboxService, observability()),
                            new SqlQueryJobResultAdapter(objectMapper),
                            new PythonSandboxJobMeteringAdapter(objectMapper));
                    sandboxJobAdapters = local;
                }
            }
        }
        return local;
    }

    /** 启动时把四个适配器登记进注册表；Spring 多上下文下等价登记保留先来者。 */
    @PostConstruct
    void registerSandboxJobAdapters() {
        SandboxJobAdapterRegistry.registerEquivalent(sandboxJobAdapters());
    }

    private SqlQueryJobRequestAdapter requestAdapter() {
        return (SqlQueryJobRequestAdapter) sandboxJobAdapters().request();
    }

    private PythonSandboxJobRunnerAdapter runnerAdapter() {
        return (PythonSandboxJobRunnerAdapter) sandboxJobAdapters().runner();
    }

    private SqlQueryJobResultAdapter resultAdapter() {
        return (SqlQueryJobResultAdapter) sandboxJobAdapters().result();
    }

    private PythonSandboxJobMeteringAdapter meteringAdapter() {
        return (PythonSandboxJobMeteringAdapter) sandboxJobAdapters().metering();
    }

    private SandboxToolJobLifecycle.LifecycleDeps lifecycleDeps() {
        return new SandboxToolJobLifecycle.LifecycleDeps(
                pythonSandboxDispatchStore, dataAnalysisCapacityService, dataAnalysisTerminalRecorder,
                waitGroupStore, toolJobFaultInjector, observability(), objectMapper,
                POLL_INTERVAL_MS, DAG_LEASE_RENEW_AHEAD_MILLIS, fastPathMs);
    }

    private void hitFaultPoint(String runId, String checkpoint) {
        if (toolJobFaultInjector != null) {
            toolJobFaultInjector.hit(runId, checkpoint);
        }
    }

    private String fail(String code, String message, Map<String, Object> details) {
        return SandboxJobResponses.fail(objectMapper, "executeQuery", code, message, details);
    }

    /** 从 classpath 读入运行器脚本原文；资源缺失属打包错误，直接让类初始化失败。 */
    private static String loadRunnerSource() {
        try (InputStream in = SqlQueryTools.class.getResourceAsStream(RUNNER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("query runner resource missing: " + RUNNER_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException readFailure) {
            throw new ExceptionInInitializerError(readFailure);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception digestFailure) {
            throw new IllegalStateException("SHA-256 unavailable", digestFailure);
        }
    }
}
