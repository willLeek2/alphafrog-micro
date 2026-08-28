package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.credit.CostSettlementQuote;
import world.willfrog.agent.platform.credit.CostSource;
import world.willfrog.agent.platform.credit.EndpointCostAdapterRegistry;
import world.willfrog.agent.platform.credit.LlmCallBillingContext;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunCreditSummaryDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunLlmCallCreditDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunCreditSummary;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunLlmCallCredit;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成式单测：聚焦「immediate PENDING_RETRY -> delayed SETTLED 后 summary/query 不再 pending、
 * 不双倍计数」这条主轴，确保 dedup-by-llmCallId 在两阶段结算中真实生效。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunCreditSettlementServiceDedupTest {

    @Mock
    private AgentRunObservabilityService observabilityService;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private EndpointCostAdapterRegistry adapterRegistry;
    @Mock
    private AgentRunLlmCallCreditDao llmCallCreditDao;
    @Mock
    private AgentRunCreditSummaryDao summaryDao;
    @Mock
    private UserDao userDao;
    @Mock
    private AgentCreditDebitOperator debitOperator;
    @Mock
    private Executor creditSettlementExecutor;
    @Mock
    private ScheduledExecutorService creditSettlementScheduler;

    private AgentRunCreditSettlementService service;
    private final List<AgentRunLlmCallCredit> persisted = new ArrayList<>();

    private static final String RUN_ID = "run-dedup";
    private static final String USER_ID = "42";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AgentRunCreditSettlementService(
                observabilityService, runMapper, adapterRegistry,
                llmCallCreditDao, summaryDao, userDao, debitOperator, objectMapper,
                creditSettlementExecutor, creditSettlementScheduler);

        // 让 llmCallCreditDao 在测试中维持「已写入记录」的状态：
        //   insertIgnoreDuplicate 推入 persisted；listByRunId 返回 persisted 快照。
        lenient().when(llmCallCreditDao.insertIgnoreDuplicate(any()))
                .thenAnswer(invocation -> {
                    AgentRunLlmCallCredit record = invocation.getArgument(0);
                    persisted.add(record);
                    return 1;
                });
        lenient().when(llmCallCreditDao.listByRunId(anyString()))
                .thenAnswer(invocation -> new ArrayList<>(persisted));

        // run/observability 准备：两条 trace，分别对应 call-stable / call-pending。
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId(USER_ID);
        run.setSnapshotJson("{}");
        lenient().when(runMapper.findById(RUN_ID)).thenReturn(run);
        lenient().when(observabilityService.loadObservabilityJson(eq(RUN_ID), anyString()))
                .thenReturn(twoTraceObservabilityJson());

        // 普通用户：非 admin（不会走 admin 分支）
        User user = new User();
        user.setUserId(42L);
        user.setUserType(0);
        user.setCredit(new BigDecimal("100.000000"));
        lenient().when(userDao.getUserById(42L)).thenReturn(user);
    }

    @Test
    void delayedSettledOverridesImmediatePendingInSummary() {
        // attempt=1：call-stable -> SETTLED 0.5；call-pending -> PENDING_RETRY 0
        when(adapterRegistry.quote(anyString(), argMatches("call-stable"), eq(1)))
                .thenReturn(settled("call-stable", "0.500000"));
        when(adapterRegistry.quote(anyString(), argMatches("call-pending"), eq(1)))
                .thenReturn(pendingRetry("call-pending"));
        // attempt=2：call-pending 这次能拿到 cost -> SETTLED 0.3
        when(adapterRegistry.quote(anyString(), argMatches("call-pending"), eq(2)))
                .thenReturn(settled("call-pending", "0.300000"));

        // ---- Phase 1: immediate ----
        service.settleOnce(RUN_ID, USER_ID, 1);

        AgentRunCreditSummary afterImmediate = captureLatestSummary();
        assertEquals("PENDING_RETRY", afterImmediate.getSettlementStatus(),
                "immediate 阶段有 1 个 pending，summary 应该是 PENDING_RETRY");
        assertEquals(0, new BigDecimal("0.500000").compareTo(afterImmediate.getTotalCreditConsumed()));
        assertEquals(2, persisted.size(), "attempt=1 写了 2 条 per-call 记录");

        // ---- Phase 2: delayed ----
        service.settleOnce(RUN_ID, USER_ID, 2);

        // 累计写了 3 条原始 per-call（attempt=1 SETTLED + attempt=1 PENDING + attempt=2 SETTLED），
        // 但 summary 必须按 llmCallId 去重：totalCallCount=2，pending=0，settled=2。
        assertEquals(3, persisted.size(), "attempt=2 又新增了 call-pending 的 attempt=2 记录");

        AgentRunCreditSummary afterDelayed = captureLatestSummary();
        assertEquals("SETTLED", afterDelayed.getSettlementStatus(),
                "delayed 阶段所有 pending 都终结后，summary 必须 SETTLED 而非 PENDING_RETRY");
        assertEquals(0, new BigDecimal("0.800000").compareTo(afterDelayed.getTotalCreditConsumed()),
                "totalCreditConsumed = immediate(0.5) + delayed(0.3) = 0.8");
        assertEquals(0, new BigDecimal("0.500000").compareTo(afterDelayed.getImmediateCreditConsumed()));
        assertEquals(0, new BigDecimal("0.300000").compareTo(afterDelayed.getDelayedCreditConsumed()));
        String ext = afterDelayed.getExt();
        assertTrue(ext.contains("\"totalCallCount\":2"),
                "summary.ext 必须按 llmCallId 去重后为 2，而不是 3：" + ext);
        assertTrue(ext.contains("\"pendingCount\":0"),
                "去重后没有 pending 记录：" + ext);
        assertTrue(ext.contains("\"immediateSettledCount\":1"));
        assertTrue(ext.contains("\"delayedSettledCount\":1"));

        // ledger 写入路径：debit operator 应被调用两次（immediate / delayed），各对应一笔 SETTLED 金额。
        verify(debitOperator).debitAndWriteLedger(
                argLedgerWithIdem(RUN_ID + ":immediate"),
                eq(new BigDecimal("0.500000")), eq(false), eq(42L));
        verify(debitOperator).debitAndWriteLedger(
                argLedgerWithIdem(RUN_ID + ":delayed"),
                eq(new BigDecimal("0.300000")), eq(false), eq(42L));
    }

    @Test
    void repeatedRefreshAfterInitialMissingEventuallySettles() {
        lenient().when(adapterRegistry.supportsCostFetch(anyString())).thenReturn(true);

        // attempt=1：call-stable SETTLED；call-pending MISSING（retry 也拿不到 cost）
        when(adapterRegistry.quote(anyString(), argMatches("call-stable"), eq(1)))
                .thenReturn(settled("call-stable", "0.500000"));
        when(adapterRegistry.quote(anyString(), argMatches("call-pending"), eq(1)))
                .thenReturn(missingRetryExhausted("call-pending"));

        // 第一次 refresh：call-pending 仍 missing -> 不写 attempt=3 占位
        // 第二次 refresh：call-pending 终于拿到 cost -> 插入 attempt=3 SETTLED
        when(adapterRegistry.quote(anyString(), argMatches("call-pending"), eq(3)))
                .thenReturn(missingRetryExhausted("call-pending"),
                        settled("call-pending", "0.200000"));

        service.settleOnce(RUN_ID, USER_ID, 1);
        assertEquals(2, persisted.size(), "attempt=1 写 2 条");

        service.refreshCosts(RUN_ID, USER_ID);
        assertEquals(2, persisted.size(), "第一次 refresh missing 不新增记录");

        service.refreshCosts(RUN_ID, USER_ID);
        assertEquals(3, persisted.size(), "第二次 refresh 新增 attempt=3 SETTLED");

        AgentRunCreditSummary summary = captureLatestSummary();
        assertEquals("SETTLED", summary.getSettlementStatus());
        assertEquals(0, new BigDecimal("0.700000").compareTo(summary.getTotalCreditConsumed()));

        verify(debitOperator).debitAndWriteLedger(
                argLedgerWithIdem(RUN_ID + ":refresh:call-pending"),
                eq(new BigDecimal("0.200000")), eq(false), eq(42L));
    }

    @Test
    void refreshCostsFetchesMissingCallsAndWritesPerCallLedger() {
        lenient().when(adapterRegistry.supportsCostFetch(anyString())).thenReturn(true);

        // attempt=1：call-stable 已 SETTLED；call-pending 未拿到 cost -> MISSING
        when(adapterRegistry.quote(anyString(), argMatches("call-stable"), eq(1)))
                .thenReturn(settled("call-stable", "0.500000"));
        when(adapterRegistry.quote(anyString(), argMatches("call-pending"), eq(1)))
                .thenReturn(missingRetryExhausted("call-pending"));
        // attempt=3 refresh：call-pending 这次拿到实际 cost
        when(adapterRegistry.quote(anyString(), argMatches("call-pending"), eq(3)))
                .thenReturn(settled("call-pending", "0.200000"));

        service.settleOnce(RUN_ID, USER_ID, 1);
        assertEquals(2, persisted.size(), "attempt=1 写 2 条");

        service.refreshCosts(RUN_ID, USER_ID);

        assertEquals(3, persisted.size(), "refresh 新增 call-pending 的 attempt=3 记录");
        AgentRunLlmCallCredit refreshRecord = persisted.stream()
                .filter(r -> r.getLlmCallId().equals("call-pending") && r.getSettlementAttempt() == 3)
                .findFirst().orElseThrow();
        assertEquals("SETTLED", refreshRecord.getSettlementStatus());
        assertEquals(0, new BigDecimal("0.200000").compareTo(refreshRecord.getCreditDelta()));

        verify(debitOperator).debitAndWriteLedger(
                argLedgerWithIdem(RUN_ID + ":refresh:call-pending"),
                eq(new BigDecimal("0.200000")), eq(false), eq(42L));

        AgentRunCreditSummary summary = captureLatestSummary();
        assertEquals("SETTLED", summary.getSettlementStatus());
        assertEquals(0, new BigDecimal("0.700000").compareTo(summary.getTotalCreditConsumed()));
    }

    private CostSettlementQuote missingRetryExhausted(String callId) {
        return CostSettlementQuote.builder()
                .runId(RUN_ID)
                .callId(callId)
                .endpoint("openrouter")
                .model("moonshotai/kimi-k2.6")
                .costSource(CostSource.OPENROUTER_ACTUAL)
                .currency("USD")
                .costAmount(BigDecimal.ZERO)
                .creditDelta(BigDecimal.ZERO)
                .costAvailable(false)
                .needsDelayedRetry(false)
                .settlementAttempt(1)
                .build();
    }

    private AgentRunCreditSummary captureLatestSummary() {
        ArgumentCaptor<AgentRunCreditSummary> captor = ArgumentCaptor.forClass(AgentRunCreditSummary.class);
        verify(summaryDao, atLeastOnce()).upsert(captor.capture());
        List<AgentRunCreditSummary> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private CostSettlementQuote settled(String callId, String creditDelta) {
        return CostSettlementQuote.builder()
                .runId(RUN_ID)
                .callId(callId)
                .endpoint("openrouter")
                .model("moonshotai/kimi-k2.6")
                .costSource(CostSource.OPENROUTER_ACTUAL)
                .currency("USD")
                .costAmount(new BigDecimal(creditDelta))
                .creditDelta(new BigDecimal(creditDelta))
                .costAvailable(true)
                .needsDelayedRetry(false)
                .settlementAttempt(1)
                .build();
    }

    private CostSettlementQuote pendingRetry(String callId) {
        return CostSettlementQuote.builder()
                .runId(RUN_ID)
                .callId(callId)
                .endpoint("openrouter")
                .model("moonshotai/kimi-k2.6")
                .costSource(CostSource.OPENROUTER_ACTUAL)
                .currency("USD")
                .costAmount(BigDecimal.ZERO)
                .creditDelta(BigDecimal.ZERO)
                .costAvailable(false)
                .needsDelayedRetry(true)
                .settlementAttempt(1)
                .build();
    }

    private String twoTraceObservabilityJson() {
        // observability.diagnostics.llmTraces[] 是 settlement 读取入口的固定结构。
        // 让 traceId == generationId，方便 LlmCallBillingContext.callId 与 per-call.llmCallId 都一致。
        return "{\"diagnostics\":{\"llmTraces\":["
                + "{\"traceId\":\"call-stable\",\"generationId\":\"call-stable\","
                + "\"endpoint\":\"openrouter\",\"model\":\"moonshotai/kimi-k2.6\",\"phase\":\"planning\"},"
                + "{\"traceId\":\"call-pending\",\"generationId\":\"call-pending\","
                + "\"endpoint\":\"openrouter\",\"model\":\"moonshotai/kimi-k2.6\",\"phase\":\"execution\"}"
                + "]}}";
    }

    private LlmCallBillingContext argMatches(String callId) {
        return org.mockito.ArgumentMatchers.argThat(ctx ->
                ctx != null && callId.equals(ctx.getCallId()));
    }

    private static world.willfrog.alphafrogmicro.common.pojo.agent.AgentCreditLedger argLedgerWithIdem(String idem) {
        return org.mockito.ArgumentMatchers.argThat(ledger ->
                ledger != null && idem.equals(ledger.getIdempotencyKey()));
    }
}
