package world.willfrog.agentlangchain.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import world.willfrog.agentlangchain.config.LangchainServiceProperties;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.control.AgentLangchainOrchestrator;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static world.willfrog.agentlangchain.control.AgentLangchainOrchestrator.LINEAR_PIPELINE_READY;
import static world.willfrog.agentlangchain.control.AgentLangchainOrchestrator.LINEAR_PIPELINE_UNAVAILABLE;
import static world.willfrog.agentlangchain.control.AgentLangchainOrchestrator.PROVIDER_DISABLED;

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
    void healthReportsProviderDisabledWithoutReadinessAlert() throws Exception {
        LangchainServiceProperties.Provider provider = new LangchainServiceProperties.Provider();
        when(properties.getProvider()).thenReturn(provider);
        when(orchestrator.orchestrationStatus(false)).thenReturn(PROVIDER_DISABLED);

        mockMvc.perform(get("/agent-langchain/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.version", is("UNKNOWN")))
                .andExpect(jsonPath("$.providerEnabled", is(false)))
                .andExpect(jsonPath("$.orchestrationStatus", is(PROVIDER_DISABLED)));
    }

    @Test
    void healthReportsReadyPipelineWhenProviderEnabled() throws Exception {
        LangchainServiceProperties.Provider provider = new LangchainServiceProperties.Provider();
        provider.setEnabled(true);
        when(properties.getProvider()).thenReturn(provider);
        when(orchestrator.orchestrationStatus(true)).thenReturn(LINEAR_PIPELINE_READY);

        mockMvc.perform(get("/agent-langchain/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.orchestrationStatus", is(LINEAR_PIPELINE_READY)));
    }

    @Test
    void healthReportsUnavailablePipelineWhenProviderEnabled() throws Exception {
        LangchainServiceProperties.Provider provider = new LangchainServiceProperties.Provider();
        provider.setEnabled(true);
        when(properties.getProvider()).thenReturn(provider);
        when(orchestrator.orchestrationStatus(true)).thenReturn(LINEAR_PIPELINE_UNAVAILABLE);

        mockMvc.perform(get("/agent-langchain/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.orchestrationStatus", is(LINEAR_PIPELINE_UNAVAILABLE)));
    }

    @Test
    void schedulerReturnsSnapshot() throws Exception {
        when(concurrencyScheduler.schedulerSnapshot()).thenReturn(Map.ofEntries(
                Map.entry("instanceId", "test-app@host-1@123"),
                Map.entry("running", 3),
                Map.entry("queued", 5),
                Map.entry("rejectedTotal", 1L),
                Map.entry("corePoolSize", 50),
                Map.entry("maxPoolSize", 50),
                Map.entry("queueCapacity", 200),
                Map.entry("hardCorePoolSize", 100),
                Map.entry("hardMaxPoolSize", 100),
                Map.entry("hardQueueCapacity", 1000),
                Map.entry("oldestQueuedAgeMs", 45000L)
        ));

        mockMvc.perform(get("/agent-langchain/scheduler"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId", is("test-app@host-1@123")))
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
