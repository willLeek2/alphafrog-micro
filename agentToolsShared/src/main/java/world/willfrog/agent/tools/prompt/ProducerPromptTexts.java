package world.willfrog.agent.tools.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 实际结果构造点显式标出政策字段；JSON编码保留同一字段实际采用的来源。 */
@Component
public final class ProducerPromptTexts {
    private final AgentPromptTextReader reader;
    public ProducerPromptTexts(AgentPromptTextReader reader) { this.reader = reader; }
    public PromptText policy(String fragment) { return policy(fragment, Map.of()); }
    public PromptText policy(String fragment, Map<String, String> facts) {
        var root = reader.current();
        var body = reader.read(root, Map.of(fragment, fragment)).get(fragment);
        return reader.render(body, facts, Map.of());
    }
    public static PromptText json(ObjectMapper mapper, Object payload) throws Exception {
        List<PromptVersionSource> sources = new ArrayList<>();
        Object actual = materialize(payload, sources);
        return new PromptText(mapper.writeValueAsString(actual), List.copyOf(new LinkedHashSet<>(sources)));
    }
    public static PromptText failure(ObjectMapper mapper, String tool, String code, Object message,
                                     Map<String, Object> details, Map<String, Object> data,
                                     world.willfrog.agent.platform.failure.ToolFailureCategory category) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false); payload.put("tool", tool); payload.put("data", data == null ? Map.of() : data);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code == null ? "" : code); error.put("message", message == null ? "" : message);
        error.put("details", details == null ? Map.of() : details);
        world.willfrog.agent.platform.failure.ToolFailureCategory.putCategoryIfResolved(error, code, category);
        payload.put("error", error);
        try { return json(mapper, payload); }
        catch (Exception failure) {
            world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks.rethrowIfBlocked(failure);
            throw new world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException(
                    world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_SOURCE_MISSING,
                    "工具结果正文与来源无法一起编码");
        }
    }

    private static Object materialize(Object value, List<PromptVersionSource> sources) {
        if (value instanceof PromptText text) { sources.addAll(text.sources()); return text.text(); }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> actual = new LinkedHashMap<>();
            map.forEach((key, item) -> actual.put(String.valueOf(key), materialize(item, sources)));
            return actual;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> materialize(item, sources)).toList();
        return value;
    }
}
