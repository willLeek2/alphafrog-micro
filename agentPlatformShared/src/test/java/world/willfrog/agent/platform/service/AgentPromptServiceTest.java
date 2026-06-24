package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AgentPromptService} dataFreshness snapshot behavior.
 */
@ExtendWith(MockitoExtension.class)
class AgentPromptServiceTest {

    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private AgentLlmProperties properties;

    private AgentPromptService service;
    private Method composeSystemPrompt;

    @BeforeEach
    void setUp() throws Exception {
        AgentLlmProperties.Prompts prompts = new AgentLlmProperties.Prompts();
        prompts.setAgentRunSystemPrompt("你是专业金融分析代理（测试）");
        lenient().when(properties.getPrompts()).thenReturn(prompts);
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        lenient().when(properties.getRuntime()).thenReturn(runtime);
        lenient().when(localConfigLoader.current()).thenReturn(Optional.empty());

        service = new AgentPromptService(properties, localConfigLoader);
        composeSystemPrompt = AgentPromptService.class.getDeclaredMethod("composeSystemPrompt", String.class);
        composeSystemPrompt.setAccessible(true);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void shouldUseAgentContextSnapshotWhenSet() throws Exception {
        // snapshot in AgentContext — live config should NOT be consulted
        AgentLlmProperties.DataFreshness snap = freshness("2020-01-01", "2026-06-24-snap", "2026-06-24", "frozen");
        AgentContext.setDataFreshness(snap);

        // properties.getDataFreshness() is NOT stubbed → if code reaches it, test correctly fails
        String composed = (String) composeSystemPrompt.invoke(service, "阶段指令");
        assertTrue(composed.contains("2026-06-24-snap"),
                "should use frozen snapshot endDate, got: " + composed);
    }

    @Test
    void shouldFallbackToLiveConfigWhenAgentContextNull() throws Exception {
        AgentContext.setDataFreshness(null);

        when(properties.getDataFreshness()).thenReturn(
                freshness("2020-01-01", "2026-12-31-fallback", "2026-12-31", "live"));

        String composed = (String) composeSystemPrompt.invoke(service, "阶段指令");
        assertTrue(composed.contains("2026-12-31-fallback"),
                "should fallback to live config endDate, got: " + composed);
    }

    @Test
    void shouldNotInjectFreshnessParagraphWhenNoConfigAtAll() throws Exception {
        AgentContext.setDataFreshness(null);
        when(properties.getDataFreshness()).thenReturn(null);

        String composed = (String) composeSystemPrompt.invoke(service, "阶段指令");
        // must still contain the global prompt even without data freshness
        assertTrue(composed.contains("专业金融分析代理"),
                "should still contain global instructions, got: " + composed);
    }

    @Test
    void snapshotDataFreshness_shouldMergeLocalOverridesBase() {
        AgentLlmProperties.DataFreshness base = freshness("2018-01-01", "2025-12-31-base", "2025-12-31", "base");
        AgentLlmProperties.DataFreshness local = freshness(null, "2026-06-24-local", null, null);
        AgentLlmProperties full = new AgentLlmProperties();
        full.setDataFreshness(local);

        when(properties.getDataFreshness()).thenReturn(base);
        when(localConfigLoader.current()).thenReturn(Optional.of(full));

        AgentLlmProperties.DataFreshness snap = service.snapshotDataFreshness();
        assertNotNull(snap);
        assertEquals("2026-06-24-local", snap.getEndDate()); // from local (Nacos)
        assertEquals("2018-01-01", snap.getStartDate()); // from base (static)
    }

    private static AgentLlmProperties.DataFreshness freshness(String start, String end, String asOf, String desc) {
        AgentLlmProperties.DataFreshness f = new AgentLlmProperties.DataFreshness();
        f.setStartDate(start);
        f.setEndDate(end);
        f.setAsOfDate(asOf);
        f.setDescription(desc);
        return f;
    }
}
