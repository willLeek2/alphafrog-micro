package world.willfrog.agent.platform.dataanalysis;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 外部工具上下文切换的持久化锚点，存放在
 * {@code alphafrog_agent_run.tool_job_anchor_json}。
 *
 * <p>数据库中的本对象是恢复真相源：Redis 只保存到期索引和热副本，丢失后可以
 * 从这里重建。对象同时冻结任务身份、挂起点、已完成 Todo、dataset 快照、
 * Sandbox 容量 reservation、终态结果、finalizer 进度和恢复租约。</p>
 *
 * <p>所有写入都必须带 operation/toolCall/attempt/checkpointVersion 等 CAS 条件，
 * 这样旧 worker、重复回调和进程重启后的扫描器不会覆盖新一轮恢复。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolJobAnchor {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // schemaVersion 允许未来升级 JSON 结构时保留兼容读取路径。
    private int schemaVersion = 1;
    // checkpointVersion 每次原子合并后递增，是防止丢失更新的版本栅栏。
    private int checkpointVersion;
    // operationId 标识一次可幂等创建的外部操作，不随进程重启变化。
    private String operationId;
    // requestFingerprint 绑定本次工具入参，阻止同一 operationId 被不同请求复用。
    private String requestFingerprint;
    // canonicalCreateSpecJson 保存规范化创建参数，用于故障恢复时验证任务身份。
    private String canonicalCreateSpecJson;
    // createRequestJson 保存向 Sandbox 提交的原始创建请求，必要时可重放创建协议。
    private String createRequestJson;
    // anchorState 描述 PREPARING 到 CONSUMED 的任务生命周期，不等同于 Run 状态。
    private String anchorState;
    // taskId 是 Sandbox 返回的真实后台任务标识，轮询终态时使用。
    private String taskId;
    // toolCallId 是 Agent 侧逻辑调用标识，与 attempt 一起隔离重试轮次。
    private String toolCallId;
    // attempt 标记第几次调用尝试，旧尝试的终态不能恢复新尝试。
    private int attempt;
    // todoId 保存 LINEAR 工作流的挂起节点。
    private String todoId;
    // sequence 保存该节点在原 plan 中的顺序，恢复时用于顺序校验。
    private int sequence;
    // runDisposition 冻结 RUNNING、PAUSED、CANCELED 或 CHECKPOINT_FAILED 等处置。
    private String runDisposition;
    // blockingOwnerId 标识当前同步等待 DAG 工具结果的进程，防止其他实例抢占活 worker。
    private String blockingOwnerId;
    // blockingLeaseUntil 是同步等待 worker 的可续租期限；只有过期租约可被恢复扫描接管。
    private Instant blockingLeaseUntil;
    // cleanupSourceOwnerId 冻结进入 CLEARING 前的 worker owner，供 Redis 崩溃恢复核对旧热副本。
    private String cleanupSourceOwnerId;
    // cleanupSourceLeaseUntil 与 cleanupSourceOwnerId 组成不可变的旧 Redis 身份。
    private Instant cleanupSourceLeaseUntil;
    // autoResume=false 时只做终态收尾和容量释放，不自动重新入队。
    private boolean autoResume = true;
    // resumeState 表示 READY、LAUNCHING、CONSUMED 三阶段交接状态。
    private String resumeState;
    // resumeToken 为每轮 READY 生成随机令牌，提供启动幂等身份。
    private String resumeToken;
    // resumeLeaseVersion 每次 claim 加一，作为跨进程 launcher 的 fencing token。
    private long resumeLeaseVersion;
    // resumeClaimedAt 用来识别 LAUNCHING 卡死并允许启动恢复重新接管。
    private Instant resumeClaimedAt;
    // resumeLauncherOwnerId 是当前恢复 launcher 的进程级身份，不能用 JVM 本地 map 代替。
    private String resumeLauncherOwnerId;
    // resumeLauncherLeaseUntil 是 launcher 必须持续续租的数据库租约；过期后才允许跨实例接管。
    private Instant resumeLauncherLeaseUntil;

    // 以下字段是在工具进入 pending 时冻结的工作流上下文。
    // completedTodosJson 保存完整的已完成 Todo 记录，而不只是 id 列表。
    private String completedTodosJson;
    // datasetRefsJson 保留 Todo 输出中已注册的数据引用，兼容早期 checkpoint。
    private String datasetRefsJson;
    // toolCallsUsed 保存挂起点的 run 级工具调用计数，恢复后继续累计。
    private int toolCallsUsed;

    // reservationJson 是 Sandbox 容量账本快照，终态确认后据此精确释放容量。
    private String reservationJson;
    // estimateJson 保存准入估算，构造终态 envelope 时复用并校验资源类别。
    private String estimateJson;
    // financeRecordLimitsJson 冻结本次 executePython 的 Java 通道开关、上限和目标环境身份。
    private String financeRecordLimitsJson;

    // datasetSnapshotJson 保存可恢复的数据集注册表快照。
    private String datasetSnapshotJson;
    // datasetSnapshotDigest 校验快照内容，阻止损坏或错配快照被恢复。
    private String datasetSnapshotDigest;

    // terminalStatus 是规范化后的 Sandbox 终态，如 SUCCEEDED、FAILED、RESULT_LOST。
    private String terminalStatus;
    // terminalResultPreview 是限定大小的可读摘要，供恢复节点直接注入。
    private String terminalResultPreview;
    // terminalRawRef 指向完整结果产物，避免大对象进入 anchor。
    private String terminalRawRef;
    // terminalStderrPreview 保存可注入修复 prompt 的有界 stderr，完整结果仍由 Sandbox 保管。
    private String terminalStderrPreview;
    // terminalErrorCode 保存失败分类，供工作流决定失败语义。
    private String terminalErrorCode;
    // terminalExitReason 是 Sandbox resourceUsage 的结束分类，用于区分用户代码失败与基础设施故障。
    private String terminalExitReason;
    // terminalUsageJson 保存实际资源用量，finalizer 会幂等落账。
    private String terminalUsageJson;
    // terminalAt 保存 Sandbox 终态发生时间，不使用轮询发现时间替代。
    private Instant terminalAt;
    // nullable 用于区分“明确不可重试”和“旧协议未返回分类”；缺失时 fail-closed。
    private Boolean terminalRetryable;

    // pythonRequestFingerprint 排除 operationId，用于跨 worker 判断模型是否原样重放已失败代码。
    private String pythonRequestFingerprint;
    // pythonRepairAttempt 表示当前 todo 已启动的修复轮次，0 是初次执行。
    private int pythonRepairAttempt;
    // pythonRepairPending 表示终态结果已消费、同一 todo 的修复 LLM 尚未产生下一条 Sandbox anchor。
    private boolean pythonRepairPending;
    // pythonRepairExhausted 保留“已耗尽”终态，防止消费成功后崩溃重入丢失失败语义。
    private boolean pythonRepairExhausted;
    // pythonFailedRequestFingerprints 是 durable 失败历史，阻止新 toolCallId 绕过同参数判重。
    private List<String> pythonFailedRequestFingerprints = Collections.emptyList();

    // resultFetchState 区分等待结果体与已经确认丢失。
    private String resultFetchState;
    // resultFetchAttempts 驱动有界重试，达到阈值后转 RESULT_LOST。
    private int resultFetchAttempts;
    // terminalConfirmedAt 是首次确认任务终态的时间，用于结果保留期限计算。
    private Instant terminalConfirmedAt;
    // sandboxTerminalStatus 保留 Sandbox 原始终态，便于契约审计。
    private String sandboxTerminalStatus;

    // finalizerStep 是可重入收尾状态机最后一个已持久化步骤。
    private String finalizerStep;
    // finalizerError 保存 fail-closed 的阻塞原因，重启扫描后仍可诊断。
    private String finalizerError;

    // usagePersisted 防止资源用量重复落账。
    private boolean usagePersisted;
    // terminalEventEmitted 防止终态事件重复写入事件流。
    private boolean terminalEventEmitted;
    // resultConsumed 表示工作流已接受终态结果，anchor 才可安全清理。
    private boolean resultConsumed;

    // nextPollAt 决定 Redis due 索引中的下一次检查时间。
    private Instant nextPollAt;
    // timeoutAt 是外部任务最大等待期限，超时后进入终态收尾。
    private Instant timeoutAt;

    public ToolJobAnchor() {}

    public static ToolJobAnchor fromJson(String json) {
        try {
            // 统一使用注册 Java Time 模块的 mapper，确保 Instant 可跨重启还原。
            ToolJobAnchor anchor = MAPPER.readValue(json, ToolJobAnchor.class);
            normalizeLegacyResultConsumed(anchor);
            return anchor;
        } catch (JsonProcessingException e) {
            // 锚点损坏必须显式失败；静默构造空对象会绕过 CAS 身份保护。
            throw new IllegalArgumentException("Failed to parse ToolJobAnchor", e);
        }
    }

    /**
     * Normalize legacy dual-track data to the ACCEPTED single-track model.
     *
     * <p>Old data with {@code resultConsumed=true} but {@code resumeState} still
     * LAUNCHING (or null) is upgraded to ACCEPTED. Contradictory READY+true
     * fails closed rather than silently promoting.
     *
     * <p>This migration logic can be removed after 26Q3-W7.
     */
    private static void normalizeLegacyResultConsumed(ToolJobAnchor anchor) {
        if (!anchor.resultConsumed) {
            return; // nothing to normalize
        }
        if ("CONSUMED".equals(anchor.resumeState) || "ACCEPTED".equals(anchor.resumeState)) {
            return; // already consistent
        }
        if ("LAUNCHING".equals(anchor.resumeState) || anchor.resumeState == null) {
            // Legacy: handoff was accepted but resumeState wasn't advanced. Upgrade.
            anchor.resumeState = "ACCEPTED";
            return;
        }
        if ("READY".equals(anchor.resumeState)) {
            // Contradictory: READY cannot have consumed result. Fail closed.
            throw new IllegalArgumentException(
                    "ToolJobAnchor has contradictory state: resumeState=READY but resultConsumed=true");
        }
    }

    public String toJson() {
        try {
            // 每次写库前序列化完整状态，数据库 CAS 决定是否接受这份新快照。
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // 序列化失败时不允许写空 JSON，否则恢复所需上下文会永久丢失。
            throw new IllegalArgumentException("Failed to serialize ToolJobAnchor", e);
        }
    }

    // ---- getters / setters ----

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public int getCheckpointVersion() { return checkpointVersion; }
    public void setCheckpointVersion(int checkpointVersion) { this.checkpointVersion = checkpointVersion; }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) { this.requestFingerprint = requestFingerprint; }

    public String getCanonicalCreateSpecJson() { return canonicalCreateSpecJson; }
    public void setCanonicalCreateSpecJson(String canonicalCreateSpecJson) { this.canonicalCreateSpecJson = canonicalCreateSpecJson; }

    public String getCreateRequestJson() { return createRequestJson; }
    public void setCreateRequestJson(String createRequestJson) { this.createRequestJson = createRequestJson; }

    public String getAnchorState() { return anchorState; }
    public void setAnchorState(String anchorState) { this.anchorState = anchorState; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public String getTodoId() { return todoId; }
    public void setTodoId(String todoId) { this.todoId = todoId; }

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public String getRunDisposition() { return runDisposition; }
    public void setRunDisposition(String runDisposition) { this.runDisposition = runDisposition; }

    public String getBlockingOwnerId() { return blockingOwnerId; }
    public void setBlockingOwnerId(String blockingOwnerId) { this.blockingOwnerId = blockingOwnerId; }

    public Instant getBlockingLeaseUntil() { return blockingLeaseUntil; }
    public void setBlockingLeaseUntil(Instant blockingLeaseUntil) { this.blockingLeaseUntil = blockingLeaseUntil; }

    public String getCleanupSourceOwnerId() { return cleanupSourceOwnerId; }
    public void setCleanupSourceOwnerId(String cleanupSourceOwnerId) { this.cleanupSourceOwnerId = cleanupSourceOwnerId; }

    public Instant getCleanupSourceLeaseUntil() { return cleanupSourceLeaseUntil; }
    public void setCleanupSourceLeaseUntil(Instant cleanupSourceLeaseUntil) { this.cleanupSourceLeaseUntil = cleanupSourceLeaseUntil; }

    public boolean isAutoResume() { return autoResume; }
    public void setAutoResume(boolean autoResume) { this.autoResume = autoResume; }

    public String getResumeState() { return resumeState; }
    public void setResumeState(String resumeState) { this.resumeState = resumeState; }

    public String getResumeToken() { return resumeToken; }
    public void setResumeToken(String resumeToken) { this.resumeToken = resumeToken; }

    public long getResumeLeaseVersion() { return resumeLeaseVersion; }
    public void setResumeLeaseVersion(long resumeLeaseVersion) { this.resumeLeaseVersion = resumeLeaseVersion; }

    public Instant getResumeClaimedAt() { return resumeClaimedAt; }
    public void setResumeClaimedAt(Instant resumeClaimedAt) { this.resumeClaimedAt = resumeClaimedAt; }

    public String getResumeLauncherOwnerId() { return resumeLauncherOwnerId; }
    public void setResumeLauncherOwnerId(String resumeLauncherOwnerId) { this.resumeLauncherOwnerId = resumeLauncherOwnerId; }

    public Instant getResumeLauncherLeaseUntil() { return resumeLauncherLeaseUntil; }
    public void setResumeLauncherLeaseUntil(Instant resumeLauncherLeaseUntil) { this.resumeLauncherLeaseUntil = resumeLauncherLeaseUntil; }

    public String getEstimateJson() { return estimateJson; }
    public void setEstimateJson(String estimateJson) { this.estimateJson = estimateJson; }

    public String getFinanceRecordLimitsJson() { return financeRecordLimitsJson; }
    public void setFinanceRecordLimitsJson(String financeRecordLimitsJson) {
        this.financeRecordLimitsJson = financeRecordLimitsJson;
    }

    public String getCompletedTodosJson() { return completedTodosJson; }
    public void setCompletedTodosJson(String completedTodosJson) { this.completedTodosJson = completedTodosJson; }

    public String getDatasetRefsJson() { return datasetRefsJson; }
    public void setDatasetRefsJson(String datasetRefsJson) { this.datasetRefsJson = datasetRefsJson; }

    public int getToolCallsUsed() { return toolCallsUsed; }
    public void setToolCallsUsed(int toolCallsUsed) { this.toolCallsUsed = toolCallsUsed; }

    public String getReservationJson() { return reservationJson; }
    public void setReservationJson(String reservationJson) { this.reservationJson = reservationJson; }

    public String getDatasetSnapshotJson() { return datasetSnapshotJson; }
    public void setDatasetSnapshotJson(String datasetSnapshotJson) { this.datasetSnapshotJson = datasetSnapshotJson; }

    public String getDatasetSnapshotDigest() { return datasetSnapshotDigest; }
    public void setDatasetSnapshotDigest(String datasetSnapshotDigest) { this.datasetSnapshotDigest = datasetSnapshotDigest; }

    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }

    public String getTerminalResultPreview() { return terminalResultPreview; }
    public void setTerminalResultPreview(String terminalResultPreview) { this.terminalResultPreview = terminalResultPreview; }

    public String getTerminalRawRef() { return terminalRawRef; }
    public void setTerminalRawRef(String terminalRawRef) { this.terminalRawRef = terminalRawRef; }

    public String getTerminalStderrPreview() { return terminalStderrPreview; }
    public void setTerminalStderrPreview(String terminalStderrPreview) { this.terminalStderrPreview = terminalStderrPreview; }

    public String getTerminalErrorCode() { return terminalErrorCode; }
    public void setTerminalErrorCode(String terminalErrorCode) { this.terminalErrorCode = terminalErrorCode; }

    public String getTerminalExitReason() { return terminalExitReason; }
    public void setTerminalExitReason(String terminalExitReason) { this.terminalExitReason = terminalExitReason; }

    public String getTerminalUsageJson() { return terminalUsageJson; }
    public void setTerminalUsageJson(String terminalUsageJson) { this.terminalUsageJson = terminalUsageJson; }

    public Instant getTerminalAt() { return terminalAt; }
    public void setTerminalAt(Instant terminalAt) { this.terminalAt = terminalAt; }

    public Boolean getTerminalRetryable() { return terminalRetryable; }
    public void setTerminalRetryable(Boolean terminalRetryable) { this.terminalRetryable = terminalRetryable; }

    public String getPythonRequestFingerprint() { return pythonRequestFingerprint; }
    public void setPythonRequestFingerprint(String pythonRequestFingerprint) {
        this.pythonRequestFingerprint = pythonRequestFingerprint;
    }

    public int getPythonRepairAttempt() { return pythonRepairAttempt; }
    public void setPythonRepairAttempt(int pythonRepairAttempt) {
        this.pythonRepairAttempt = Math.max(0, pythonRepairAttempt);
    }

    public boolean isPythonRepairPending() { return pythonRepairPending; }
    public void setPythonRepairPending(boolean pythonRepairPending) {
        this.pythonRepairPending = pythonRepairPending;
    }

    public boolean isPythonRepairExhausted() { return pythonRepairExhausted; }
    public void setPythonRepairExhausted(boolean pythonRepairExhausted) {
        this.pythonRepairExhausted = pythonRepairExhausted;
    }

    public List<String> getPythonFailedRequestFingerprints() { return pythonFailedRequestFingerprints; }
    public void setPythonFailedRequestFingerprints(List<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) {
            this.pythonFailedRequestFingerprints = Collections.emptyList();
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String fingerprint : fingerprints) {
            if (fingerprint != null && !fingerprint.isBlank()) {
                normalized.add(fingerprint.trim());
            }
        }
        this.pythonFailedRequestFingerprints = List.copyOf(normalized);
    }

    public String getResultFetchState() { return resultFetchState; }
    public void setResultFetchState(String resultFetchState) { this.resultFetchState = resultFetchState; }

    public int getResultFetchAttempts() { return resultFetchAttempts; }
    public void setResultFetchAttempts(int resultFetchAttempts) { this.resultFetchAttempts = resultFetchAttempts; }

    public Instant getTerminalConfirmedAt() { return terminalConfirmedAt; }
    public void setTerminalConfirmedAt(Instant terminalConfirmedAt) { this.terminalConfirmedAt = terminalConfirmedAt; }

    public String getSandboxTerminalStatus() { return sandboxTerminalStatus; }
    public void setSandboxTerminalStatus(String sandboxTerminalStatus) { this.sandboxTerminalStatus = sandboxTerminalStatus; }

    public String getFinalizerStep() { return finalizerStep; }
    public void setFinalizerStep(String finalizerStep) { this.finalizerStep = finalizerStep; }

    public String getFinalizerError() { return finalizerError; }
    public void setFinalizerError(String finalizerError) { this.finalizerError = finalizerError; }

    public boolean isUsagePersisted() { return usagePersisted; }
    public void setUsagePersisted(boolean usagePersisted) { this.usagePersisted = usagePersisted; }

    public boolean isTerminalEventEmitted() { return terminalEventEmitted; }
    public void setTerminalEventEmitted(boolean terminalEventEmitted) { this.terminalEventEmitted = terminalEventEmitted; }

    @JsonIgnore
    public boolean isResultConsumed() { return "ACCEPTED".equals(resumeState) || "CONSUMED".equals(resumeState); }

    @JsonProperty("resultConsumed")
    public boolean getResultConsumed() { return "ACCEPTED".equals(resumeState) || "CONSUMED".equals(resumeState); }

    @JsonProperty("resultConsumed")
    public void setResultConsumed(boolean resultConsumed) { this.resultConsumed = resultConsumed; }

    public Instant getNextPollAt() { return nextPollAt; }
    public void setNextPollAt(Instant nextPollAt) { this.nextPollAt = nextPollAt; }

    public Instant getTimeoutAt() { return timeoutAt; }
    public void setTimeoutAt(Instant timeoutAt) { this.timeoutAt = timeoutAt; }
}
