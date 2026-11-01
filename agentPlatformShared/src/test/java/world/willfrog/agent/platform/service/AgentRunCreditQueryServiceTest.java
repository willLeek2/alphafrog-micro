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
