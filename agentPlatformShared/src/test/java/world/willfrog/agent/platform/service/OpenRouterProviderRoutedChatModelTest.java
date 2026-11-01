package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.openai.internal.OpenAiUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.ProviderFailureCategory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OpenRouterProviderRoutedChatModelTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }



















    // ── Provider order rotation tests (§2.3) ──











    private OpenRouterProviderRoutedChatModel createMinimalModel(List<String> providerOrder) {
        return new OpenRouterProviderRoutedChatModel(
                new ObjectMapper(),
                "https://openrouter.ai/api/v1",
                "test-key",
                Map.of(),
                "moonshotai/kimi-k2.5",
                0.7D,
                1024,
                providerOrder,
                null,
                null,
                null,
                null,
                "openrouter",
                null,
                null
        );
    }

    // ── Provider error classification tests (Phase 3.1) ──









}
