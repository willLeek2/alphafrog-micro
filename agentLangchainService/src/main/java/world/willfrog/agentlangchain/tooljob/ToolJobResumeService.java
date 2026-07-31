package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.workflow.AgentRunDatasetRegistry;

import java.util.Collections;
import java.util.List;

/**
 * durable anchor 到新 Agent worker 之间的恢复租约状态机。
 *
 * <p>READY 负责竞争 claim，LAUNCHING 表示某个 token/version 已取得启动权，
 * CONSUMED 表示结果已被工作流接受。所有推进和清理都通过数据库 CAS；Redis 仅在
 * DB 成功后清理。数据集注册表在提交新 worker 前恢复，失败则回滚到新的 READY 租约。</p>
 */
@Service
public class ToolJobResumeService {

    private static final Logger log = LoggerFactory.getLogger(ToolJobResumeService.class);

    private final ToolJobAnchorService anchorService;
    private final ToolJobRedisCache redisCache;
    private final ToolJobConfig config;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private AgentRunDatasetRegistry datasetRegistry;

    @Autowired(required = false)
    private ToolJobResumeLauncher resumeLauncher;

    public ToolJobResumeService(ToolJobAnchorService anchorService,
                                ToolJobRedisCache redisCache, ToolJobConfig config,
                                ObjectMapper objectMapper) {
        this.anchorService = anchorService;
        this.redisCache = redisCache;
        this.config = config;
        this.objectMapper = objectMapper;
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
                return true; // anchor still CONSUMED, will retry next scan
            }
            // durable clear 已成功，Redis 残留现在可以安全删除。
            redisCache.removeDue(runId);
            redisCache.deletePendingCache(runId);
            return true;
        }
        // READY 需要竞争新启动租约。
        if ("READY".equals(state)) return launchFromReady(runId, anchor);
        // LAUNCHING 可能是正在运行，也可能是进程崩溃留下，需要活性/TTL 判断。
        if ("LAUNCHING".equals(state)) return reenterLaunching(runId, anchor);
        return false;
    }

    private boolean launchFromReady(String runId, ToolJobAnchor anchor) {
        // 在修改内存 anchor 前冻结 READY 的 token/version，作为 optimistic CAS 条件。
        String expectedToken = anchor.getResumeToken();
        long expectedVersion = anchor.getResumeLeaseVersion();

        // 准备 READY→LAUNCHING；claim 时单调递增 version，使所有旧重放失效。
        anchor.setResumeState("LAUNCHING");
        anchor.setResumeLeaseVersion(expectedVersion + 1);
        anchor.setResumeClaimedAt(java.time.Instant.now());

        // 只有一个进程能命中 READY + expectedToken + expectedVersion。
        boolean claimed = anchor.isResultConsumed()
                ? anchorService.casResumeStateAndStatus(
                runId, anchor, AgentRunStatus.EXECUTING, AgentRunStatus.RECEIVED,
                "READY", expectedToken, expectedVersion)
                : anchorService.casResumeState(
                runId, anchor, AgentRunStatus.RECEIVED, "READY", expectedToken, expectedVersion);
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
        return doLaunch(runId, anchor, expectedVersion, claimedToken, claimedVersion);
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

    private boolean reenterLaunching(String runId, ToolJobAnchor anchor) {
        log.info("Re-entering LAUNCHING resume for run={}", runId);

        // 只有 claim 超过 TTL 且本进程 launcher 也报告 inactive，才能判定为崩溃遗留。
        if (anchor.getResumeClaimedAt() != null) {
            // claimedAt 是 durable 时间，因此进程重启后仍能计算 staleDeadline。
            long staleDeadline = anchor.getResumeClaimedAt().toEpochMilli()
                    + config.getLaunchingStaleSeconds() * 1000;
            if (System.currentTimeMillis() > staleDeadline) {
                long claimedVersion = anchor.getResumeLeaseVersion();
                String claimedToken = anchor.getResumeToken();
                // TTL 只是诊断阈值；本地任务仍 active 时不能抢走其租约。
                if (resumeLauncher != null && resumeLauncher.isActive(runId, claimedToken, claimedVersion)) {
                    log.info("LAUNCHING claim past TTL but launcher still active for run={} token={} v{}",
                            runId, claimedToken, claimedVersion);
                    return false; // don't roll back — launcher is still running
                }
                log.warn("LAUNCHING claim stale for run={}, claimedAt={}, rolling back to READY",
                        runId, anchor.getResumeClaimedAt());
                // 确认 stale 后生成新 token 并递增 version，彻底 fence 旧 launcher。
                anchor.setResumeState("READY");
                anchor.setResumeLeaseVersion(claimedVersion + 1);
                anchor.setResumeToken(java.util.UUID.randomUUID().toString());
                anchor.setResumeClaimedAt(null);
                if (anchor.isResultConsumed()) {
                    // 已消费 handoff 的 resumed worker 运行在 EXECUTING；回滚 READY 时必须同时
                    // 恢复 RECEIVED，否则 READY 会落在扫描器无法再次 claim 的状态中。
                    anchorService.casResumeStateAndStatus(
                            runId, anchor, AgentRunStatus.RECEIVED, AgentRunStatus.EXECUTING,
                            "LAUNCHING", claimedToken, claimedVersion);
                } else {
                    anchorService.casResumeState(runId, anchor, AgentRunStatus.RECEIVED, "LAUNCHING",
                            claimedToken, claimedVersion);
                }
                // 本轮不直接递归 launch；下一次扫描按新 READY 正常竞争。
                return false;
            }
        }

        // 未过期 LAUNCHING 允许重复调用 launcher；其 activeClaims 按 token/version 幂等。
        if (!restoreDatasetRegistry(runId, anchor)) {
            log.error("Dataset restore failed on LAUNCHING reentry for run={}, will retry", runId);
            return false;
        }
        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — cannot recover LAUNCHING run={}", runId);
            return false;
        }
        // 从同一 LAUNCHING anchor 重建完全相同的恢复上下文。
        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            return resumeLauncher.launch(runId, ctx);
        } catch (Exception e) {
            log.error("Re-launch threw for run={}, will retry", runId, e);
            return false;
        }
    }

    private boolean doLaunch(String runId, ToolJobAnchor anchor,
                              long originalVersion, String claimedToken, long claimedVersion) {
        // launcher 未装配时不能丢弃 claim，回滚为新版本 READY 供后续恢复。
        if (resumeLauncher == null) {
            log.warn("No resumeLauncher wired — rolling back run={}", runId);
            rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
            return false;
        }
        // 只从已 claim 的 durable anchor 构建 context，不读取旧 worker 内存。
        ToolJobResumeContext ctx = buildResumeContext(runId, anchor);
        try {
            // launch=false 表示任务未进入 bounded scheduler，必须回滚 claim。
            if (!resumeLauncher.launch(runId, ctx)) {
                rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
                return false;
            }
            // true 表示 launcher 已幂等接受，实际 worker 可能仍在队列等待。
            return true;
        } catch (Exception e) {
            log.error("Launch threw for run={}, rolling back", runId, e);
            rollbackToReady(runId, anchor, originalVersion, claimedToken, claimedVersion);
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

        // 副作用都已落稳时先 CAS 写 CONSUMED，再执行 token-gated clear。
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
            return false; // keep Redis cache, retry on next cycle
        }
        redisCache.removeDue(runId);
        redisCache.deletePendingCache(runId);
        log.info("Full cleanup completed for run={}", runId);
        return true;
    }

    /**
     * Persists the first half of the resume handoff. The terminal result has
     * been accepted by the workflow, but the old anchor is deliberately kept
     * until the resumed workflow has durably reached either a final result or
     * a later tool-job checkpoint.
     */
    public boolean markHandoffAccepted(String runId, ToolJobResumeContext context) {
        // 交接上下文必须绑定当前 run/token/version，且 executor 已把 resultConsumed 推进为 true。
        if (runId == null || runId.isBlank() || context == null || !runId.equals(context.getRunId())
                || context.getResumeToken() == null || context.getResumeToken().isBlank()
                || context.getResumeLeaseVersion() <= 0 || !context.isResultConsumed()
                || context.getTodoId() == null || context.getTodoId().isBlank()
                || context.getCompletedTodos() == null) {
            return false;
        }
        // 只接受仍由相同 lease 持有的 LAUNCHING anchor。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null || !"LAUNCHING".equals(anchor.getResumeState())
                || !context.getResumeToken().equals(anchor.getResumeToken())
                || context.getResumeLeaseVersion() != anchor.getResumeLeaseVersion()) {
            return false;
        }
        try {
            // 把注入终态后新增的 completedTodo 前缀写回 anchor，避免回调后崩溃丢进度。
            anchor.setCompletedTodosJson(objectMapper.writeValueAsString(context.getCompletedTodos()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize accepted resume handoff for run={}", runId, e);
            return false;
        }
        // todoId 已被 executor 推进到下一节点或 FINAL 哨兵。
        anchor.setTodoId(context.getTodoId());
        anchor.setSequence(context.getTodoSequence());
        anchor.setToolCallsUsed(context.getToolCallsUsed());
        anchor.setResultConsumed(true);
        // 同一条 CAS 持久化“结果已接受”并把 Run 从 RECEIVED 恢复为 EXECUTING。
        // 旧 LAUNCHING anchor 继续保留，直到最终结果落稳或被下一次 PREPARING 精确替换。
        boolean accepted = anchorService.casResumeStateAndStatus(
                runId, anchor, AgentRunStatus.EXECUTING, AgentRunStatus.RECEIVED,
                "LAUNCHING", context.getResumeToken(), context.getResumeLeaseVersion());
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
     * Clears only the exact old handoff claim, after the pipeline callback has
     * returned from durable result/checkpoint persistence. A later suspension
     * has a different state/token/version and is therefore never cleared here.
     */
    public boolean completeHandoff(String runId, String token, long version) {
        // pipeline 回调只允许清理自己最初提交的旧 claim。
        ToolJobAnchor anchor = anchorService.loadAnchor(runId);
        if (anchor == null) {
            // 已被其他幂等路径清理，视为完成。
            return true;
        }
        // 若恢复执行再次挂起，anchor 已换 state/token/version；这里必须返回 false 且绝不清理。
        if (!"LAUNCHING".equals(anchor.getResumeState()) || !anchor.isResultConsumed()
                || token == null || !token.equals(anchor.getResumeToken())
                || version != anchor.getResumeLeaseVersion()) {
            return false;
        }
        // DB token-gated clear 是最终 durable gate。
        if (!anchorService.clearAnchorWithToken(runId, "LAUNCHING", token, version)) {
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
        // 还原不再执行的 Todo 前缀和 dataset 快照。
        ctx.setCompletedTodos(parseCompletedTodos(anchor.getCompletedTodosJson()));
        ctx.setDatasetSnapshotJson(anchor.getDatasetSnapshotJson());
        ctx.setDatasetSnapshotDigest(anchor.getDatasetSnapshotDigest());
        // 延续工具预算与终态结果。
        ctx.setToolCallsUsed(anchor.getToolCallsUsed());
        ctx.setTerminalSuccess("SUCCEEDED".equals(anchor.getTerminalStatus()));
        ctx.setTerminalResultPreview(anchor.getTerminalResultPreview());
        ctx.setTerminalRawRef(anchor.getTerminalRawRef());
        // crash reentry 时该标记决定从当前挂起节点还是下一节点继续。
        ctx.setResultConsumed(anchor.isResultConsumed());
        return ctx;
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
                // 两种格式都无法解析时返回空前缀并记录警告；上层顺序校验仍会 fail-closed。
                log.warn("Failed to parse completedTodosJson", ex2);
                return Collections.emptyList();
            }
        }
    }
}
