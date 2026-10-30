package world.willfrog.agent.tools.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.prompt.AgentPromptTextReader;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 市场工具把政策正文放入具体结果字段；编码时只保存实际保留字段的来源。 */
@Component
public final class MarketDataPromptPolicy {
    private final AgentPromptTextReader reader;

    public MarketDataPromptPolicy(AgentPromptTextReader reader) {
        this.reader = reader;
    }

    public PromptText read(String fragment, Map<String, String> facts) {
        var root = reader.current();
        var version = reader.read(root, Map.of(fragment, fragment)).get(fragment);
        return reader.render(version, facts, Map.of());
    }

    /** 指令字段用PromptText显式传入；普通字符串继续表示该构造点提供的业务事实。 */
    public static JsonValue materialize(Object value) {
        List<PromptVersionSource> sources = new ArrayList<>();
        Object actual = materialize(value, sources);
        return new JsonValue(actual, List.copyOf(new LinkedHashSet<>(sources)));
    }

    private static Object materialize(Object value, List<PromptVersionSource> sources) {
        if (value instanceof PromptText prompt) {
            sources.addAll(prompt.sources());
            return prompt.text();
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> actual = new LinkedHashMap<>();
            values.forEach((key, item) -> actual.put(String.valueOf(key), materialize(item, sources)));
            return actual;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(item -> materialize(item, sources)).toList();
        }
        return value;
    }

    public record JsonValue(Object value, List<PromptVersionSource> sources) { }
}
