package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.util.PromptFileLoader;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Optional;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentContextCompressorPromptAuthorityTest {

    @AfterEach
    void clearContext() { AgentContext.clear(); }

    @Test
    void summarySystemPrompt_shouldRenderSelectedVersionWithOnlyDynamicLimitInJava() {
        var versioned = new VersionedModelTestFixture();
        String template = PromptFileLoader.load("prompts/agent/follow_up_summary_system.txt");
        versioned.put("followUpSummarySystemPrompt", template, "maxChars");
        versioned.putImported("followUpSummaryDialogue");
        AgentLlmProperties properties = new AgentLlmProperties();
        var config = properties.getRuntime().getMultiTurn().getCompression();
        config.setEnabled(true);
        config.setStrategy("llm_summary");
        config.setSummaryEndpoint("summary-endpoint");
        config.setSummaryModel("summary-model");
        config.setMinMessagesForCompression(1);
        config.setMinMessagesForSummary(1);
        config.setSummaryMaxChars(321);
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        AgentAiServiceFactory factory = mock(AgentAiServiceFactory.class);
        ChatModel model = mock(ChatModel.class);
        var route = new AgentLlmResolver.ResolvedLlm("summary-endpoint", "https://example.invalid", "summary-model",
                "fixture-key", null, List.of(), 256);
        when(factory.resolveLlm("summary-endpoint", "summary-model")).thenReturn(route);
        when(factory.buildArchivedChatModelWithTemperature(eq(route), anyDouble()))
                .thenReturn(versioned.archived(model, "summary-endpoint"));
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(new AiMessage("摘要结果")).build());
        AgentContextCompressor compressor = new AgentContextCompressor(factory, loader, properties);
        ReflectionTestUtils.setField(compressor, "maxHistoryMessages", 10);
        ReflectionTestUtils.setField(compressor, "maxMessageChars", 8000);
        versioned.inject(compressor);
        AgentRunMessage history = new AgentRunMessage();
        history.setId(1L);
        history.setRunId(AgentContext.getRunId());
        history.setSeq(1);
        history.setRole(AgentRunMessage.ROLE_USER);
        history.setMsgType(AgentRunMessage.MSG_TYPE_INITIAL);
        history.setContent("原始问题");

        var result = compressor.buildCompressedContext(List.of(history), "新问题");
        assertEquals("摘要结果", result.text());
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(model).chat(messages.capture());
        String rendered = ((SystemMessage) messages.getValue().get(0)).text();

        assertEquals(template.replace("{{maxChars}}", "321"), rendered);
        assertFalse(rendered.contains("{{maxChars}}"), "动态字符上限占位符必须完全渲染");
        assertEquals("followUpSummarySystemPrompt", versioned.saved.get(0).sources().parts().get(0).versions().get(0).promptId());

        history.setRole(AgentRunMessage.ROLE_SYSTEM);
        assertEquals(ModelSendBlocked.PROMPT_SOURCE_MISSING,
                assertThrows(ModelSendBlockedException.class,
                        () -> compressor.buildCompressedContext(List.of(history), "新问题")).code());
        history.setRole(AgentRunMessage.ROLE_USER);
        versioned.rejectSave = true;
        assertEquals(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                assertThrows(ModelSendBlockedException.class,
                        () -> compressor.buildCompressedContext(List.of(history), "新问题")).code());
        verify(model, times(1)).chat(anyList());
    }
}
