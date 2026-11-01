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
