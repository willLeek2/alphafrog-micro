package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static world.willfrog.agent.platform.service.AgentCallDetailPersistence.OBSERVABILITY_PREVIEW_MAX_CHARS;

class AgentCallDetailPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scrubLlmTrace_removesRawFieldsAndSetsFlagOnlyWhenBlobStored() {
        AgentObservabilityService.LlmTrace trace = new AgentObservabilityService.LlmTrace();
        trace.setTraceId("llm-1");
        trace.setOutputText("full output");
        trace.setInputMessages(Map.of("role", "user"));
        trace.setHttpRequest(new AgentObservabilityService.RawHttpTrace());
        trace.setCurlCommand("curl secret");
        trace.setEndpoint("openrouter");
        trace.setGenerationId("gen-1");

        AgentCallDetailPersistence.scrubLlmTrace(trace, true);

        assertTrue(trace.isDetailBlobStored());
        assertNull(trace.getOutputText());
        assertNull(trace.getInputMessages());
        assertNull(trace.getHttpRequest());
        assertNull(trace.getCurlCommand());
        assertEquals("openrouter", trace.getEndpoint());
        assertEquals("gen-1", trace.getGenerationId());
    }

    @Test
    void scrubLlmTrace_withoutBlobStored_doesNotSetFlag() {
        AgentObservabilityService.LlmTrace trace = new AgentObservabilityService.LlmTrace();
        trace.setTraceId("llm-2");
        trace.setModel("qwen");

        AgentCallDetailPersistence.scrubLlmTrace(trace, false);

        assertFalse(trace.isDetailBlobStored());
    }

    @Test
    void scrubLlmTrace_capsLargePreviewInSnapshotShape() throws Exception {
        String huge = "x".repeat(50_000);
        AgentObservabilityService.LlmTrace trace = new AgentObservabilityService.LlmTrace();
        trace.setTraceId("llm-3");
        trace.setOutputText(huge);

        AgentCallDetailPersistence.scrubLlmTrace(trace, true);

        assertTrue(trace.getResponsePreview().length() <= OBSERVABILITY_PREVIEW_MAX_CHARS + 3);
        String serialized = objectMapper.writeValueAsString(trace);
        assertFalse(serialized.contains(huge));
    }

    @Test
    void scrubObservabilityMap_removesRawAndCapsPreview() throws Exception {
        String huge = "y".repeat(50_000);
        Map<String, Object> llmTrace = new LinkedHashMap<>();
        llmTrace.put("traceId", "llm-1");
        llmTrace.put("outputText", huge);
        llmTrace.put("httpRequest", Map.of("url", "http://x"));
        llmTrace.put("endpoint", "openrouter");
        llmTrace.put("generationId", "gen-map-1");
        llmTrace.put("detailBlobStored", true);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("llmTraces", List.of(llmTrace));
        Map<String, Object> observability = new LinkedHashMap<>();
        observability.put("diagnostics", diagnostics);

        AgentCallDetailPersistence.scrubObservabilityMap(observability);

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbed = ((List<Map<String, Object>>) diagnostics.get("llmTraces")).get(0);
        assertFalse(scrubbed.containsKey("outputText"));
        assertFalse(scrubbed.containsKey("httpRequest"));
        assertEquals("openrouter", scrubbed.get("endpoint"));
        assertEquals("gen-map-1", scrubbed.get("generationId"));
        assertEquals(true, scrubbed.get("detailBlobStored"));
        String preview = String.valueOf(scrubbed.get("responsePreview"));
        assertTrue(preview.length() <= OBSERVABILITY_PREVIEW_MAX_CHARS + 3);
        assertFalse(objectMapper.writeValueAsString(observability).contains(huge));
    }

    @Test
    void hasPersistableDetailBlob_requiresMoreThanTypeAndId() {
        Map<String, Object> empty = Map.of("type", "llm", "traceId", "a");
        assertFalse(AgentCallDetailPersistence.hasPersistableDetailBlob(empty));
        Map<String, Object> withBody = new LinkedHashMap<>(empty);
        withBody.put("outputText", "hello");
        assertTrue(AgentCallDetailPersistence.hasPersistableDetailBlob(withBody));
    }

    @Test
    void toToolDetailBlob_capturesParamsAndOutput() {
        AgentObservabilityService.ToolTrace trace = new AgentObservabilityService.ToolTrace();
        trace.setTraceId("tool-1");
        trace.setParams(Map.of("query", "茅台"));
        trace.setOutput("{\"hits\":[]}");

        Map<String, Object> blob = AgentCallDetailPersistence.toToolDetailBlob(trace);

        assertEquals("tool", blob.get("type"));
        assertEquals("茅台", ((Map<?, ?>) blob.get("params")).get("query"));
        assertTrue(blob.containsKey("output"));
    }
}
