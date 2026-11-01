package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.prompt.PromptRunSelection;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.lang.reflect.Method;
import java.time.LocalDate;
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









    private static AgentLlmProperties.DataFreshness freshness(String start, String end, String asOf, String desc) {
        AgentLlmProperties.DataFreshness f = new AgentLlmProperties.DataFreshness();
        f.setStartDate(start);
        f.setEndDate(end);
        f.setAsOfDate(asOf);
        f.setDescription(desc);
        return f;
    }





}
