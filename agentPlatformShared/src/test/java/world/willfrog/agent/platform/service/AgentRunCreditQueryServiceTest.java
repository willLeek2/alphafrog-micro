package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunCreditSummaryDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunLlmCallCreditDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunCreditSummary;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunLlmCallCredit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunCreditQueryServiceTest {

    private final AgentRunCreditSummaryDao summaryDao = mock(AgentRunCreditSummaryDao.class);
    private final AgentRunLlmCallCreditDao callCreditDao = mock(AgentRunLlmCallCreditDao.class);
    private final AgentRunCreditQueryService service = new AgentRunCreditQueryService(
            summaryDao, callCreditDao, new ObjectMapper());

    @Test
    void buildProjectsSummaryAndPerCallRecords() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");

        AgentRunCreditSummary summary = new AgentRunCreditSummary();
        summary.setRunId("run-1");
        summary.setUserId("user-1");
        summary.setTotalCreditConsumed(new BigDecimal("1.750000"));
        summary.setCurrency("USD");
        summary.setUpdatedAt(OffsetDateTime.of(2026, 6, 12, 8, 0, 0, 0, ZoneOffset.UTC));
        summary.setLastSettlementAt(OffsetDateTime.of(2026, 6, 12, 8, 1, 0, 0, ZoneOffset.UTC));

        when(summaryDao.findByRunId("run-1")).thenReturn(summary);
        when(callCreditDao.listByRunId("run-1")).thenReturn(List.of(
                call("call-1", "SETTLED", 1, "0.250000", "{\"phase\":\"planning\",\"todoId\":\"todo_1\"}"),
                call("call-2", "SETTLED", 2, "1.500000", "{\"phase\":\"execution\"}"),
                call("call-3", "PENDING_RETRY", 1, "0", "{}"),
                call("call-4", "MISSING", 2, "0", "{}")
        ));

        var response = service.build(run);

        assertEquals("run-1", response.getRunId());
        assertEquals("user-1", response.getOwnerUserId());
        assertEquals("1.75", response.getTotalCredits());
        assertEquals("USD", response.getCurrency());
        assertEquals(4, response.getRecordsCount());
        assertEquals("planning", response.getRecords(0).getPhase());
        assertEquals("todo_1", response.getRecords(0).getTodoId());
        assertEquals(1, response.getSummary().getImmediateCount());
        assertEquals(1, response.getSummary().getDelayedCount());
        assertEquals(1, response.getSummary().getPendingCount());
        assertEquals(1, response.getSummary().getMissingCount());
        assertEquals(4, response.getSummary().getTotalCallCount());
        assertEquals("1.75", response.getSummary().getTotalCredits());
    }

    @Test
    void summaryCountsDedupByLlmCallIdSoAttempt2OverridesAttempt1Pending() {
        // 同一个 llmCallId 既有 attempt=1 PENDING_RETRY 又有 attempt=2 SETTLED，
        // summary 计数应该按最高 attempt 终态汇总：1 个 delayed，0 个 pending，totalCalls=1。
        // 但 records 列表仍然保留两条原始记录作为审计时间线。
        AgentRun run = new AgentRun();
        run.setId("run-dedup");
        run.setUserId("user-1");

        when(summaryDao.findByRunId("run-dedup")).thenReturn(null);
        when(callCreditDao.listByRunId("run-dedup")).thenReturn(List.of(
                callFor("run-dedup", "call-A", "PENDING_RETRY", 1, "0", "{}"),
                callFor("run-dedup", "call-A", "SETTLED", 2, "0.500000", "{}")
        ));

        var response = service.build(run);

        // records: 审计时间线保留 2 条
        assertEquals(2, response.getRecordsCount());
        // summary: 按 llmCallId 去重后只剩 attempt=2 终态
        assertEquals(0, response.getSummary().getImmediateCount());
        assertEquals(1, response.getSummary().getDelayedCount());
        assertEquals(0, response.getSummary().getPendingCount());
        assertEquals(0, response.getSummary().getMissingCount());
        assertEquals(1, response.getSummary().getTotalCallCount());
        // sumCreditDelta fallback 也只用去重后的 0.5
        assertEquals("0.5", response.getTotalCredits());
    }

    private AgentRunLlmCallCredit call(String callId, String status, int attempt, String delta, String ext) {
        return callFor("run-1", callId, status, attempt, delta, ext);
    }

    private AgentRunLlmCallCredit callFor(String runId, String callId, String status, int attempt,
                                          String delta, String ext) {
        AgentRunLlmCallCredit record = new AgentRunLlmCallCredit();
        record.setRunId(runId);
        record.setUserId("user-1");
        record.setLlmCallId(callId);
        record.setEndpointName("openrouter");
        record.setModelName("moonshotai/kimi-k2.6");
        record.setCostSource("OPENROUTER");
        record.setCurrency("USD");
        record.setCostAmount(new BigDecimal(delta));
        record.setCreditDelta(new BigDecimal(delta));
        record.setSettlementStatus(status);
        record.setSettlementAttempt(attempt);
        record.setReason(status.toLowerCase());
        record.setExt(ext);
        return record;
    }
}
