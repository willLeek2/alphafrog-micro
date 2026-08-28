package world.willfrog.agent.platform.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagObservabilityBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagObservabilityBuilder builder = new RagObservabilityBuilder(objectMapper);

    @Test
    @SuppressWarnings("unchecked")
    void build_shouldAggregateRagDetailsRereadModesAndCitationMarkers() {
        AgentRunObservabilityService.ToolTrace ragTrace = toolTrace("rag-1", "ragSearch");
        AgentRunObservabilityService.ToolTrace rereadTrace = toolTrace("reread-1", "rereadToolResult");
        Map<String, String> details = Map.of(
                "rag-1", toolDetail(
                        Map.of("query", "alpha"),
                        """
                        {"ok":true,"data":{"summary":{"hit_count":3,"omitted_count":1,"visible_chars":240,"budget_hit":true},"rawRef":"raw_ref_001","top_refs":[{"ref_id":"rag_ref_001","source_key":"oss://a#chunk=0"},{"ref_id":"rag_ref_002","source_key":"oss://b#chunk=1"}]}}
                        """
                ),
                "reread-1", toolDetail(
                        Map.of("rawRef", "raw_ref_001", "keyword", "Alpha", "limit", 1200),
                        """
                        {"ok":true,"data":{"content":"abcdef","hasMore":true,"nextOffset":6}}
                        """
                )
        );

        Map<String, Object> result = builder.build(
                "run-1",
                "结论 <rag-cite ref=\"rag_ref_001\" /> <rag-cite ref=\"rag_ref_099\" /> <rag-cite ref=\"bad\" />",
                List.of(ragTrace, rereadTrace),
                traceId -> Optional.ofNullable(details.get(traceId))
        );

        assertFalse(result.isEmpty());
        assertEquals("v1", result.get("rag_observability_version"));
        assertEquals("xml_rag_cite_v1", result.get("citation_marker_version"));
        Map<String, Object> aggregate = (Map<String, Object>) result.get("aggregate");
        assertEquals(3, aggregate.get("retrieved_count"));
        assertEquals(2, aggregate.get("visible_count"));
        assertEquals(1, aggregate.get("omitted_count"));
        assertEquals(240, aggregate.get("visible_chars"));
        assertEquals(true, aggregate.get("budget_hit"));
        assertEquals(true, aggregate.get("raw_ref_created"));
        assertEquals(true, aggregate.get("reread_called"));
        assertEquals(List.of("keyword"), aggregate.get("reread_modes"));
        assertEquals(List.of("rag_ref_001"), aggregate.get("final_answer_cited_refs"));
        assertEquals(List.of("rag_ref_099"), aggregate.get("invalid_cited_refs"));
        assertEquals(1, aggregate.get("invalid_citations_ignored"));
        assertEquals(List.of("rag_ref_001", "rag_ref_002"), aggregate.get("visible_ref_registry"));
    }

    @Test
    void build_shouldMarkSourceIncompleteWhenDetailBlobMissing() {
        AgentRunObservabilityService.ToolTrace ragTrace = toolTrace("missing-1", "ragSearch");

        Map<String, Object> result = builder.build(
                "run-1",
                "",
                List.of(ragTrace),
                traceId -> Optional.empty()
        );

        assertEquals(true, result.get("source_incomplete"));
        assertTrue(result.containsKey("source_incomplete_reasons"));
    }

    @Test
    void build_shouldReturnEmptyWithoutRagSignals() {
        Map<String, Object> result = builder.build(
                "run-1",
                "普通答案 [1]",
                List.of(toolTrace("web-1", "searchWeb")),
                traceId -> Optional.empty()
        );

        assertTrue(result.isEmpty());
    }

    private AgentRunObservabilityService.ToolTrace toolTrace(String traceId, String toolName) {
        AgentRunObservabilityService.ToolTrace trace = new AgentRunObservabilityService.ToolTrace();
        trace.setTraceId(traceId);
        trace.setToolName(toolName);
        trace.setSuccess(true);
        return trace;
    }

    private String toolDetail(Map<String, Object> params, String output) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "tool",
                    "traceId", "trace-1",
                    "params", params,
                    "output", output
            ));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
