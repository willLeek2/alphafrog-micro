package world.willfrog.alphafrogmicro.frontend.service.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunResultMessage;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunResultRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunResultCacheServiceTest {

    private AgentDubboService agentDubboService;
    private AgentRunResultCacheService cacheService;

    @BeforeEach
    void setUp() {
        agentDubboService = mock(AgentDubboService.class);
        cacheService = new AgentRunResultCacheService();
        ReflectionTestUtils.setField(cacheService, "agentDubboService", agentDubboService);
        ReflectionTestUtils.setField(cacheService, "cacheTtlSeconds", 30L);
        when(agentDubboService.getResult(any(GetAgentRunResultRequest.class))).thenReturn(
                AgentRunResultMessage.newBuilder()
                        .setId("run-1")
                        .setStatus("COMPLETED")
                        .setObservabilityJson("{\"summary\":{}}")
                        .build()
        );
    }

    @Test
    void getRunResult_shouldCacheWithinTtl() {
        AgentRunResultMessage first = cacheService.getRunResult("u1", "run-1", false);
        AgentRunResultMessage second = cacheService.getRunResult("u1", "run-1", false);

        assertEquals(first, second);
        verify(agentDubboService, times(1)).getResult(any(GetAgentRunResultRequest.class));
    }

    @Test
    void getRunResult_shouldUseDifferentKeysPerUserOrRun() {
        cacheService.getRunResult("u1", "run-1", false);
        cacheService.getRunResult("u2", "run-1", false);
        cacheService.getRunResult("u1", "run-2", false);

        verify(agentDubboService, times(3)).getResult(any(GetAgentRunResultRequest.class));
    }

    @Test
    void getRunResult_shouldIsolateAdminAndNonAdminEntriesForSameRun() {
        cacheService.getRunResult("u1", "run-1", true);
        cacheService.getRunResult("u1", "run-1", false);
        cacheService.getRunResult("u1", "run-1", true);

        var captor = org.mockito.ArgumentCaptor.forClass(GetAgentRunResultRequest.class);
        verify(agentDubboService, times(2)).getResult(captor.capture());
        assertEquals(true, captor.getAllValues().get(0).getIsAdmin());
        assertEquals(false, captor.getAllValues().get(1).getIsAdmin());
    }
}
