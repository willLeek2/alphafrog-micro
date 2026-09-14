package world.willfrog.agent.tools.python;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 260623-harness-optimization-03: 回归测试 —— executePython tool description 通过 classpath 加载后
 * 必须对齐 run-level 编号语义，且不暴露旧路径/originalId。
 */
class PythonSandboxToolsDescriptionRegressionTest {

    @Test
    void loadToolDescription_shouldContainRunLevelSurface() {
        String desc = PythonSandboxTools.loadToolDescription();
        assertFalse(desc.isBlank(), "tool description 必须能加载（或至少 fallback 非空）");
        assertTrue(desc.contains("agent run-level"),
                "tool description 必须说明 run-level 编号");
        assertTrue(desc.contains("paths_dataset.csv"),
                "tool description 必须包含 paths_dataset.csv");
        assertTrue(desc.contains("path_manifest.csv"),
                "tool description 必须包含 path_manifest.csv");
        assertTrue(desc.contains("resolveFinanceMethods"),
                "tool description 必须提示可把金融问题原始表达交给 resolveFinanceMethods");
        assertTrue(desc.contains("unresolved boundaries") || desc.contains("未解决边界") || desc.contains("do not invent"),
                "tool description 必须提示候选有未解决边界时不能擅自补造");
        assertTrue(desc.contains("source_resolver_tool_call_id") || desc.contains("resolverToolCallId"),
                "tool description 必须提示显式传递 source_resolver_tool_call_id");
    }

    @Test
    void loadToolDescription_shouldMentionCompatibleLibraryPreference() {
        String desc = PythonSandboxTools.loadToolDescription();
        assertTrue(desc.contains("compatible public libraries") || desc.contains("兼容公共库"),
                "tool description 必须提示优先使用兼容公共库但不强制");
        assertTrue(desc.contains("custom calculations") || desc.contains("自定义计算"),
                "tool description 必须提示自定义计算按通用字段声明");
    }

    @Test
    void loadToolDescription_shouldNotExposeOldPathsOrOriginalIds() {
        String desc = PythonSandboxTools.loadToolDescription();
        assertFalse(desc.contains("/sandbox/input/<dataset_id>"),
                "tool description 不得出现旧 /sandbox/input/<dataset_id>");
        assertFalse(desc.contains("originalId"),
                "tool description 不得向 LLM 暴露 originalId 概念");
        assertFalse(desc.contains("scopeHash"),
                "tool description 不得出现 scopeHash");
        assertFalse(desc.contains("dataset_id directory not found"),
                "tool description 不得出现旧失败模式文案");
    }
}
