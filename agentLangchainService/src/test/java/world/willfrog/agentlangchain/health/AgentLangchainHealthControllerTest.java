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
import world.willfrog.agentlangchain.control.dualpool.DualPoolDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRecoveryDispatcher;
import world.willfrog.agentlangchain.control.dualpool.DualPoolSchedulerSettings;
import world.willfrog.agentlangchain.control.dualpool.FrozenEffectiveSettings;
import world.willfrog.agentlangchain.tooljob.WaitMemberResultReceiver;
import world.willfrog.agent.platform.capacity.SchedulerBackpressureProbe;

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

    @MockBean
    private DualPoolDispatcher dualPoolDispatcher;

    @MockBean
    private SchedulerBackpressureProbe schedulerBackpressureProbe;

    // 下面这几个是 /scheduler 那棵树里的读数来源：控制器少一个都建不出来，
    // 所以这里的替身要与构造器的入参一一对上。
    @MockBean
    private DualPoolRecoveryDispatcher dualPoolRecoveryDispatcher;

    @MockBean
    private DualPoolSchedulerSettings dualPoolSchedulerSettings;

    @MockBean
    private FrozenEffectiveSettings frozenEffectiveSettings;

    @MockBean
    private WaitMemberResultReceiver waitMemberResultReceiver;

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
        when(dualPoolDispatcher.snapshot()).thenReturn(Map.of());
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
