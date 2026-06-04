package world.willfrog.agentlangchain.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.orchestration.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.orchestration.LangchainRunConcurrencyScheduler;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentLangchainHealthController.class)
class AgentLangchainHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LangchainRunConcurrencyScheduler concurrencyScheduler;

    @MockBean
    private LangchainToolConcurrencyThrottle toolThrottle;

    @MockBean
    private LangchainServiceProperties properties;

    @MockBean
    private AgentLangchainOrchestrator orchestrator;

    @Test
    void healthReportsOk() throws Exception {
        LangchainServiceProperties.Provider provider = new LangchainServiceProperties.Provider();
        when(properties.getProvider()).thenReturn(provider);

        mockMvc.perform(get("/agent-langchain/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void schedulerReturnsSnapshot() throws Exception {
        when(concurrencyScheduler.schedulerSnapshot()).thenReturn(Map.of(
                "running", 3,
                "queued", 5,
                "rejectedTotal", 1L,
                "corePoolSize", 50,
                "maxPoolSize", 50,
                "queueCapacity", 200,
                "hardCorePoolSize", 100,
                "hardMaxPoolSize", 100,
                "hardQueueCapacity", 1000,
                "oldestQueuedAgeMs", 45000L
        ));

        mockMvc.perform(get("/agent-langchain/scheduler"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running", is(3)))
                .andExpect(jsonPath("$.queued", is(5)))
                .andExpect(jsonPath("$.rejectedTotal", is(1)))
                .andExpect(jsonPath("$.corePoolSize", is(50)))
                .andExpect(jsonPath("$.oldestQueuedAgeMs", is(45000)))
                .andExpect(jsonPath("$.hardCorePoolSize", is(100)));
    }

    @Test
    void toolThrottleReturnsMetrics() throws Exception {
        when(toolThrottle.throttleMetrics()).thenReturn(Map.of(
                "enabled", false,
                "maxPermits", 20,
                "availablePermits", 20,
                "queueLength", 0
        ));

        mockMvc.perform(get("/agent-langchain/tool-throttle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.maxPermits", is(20)))
                .andExpect(jsonPath("$.availablePermits", is(20)))
                .andExpect(jsonPath("$.queueLength", is(0)));
    }
}
