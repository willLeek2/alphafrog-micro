package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.artifact.RunRawRefStore;
import world.willfrog.agent.platform.artifact.ToolOutputReadResult;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RereadToolHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

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

    @Test
    void rereadShouldResolveRunScopedShortRawRef() throws Exception {
        AgentContext.setRunId("run-1");
        RereadToolHandler handler = new RereadToolHandler(
                new StubToolOutputRefService(),
                objectMapper,
                Optional.empty(),
                Optional.of(new StubRunRawRefStore())
        );

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw_ref_001", "现金流", 0, 80),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals("short-content", data.get("content"));
    }

    @Test
    void rereadShouldNotRouteMalformedShortRawRefToRunStore() throws Exception {
        AgentContext.setRunId("run-1");
        RunRawRefStore rawRefStore = mock(RunRawRefStore.class);
        RereadToolHandler handler = new RereadToolHandler(
                new StubToolOutputRefService(),
                objectMapper,
                Optional.empty(),
                Optional.of(rawRefStore)
        );

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw_ref_xyz", "现金流", 0, 80),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        verifyNoInteractions(rawRefStore);
    }

    @Test
    void rereadKeywordLimitExceedsKeywordCharLimitShouldReject() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setMaxLimit(4000);
        cfg.getTools().getReread().setKeywordCharLimit(1500);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", "现金流", 0, 3000),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.FALSE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals("LIMIT_TOO_LARGE_WITH_KEYWORD", error.get("code"));
    }

    @Test
    void rereadKeywordNoLimitShouldDefaultToKeywordCharLimit() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setKeywordCharLimit(1200);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        // limit=0 → normalized to keywordCharLimit=1200, should pass
        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", "现金流", 0, 0),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
    }

    @Test
    void rereadRangeBelowMinLimitShouldReject() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setRangeMinLimitWithoutKeyword(3000);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", null, 0, 2000),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.FALSE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertEquals("LIMIT_TOO_SMALL_WITHOUT_KEYWORD", error.get("code"));
    }

    @Test
    void rereadRangeMax6000WithShortRawRefShouldSucceed() throws Exception {
        AgentContext.setRunId("run-range");
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setMaxLimit(6000);
        cfg.getTools().getReread().setRangeMaxLimit(6000);
        when(loader.current()).thenReturn(Optional.of(cfg));

        RunRawRefStore largeStore = mock(RunRawRefStore.class);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6000; i++) sb.append("x");
        when(largeStore.read(eq("run-range"), eq("raw_ref_001"), eq(0), eq(6000), isNull()))
                .thenReturn(ToolOutputReadResult.builder()
                        .content(sb.toString()).hasMore(false).nextOffset(6000).totalLength(6000).build());

        RereadToolHandler handler = new RereadToolHandler(
                new StubToolOutputRefService(), objectMapper, Optional.of(loader), Optional.of(largeStore));

        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw_ref_001", null, 0, 6000),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.TRUE, response.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertEquals(6000, ((String) data.get("content")).length());
    }

    @Test
    void rereadNullKeywordCharLimitShouldFallbackToMaxLimit() throws Exception {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(2000);
        cfg.getTools().getReread().setMaxLimit(3000);
        // keywordCharLimit is null → should fallback to maxLimit=3000
        when(loader.current()).thenReturn(Optional.of(cfg));

        RereadToolHandler handler = new RereadToolHandler(new StubToolOutputRefService(), objectMapper, Optional.of(loader));

        // limit=3500 > maxLimit=3000 → rejected
        Map<String, Object> response = objectMapper.readValue(
                handler.reread("raw-ref:1", "现金流", 0, 3500),
                new TypeReference<>() {}
        );

        assertEquals(Boolean.FALSE, response.get("ok"));
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

    private static class StubRunRawRefStore implements RunRawRefStore {
        @Override
        public String register(String runId, String userId, String displayName, String content, long ttlSeconds) {
            return "raw_ref_001";
        }

        @Override
        public String read(String runId, String shortId) {
            return "short-content";
        }

        @Override
        public ToolOutputReadResult read(String runId, String shortId, int offset, int limit, String keyword) {
            return ToolOutputReadResult.builder()
                    .content("short-content")
                    .hasMore(false)
                    .nextOffset(13)
                    .totalLength(13)
                    .build();
        }

        @Override
        public boolean belongsToRun(String runId, String shortId) {
            return true;
        }
    }
}
