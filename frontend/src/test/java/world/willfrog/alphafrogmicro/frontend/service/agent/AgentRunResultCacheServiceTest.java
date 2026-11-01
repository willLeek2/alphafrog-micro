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



}
