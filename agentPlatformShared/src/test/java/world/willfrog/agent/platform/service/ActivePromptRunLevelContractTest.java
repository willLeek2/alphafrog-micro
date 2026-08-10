package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 260623-harness-optimization-03: 主动 prompt 文件回归 —— 所有向 LLM 暴露的 executePython 相关 prompt
 * 必须 teaching run-level 编号机制，且不得再出现旧 3 层路径 / data.dataset_id / originalId 等旧契约。
 */
class ActivePromptRunLevelContractTest {

    private static final String[] ACTIVE_PROMPT_FILES = {
            "prompts/agent/agent_run_system.txt",
            "prompts/python/execute_python_tool_description.txt",
            "prompts/python/python_refine_system.txt",
            "prompts/python/python_refine_requirements.txt",
            "prompts/todo/dag_react_system.txt",
            "prompts/todo/dag_react_system_default.txt",
            "prompts/todo/todo_planner_system.txt",
            "prompts/workflow/workflow_todo_recovery_system.txt",
            "prompts/sub_agent/sub_agent_planner_system.txt",
            "prompts/parallel/parallel_planner_system.txt",
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "prompts/todo/todo_planner_system.txt",
            "prompts/workflow/workflow_todo_recovery_system.txt",
            "prompts/sub_agent/sub_agent_planner_system.txt",
            "prompts/parallel/parallel_planner_system.txt",
            "prompts/python/python_refine_system.txt",
            "prompts/python/python_refine_requirements.txt",
    })
    void activePromptFiles_shouldTeachRunLevelIds(String path) {
        String prompt = PromptFileLoader.load(path);
        assertFalse(prompt.isBlank(), path + " 必须可加载");
        assertTrue(containsRunLevelInstruction(prompt),
                path + " 必须说明 run-level 编号机制");
        assertTrue(prompt.contains("listMyData"),
                path + " 必须说明 listMyData 恢复路径");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "prompts/python/execute_python_tool_description.txt",
            "prompts/workflow/workflow_todo_recovery_system.txt",
            "prompts/python/python_refine_requirements.txt",
    })
    void activePromptFiles_shouldMentionCsvIndexes(String path) {
        String prompt = PromptFileLoader.load(path);
        assertFalse(prompt.isBlank(), path + " 必须可加载");
        assertTrue(prompt.contains("paths_dataset.csv"),
                path + " 必须说明 paths_dataset.csv");
        assertTrue(prompt.contains("path_manifest.csv"),
                path + " 必须说明 path_manifest.csv");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "prompts/agent/agent_run_system.txt",
            "prompts/python/execute_python_tool_description.txt",
            "prompts/todo/dag_react_system.txt",
            "prompts/todo/dag_react_system_default.txt",
            "prompts/todo/todo_planner_system.txt",
            "prompts/workflow/workflow_todo_recovery_system.txt",
            "prompts/sub_agent/sub_agent_planner_system.txt",
            "prompts/parallel/parallel_planner_system.txt",
            "prompts/python/python_refine_system.txt",
            "prompts/python/python_refine_requirements.txt",
    })
    void activePromptFiles_shouldNotContainOldContractPatterns(String path) {
        String prompt = PromptFileLoader.load(path);
        assertFalse(prompt.isBlank(), path + " 必须可加载");
        assertFalse(prompt.contains("data.dataset_id"),
                path + " 不得出现旧 data.dataset_id 引用");
        assertFalse(prompt.contains("glob.glob('/sandbox/input/*')"),
                path + " 不得要求 glob 遍历旧挂载目录");
        assertFalse(prompt.contains("/sandbox/input/<dataset_id>/<dataset_id>.meta.json"),
                path + " 不得出现旧 meta.json 路径");
        assertFalse(prompt.contains("scopeHash"),
                path + " 不得出现 scopeHash");
        assertFalse(prompt.contains("originalId"),
                path + " 不得向 LLM 暴露 originalId");
        assertFalse(prompt.contains("dataset_id directory not found"),
                path + " 不得出现旧失败模式文案");
        assertFalse(prompt.contains("遍历所有挂载的数据集"),
                path + " 不得要求遍历所有挂载数据集");
        assertFalse(prompt.contains("数据文件路径固定为 /sandbox/input/<dataset_id>/<dataset_id>.csv"),
                path + " 不得声明旧固定路径");
        assertFalse(prompt.contains("必须来自已完成依赖任务的 `data.dataset_id`"),
                path + " 不得要求从 data.dataset_id 获取 ID");
        assertFalse(prompt.contains("load_datasets(\"1,3\")"),
                path + " 不得示例把逗号分隔编号直接传给 load_datasets");
        assertFalse(prompt.contains("load_datasets('1,3')"),
                path + " 不得示例把逗号分隔编号直接传给 load_datasets");
    }

    @Test
    void applicationAgentLlmPromptsYml_shouldNotMaintainInlinePromptBodies() {
        String yaml = loadResourceAsString("application-agent-llm-prompts.yml");
        assertFalse(yaml.isBlank(), "application-agent-llm-prompts.yml 必须可加载");
        assertTrue(yaml.contains("prompts: {}"), "YAML 应只保留空 prompts 投影入口");
        assertTrue(yaml.contains("Prompt 权威正文只保存在"), "YAML 必须说明 Q-09 权威边界");
        assertFalse(yaml.contains("agent-run-system-prompt:"), "YAML 不得手写第二份 Prompt 正文");
        assertFalse(yaml.contains("python-refine-requirements:"), "YAML 不得手写第二份列表正文");
        assertFalse(yaml.contains("dataset-field-specs:"), "YAML 不得手写第二份字段规格");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "prompts/workflow/workflow_todo_recovery_system.txt",
            "prompts/sub_agent/sub_agent_planner_system.txt",
    })
    void activePromptFiles_shouldTeachRawRefRereadTool(String path) {
        String prompt = PromptFileLoader.load(path);
        assertFalse(prompt.isBlank(), path + " 必须可加载");
        assertTrue(prompt.contains("rereadToolResult"),
                path + " 必须说明 rawRef 使用 rereadToolResult");
        assertTrue(prompt.contains("loadDocument"),
                path + " 必须说明 rawRef 不传给 loadDocument");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "prompts/agent/agent_run_system.txt",
            "prompts/todo/dag_react_system.txt",
            "prompts/todo/dag_react_system_default.txt",
    })
    void stableAndGenericPromptLayers_shouldRemainCapabilityNeutral(String path) {
        String prompt = PromptFileLoader.load(path);
        assertFalse(prompt.isBlank(), path + " 必须可加载");
        for (String toolName : new String[]{
                "executePython", "listMyData", "searchWeb", "checkParallelLimits",
                "rereadToolResult", "loadDocument"}) {
            assertFalse(prompt.contains(toolName),
                    path + " 不得泄漏未确认开放的工具 " + toolName);
        }
    }

    @Test
    void pythonSandboxGuide_shouldTeachRunLevelContract() {
        String guide = loadResourceAsString("agent_guides/python_sandbox.md");
        assertFalse(guide.isBlank(), "agent_guides/python_sandbox.md 必须可加载");
        assertTrue(guide.contains("run-level"),
                "guide 必须说明 run-level 编号");
        assertTrue(guide.contains("paths_dataset.csv"),
                "guide 必须说明 paths_dataset.csv");
        assertTrue(guide.contains("path_manifest.csv"),
                "guide 必须说明 path_manifest.csv");
        assertTrue(guide.contains("listMyData"),
                "guide 必须说明 listMyData 恢复路径");
        assertTrue(guide.contains("UNCERTAIN"),
                "guide 必须说明 UNCERTAIN 语义");
        assertFalse(guide.contains("dataset_ids` 必填"),
                "guide 不得再说 dataset_ids 必填（现在 dataset_ids / manifest_ids 至少一个）");
        assertFalse(guide.contains("/sandbox/input/<dataset_id>/<dataset_id>.csv") || guide.contains("/sandbox/input/<dataset_id>/"),
                "guide 不得再正向 teaching 旧 /sandbox/input/<dataset_id>/ 路径");
    }

    @Test
    void executePythonTipsGuide_shouldTeachRunLevelContract() {
        String guide = loadResourceAsString("agent_guides/execute_python_tips.md");
        assertFalse(guide.isBlank(), "agent_guides/execute_python_tips.md 必须可加载");
        assertTrue(guide.contains("run-level"),
                "tips guide 必须说明 run-level 编号");
        assertTrue(guide.contains("paths_dataset.csv"),
                "tips guide 必须说明 paths_dataset.csv");
        assertTrue(guide.contains("path_manifest.csv"),
                "tips guide 必须说明 path_manifest.csv");
        assertTrue(guide.contains("listMyData"),
                "tips guide 必须说明 listMyData 恢复路径");
        assertTrue(guide.contains("UNCERTAIN"),
                "tips guide 必须说明 UNCERTAIN 语义");
        assertFalse(guide.contains("glob.glob(\"/sandbox/input/*\")"),
                "tips guide 不得要求 glob 遍历旧挂载目录");
        assertFalse(guide.contains("<dataset_id>.csv"),
                "tips guide 不得使用旧 <dataset_id>.csv 模板");
    }

    @Test
    void datasetManifestGuide_shouldTeachRunLevelManifestIds() {
        String guide = loadResourceAsString("agent_guides/dataset_manifest.md");
        assertFalse(guide.isBlank(), "agent_guides/dataset_manifest.md 必须可加载");
        assertTrue(guide.contains("manifest_ids"),
                "manifest guide 必须说明 manifest_ids");
        assertTrue(guide.contains("run-level"),
                "manifest guide 必须说明 run-level 编号");
        assertTrue(guide.contains("load_manifest(\"1\")"),
                "manifest guide 必须示例按 run-level 编号读取 manifest");
        assertFalse(guide.contains("/sandbox/input/<manifest_id>"),
                "manifest guide 不得 teaching 旧 /sandbox/input/<manifest_id> 路径");
        assertFalse(guide.contains("executePython` 的 `dataset_ids` 可只传 manifest id"),
                "manifest guide 不得要求用 dataset_ids 传 manifest");
    }

    private static boolean containsRunLevelInstruction(String prompt) {
        return prompt.contains("run-level") || prompt.contains("run level") || prompt.contains("run-level 整数编号");
    }

    private static String loadResourceAsString(String path) {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (is == null) {
            is = ActivePromptRunLevelContractTest.class.getClassLoader().getResourceAsStream(path);
        }
        if (is == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }
}
