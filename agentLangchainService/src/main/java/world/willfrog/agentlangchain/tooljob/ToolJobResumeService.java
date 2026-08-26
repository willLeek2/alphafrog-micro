package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 数据库里的 anchor 到新 Agent worker 之间的恢复租约状态机。
 *
 * <p>四阶段模型：READY 负责竞争 claim，LAUNCHING 表示某个 token/version 已取得启动权，
 * ACCEPTED 表示 handoff 已被工作流接受，CONSUMED 表示结果已被最终消费且 anchor 可安全清理。
 * 所有推进和清理都通过数据库 CAS；Redis 仅在 DB 成功后清理。数据集注册表在提交新 worker
 * 前恢复，失败则按原状态回退（READY→READY，ACCEPTED→ACCEPTED）。</p>
 */
@Service
public class ToolJobResumeService {

    private static final Logger log = LoggerFactory.getLogger(ToolJobResumeService.class);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final ToolJobConfig config;
    private final ObjectMapper objectMapper;
    private final String launcherOwnerId;

    @Autowired(required = false)
    private AgentRunDatasetRegistry datasetRegistry;

    @Autowired(required = false)
    private ToolJobResumeLauncher resumeLauncher;

    @Autowired
    public ToolJobResumeService(ToolJobAnchorService anchorService,
                                ToolJobRedisCache redisCache, ToolJobConfig config,
                                ObjectMapper objectMapper) {
        this(anchorService, redisCache, config, objectMapper, "resume-launcher-" + UUID.randomUUID());
    }

