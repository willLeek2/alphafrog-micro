package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RereadToolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rereadWithoutKeywordShouldRejectLimitAtOrBelowHalfResultThreshold() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setMaxLimit(4000);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", "", 0, 1000),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.FALSE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals("LIMIT_TOO_SMALL_WITHOUT_KEYWORD", error.get("code"));
    }

    @Test
    void rereadWithKeywordShouldAllowSmallLimit() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setMaxLimit(4000);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", "中证消费", 0, 80),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("content", data.get("content"));
        assertEquals("中证消费", data.get("keyword"));
    }

    @Test
    void rereadWithoutKeywordShouldUseConfiguredDefaultWhenLimitOmitted() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setMaxLimit(1200);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", null, 0, 0),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
    }

    private static class StubToolOutputRefService implements ToolOutputRefService {
        @Override
        public world.willfrog.agent.platform.artifact.PersistentArtifactRegistration registerRawOutput(
                String logicalId, String displayName, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public world.willfrog.agent.platform.artifact.PersistentArtifactRegistration rebindFromLocator(
                String logicalId, String displayName, RawPayloadLocator locator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RawPayloadLocator locatorFor(String rawRef) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ToolOutputReadResult read(String rawRef, int offset, int limit, String keyword) {
            return ToolOutputReadResult.builder()
                    .content("content")
                    .hasMore(false)
                    .nextOffset(7)
                    .totalLength(7)
                    .build();
        }
    }
}
