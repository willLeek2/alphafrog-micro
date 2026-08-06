package world.willfrog.agent.tools.compaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.PersistentArtifactRegistration;
import world.willfrog.agent.platform.artifact.RawPayloadLocator;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolOutputCompactionServiceTest {

    private ToolOutputCompactionService service;
    private RawPayloadLocator locator;
    private AgentLlmProperties cfg;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        cfg = new AgentLlmProperties();
        cfg.getTools().getResult().setMaxStringLength(10);
        when(loader.current()).thenReturn(Optional.of(cfg));

        ToolOutputRefService refService = mock(ToolOutputRefService.class);
        locator = RawPayloadLocator.builder().path("/tmp/raw.json").contentHash("hash").build();
        when(refService.registerRawOutput(anyString(), anyString(), anyString()))
                .thenReturn(PersistentArtifactRegistration.builder()
                        .artifactId("raw-ref:test123")
                        .locator(locator)
                        .build());
        when(refService.rebindFromLocator(anyString(), anyString(), any(RawPayloadLocator.class)))
                .thenReturn(PersistentArtifactRegistration.builder()
                        .artifactId("raw-ref:rebound456")
                        .locator(locator)
                        .build());

        ToolSummaryService summaryService = mock(ToolSummaryService.class);
        when(summaryService.summarize(anyString(), anyString(), anyString())).thenReturn("short summary");

        service = new ToolOutputCompactionService(summaryService, refService, loader, objectMapper);
        AgentContext.clear();
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");
        AgentContext.setToolCallId("tc-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void compactShouldApplySummaryAndRawRefWhenOutputExceedsLimit() {
        String raw = "x".repeat(50);
        ToolOutputCompactionService.CompactionResult result =
                service.compact("getStockInfo", raw, "查股票");

        assertTrue(result.isCompactionApplied());
        assertTrue(result.getModelOutput().contains("short summary"));
        assertTrue(result.getModelOutput().contains("raw-ref:test123"));
        assertFalse(result.getCacheTemplate().contains("raw-ref:test123"));
    }

    @Test
    void rebindForCacheHitShouldInjectCurrentRunRawRef() {
        String raw = "y".repeat(50);
        ToolOutputCompactionService.CompactionResult first = service.compact("getStockInfo", raw, "查股票");
        AgentContext.setRunId("run-2");
        AgentContext.setToolCallId("tc-2");

        String rebound = service.rebindForCacheHit(first.getCacheTemplate(), first.getRawLocator());

        assertTrue(rebound.contains("raw-ref:rebound456"));
        assertFalse(rebound.contains("\"rawRef\":\"\""));
    }

    @Test
    void compactSearchShouldExposeHasDailyCodeAndName() throws Exception {
        cfg.getTools().getResult().setMaxStringLength(800);
        String raw = """
                {"ok":true,"tool":"searchIndex","data":{"items":[
                  {"ts_code":"000806.CSI","name":"消费服务","has_daily":1,"full_name":"很长的全称"},
                  {"ts_code":"931354.CSI","name":"CS消费","has_daily":0},
                  {"ts_code":"931139.CSI","name":"CS消费50","has_daily":1}
                ],"extra":"%s"}}
                """.formatted("x".repeat(900));

        ToolOutputCompactionService.CompactionResult result =
                service.compact("searchIndex", raw, "查消费指数");

        String modelOutput = result.getModelOutput();
        assertTrue(modelOutput.contains("compactSearchItems"));
        assertTrue(modelOutput.contains("000806.CSI"));
        assertTrue(modelOutput.contains("CS消费50"));
        assertFalse(modelOutput.contains("931354.CSI"));

        @SuppressWarnings("unchecked")
        var root = objectMapper.readValue(modelOutput, java.util.Map.class);
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) root.get("data");
        @SuppressWarnings("unchecked")
        var compact = (java.util.Map<String, Object>) data.get("compactSearchItems");
        String compactJson = objectMapper.writeValueAsString(compact);
        assertTrue(compactJson.length() <= cfg.getTools().getResult().getMaxStringLength() / 4);
        assertEquals("has_daily=1", compact.get("filter"));
    }
}
