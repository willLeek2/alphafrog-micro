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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolOutputCompactionServiceTest {

    private ToolOutputCompactionService service;
    private RawPayloadLocator locator;

    @BeforeEach
    void setUp() {
        AgentLlmLocalConfigLoader loader = mock(AgentLlmLocalConfigLoader.class);
        AgentLlmProperties cfg = new AgentLlmProperties();
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

        service = new ToolOutputCompactionService(summaryService, refService, loader, new ObjectMapper());
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
}
