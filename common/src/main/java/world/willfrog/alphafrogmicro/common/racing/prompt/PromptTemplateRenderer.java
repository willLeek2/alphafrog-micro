package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;
import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_VARIABLE_INVALID;

/** 简单的双花括号模板：仅扫描原模板，不解释插入数据中的占位符。 */
public final class PromptTemplateRenderer {

    private PromptTemplateRenderer() {
    }

    /** 保存版本时校验占位符与声明双向一致，固定正文不解析双花括号。 */
    public static void validate(PromptBodyType type, String body,
                                List<PromptVariableDeclaration> variables) {
        if (type == null || body == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "body");
        }
        Map<String, PromptVariableDeclaration> declarations = declarations(variables);
        if (type == PromptBodyType.FIXED) {
            if (!declarations.isEmpty()) {
                throw invalid(declarations.keySet().iterator().next());
            }
            return;
        }
        Set<String> used = new LinkedHashSet<>();
        for (Placeholder placeholder : placeholders(body)) {
            if (!declarations.containsKey(placeholder.name())) {
                throw invalid(placeholder.name());
            }
            used.add(placeholder.name());
        }
        for (String name : declarations.keySet()) {
            if (!used.contains(name)) {
                throw invalid(name);
            }
        }
    }

    public static PromptText render(PromptVersionBody version, Map<String, String> values) {
        if (version == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "version");
        }
        if (version.type() == PromptBodyType.FIXED) {
            return PromptText.instruction(version.body(), version.source());
        }
        Map<String, PromptVariableDeclaration> declarations = declarations(version.variables());
        Map<String, String> actualValues = values == null ? Map.of() : new LinkedHashMap<>(values);
        StringBuilder rendered = new StringBuilder();
        int cursor = 0;
        for (Placeholder placeholder : placeholders(version.body())) {
            rendered.append(version.body(), cursor, placeholder.start());
            PromptVariableDeclaration declaration = declarations.get(placeholder.name());
            String value = actualValues.containsKey(placeholder.name())
                    ? actualValues.get(placeholder.name()) : declaration.defaultValue();
            if ((declaration.required() && !actualValues.containsKey(placeholder.name()))
                    || value == null || (value.isEmpty() && !declaration.allowEmpty())) {
                throw invalid(placeholder.name());
            }
            rendered.append(value);
            cursor = placeholder.end();
        }
        rendered.append(version.body(), cursor, version.body().length());
        return PromptText.instruction(rendered.toString(), version.source());
    }

    public static PromptText render(PromptVersionBody version, String expectedPurpose,
                                    Map<String, String> values) {
        if (version == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "version");
        }
        return render(version.requirePurpose(expectedPurpose), values);
    }

    private static Map<String, PromptVariableDeclaration> declarations(
            List<PromptVariableDeclaration> variables) {
        if (variables == null) {
            throw invalid("variables");
        }
        Map<String, PromptVariableDeclaration> declarations = new LinkedHashMap<>();
        for (PromptVariableDeclaration variable : variables) {
            if (variable == null) {
                throw invalid("variables");
            }
            if (declarations.putIfAbsent(variable.name(), variable) != null) {
                throw invalid(variable.name());
            }
        }
        return declarations;
    }

    private static List<Placeholder> placeholders(String body) {
        List<Placeholder> placeholders = new ArrayList<>();
        int cursor = 0;
        int opening;
        while ((opening = body.indexOf("{{", cursor)) >= 0) {
            int closing = body.indexOf("}}", opening + 2);
            if (closing < 0) {
                throw invalid("variables");
            }
            String name = body.substring(opening + 2, closing);
            if (!PromptVariableDeclaration.isValidName(name)) {
                throw invalid("variables");
            }
            placeholders.add(new Placeholder(opening, closing + 2, name));
            cursor = closing + 2;
        }
        return placeholders;
    }

    private static ModelSendBlockedException invalid(String name) {
        return new ModelSendBlockedException(PROMPT_VARIABLE_INVALID, name);
    }

    private record Placeholder(int start, int end, String name) {
    }
}
