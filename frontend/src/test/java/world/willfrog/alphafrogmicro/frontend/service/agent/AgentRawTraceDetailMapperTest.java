package world.willfrog.alphafrogmicro.frontend.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRawTraceDetailMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildLlmPayload_shouldRedactHeadersBodiesAndUrlsAcrossProviderShapes() {
        String raw = """
                {
                  "type":"llm_raw_http",
                  "runId":"run-1",
                  "traceId":"llm-1",
                  "httpRequest":{
                    "url":"https://openrouter.ai/api/v1/chat/completions?api_key=sk-openrouter-secret",
                    "headers":{
                      "Authorization":"Bearer sk-openai-secret",
                      "x-api-key":"sk-anthropic-secret",
                      "Cookie":"dashscope_token=sk-dashscope-secret"
                    },
                    "body":"api_key=sk-body-secret password=plain-secret access_token=ya29_google_secret"
                  },
                  "httpResponse":{
                    "headers":{"set-cookie":"sid=secret-cookie"},
                    "body":"{\\"secret\\":\\"sk-response-secret\\",\\"output\\":\\"ok\\"}"
                  }
                }
                """;

        var payload = AgentRawTraceDetailMapper.buildLlmPayload(
                objectMapper,
                "run-1",
                "llm-1",
                raw,
                Map.of("createdAtMillis", 1000L, "expiresAtMillis", 2000L));

        String fullJson = new String(payload.fullDetailBytes(), StandardCharsets.UTF_8);
        String requestJson = new String(Base64.getDecoder().decode(
                String.valueOf(payload.fullDetail().get("httpRequestBase64"))), StandardCharsets.UTF_8);
        String responseJson = new String(Base64.getDecoder().decode(
                String.valueOf(payload.fullDetail().get("httpResponseBase64"))), StandardCharsets.UTF_8);
        String combined = fullJson + requestJson + responseJson;

        assertFalse(combined.contains("sk-openrouter-secret"));
        assertFalse(combined.contains("sk-openai-secret"));
        assertFalse(combined.contains("sk-anthropic-secret"));
        assertFalse(combined.contains("sk-dashscope-secret"));
        assertFalse(combined.contains("sk-body-secret"));
        assertFalse(combined.contains("ya29_google_secret"));
        assertFalse(combined.contains("secret-cookie"));
        assertFalse(combined.contains("sk-response-secret"));
        assertTrue(combined.contains(AgentRawTraceDetailMapper.REDACTION_TEXT));
    }

    @Test
    void buildToolPayload_shouldRedactParamsAndOutput() {
        String raw = """
                {
                  "type":"tool",
                  "traceId":"tool-1",
                  "params":{
                    "api_key":"sk-tool-param-secret",
                    "query":"token=sk-query-secret"
                  },
                  "output":{
                    "result":"ok",
                    "nested":{
                      "password":"plain-password",
                      "url":"https://example.test/callback?access_token=ya29_tool_secret"
                    }
                  }
                }
                """;

        var payload = AgentRawTraceDetailMapper.buildToolPayload(
                objectMapper,
                "run-1",
                "tool-1",
                raw);

        String fullJson = new String(payload.fullDetailBytes(), StandardCharsets.UTF_8);
        String paramsJson = new String(Base64.getDecoder().decode(
                String.valueOf(payload.fullDetail().get("paramsBase64"))), StandardCharsets.UTF_8);
        String outputJson = new String(Base64.getDecoder().decode(
                String.valueOf(payload.fullDetail().get("outputBase64"))), StandardCharsets.UTF_8);
        String combined = fullJson + paramsJson + outputJson;

        assertFalse(combined.contains("sk-tool-param-secret"));
        assertFalse(combined.contains("sk-query-secret"));
        assertFalse(combined.contains("plain-password"));
        assertFalse(combined.contains("ya29_tool_secret"));
        assertTrue(combined.contains(AgentRawTraceDetailMapper.REDACTION_TEXT));
    }
}
