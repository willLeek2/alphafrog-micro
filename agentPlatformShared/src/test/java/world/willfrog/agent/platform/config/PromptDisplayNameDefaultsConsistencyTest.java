package world.willfrog.agent.platform.config;

import org.junit.jupiter.api.Test;
import world.willfrog.alphafrogmicro.common.config.PromptDisplayNameDefaults;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 common 模块的 {@link PromptDisplayNameDefaults#DEFAULTS} 与
 * {@link AgentLlmProperties.Prompts} 的提示词字段保持一一对齐：
 * 新增提示词字段而忘了补默认中文名时，这里会直接失败。
 *
 * <p>两个刻意的排除项：{@code displayNames} 字段本身（它是名称表，不是提示词内容字段，
 * 故意不进默认表）与 static 字段（默认表只对齐实例字段）。另过滤合成字段，避免字节码
 * 增强工具注入的字段干扰反射结果。</p>
 *
 * <p>不做键顺序断言：{@code getDeclaredFields()} 的返回顺序在 Java 文档中明确不保证，
 * 而 DEFAULTS 的顺序口径见该类的类注释，由评审保证。</p>
 */
class PromptDisplayNameDefaultsConsistencyTest {

    /** 名称表字段本身不进默认表，见类注释。 */
    private static final String NAME_TABLE_FIELD = "displayNames";

    @Test
    void defaultsShouldCoverExactlyAllPromptContentFields() {
        Set<String> promptContentFields = new LinkedHashSet<>();
        for (Field field : AgentLlmProperties.Prompts.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue; // 排除 static 字段：默认表只覆盖实例字段
            }
            if (field.isSynthetic()) {
                continue; // 排除编译器或字节码增强工具注入的合成字段
            }
            if (NAME_TABLE_FIELD.equals(field.getName())) {
                continue; // displayNames 是名称表本身，不是提示词内容字段
            }
            promptContentFields.add(field.getName());
        }

        assertEquals(promptContentFields, PromptDisplayNameDefaults.DEFAULTS.keySet(),
                "DEFAULTS 的键集合应与 Prompts 的提示词内容字段完全一致（多=该删，少=漏补默认名）");
    }

    @Test
    void defaultsShouldContainFortySixNonBlankEntries() {
        assertEquals(46, PromptDisplayNameDefaults.DEFAULTS.size(),
                "当前提示词内容字段共 46 个，默认表条目数应与之相等");

        PromptDisplayNameDefaults.DEFAULTS.forEach((fieldName, displayName) -> {
            assertFalse(displayName == null || displayName.isBlank(),
                    "字段 " + fieldName + " 的默认中文名不能为空");
        });
        // 全表至少要有一个非西文的中文名，防止整表被误替换成英文占位
        assertTrue(PromptDisplayNameDefaults.DEFAULTS.values().stream().anyMatch(v -> v.codePoints().anyMatch(cp -> cp > 0x4E00)),
                "默认表应包含中文名");
    }
}
