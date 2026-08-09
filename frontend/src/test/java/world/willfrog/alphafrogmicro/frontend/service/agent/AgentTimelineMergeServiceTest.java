package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.frontend.model.agent.TimelineResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTimelineMergeServiceTest {

    @Test
    void mergeTraversesTraceListsThroughEventSafeViewWithoutRawFallback() {
        String observability = """
                {"diagnostics":{"llmTraces":[{
                  "traceId":"trace-1","time":"2026-08-09T10:00:00Z","phase":"execution",
                  "model":"X-Api-Key: private-model-secret","endpoint":"https://example.test/?api_key=private-key",
                  "durationMs":12,"inputMessages":[{"content":"raw prompt"}],"outputText":"raw answer"
                }]}}
                """;
        List<TimelineResponse.TimelineItem> items = new ArrayList<>();

        new AgentTimelineMergeService(new ObjectMapper())
                .mergeTraceItems(observability, items, null, null, 10);

        assertTrue(items.toString().contains(AgentExternalObservabilityMapper.REDACTION_TEXT));
        assertFalse(items.toString().contains("private-model-secret"));
        assertFalse(items.toString().contains("private-key"));
        assertFalse(items.toString().contains("raw prompt"));
        assertFalse(items.toString().contains("raw answer"));
    }

    @Test
    void malformedObservabilityFailsClosedWithoutAddingItems() {
        List<TimelineResponse.TimelineItem> items = new ArrayList<>();

        new AgentTimelineMergeService(new ObjectMapper())
                .mergeTraceItems("{malformed", items, null, null, 10);

        assertTrue(items.isEmpty());
    }
}
