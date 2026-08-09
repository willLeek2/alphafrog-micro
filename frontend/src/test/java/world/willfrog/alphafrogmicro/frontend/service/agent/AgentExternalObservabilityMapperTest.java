package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentExternalObservabilityMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void malformedJsonNeverEchoesRawInput() {
        assertNull(AgentExternalObservabilityMapper.parse(
                objectMapper,
                "{broken Cookie: sid=plain-business-secret",
                AgentExternalObservabilityMapper.View.EVENT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runSnapshotUsesTopLevelAndCompletedItemAllowlists() {
        String snapshot = """
                {
                  "answer":"ok",
                  "status":"COMPLETED",
                  "observability":{"diagnostics":{"httpRequest":{"Authorization":"Bearer secret-token-value"}}},
                  "internal_field":"must-not-leak",
                  "completed_items":[{
                    "todoId":"todo-1","sequence":1,"description":"step","summary":"done",
                    "output":"raw tool output","modelOutput":"raw model output"
                  }]
                }
                """;

        Map<String, Object> mapped = (Map<String, Object>) AgentExternalObservabilityMapper.parse(
                objectMapper, snapshot, AgentExternalObservabilityMapper.View.RUN_SNAPSHOT);

        assertEquals("ok", mapped.get("answer"));
        assertFalse(mapped.containsKey("observability"));
        assertFalse(mapped.containsKey("internal_field"));
        Map<String, Object> item = (Map<String, Object>) ((List<?>) mapped.get("completed_items")).get(0);
        assertEquals("done", item.get("summary"));
        assertFalse(item.containsKey("output"));
        assertFalse(item.containsKey("modelOutput"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runSnapshotAllowsOnlyNamedRecoveryFields() {
        Map<String, Object> mapped = (Map<String, Object>) AgentExternalObservabilityMapper.parse(
                objectMapper,
                """
                {
                  "status":"PARTIAL",
                  "recovery_rationale":"safe rationale",
                  "recovery_judge_decision_id":"decision-1",
                  "recovery_raw_http":{"body":"ordinary private payload"},
                  "recovery_output":"full tool output"
                }
                """,
                AgentExternalObservabilityMapper.View.RUN_SNAPSHOT);

        assertEquals("safe rationale", mapped.get("recovery_rationale"));
        assertEquals("decision-1", mapped.get("recovery_judge_decision_id"));
        assertFalse(mapped.containsKey("recovery_raw_http"));
        assertFalse(mapped.containsKey("recovery_output"));
        assertFalse(mapped.toString().contains("ordinary private payload"));
        assertFalse(mapped.toString().contains("full tool output"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void planViewUsesRootAndItemAllowlistsWithoutPlannerReasoningOrToolParameters() {
        Map<String, Object> mapped = (Map<String, Object>) AgentExternalObservabilityMapper.parse(
                objectMapper,
                """
                {
                  "analysis":"full planner reasoning",
                  "executionMode":"DAG",
                  "strategy":{"reasoning":"nested strategy reasoning"},
                  "extractedEntities":["沪深300",{"reasoning":"nested entity reasoning"}],
                  "internalPlannerState":"hidden state",
                  "items":[{
                    "id":"todo-1","sequence":1,"type":"TOOL_CALL","toolName":"searchIndex",
                    "description":"查询指数公开信息",
                    "dependsOn":["todo-0",{"params":{"keyword":"nested dependency query"}}],
                    "parallelizable":true,
                    "params":{"keyword":"private business query"},
                    "reasoning":"full todo reasoning","output":"full tool output"
                  }]
                }
                """,
                AgentExternalObservabilityMapper.View.PLAN);

        assertEquals("DAG", mapped.get("executionMode"));
        assertFalse(mapped.containsKey("strategy"));
        assertEquals(List.of("沪深300"), mapped.get("extractedEntities"));
        assertFalse(mapped.containsKey("analysis"));
        assertFalse(mapped.containsKey("internalPlannerState"));
        Map<String, Object> item = (Map<String, Object>) ((List<?>) mapped.get("items")).get(0);
        assertEquals("查询指数公开信息", item.get("description"));
        assertEquals("searchIndex", item.get("toolName"));
        assertEquals(List.of("todo-0"), item.get("dependsOn"));
        assertFalse(item.containsKey("params"));
        assertFalse(item.containsKey("reasoning"));
        assertFalse(item.containsKey("output"));
        assertFalse(mapped.toString().contains("private business query"));
        assertFalse(mapped.toString().contains("nested dependency query"));
        assertFalse(mapped.toString().contains("nested strategy reasoning"));
        assertFalse(mapped.toString().contains("nested entity reasoning"));
        assertFalse(mapped.toString().contains("full todo reasoning"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventViewDropsRawTraceFieldsAndCapsSafePreview() {
        String event = """
                {
                  "todo_id":"todo-1",
                  "params":{"password":"plain"},
                  "output":"raw output",
                  "httpRequest":{"url":"https://example.test/?api_key=secret-value"},
                  "result_preview":"%s"
                }
                """.formatted("x".repeat(AgentCallDetailMapper.PREVIEW_MAX_CHARS + 10));

        Map<String, Object> mapped = (Map<String, Object>) AgentExternalObservabilityMapper.parse(
                objectMapper, event, AgentExternalObservabilityMapper.View.EVENT);

        assertEquals("todo-1", mapped.get("todo_id"));
        assertFalse(mapped.containsKey("params"));
        assertFalse(mapped.containsKey("output"));
        assertFalse(mapped.containsKey("httpRequest"));
        assertEquals(AgentCallDetailMapper.PREVIEW_MAX_CHARS,
                String.valueOf(mapped.get("result_preview")).length());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminViewPreservesShapeButRedactsKeysAndCredentialValues() {
        Map<String, Object> mapped = (Map<String, Object>) AgentExternalObservabilityMapper.parse(
                objectMapper,
                """
                {"httpRequest":{"Authorization":"Bearer secret-value"},
                 "body":"api_key=secret-value AKIAIOSFODNN7EXAMPLE",
                 "inputTokens":123,"output_tokens":456,
                 "nested":{"openAiApiKey":"nested-secret","xAuthToken":"token-secret"}}
                """,
                AgentExternalObservabilityMapper.View.ADMIN);

        Map<String, Object> request = (Map<String, Object>) mapped.get("httpRequest");
        assertEquals(AgentExternalObservabilityMapper.REDACTION_TEXT, request.get("Authorization"));
        assertTrue(String.valueOf(mapped.get("body")).contains(AgentExternalObservabilityMapper.REDACTION_TEXT));
        assertFalse(mapped.toString().contains("secret-value"));
        assertFalse(mapped.toString().contains("AKIAIOSFODNN7EXAMPLE"));
        assertEquals(123, mapped.get("inputTokens"));
        assertEquals(456, mapped.get("output_tokens"));
        Map<String, Object> nested = (Map<String, Object>) mapped.get("nested");
        assertEquals(AgentExternalObservabilityMapper.REDACTION_TEXT, nested.get("openAiApiKey"));
        assertEquals(AgentExternalObservabilityMapper.REDACTION_TEXT, nested.get("xAuthToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stringScrubRedactsWholeHeaderAndQuotedValuesAcrossDeepLists() {
        assertEquals("Cookie: " + AgentExternalObservabilityMapper.REDACTION_TEXT,
                AgentExternalObservabilityMapper.safePreview(
                        "Cookie: session=first-secret; refresh=second-secret", 10_000));
        assertEquals("body={\"password\":\"" + AgentExternalObservabilityMapper.REDACTION_TEXT + "\"}",
                AgentExternalObservabilityMapper.safePreview(
                        "body={\"password\":\"p@ss word!\"}", 10_000));
        assertEquals("X-Api-Key: " + AgentExternalObservabilityMapper.REDACTION_TEXT,
                AgentExternalObservabilityMapper.safePreview("X-Api-Key: alpha beta", 10_000));
        assertEquals("password=" + AgentExternalObservabilityMapper.REDACTION_TEXT,
                AgentExternalObservabilityMapper.safePreview("password=p@ss word!:", 10_000));

        Map<String, Object> mapped = (Map<String, Object>) AgentExternalObservabilityMapper.parse(
                objectMapper,
                """
                {"nested":[
                  {"openAiApiKey":"list-secret-one"},
                  {"deeper":{"xAuthToken":"list-secret-two"}}
                ]}
                """,
                AgentExternalObservabilityMapper.View.ADMIN);
        assertFalse(mapped.toString().contains("list-secret-one"));
        assertFalse(mapped.toString().contains("list-secret-two"));
        assertTrue(mapped.toString().contains(AgentExternalObservabilityMapper.REDACTION_TEXT));
    }

    @Test
    void parseToJsonSerializationFailureReturnsNullWithoutFallback() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.readValue("{\"safe\":true}", Object.class)).thenReturn(Map.of("safe", true));
        when(failingMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("serializer failed"));

        assertNull(AgentExternalObservabilityMapper.parseToJson(
                failingMapper,
                "{\"safe\":true}",
                AgentExternalObservabilityMapper.View.ADMIN));
    }
}
