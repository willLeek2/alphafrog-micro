package world.willfrog.alphafrogmicro.common.service.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigConflictException;
import world.willfrog.alphafrogmicro.common.exception.config.ConfigNotFoundException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLlmModelCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createKeepsExistingKeysAndWritesHyphenEndpoint() throws Exception {
        ObjectNode original = document();
        List<AgentLlmModelCatalog.Placement> placements = AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                [{"endpoint":"dashscope-cn","displayName":"Qwen4-Max(DashScope)","baseRate":0.35}]
                """));

        ObjectNode updated = AgentLlmModelCatalog.create(original, "qwen4-max", placements);

        assertEquals("secret-openrouter", original.at("/endpoints/openrouter/apiKey").asText());
        assertFalse(original.at("/models").toString().contains("qwen4-max"));
        assertEquals("secret-openrouter", updated.at("/endpoints/openrouter/apiKey").asText());
        assertEquals("secret-dashscope", updated.at("/endpoints/dashscope-cn/apiKey").asText());
        assertEquals("qwen4-max", updated.at("/models/2").asText());
        assertEquals(0.35, updated.at("/endpoints/dashscope-cn/models/qwen4-max/baseRate").asDouble(), 0.0001);
        assertEquals("Qwen4-Max(DashScope)",
                updated.at("/endpoints/dashscope-cn/models/qwen4-max/displayName").asText());
        assertEquals("Qwen3.6-Flash",
                updated.at("/endpoints/dashscope-cn/models/qwen3.6-flash/displayName").asText());
    }

    @Test
    void createAcceptsSlashAndTildeId() throws Exception {
        ObjectNode updated = AgentLlmModelCatalog.create(document(), "~deepseek/deepseek-flash-latest",
                AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"openrouter","displayName":"DeepSeek Flash Latest","baseRate":0.05,
                          "validProviders":["together","deepseek"]}]
                        """)));

        JsonNode meta = updated.at("/endpoints/openrouter/models/~deepseek~1deepseek-flash-latest");
        assertEquals("DeepSeek Flash Latest", meta.get("displayName").asText());
        assertEquals(List.of("together", "deepseek"),
                objectMapper.convertValue(meta.get("validProviders"), List.class));
        assertTrue(updated.get("models").toString().contains("~deepseek/deepseek-flash-latest"));
    }

    @Test
    void createRejectsDuplicateUnknownEndpointAndNonPositiveRate() throws Exception {
        ObjectNode doc = document();
        List<AgentLlmModelCatalog.Placement> dashscope = AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                [{"endpoint":"dashscope-cn","displayName":"X","baseRate":0.1}]
                """));
        assertThrows(ConfigConflictException.class,
                () -> AgentLlmModelCatalog.create(doc, "qwen3.6-flash", dashscope));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLlmModelCatalog.create(doc, "new-model",
                        AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                                [{"endpoint":"missing","displayName":"X","baseRate":0.1}]
                                """))));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"openrouter","displayName":"X","baseRate":0}]
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"openrouter","displayName":"X"}]
                        """)));
    }

    @Test
    void updateReplacesPlacementsAndKeepsAllowList() throws Exception {
        ObjectNode updated = AgentLlmModelCatalog.update(document(), "moonshotai/kimi-k3",
                AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"fireworks","displayName":"Kimi-K3-Fireworks","baseRate":0.5,
                          "validProviders":["fireworks"]}]
                        """)));

        assertFalse(updated.at("/endpoints/openrouter/models").has("moonshotai/kimi-k3"));
        assertEquals("Kimi-K3-Fireworks",
                updated.at("/endpoints/fireworks/models/moonshotai~1kimi-k3/displayName").asText());
        assertEquals("moonshotai/kimi-k3", updated.at("/models/0").asText());
        assertEquals("moonshotai/kimi-k3", updated.get("defaultModel").asText());
    }

    @Test
    void deleteRemovesAllowListAndEveryEndpointButKeepsReferences() throws Exception {
        ObjectNode doc = document();
        ObjectNode removed = AgentLlmModelCatalog.delete(doc, "qwen3.6-flash");
        assertFalse(removed.at("/endpoints/dashscope-cn/models").has("qwen3.6-flash"));
        assertFalse(removed.get("models").toString().contains("qwen3.6-flash"));
        assertEquals("secret-dashscope", removed.at("/endpoints/dashscope-cn/apiKey").asText());

        IllegalArgumentException summary = assertThrows(IllegalArgumentException.class,
                () -> AgentLlmModelCatalog.delete(doc, "qwen/qwen3.8-flash"));
        assertTrue(summary.getMessage().contains("/tools/result/summary-model"));
        IllegalArgumentException defaultModel = assertThrows(IllegalArgumentException.class,
                () -> AgentLlmModelCatalog.delete(doc, "moonshotai/kimi-k3"));
        assertTrue(defaultModel.getMessage().contains("/defaultModel"));
        assertThrows(ConfigNotFoundException.class, () -> AgentLlmModelCatalog.delete(doc, "missing-model"));
    }

    @Test
    void featuresStayStringArrayThroughListAndUpdate() throws Exception {
        ObjectNode created = AgentLlmModelCatalog.create(document(), "qwen3.7-max",
                AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"dashscope-cn","displayName":"Qwen3.7-Max","baseRate":0.6,
                          "features":["thinking"]}]
                        """)));
        assertEquals(List.of("thinking"), objectMapper.convertValue(
                created.at("/endpoints/dashscope-cn/models/qwen3.7-max/features"), List.class));

        AgentLlmModelCatalog.ModelRecord listed = AgentLlmModelCatalog.list(created).stream()
                .filter(record -> "qwen3.7-max".equals(record.modelId()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("thinking"), listed.placements().get(0).features());

        ObjectNode updated = AgentLlmModelCatalog.update(created, "qwen3.7-max",
                AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"dashscope-cn","displayName":"Qwen3.7-Max","baseRate":0.6,
                          "features":["thinking"]}]
                        """)));
        assertEquals(List.of("thinking"), objectMapper.convertValue(
                updated.at("/endpoints/dashscope-cn/models/qwen3.7-max/features"), List.class));
        assertThrows(IllegalArgumentException.class,
                () -> AgentLlmModelCatalog.readPlacements(objectMapper.readTree("""
                        [{"endpoint":"dashscope-cn","displayName":"X","baseRate":0.1,"features":{"thinking":true}}]
                        """)));
    }

    @Test
    void listMarksEndpointOnlyModelsAsNotStartable() throws Exception {
        List<AgentLlmModelCatalog.ModelRecord> records = AgentLlmModelCatalog.list(document());
        AgentLlmModelCatalog.ModelRecord flash = records.stream()
                .filter(record -> "qwen3.6-flash".equals(record.modelId()))
                .findFirst()
                .orElseThrow();
        assertFalse(flash.startable());
        assertEquals("dashscope-cn", flash.placements().get(0).endpoint());
        assertEquals(0.1, flash.placements().get(0).baseRate(), 0.0001);

        AgentLlmModelCatalog.ModelRecord kimi = records.stream()
                .filter(record -> "moonshotai/kimi-k3".equals(record.modelId()))
                .findFirst()
                .orElseThrow();
        assertTrue(kimi.startable());
    }

    private ObjectNode document() throws Exception {
        return (ObjectNode) objectMapper.readTree("""
                {
                  "defaultModel": "moonshotai/kimi-k3",
                  "models": ["moonshotai/kimi-k3", "qwen/qwen3.8-flash"],
                  "endpoints": {
                    "openrouter": {
                      "apiKey": "secret-openrouter",
                      "models": {
                        "moonshotai/kimi-k3": {"displayName": "Kimi-K3", "baseRate": 0.5}
                      }
                    },
                    "fireworks": {
                      "apiKey": "secret-fireworks",
                      "models": {}
                    },
                    "dashscope-cn": {
                      "apiKey": "secret-dashscope",
                      "models": {
                        "qwen3.6-flash": {"displayName": "Qwen3.6-Flash", "baseRate": 0.1}
                      }
                    }
                  },
                  "tools": {"result": {"summary-model": "qwen/qwen3.8-flash", "summary-endpoint": "openrouter"}}
                }
                """);
    }
}
