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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D25 G7：独立钉住 tool-throttle 观测的 {@code scope=per-node}，
 * 与 ccmax G4 的 scheduler instanceId HTTP contract 零 overlap
 *（故意不改 {@link AgentLangchainHealthControllerTest}）。
 */
@WebMvcTest(AgentLangchainHealthController.class)
class AgentLangchainToolThrottleScopeControllerTest {

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
    void toolThrottleExposesStablePerNodeScope() throws Exception {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("scope", "per-node");
        metrics.put("enabled", false);
        metrics.put("maxPermits", 20);
        metrics.put("availablePermits", 20);
        metrics.put("queueLength", 0);
        when(toolThrottle.throttleMetrics()).thenReturn(metrics);

        mockMvc.perform(get("/agent-langchain/tool-throttle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope", is("per-node")))
                .andExpect(jsonPath("$.enabled", is(false)))
                .andExpect(jsonPath("$.maxPermits", is(20)));
    }
}
