package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.credit.CostSettlementQuote;
import world.willfrog.agent.platform.credit.CostSource;
import world.willfrog.agent.platform.credit.EndpointCostAdapterRegistry;
import world.willfrog.agent.platform.credit.LlmCallBillingContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunCreditSummaryDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunLlmCallCreditDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunCreditSummary;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunLlmCallCredit;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 异步编排 Agent Run 的 credit 结算（per-endpoint cost accounting）。
 *
 * <h2>两阶段结算</h2>
 * <ol>
 *   <li><b>Phase 1（immediate）</b>：run 结束后立刻异步触发，对已有 LLM trace 调 adapter
 *       算 quote。OpenRouter 类的 endpoint 若 {@code needsDelayedRetry=true}（如异步 cost
 *       暂未回包），per-call 记 PENDING_RETRY；其他端点或能立刻拿到 cost 的记 SETTLED。
 *       汇总 immediate delta 写一条 ledger（biz_type=RUN_SETTLEMENT_IMMEDIATE，idem={runId}:immediate），
 *       upsert summary（immediateCreditConsumed、totalCreditConsumed=immediate、settlementStatus=SETTLED 暂态）。</li>
 *   <li><b>Phase 2（delayed）</b>：30s 后由 {@link ScheduledExecutorService} 触发。OpenRouter
 *       类的 PENDING_RETRY 调用重新拉一次 cost，能拉到的补 SETTLED + ledger（delayed），
 *       仍拉不到的落 MISSING（delta=0，仅审计）。final summary.totalCreditConsumed = immediate + delayed。</li>
 * </ol>
 *
 * <h2>幂等性</h2>
 * <ul>
 *   <li>per-call：{@code (runId, attempt, llmCallId)} 唯一键，由 {@code alphafrog_agent_run_llm_call_credit}
 *       的 unique(idempotency_key) 兜底；重跑只写一次。</li>
 *   <li>ledger：{@code {runId}:immediate} / {@code {runId}:delayed} 两条独立 idempotency_key，
 *       落在 unique(biz_type, source_id, idempotency_key) 索引上。</li>
 *   <li>summary：upsert by run_id，重复执行只覆盖 lastSettlementAt 等元数据。</li>
 * </ul>
 *
 * <h2>非阻塞</h2>
 * 通过 {@code @Async("creditSettlementExecutor")} 在独立线程池执行；30s 延迟 retry
 * 走 {@code creditSettlementScheduler}。不占用 agent run 线程。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunCreditSettlementService {

    public static final String SETTLEMENT_STATUS_SETTLED = "SETTLED";
    public static final String SETTLEMENT_STATUS_PENDING_RETRY = "PENDING_RETRY";
    public static final String SETTLEMENT_STATUS_MISSING = "MISSING";

    public static final String LEDGER_BIZ_IMMEDIATE = "RUN_SETTLEMENT_IMMEDIATE";
    public static final String LEDGER_BIZ_DELAYED = "RUN_SETTLEMENT_DELAYED";
    public static final String LEDGER_BIZ_REFRESH = "RUN_SETTLEMENT_REFRESH";

    public static final String SUMMARY_STATUS_SETTLED = "SETTLED";
    public static final String SUMMARY_STATUS_PENDING_RETRY = "PENDING_RETRY";
    public static final String SUMMARY_STATUS_MISSING = "MISSING";

    private static final int ATTEMPT_IMMEDIATE = 1;
    private static final int ATTEMPT_DELAYED = 2;
    private static final int ATTEMPT_REFRESH = 3;
    private static final String DEFAULT_CURRENCY = "USD";
    private static final int ADMIN_USER_TYPE = 1127;

    private final AgentObservabilityService observabilityService;
    private final AgentRunMapper runMapper;
    private final EndpointCostAdapterRegistry adapterRegistry;
    private final AgentRunLlmCallCreditDao llmCallCreditDao;
    private final AgentRunCreditSummaryDao summaryDao;
    private final UserDao userDao;
    private final AgentCreditDebitOperator debitOperator;
    private final ObjectMapper objectMapper;
    @Qualifier("creditSettlementExecutor")
    private final java.util.concurrent.Executor creditSettlementExecutor;
    @Qualifier("creditSettlementScheduler")
    private final ScheduledExecutorService creditSettlementScheduler;

    @Value("${agent.credit.settlement.delayed-retry-seconds:30}")
    private long delayedRetrySeconds;

    /**
     * 入口：run 结束后异步编排两阶段结算。
     * 在 agent 线程上调用此方法，立即返回；实际工作由 {@code creditSettlementExecutor} 接管。
     */
    public void settleAsync(String runId, String userId) {
        if (runId == null || runId.isBlank() || userId == null || userId.isBlank()) {
            log.warn("settleAsync skipped: invalid args runId={} userId={}", runId, userId);
            return;
        }
        creditSettlementExecutor.execute(() -> {
            try {
                settleOnce(runId, userId, ATTEMPT_IMMEDIATE);
            } catch (Exception e) {
                log.error("Immediate settlement failed: runId={} userId={}", runId, userId, e);
            }
        });
    }

    /**
     * 手动刷新：对 run 中所有支持 cost fetch 的 endpoint 且尚未拿到实际 cost 的 call，
     * 重新 quote 一次（attempt=3）。拿到实际 cost 后补写 per-call 记录和 ledger，
     * 利用 idempotency_key 的 ON CONFLICT DO NOTHING 防止重复扣费。
     *
     * @param runId  run id
     * @param userId run owner user id
     */
    public void refreshCosts(String runId, String userId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        AgentRun run = runMapper.findById(runId);
        String observabilityJson = "";
        if (run != null) {
            observabilityJson = observabilityService.loadObservabilityJson(runId, run.getSnapshotJson());
        }
        List<AgentObservabilityService.LlmTrace> traces = extractLlmTraces(observabilityJson);
        if (traces.isEmpty()) {
            rebuildSummary(runId, userId, OffsetDateTime.now(ZoneOffset.UTC));
            return;
        }

        List<AgentRunLlmCallCredit> existingRecords = llmCallCreditDao.listByRunId(runId);
        Map<String, AgentRunLlmCallCredit> effectiveByCallId = dedupByLatestAttempt(existingRecords);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (AgentObservabilityService.LlmTrace trace : traces) {
            if (trace == null || trace.getTraceId() == null || trace.getTraceId().isBlank()) {
                continue;
            }
            String endpoint = trace.getEndpoint();
            if (!adapterRegistry.supportsCostFetch(endpoint)) {
                continue;
            }
            String llmCallId = resolveLlmCallId(trace);
            if (hasActualCostSettled(effectiveByCallId.get(llmCallId))) {
                continue;
            }
            LlmCallBillingContext ctx = buildContext(runId, trace);
            CostSettlementQuote quote = adapterRegistry.quote(endpoint, ctx, ATTEMPT_REFRESH);
            String perCallStatus = resolvePerCallStatus(quote, ATTEMPT_REFRESH);
            if (!SETTLEMENT_STATUS_SETTLED.equals(perCallStatus)) {
                // refresh 只补实际拉到的 cost；没拉到的保持已有记录，不生成 attempt=3 MISSING 占位，
                // 避免后续 refresh 真正拿到 cost 时被 ON CONFLICT 挡住。
                continue;
            }
            AgentRunLlmCallCredit record = buildPerCallRecord(
                    runId, userId, llmCallId, trace, quote, perCallStatus, ATTEMPT_REFRESH, now);
            int inserted = llmCallCreditDao.insertIgnoreDuplicate(record);
            if (inserted <= 0) {
                continue;
            }
            if (quote.getCreditDelta() != null && quote.getCreditDelta().signum() > 0) {
                writeRefreshLedger(runId, userId, llmCallId, quote.getCreditDelta(), now);
            }
        }
        rebuildSummary(runId, userId, now);
    }

    private boolean hasActualCostSettled(AgentRunLlmCallCredit record) {
        if (record == null) {
            return false;
        }
        if (!SETTLEMENT_STATUS_SETTLED.equalsIgnoreCase(record.getSettlementStatus())) {
            return false;
        }
        return CostSource.OPENROUTER_ACTUAL.name().equals(record.getCostSource())
                && record.getCostAmount() != null
                && record.getCostAmount().compareTo(BigDecimal.ZERO) > 0;
    }

    private void writeRefreshLedger(String runId, String userId, String llmCallId,
                                    BigDecimal delta, OffsetDateTime now) {
        if (delta == null || delta.signum() <= 0) {
            return;
        }
        boolean isAdmin = isAdminUser(userId);
        Long userIdLong = parseUserId(userId);
        AgentCreditLedger ledger = new AgentCreditLedger();
        ledger.setLedgerId(UUID.randomUUID().toString().replace("-", ""));
        ledger.setUserId(userId == null ? "" : userId);
        ledger.setBizType(LEDGER_BIZ_REFRESH);
        ledger.setSourceType("AGENT_RUN");
        ledger.setSourceId(runId);
        ledger.setOperatorId("");
        ledger.setIdempotencyKey(runId + ":refresh:" + llmCallId);
        ledger.setReason("agent_run_refresh_settlement");
        ledger.setExt("{\"llmCallId\":\"" + escape(llmCallId) + "\",\"isAdmin\":" + isAdmin + "}");
        try {
            debitOperator.debitAndWriteLedger(ledger, delta, isAdmin, userIdLong);
        } catch (Exception e) {
            log.warn("Refresh ledger debit failed (likely idempotency conflict): runId={} callId={} err={}",
                    runId, llmCallId, e.getMessage());
        }
    }

    /**
     * 执行单次结算；attempt=1 立即结算，attempt=2 延迟重试。
     * 重复调用幂等：相同 (runId, attempt, llmCallId) 的 per-call 记录仅写一次；
     * 相同 (runId, attempt) 的 ledger 仅写一次。
     *
     * <h3>delayed retry 范围</h3>
     * attempt=2 只对 attempt=1 写过的 PENDING_RETRY 记录重新拉取 cost，
     * 不会重复走已 SETTLED 的 per-call。
     *
     * <h3>summary 幂等性</h3>
     * summary 不再按 attemptDelta 累加，而是每次从 per-call 表按 attempt 全量聚合
     * （immediate 取 attempt=1 的 SETTLED，delayed 取 attempt=2 的 SETTLED），
     * 避免 cancel + executor terminal + 异常重入 多次触发造成 total 膨胀。
     *
     * <h3>实际扣减</h3>
     * 普通用户在 attempt=1 + 终态时事务化扣减余额（userDao.decreaseCreditByUserIdDecimal
     * 带 GREATEST(0, ...) 兜底），写负数 delta 的 ledger（balanceBefore/After 来自
     * FOR UPDATE 锁后的真实值）；admin 用户只写审计 ledger，不扣减余额。
     */
    void settleOnce(String runId, String userId, int attempt) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        AgentRun run = runMapper.findById(runId);
        String observabilityJson = "";
        if (run != null) {
            observabilityJson = observabilityService.loadObservabilityJson(runId, run.getSnapshotJson());
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int newSettledCount = 0;
        int newPendingCount = 0;
        int newMissingCount = 0;
        BigDecimal newImmediateDelta = BigDecimal.ZERO;
        BigDecimal newDelayedDelta = BigDecimal.ZERO;
        List<AgentRunLlmCallCredit> newSettledImmediate = new ArrayList<>();
        List<AgentRunLlmCallCredit> newSettledDelayed = new ArrayList<>();

        if (attempt == ATTEMPT_IMMEDIATE) {
            // attempt=1：拉 observability 全量 traces，逐个 quote + 写 per-call
            List<AgentObservabilityService.LlmTrace> traces = extractLlmTraces(observabilityJson);
            for (AgentObservabilityService.LlmTrace trace : traces) {
                if (trace == null || trace.getTraceId() == null || trace.getTraceId().isBlank()) {
                    continue;
                }
                String llmCallId = resolveLlmCallId(trace);
                LlmCallBillingContext ctx = buildContext(runId, trace);
                CostSettlementQuote quote = adapterRegistry.quote(trace.getEndpoint(), ctx, ATTEMPT_IMMEDIATE);

                String perCallStatus = resolvePerCallStatus(quote, ATTEMPT_IMMEDIATE);
                AgentRunLlmCallCredit record = buildPerCallRecord(
                        runId, userId, llmCallId, trace, quote, perCallStatus, ATTEMPT_IMMEDIATE, now);
                int inserted = llmCallCreditDao.insertIgnoreDuplicate(record);
                if (inserted <= 0) {
                    // 重复 hook，per-call 已存在；不重复扣费也不计入本次 new
                    continue;
                }
                if (SETTLEMENT_STATUS_SETTLED.equals(perCallStatus)) {
                    newSettledCount++;
                    if (quote.getCreditDelta() != null && quote.getCreditDelta().signum() > 0) {
                        newImmediateDelta = newImmediateDelta.add(quote.getCreditDelta());
                    }
                    newSettledImmediate.add(record);
                } else if (SETTLEMENT_STATUS_PENDING_RETRY.equals(perCallStatus)) {
                    newPendingCount++;
                } else {
                    newMissingCount++;
                }
            }
        } else {
            // attempt=2：只重试 attempt=1 写过的 PENDING_RETRY 记录
            List<AgentRunLlmCallCredit> pending = listPendingRetryRecords(runId);
            for (AgentRunLlmCallCredit prior : pending) {
                LlmCallBillingContext ctx = buildContextFromPrior(runId, prior);
                CostSettlementQuote quote = adapterRegistry.quote(prior.getEndpointName(), ctx, ATTEMPT_DELAYED);

                String perCallStatus = resolvePerCallStatus(quote, ATTEMPT_DELAYED);
                AgentRunLlmCallCredit retryRecord = buildRetryRecord(
                        runId, userId, prior, quote, perCallStatus, now);
                int inserted = llmCallCreditDao.insertIgnoreDuplicate(retryRecord);
                if (inserted <= 0) {
                    continue;
                }
                if (SETTLEMENT_STATUS_SETTLED.equals(perCallStatus)) {
                    newSettledCount++;
                    if (quote.getCreditDelta() != null && quote.getCreditDelta().signum() > 0) {
                        newDelayedDelta = newDelayedDelta.add(quote.getCreditDelta());
                    }
                    newSettledDelayed.add(retryRecord);
                } else {
                    // attempt=2 不再 PENDING_RETRY，要么 SETTLED，要么 MISSING；
                    // {@link #resolvePerCallStatus} 已经在 attempt=DELAYED 时把 pending 收敛成 MISSING，
                    // 这里只需要计数即可，不会再有「先插 pending 再改 MISSING 后重复 insert」的二次写入。
                    newMissingCount++;
                }
            }
        }

        // 写 ledger（只有真正发生 SETTLED 的新记录才写）
        if (newImmediateDelta.signum() > 0) {
            writeSettlementLedger(runId, userId, ATTEMPT_IMMEDIATE, newImmediateDelta, newSettledImmediate, now);
        }
        if (newDelayedDelta.signum() > 0) {
            writeSettlementLedger(runId, userId, ATTEMPT_DELAYED, newDelayedDelta, newSettledDelayed, now);
        }

        // 重算 summary（从 per-call 表按 attempt 聚合，不依赖累加器）
        rebuildSummary(runId, userId, now);

        // attempt=1 时若有 PENDING_RETRY 则调度 attempt=2；否则不调度（避免无效延迟）
        if (attempt == ATTEMPT_IMMEDIATE && newPendingCount > 0) {
            scheduleDelayedRetry(runId, userId);
        }
    }

    private List<AgentRunLlmCallCredit> listPendingRetryRecords(String runId) {
        List<AgentRunLlmCallCredit> all = llmCallCreditDao.listByRunId(runId);
        List<AgentRunLlmCallCredit> pending = new ArrayList<>();
        for (AgentRunLlmCallCredit record : all) {
            if (record == null) {
                continue;
            }
            Integer attempt = record.getSettlementAttempt();
            String status = record.getSettlementStatus();
            if (attempt != null && attempt == ATTEMPT_IMMEDIATE
                    && SETTLEMENT_STATUS_PENDING_RETRY.equalsIgnoreCase(status)) {
                pending.add(record);
            }
        }
        return pending;
    }

    private void scheduleDelayedRetry(String runId, String userId) {
        try {
            creditSettlementScheduler.schedule(() -> {
                try {
                    settleOnce(runId, userId, ATTEMPT_DELAYED);
                } catch (Exception e) {
                    log.error("Delayed settlement failed: runId={} userId={}", runId, userId, e);
                }
            }, delayedRetrySeconds, TimeUnit.SECONDS);
            log.info("Scheduled delayed settlement retry: runId={} delaySec={}", runId, delayedRetrySeconds);
        } catch (Exception e) {
            log.warn("Failed to schedule delayed settlement retry: runId={} err={}", runId, e.getMessage());
        }
    }

    /**
     * 构建一条 settlement ledger 模板，并委托给 {@link AgentCreditDebitOperator}
     * 在独立事务里完成「幂等检查 + 扣减余额 + 写 ledger」。普通用户扣 balance，
     * admin 用户只审计。
     *
     * <p>注：之前把扣减+写 ledger 直接放在本类的 {@code @Transactional} 方法里，
     * 由 {@link #settleOnce} self-invocation 调用，Spring AOP 代理拦不到，事务实际不生效。
     * 现在抽到独立 @Service（{@link AgentCreditDebitOperator}）通过依赖注入调用，事务才会真正生效。
     */
    void writeSettlementLedger(String runId, String userId, int attempt, BigDecimal delta,
                               List<AgentRunLlmCallCredit> newSettledRecords, OffsetDateTime now) {
        if (delta == null || delta.signum() <= 0) {
            return;
        }
        String bizType = attempt == ATTEMPT_IMMEDIATE ? LEDGER_BIZ_IMMEDIATE : LEDGER_BIZ_DELAYED;
        String idempotencyKey = runId + ":" + (attempt == ATTEMPT_IMMEDIATE ? "immediate" : "delayed");
        boolean isAdmin = isAdminUser(userId);
        Long userIdLong = parseUserId(userId);

        AgentCreditLedger ledger = new AgentCreditLedger();
        ledger.setLedgerId(UUID.randomUUID().toString().replace("-", ""));
        ledger.setUserId(userId == null ? "" : userId);
        ledger.setBizType(bizType);
        ledger.setSourceType("AGENT_RUN");
        ledger.setSourceId(runId);
        ledger.setOperatorId("");
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setReason(attempt == ATTEMPT_IMMEDIATE
                ? "agent_run_immediate_settlement"
                : "agent_run_delayed_settlement");
        ledger.setExt("{\"llmCallCount\":" + newSettledRecords.size()
                + ",\"settlementAttempt\":" + attempt
                + ",\"isAdmin\":" + isAdmin + "}");
        try {
            debitOperator.debitAndWriteLedger(ledger, delta, isAdmin, userIdLong);
        } catch (Exception e) {
            log.warn("Ledger debit failed (likely idempotency conflict): runId={} attempt={} err={}",
                    runId, attempt, e.getMessage());
        }
    }

    /**
     * 重算 summary：从 per-call 表按 attempt 聚合 SETTLED 金额，
     * 而不是累加 attemptDelta。保证重复 hook / 异常重入 / cancel + executor
     * 双触发 都不会让 total 膨胀。
     *
     * <p>聚合规则：按 llmCallId 去重，每个 llmCallId 取最高 attempt 的记录作为「effective」状态：
     * <ul>
     *   <li>同一 llmCallId 既有 attempt=1 PENDING_RETRY 又有 attempt=2 SETTLED/MISSING：以 attempt=2 终态为准；</li>
     *   <li>只有 attempt=1 PENDING_RETRY：仍计入 pending；</li>
     *   <li>没有 llmCallId 的脏数据：跳过，不参与计数。</li>
     * </ul>
     * 这样 attempt=1 PENDING_RETRY 的「占位记录」不会污染 totalCallCount 或长期挂在 summary.PENDING_RETRY。
     */
    private void rebuildSummary(String runId, String userId, OffsetDateTime now) {
        List<AgentRunLlmCallCredit> records = llmCallCreditDao.listByRunId(runId);
        Map<String, AgentRunLlmCallCredit> effectiveByCallId = dedupByLatestAttempt(records);

        BigDecimal immediate = BigDecimal.ZERO;
        BigDecimal delayed = BigDecimal.ZERO;
        int immediateSettled = 0;
        int delayedSettled = 0;
        int pendingCount = 0;
        int missingCount = 0;

        for (AgentRunLlmCallCredit record : effectiveByCallId.values()) {
            if (record == null) {
                continue;
            }
            String status = record.getSettlementStatus();
            Integer attempt = record.getSettlementAttempt();
            BigDecimal delta = record.getCreditDelta() == null ? BigDecimal.ZERO : record.getCreditDelta();
            if (SETTLEMENT_STATUS_SETTLED.equalsIgnoreCase(status)) {
                if (attempt != null && attempt == ATTEMPT_IMMEDIATE) {
                    immediate = immediate.add(delta);
                    immediateSettled++;
                } else if (attempt != null && (attempt == ATTEMPT_DELAYED || attempt == ATTEMPT_REFRESH)) {
                    delayed = delayed.add(delta);
                    delayedSettled++;
                }
            } else if (SETTLEMENT_STATUS_PENDING_RETRY.equalsIgnoreCase(status)) {
                pendingCount++;
            } else if (SETTLEMENT_STATUS_MISSING.equalsIgnoreCase(status)) {
                missingCount++;
            }
        }
        BigDecimal total = immediate.add(delayed);
        int totalCallCount = effectiveByCallId.size();
        String summaryStatus;
        if (pendingCount > 0) {
            summaryStatus = SUMMARY_STATUS_PENDING_RETRY;
        } else if (missingCount > 0 && immediateSettled == 0 && delayedSettled == 0) {
            summaryStatus = SUMMARY_STATUS_MISSING;
        } else {
            summaryStatus = SUMMARY_STATUS_SETTLED;
        }

        AgentRunCreditSummary summary = new AgentRunCreditSummary();
        summary.setRunId(runId);
        summary.setUserId(userId == null ? "" : userId);
        summary.setTotalCreditConsumed(total);
        summary.setImmediateCreditConsumed(immediate);
        summary.setDelayedCreditConsumed(delayed);
        summary.setCurrency(DEFAULT_CURRENCY);
        summary.setSettlementStatus(summaryStatus);
        summary.setIdempotencyKey(runId + ":summary");
        summary.setExt("{\"immediateSettledCount\":" + immediateSettled
                + ",\"delayedSettledCount\":" + delayedSettled
                + ",\"pendingCount\":" + pendingCount
                + ",\"missingCount\":" + missingCount
                + ",\"totalCallCount\":" + totalCallCount + "}");
        summary.setLastSettlementAt(now);
        try {
            summaryDao.upsert(summary);
        } catch (Exception e) {
            log.warn("Summary upsert failed: runId={} err={}", runId, e.getMessage());
        }
    }

    /**
     * 按 llmCallId 去重：相同 callId 下取 attempt 更大的那条记录为有效记录，
     * 让 attempt=2 的终态（SETTLED / MISSING）覆盖 attempt=1 留下的 PENDING_RETRY 占位。
     * 跳过 llmCallId 为空的脏记录。
     */
    private Map<String, AgentRunLlmCallCredit> dedupByLatestAttempt(List<AgentRunLlmCallCredit> records) {
        Map<String, AgentRunLlmCallCredit> byCallId = new HashMap<>();
        if (records == null || records.isEmpty()) {
            return byCallId;
        }
        for (AgentRunLlmCallCredit record : records) {
            if (record == null || record.getLlmCallId() == null || record.getLlmCallId().isBlank()) {
                continue;
            }
            AgentRunLlmCallCredit existing = byCallId.get(record.getLlmCallId());
            if (existing == null) {
                byCallId.put(record.getLlmCallId(), record);
                continue;
            }
            int existingAttempt = existing.getSettlementAttempt() == null ? 0 : existing.getSettlementAttempt();
            int newAttempt = record.getSettlementAttempt() == null ? 0 : record.getSettlementAttempt();
            if (newAttempt > existingAttempt) {
                byCallId.put(record.getLlmCallId(), record);
            }
        }
        return byCallId;
    }

    private boolean isAdminUser(String userId) {
        Long userIdLong = parseUserId(userId);
        if (userIdLong == null) {
            return false;
        }
        User user = userDao.getUserById(userIdLong);
        return user != null && user.getUserType() != null && user.getUserType() == ADMIN_USER_TYPE;
    }

    private Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(userId.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private AgentRunLlmCallCredit buildPerCallRecord(String runId, String userId, String llmCallId,
                                                    AgentObservabilityService.LlmTrace trace,
                                                    CostSettlementQuote quote, String status,
                                                    int attempt, OffsetDateTime now) {
        AgentRunLlmCallCredit record = new AgentRunLlmCallCredit();
        record.setRecordId(UUID.randomUUID().toString().replace("-", ""));
        record.setRunId(runId);
        record.setUserId(userId == null ? "" : userId);
        record.setLlmCallId(llmCallId);
        record.setEndpointName(quote.getEndpoint() == null ? trace.getEndpoint() : quote.getEndpoint());
        record.setModelName(quote.getModel() == null ? trace.getModel() : quote.getModel());
        record.setCostSource(quote.getCostSource() == null ? "UNKNOWN" : quote.getCostSource().name());
        record.setCurrency(quote.getCurrency() == null ? DEFAULT_CURRENCY : quote.getCurrency());
        record.setCostAmount(quote.getCostAmount() == null ? BigDecimal.ZERO
                : quote.getCostAmount().setScale(8, RoundingMode.HALF_UP));
        record.setCreditDelta(quote.getCreditDelta() == null ? BigDecimal.ZERO
                : quote.getCreditDelta().setScale(6, RoundingMode.HALF_UP));
        record.setSettlementStatus(status);
        record.setSettlementAttempt(attempt);
        record.setReason(buildPerCallReason(quote, status, attempt));
        record.setIdempotencyKey(runId + ":attempt-" + attempt + ":" + llmCallId);
        record.setExt("{\"phase\":\"" + escape(trace.getPhase())
                + "\",\"todoId\":\"" + escape(trace.getTodoId()) + "\"}");
        return record;
    }

    private String buildPerCallReason(CostSettlementQuote quote, String status, int attempt) {
        if (SETTLEMENT_STATUS_PENDING_RETRY.equals(status)) {
            return "awaiting_delayed_cost_enrichment";
        }
        if (SETTLEMENT_STATUS_MISSING.equals(status)) {
            return "cost_unavailable_after_delayed_retry";
        }
        return "settlement_attempt_" + attempt + "_ok";
    }

    private String resolvePerCallStatus(CostSettlementQuote quote, int attempt) {
        if (quote == null) {
            return attempt == ATTEMPT_IMMEDIATE ? SETTLEMENT_STATUS_PENDING_RETRY : SETTLEMENT_STATUS_MISSING;
        }
        if (quote.isCostAvailable()) {
            return SETTLEMENT_STATUS_SETTLED;
        }
        if (quote.isNeedsDelayedRetry() && attempt == ATTEMPT_IMMEDIATE) {
            return SETTLEMENT_STATUS_PENDING_RETRY;
        }
        return SETTLEMENT_STATUS_MISSING;
    }

    private LlmCallBillingContext buildContext(String runId, AgentObservabilityService.LlmTrace trace) {
        return LlmCallBillingContext.builder()
                .runId(runId)
                .callId(trace.getTraceId())
                .endpoint(trace.getEndpoint())
                .model(trace.getModel())
                .generationId(trace.getGenerationId())
                .actualCostUsd(trace.getActualCost())
                .upstreamCostUsd(trace.getUpstreamCost())
                .hasError(trace.isHasError())
                .build();
    }

    private LlmCallBillingContext buildContextFromPrior(String runId, AgentRunLlmCallCredit prior) {
        return LlmCallBillingContext.builder()
                .runId(runId)
                .callId(prior.getLlmCallId())
                .endpoint(prior.getEndpointName())
                .model(prior.getModelName())
                .generationId(prior.getLlmCallId())
                .actualCostUsd(null)
                .hasError(false)
                .build();
    }

    private AgentRunLlmCallCredit buildRetryRecord(String runId, String userId,
                                                   AgentRunLlmCallCredit prior,
                                                   CostSettlementQuote quote, String status,
                                                   OffsetDateTime now) {
        AgentRunLlmCallCredit record = new AgentRunLlmCallCredit();
        record.setRecordId(UUID.randomUUID().toString().replace("-", ""));
        record.setRunId(runId);
        record.setUserId(userId == null ? "" : userId);
        record.setLlmCallId(prior.getLlmCallId());
        record.setEndpointName(quote.getEndpoint() == null ? prior.getEndpointName() : quote.getEndpoint());
        record.setModelName(quote.getModel() == null ? prior.getModelName() : quote.getModel());
        record.setCostSource(quote.getCostSource() == null
                ? (prior.getCostSource() == null ? "UNKNOWN" : prior.getCostSource())
                : quote.getCostSource().name());
        record.setCurrency(quote.getCurrency() == null
                ? (prior.getCurrency() == null ? DEFAULT_CURRENCY : prior.getCurrency())
                : quote.getCurrency());
        record.setCostAmount(quote.getCostAmount() == null ? BigDecimal.ZERO
                : quote.getCostAmount().setScale(8, RoundingMode.HALF_UP));
        record.setCreditDelta(quote.getCreditDelta() == null ? BigDecimal.ZERO
                : quote.getCreditDelta().setScale(6, RoundingMode.HALF_UP));
        record.setSettlementStatus(status);
        record.setSettlementAttempt(ATTEMPT_DELAYED);
        record.setReason(buildPerCallReason(quote, status, ATTEMPT_DELAYED));
        record.setIdempotencyKey(runId + ":attempt-" + ATTEMPT_DELAYED + ":" + prior.getLlmCallId());
        record.setExt(prior.getExt() == null ? "{}" : prior.getExt());
        return record;
    }

    private String resolveLlmCallId(AgentObservabilityService.LlmTrace trace) {
        if (trace.getGenerationId() != null && !trace.getGenerationId().isBlank()) {
            return trace.getGenerationId();
        }
        return trace.getTraceId();
    }

    private List<AgentObservabilityService.LlmTrace> extractLlmTraces(String observabilityJson) {
        if (observabilityJson == null || observabilityJson.isBlank()) {
            return List.of();
        }
        try {
            Map<?, ?> root = objectMapper.readValue(observabilityJson, Map.class);
            Object diagnostics = root.get("diagnostics");
            if (!(diagnostics instanceof Map<?, ?> diagMap)) {
                return List.of();
            }
            Object llmTraces = diagMap.get("llmTraces");
            if (!(llmTraces instanceof List<?> list)) {
                return List.of();
            }
            List<AgentObservabilityService.LlmTrace> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                AgentObservabilityService.LlmTrace trace = objectMapper.convertValue(item,
                        AgentObservabilityService.LlmTrace.class);
                if (trace != null) {
                    result.add(trace);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to extract LlmTraces from observability json: {}", e.getMessage());
            return List.of();
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
