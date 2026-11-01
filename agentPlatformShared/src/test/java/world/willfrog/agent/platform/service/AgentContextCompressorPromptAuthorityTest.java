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

}
