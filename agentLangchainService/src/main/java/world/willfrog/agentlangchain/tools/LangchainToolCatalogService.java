package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LangchainToolCatalogService {

    private final MarketDataTools marketDataTools;
    private final RagTools ragTools;
    private final SearchTools searchTools;
    private final PythonSandboxTools pythonSandboxTools;
    private final ListMyDataTool listMyDataTool;
    private final LoadToolGuideTool loadToolGuideTool;
    private final RereadToolHandler rereadToolHandler;
    private final ObjectMapper objectMapper;

    /**
     * 返回对外 API 可见的工具目录。
     *
     * <p>当前 Dubbo 入口 {@code ListAgentToolsRequest} 仅携带 user_id，没有 run 级能力开关上下文，
     * 因此这里构建的是「文档全量视图」：webSearch、codeInterpreter 两个能力门控按开启处理。
     * 实际运行时 LLM 看到的目录由 {@link ToolRouterToolProvider} 根据
     * {@link ToolCatalogBuilder} 的能力门控实时过滤，可能与全量视图不同。</p>
     *
     * <p>本方法复用 {@link ToolCatalogBuilder} 的共享构建路径，保证 API 目录与运行时目录的
     * canonical 覆盖、resolveFinanceMethods 兜底逻辑完全一致。</p>
     */
    public List<AgentToolMessage> listToolMessages() {
        List<ToolSpecification> specifications = ToolCatalogBuilder.buildSpecifications(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                listMyDataTool,
                loadToolGuideTool,
                rereadToolHandler,
                true,
                true
        );

        List<AgentToolMessage> messages = new ArrayList<>();
        for (ToolSpecification spec : specifications) {
            messages.add(AgentToolMessage.newBuilder()
                    .setName(nvl(spec.name()))
                    .setDescription(nvl(spec.description()))
                    .setParametersJson(writeParameters(spec))
                    .build());
        }
        return messages;
    }

    private String writeParameters(ToolSpecification spec) {
        if (spec == null || spec.parameters() == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(toJsonSchema(spec.parameters()));
        } catch (Exception e) {
            log.warn("Serialize langchain tool schema failed: tool={}", spec.name(), e);
            return "{}";
        }
    }

    private Map<String, Object> toJsonSchema(JsonSchemaElement schema) {
        Map<String, Object> json = new LinkedHashMap<>();
        if (schema == null) {
            return json;
        }
        if (schema instanceof JsonObjectSchema objectSchema) {
            json.put("type", "object");
            putDescription(json, objectSchema.description());
            Map<String, Object> properties = new LinkedHashMap<>();
            if (objectSchema.properties() != null) {
                objectSchema.properties().forEach((name, child) -> properties.put(name, toJsonSchema(child)));
            }
            json.put("properties", properties);
            if (objectSchema.required() != null && !objectSchema.required().isEmpty()) {
                json.put("required", objectSchema.required());
            }
            if (objectSchema.additionalProperties() != null) {
                json.put("additionalProperties", objectSchema.additionalProperties());
            }
            if (objectSchema.definitions() != null && !objectSchema.definitions().isEmpty()) {
                Map<String, Object> definitions = new LinkedHashMap<>();
                objectSchema.definitions().forEach((name, child) -> definitions.put(name, toJsonSchema(child)));
                json.put("$defs", definitions);
            }
            return json;
        }
        if (schema instanceof JsonStringSchema stringSchema) {
            json.put("type", "string");
            putDescription(json, stringSchema.description());
            return json;
        }
        if (schema instanceof JsonBooleanSchema booleanSchema) {
            json.put("type", "boolean");
            putDescription(json, booleanSchema.description());
            return json;
        }
        if (schema instanceof JsonIntegerSchema integerSchema) {
            json.put("type", "integer");
            putDescription(json, integerSchema.description());
            return json;
        }
        if (schema instanceof JsonNumberSchema numberSchema) {
            json.put("type", "number");
            putDescription(json, numberSchema.description());
            return json;
        }
        if (schema instanceof JsonArraySchema arraySchema) {
            json.put("type", "array");
            putDescription(json, arraySchema.description());
            json.put("items", toJsonSchema(arraySchema.items()));
            return json;
        }
        if (schema instanceof JsonEnumSchema enumSchema) {
            json.put("type", "string");
            putDescription(json, enumSchema.description());
            json.put("enum", enumSchema.enumValues());
            return json;
        }
        if (schema instanceof JsonAnyOfSchema anyOfSchema) {
            putDescription(json, anyOfSchema.description());
            List<Object> anyOf = new ArrayList<>();
            if (anyOfSchema.anyOf() != null) {
                for (JsonSchemaElement child : anyOfSchema.anyOf()) {
                    anyOf.add(toJsonSchema(child));
                }
            }
            json.put("anyOf", anyOf);
            return json;
        }
        if (schema instanceof JsonReferenceSchema referenceSchema) {
            putDescription(json, referenceSchema.description());
            json.put("$ref", referenceSchema.reference());
            return json;
        }
        putDescription(json, schema.description());
        return json;
    }

    private void putDescription(Map<String, Object> json, String description) {
        if (description != null && !description.isBlank()) {
            json.put("description", description);
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