    ToolJobResumeService(ToolJobAnchorService anchorService,
                         ToolJobRedisCache redisCache, ToolJobConfig config,
                         ObjectMapper objectMapper, String launcherOwnerId) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = objectMapper;
        this.launcherOwnerId = launcherOwnerId;
    }

    public boolean tryResume(String runId) {
        // 每次尝试都读取 PostgreSQL 最新 anchor，避免基于 Redis 热副本 claim。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) return false;

        // resumeState 决定本次是清理、首次 claim 还是 LAUNCHING 重入。
        String state = anchor.getResumeState();
        if ("CONSUMED".equals(state)) {
            // 必须先按 token/version 清理数据库，再删 Redis；顺序不能反转。
            // DB clear 失败时保留 Redis，使下一轮扫描仍能重试。
            String token = anchor.getResumeToken();
            if (token == null || token.isBlank()) {
                log.warn("CONSUMED anchor has no resumeToken for run={}, leaving Redis for retry", runId);
                return true;
            }
            // state + token + leaseVersion 精确绑定本轮消费 owner。
            if (!anchorService.clearAnchorWithToken(runId, "CONSUMED", token,
                    anchor.getResumeLeaseVersion())) {
                log.warn("CONSUMED durable clear failed for run={}, leaving Redis for retry", runId);
                return true;
            }
            // 数据库清理已成功，Redis 残留现在可以安全删除。
            redisCache.removeDue(runId);
            redisCache.deletePendingCache(runId);
            return true;
        }
        // 取消/暂停锚点（autoResume=false）只做终态收尾与容量释放，不自动恢复；
        // CONSUMED 的幂等清理在上方分支，不受此门控影响。服务层判断只是第一层，
        // 所有权 CAS（claimResumeLauncher / takeoverExpiredResumeLauncher /
        // acceptResumeHandoff）在数据库层还有同一 autoResume 条件，防止
        // "读到旧 autoResume=true 对象后，取消线程先把 autoResume=false 写进数据库，
        // 恢复线程再整体覆盖锚点"的丢取消竞态。
        if (!anchor.isAutoResume()) {
            return false;
        }
        // READY 需要竞争新启动租约。
        if ("READY".equals(state)) return launchFromReady(runId, anchor);
        // LAUNCHING 或 ACCEPTED：可能正在运行或已崩溃，检查 lease TTL + isActive。
        if ("LAUNCHING".equals(state) || "ACCEPTED".equals(state)) return reenterLaunching(runId, anchor);
        return false;
    }

    private boolean launchFromReady(String runId, ToolJobAnchor anchor) {
        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — cannot claim READY run={}", runId);
            return false;
        }
        // 在修改内存 anchor 前冻结 READY 的 token/version，作为 optimistic CAS 条件。
        String expectedToken = anchor.getResumeToken();
        long expectedVersion = anchor.getResumeLeaseVersion();

        // 准备 READY→LAUNCHING；claim 时单调递增 version，使所有旧重放失效。
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeLeaseVersion(expectedVersion + 1);
        anchor.setResumeClaimedAt(Instant.now());
        anchor.setResumeLauncherOwnerId(launcherOwnerId);
        anchor.setResumeLauncherLeaseUntil(Instant.now().plusSeconds(leaseSeconds()));

        // owner、数据库时间 lease 和 token/version 在同一条 CAS 中一起确认写入。
        boolean claimed = anchorService.claimResumeLauncher(
                runId, anchor,
                anchor.isResultConsumed() ? AgentRunStatus.EXECUTING : AgentRunStatus.RECEIVED,
                AgentRunStatus.RECEIVED, expectedToken, expectedVersion,
                launcherOwnerId, leaseSeconds());
        if (!claimed) {
            log.info("Resume CAS READY→LAUNCHING failed for run={}", runId);
            return false;
        }

        // 记录真正 claim 后的身份；回滚和 launcher 去重都使用它。
        String claimedToken = anchor.getResumeToken();
        long claimedVersion = anchor.getResumeLeaseVersion();

        // 新 worker 运行前恢复 dataset registry；失败时禁止带空映射继续。
        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed for run={}, rolling back to READY", runId);
            rollbackToReady(runId, anchor, expectedVersion, claimedToken, claimedVersion);
            return false;
        }
        // 上下文恢复成功后才向 bounded run scheduler 提交任务。
        return doLaunch(runId, anchor, expectedVersion, claimedToken, claimedVersion, "READY");
    }

    private void rollbackToReady(String runId, ToolJobAnchor anchor, long originalVersion,
                                  String claimedToken, long claimedVersion) {
        // 回滚仍要再次递增 version，绝不恢复 originalVersion。
        // 否则 READY→LAUNCHING→READY 会形成 ABA，使旧 token/version 再次合法。
        long nextVersion = claimedVersion + 1;
        // 把状态退回 READY，允许下一轮由任一实例重新 claim。
        anchor.setResumeState("READY");
        anchor.setResumeLeaseVersion(nextVersion);
        anchor.setResumeClaimedAt(null);
        anchor.setResumeLauncherOwnerId(null);
        anchor.setResumeLauncherLeaseUntil(null);
        // 只回滚自己持有的 LAUNCHING claim；若所有权已变则 rows=0 并退场。
        if (anchor.isResultConsumed()) {
            anchorService.casResumeStateAndStatus(
                    runId, anchor, AgentRunStatus.RECEIVED, AgentRunStatus.EXECUTING,
                    "LAUNCHING", claimedToken, claimedVersion);
        } else {
            anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING",
                    claimedToken, claimedVersion);
        }
    }

    private void rollbackToAccepted(String runId, ToolJobAnchor anchor, long originalVersion,
                                    String claimedToken, long claimedVersion) {
        // ACCEPTED 回退：保持 ACCEPTED 状态（handoff 已被接受），仅清除 lease 让其他实例可重试。
        long nextVersion = claimedVersion + 1;
        anchor.setResumeState("ACCEPTED");
        anchor.setResumeLeaseVersion(nextVersion);
        anchor.setResumeClaimedAt(null);
        anchor.setResumeLauncherOwnerId(null);
        anchor.setResumeLauncherLeaseUntil(null);
        anchor.setResultConsumed(true);
        anchorService.casResumeState(runId, anchor, AgentRunStatus.EXECUTING,
                "ACCEPTED", claimedToken, claimedVersion);
    }

    private void rollbackFromState(String runId, ToolJobAnchor anchor, long originalVersion,
                                   String claimedToken, long claimedVersion,
                                   String previousResumeState) {
        if ("ACCEPTED".equals(previousResumeState)) {
            rollbackToAccepted(runId, anchor, originalVersion, claimedToken, claimedVersion);
        } else {
            rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
        }
    }

    private boolean reenterLaunching(String runId, ToolJobAnchor anchor) {
        log.info("重入 LAUNCHING/ACCEPTED 恢复流程 run={}", runId);
        if (resumeLauncher == null) {
            log.warn("未注入 resumeLauncher，无法恢复 run={}", runId);
            return false;
        }
        // 未过期的持久化 lease 无论属于本实例还是别的实例，都不能重复提交 pipeline。
        if (!launcherLeaseExpired(anchor, Instant.now())) {
            return false;
        }
        // 同进程内已有活跃 launcher (runId + token + version)，避免同进程双 launch。
        // 跨进程仍以 DB lease/version/token CAS 为唯一权威。
        if (resumeLauncher.isActive(runId, anchor.getResumeToken(), anchor.getResumeLeaseVersion())) {
            log.info("同进程 launcher 仍活跃 run={}，跳过过期回收", runId);
            return false;
        }

        String previousResumeState = anchor.getResumeState();
        String expectedToken = anchor.getResumeToken();
        long expectedVersion = anchor.getResumeLeaseVersion();
        String expectedOwnerId = anchor.getResumeLauncherOwnerId();
        anchor.setResumeToken(UUID.randomUUID().toString());
        anchor.setResumeLeaseVersion(expectedVersion + 1);
        anchor.setResumeClaimedAt(Instant.now());
        anchor.setResumeLauncherOwnerId(launcherOwnerId);
        anchor.setResumeLauncherLeaseUntil(Instant.now().plusSeconds(leaseSeconds()));
        AgentRunStatus expectedStatus = anchor.isResultConsumed()
                ? AgentRunStatus.EXECUTING : AgentRunStatus.RECEIVED;
        if (!anchorService.takeoverExpiredResumeLauncher(
                runId, anchor, expectedStatus, expectedToken, expectedVersion,
                expectedOwnerId, launcherOwnerId, leaseSeconds(), legacyStaleSeconds())) {
            // 数据库时间仍未过期，或另一个实例已经先赢得 takeover。
            return false;
        }

        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed after LAUNCHING takeover for run={}, rolling back", runId);
            if ("ACCEPTED".equals(previousResumeState)) {
                rollbackToAccepted(runId, anchor, expectedVersion,
                        anchor.getResumeToken(), anchor.getResumeLeaseVersion());
            } else {
                rollbackToReady(runId, anchor, expectedVersion,
                        anchor.getResumeToken(), anchor.getResumeLeaseVersion());
            }
            return false;
        }
        return doLaunch(runId, anchor, expectedVersion,
                anchor.getResumeToken(), anchor.getResumeLeaseVersion(), previousResumeState);
    }

    private boolean doLaunch(String runId, ToolJobAnchor anchor,
                              long originalVersion, String claimedToken, long claimedVersion,
                              String previousResumeState) {
        // 只从已 claim 的数据库 anchor 构建 context，不读取旧 worker 内存。
        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            // launch=false 表示任务未进入 bounded scheduler，必须回滚 claim。
            if (!resumeLauncher.launch(runId, ctx)) {
                rollbackFromState(runId, anchor, originalVersion, claimedToken, claimedVersion,
                        previousResumeState);
                return false;
            }
            // true 表示 launcher 已幂等接受，实际 worker 可能仍在队列等待。
            return true;
        } catch (Exception e) {
            log.error("Launch threw for run={}, rolling back", runId, e);
            rollbackFromState(runId, anchor, originalVersion, claimedToken, claimedVersion,
                    previousResumeState);
            return false;
        }
    }

    public boolean markConsumed(String runId) {
        // 消费确认前再次读取当前 anchor，不能沿用 launcher 创建时的旧对象。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) return false;

        // usage/event 尚未完成时只标 CONSUMED，不清 anchor，等待 finalizer 补齐副作用。
        if (!anchor.isUsagePersisted() || !anchor.isTerminalEventEmitted()) {
            log.info("Deferring cleanup for run={}: usagePersisted={} terminalEventEmitted={}",
                    runId, anchor.isUsagePersisted(), anchor.isTerminalEventEmitted());
            anchor.setResumeState("CONSUMED");
            anchor.setResultConsumed(true);
            return anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED);
        }

        // 副作用都已确认写入数据库时先 CAS 写 CONSUMED，再执行 token-gated clear。
        anchor.setResumeState("CONSUMED");
        anchor.setResultConsumed(true);
        if (!anchorService.updateAnchor(runId, anchor, AgentRunStatus.RECEIVED)) {
            log.warn("markConsumed anchor update CAS failed for run={}, will retry", runId);
            return false;
        }

        // 仍保持 DB first、Redis second；没有 token 时拒绝无条件清理。
        String token = anchor.getResumeToken();
        if (token == null || token.isBlank()) {
            log.warn("No resumeToken for run={} — refusing to clear anchor, will retry", runId);
            return false;
        }
        if (!anchorService.clearAnchorWithToken(runId, "CONSUMED", token,
                anchor.getResumeLeaseVersion())) {
            log.warn("Token+state+version-gated clear failed for run={} — mismatch, retrying", runId);
            return false;
        }
        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);
        log.info("Full cleanup completed for run={}", runId);
        return true;
    }

    /**
     * 持久化恢复 handoff 的前半部分。终态结果已被工作流接受，但旧 anchor 会被
     * 故意保留，直到恢复后的工作流持久化到达最终结果或下一个工具任务检查点。
     */
    public boolean markHandoffAccepted(String runId, ToolJobResumeContext context) {
        // 交接上下文必须绑定当前 run/token/version，且 executor 已把 resultConsumed 推进为 true。
        if (runId == null || runId.isBlank() || context == null || !runId.equals(context.getRunId())
                || context.getResumeToken() == null || context.getResumeToken().isBlank()
                || context.getResumeLeaseVersion() <= 0 || !context.isResultConsumed()
                || context.getResumeLauncherOwnerId() == null
                || !launcherOwnerId.equals(context.getResumeLauncherOwnerId())
                || context.getTodoId() == null || context.getTodoId().isBlank()
                || context.getCompletedTodos() == null) {
            return false;
        }
        // 只接受仍由相同 lease 持有的 LAUNCHING anchor。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null || !"LAUNCHING".equals(anchor.getResumeState())
                || !context.getResumeToken().equals(anchor.getResumeToken())
                || context.getResumeLeaseVersion() != anchor.getResumeLeaseVersion()
                || !context.getResumeLauncherOwnerId().equals(anchor.getResumeLauncherOwnerId())) {
            return false;
        }
        try {
            // 把注入终态后新增的 completedTodo 前缀写回 anchor，避免回调后崩溃丢进度。
            anchor.setCompletedTodosJson(objectMapper.writeValueAsString(context.getCompletedTodos()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize accepted resume handoff for run={}", runId, e);
            return false;
        }
        // todoId 已被 executor 推进到下一节点，或已到全部节点完成后的结尾标记。
        anchor.setTodoId(context.getTodoId());
        anchor.setSequence(context.getTodoSequence());
        anchor.setToolCallsUsed(context.getToolCallsUsed());
        anchor.setPythonRepairAttempt(context.getPythonRepairAttempt());
        anchor.setPythonRepairPending(context.isPythonRepairPending());
        anchor.setPythonRepairExhausted(context.isPythonRepairExhausted());
        anchor.setPythonFailedRequestFingerprints(context.getPythonFailedRequestFingerprints());
        anchor.setResultConsumed(true);
        anchor.setResumeLauncherLeaseUntil(Instant.now().plusSeconds(leaseSeconds()));
        // 在将 Run 恢复为 EXECUTING 的同一条 CAS 中将 resumeState 推进为 ACCEPTED。
        // 旧 anchor 保留至下一个持久化检查点或最终结果。
        anchor.setResumeState("ACCEPTED");
        boolean accepted = anchorService.acceptResumeHandoff(
                runId, anchor, context.getResumeToken(), context.getResumeLeaseVersion(),
                context.getResumeLauncherOwnerId(), leaseSeconds());
        if (accepted) {
            // 先有 PostgreSQL 真相再清旧 Redis 派生项；恢复扫描可直接从 PG 重建。
            try {
                redisCache.removeDue(runId);
                redisCache.deletePendingCache(runId);
            } catch (Exception cacheFailure) {
                log.warn("Accepted handoff Redis cleanup failed for run={}, PG state remains recoverable: {}",
                        runId, cacheFailure.getMessage());
            }
        }
        return accepted;
    }

    /**
     * 仅清理精确匹配的旧 handoff claim。只在 pipeline 回调已把最终结果或检查点
     * 写入数据库并返回后调用。后续挂起会产生不同的 state/token/version，因此绝不会被这里误清理。
     */
    public boolean completeHandoff(String runId, String token, long version, String ownerId) {
        // pipeline 回调只允许清理自己最初提交的旧 claim。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) {
            // 已被其他幂等路径清理，视为完成。
            return true;
        }
        // 若恢复执行再次挂起，anchor 已换 state/token/version；这里必须返回 false 且绝不清理。
        // ACCEPTED 是 handoff 后的状态（markHandoffAccepted 已将 LAUNCHING 推进为 ACCEPTED）。
        if ((!"LAUNCHING".equals(anchor.getResumeState()) && !"ACCEPTED".equals(anchor.getResumeState()))
                || !anchor.isResultConsumed()
                || token == null || !token.equals(anchor.getResumeToken())
                || version != anchor.getResumeLeaseVersion()
                || ownerId == null || !ownerId.equals(anchor.getResumeLauncherOwnerId())) {
            return false;
        }
        // DB token-gated clear 是最后一道数据库校验闸门。
        if (!anchorService.clearAcceptedResumeHandoff(runId, token, version, ownerId)) {
            return false;
        }
        // DB 已清理后再删除 Redis 辅助状态。
        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);
        return true;
    }

    // ---- internal ----

    /** @return true if restore succeeded or no snapshot to restore */
    private boolean restoreDatasetRegistry(String runId, ToolJobAnchor anchor) {
        // registry 是可选 bean；没有数据分析能力时可无快照恢复。
        if (datasetRegistry == null) return true;
        // 历史 anchor 可能没有快照；空值按“无数据集可恢复”处理。
        String snapshotJson = anchor.getDatasetSnapshotJson();
        if (snapshotJson == null || snapshotJson.isBlank()) return true;
        try {
            // 按领域类型解析，restore 内部会校验 immutable digest/映射契约。
            world.willfrog.agent.workflow.AgentRunDatasetSnapshot snapshot =
                    objectMapper.readValue(snapshotJson, world.willfrog.agent.workflow.AgentRunDatasetSnapshot.class);
            datasetRegistry.restore(runId, snapshot);
            log.info("Dataset registry restored for run={}", runId);
            return true;
        } catch (Exception e) {
            log.error("Dataset registry restore FAILED for run={}, blocking resume", runId, e);
            return false;
        }
    }

    ToolJobResumeContext buildResumeContext(String runId, ToolJobAnchor anchor) {
        // 每次 launch 创建新 DTO，避免多个 worker 共享可变 context。
        ToolJobResumeContext ctx = new ToolJobResumeContext();
        // 恢复身份与注入位置。
        ctx.setRunId(runId);
        ctx.setTodoId(anchor.getTodoId());
        ctx.setTodoSequence(anchor.getSequence());
        // 当前 claim 的 token/version 会贯穿 launcher 去重、handoff CAS 和最终清理。
        ctx.setResumeToken(anchor.getResumeToken());
        ctx.setResumeLeaseVersion(anchor.getResumeLeaseVersion());
        ctx.setResumeLauncherOwnerId(anchor.getResumeLauncherOwnerId());
        // 还原不再执行的 Todo 前缀和 dataset 快照。
        ctx.setCompletedTodos(parseCompletedTodos(anchor.getCompletedTodosJson()));
        ctx.setDatasetSnapshotJson(anchor.getDatasetSnapshotJson());
        ctx.setDatasetSnapshotDigest(anchor.getDatasetSnapshotDigest());
        // 延续工具预算与终态结果。
        ctx.setToolCallsUsed(anchor.getToolCallsUsed());
        ctx.setTerminalSuccess("SUCCEEDED".equals(anchor.getTerminalStatus()));
        ctx.setTerminalStatus(anchor.getTerminalStatus());
        ctx.setTerminalResultPreview(anchor.getTerminalResultPreview());
        ctx.setTerminalRawRef(anchor.getTerminalRawRef());
        ctx.setTerminalStderrPreview(anchor.getTerminalStderrPreview());
        ctx.setTerminalErrorCode(anchor.getTerminalErrorCode());
        ctx.setTerminalExitReason(anchor.getTerminalExitReason());
        ctx.setTerminalRetryable(anchor.getTerminalRetryable());
        ctx.setPythonFailedCodePreview(extractPythonCodePreview(anchor));
        ctx.setPythonRepairAttempt(anchor.getPythonRepairAttempt());
        ctx.setPythonRepairPending(anchor.isPythonRepairPending());
        ctx.setPythonRepairExhausted(anchor.isPythonRepairExhausted());
        ctx.setPythonFailedRequestFingerprints(anchor.getPythonFailedRequestFingerprints());
        // crash reentry 时该标记决定从当前挂起节点还是下一节点继续。
        ctx.setResultConsumed(anchor.isResultConsumed());
        return ctx;
    }

    private static String extractPythonCodePreview(ToolJobAnchor anchor) {
        if (anchor == null || anchor.getCreateRequestJson() == null
                || anchor.getCreateRequestJson().isBlank()) {
            return null;
        }
        try {
            ExecuteRequest.Builder builder = ExecuteRequest.newBuilder();
            JsonFormat.parser().merge(anchor.getCreateRequestJson(), builder);
            return ToolJobFinalizer.boundedPreview(builder.getCode());
        } catch (Exception parseFailure) {
            log.warn("Cannot recover failed Python code from durable create request operation={}",
                    anchor.getOperationId(), parseFailure);
            return null;
        }
    }

    public boolean heartbeat(String runId, String token, long version, String ownerId) {
        if (!launcherOwnerId.equals(ownerId)) {
            return false;
        }
        return anchorService.heartbeatResumeLauncher(
                runId, token, version, ownerId, leaseSeconds());
    }

    String launcherOwnerId() {
        return launcherOwnerId;
    }

    private boolean launcherLeaseExpired(ToolJobAnchor anchor, Instant now) {
        if (anchor.getResumeLauncherLeaseUntil() != null) {
            return !anchor.getResumeLauncherLeaseUntil().isAfter(now);
        }
        // 旧 anchor 没有持久化 launcher lease 时，沿用 claimedAt stale 窗口再尝试数据库 takeover。
        return anchor.getResumeClaimedAt() == null
                || !anchor.getResumeClaimedAt().plusSeconds(legacyStaleSeconds()).isAfter(now);
    }

    private long leaseSeconds() {
        long configured = config.getResumeLauncherLeaseSeconds();
        return configured > 0 ? configured : Math.max(1L, config.getLaunchingStaleSeconds());
    }

    private long legacyStaleSeconds() {
        return Math.max(1L, config.getLaunchingStaleSeconds());
    }

    List<CompletedTodoRecord> parseCompletedTodos(String json) {
        // 空前缀是合法状态，返回不可变空列表。
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            // 当前格式保存完整 CompletedTodoRecord 列表。
            return objectMapper.readValue(json, new TypeReference<List<CompletedTodoRecord>>() {});
        } catch (JsonProcessingException e) {
            try {
                // 兼容早期只保存 todoId 字符串数组的 anchor。
                List<String> ids = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                return ids.stream().map(id -> { var r = new CompletedTodoRecord(); r.setTodoId(id); return r; }).toList();
            } catch (JsonProcessingException ex2) {
                // 两种格式都无法解析时返回空前缀并记录警告；上层顺序校验仍会拒绝。
                log.warn("Failed to parse completedTodosJson", ex2);
                return Collections.emptyList();
            }
        }
    }
}
