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
            "prompts/todo/dag_react_system.txt",
            "prompts/todo/dag_react_system_default.txt",
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
            "prompts/agent/agent_run_system.txt",
            "prompts/python/execute_python_tool_description.txt",
            "prompts/todo/dag_react_system.txt",
            "prompts/todo/dag_react_system_default.txt",
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
    }

    @Test
    void applicationAgentLlmPromptsYml_shouldNotContainOldContractPatterns() {
        String yaml = loadResourceAsString("application-agent-llm-prompts.yml");
        assertFalse(yaml.isBlank(), "application-agent-llm-prompts.yml 必须可加载");
        assertFalse(yaml.contains("data.dataset_id"),
                "application-agent-llm-prompts.yml 不得出现旧 data.dataset_id 引用");
        assertFalse(yaml.contains("glob.glob('/sandbox/input/*')"),
                "application-agent-llm-prompts.yml 不得要求 glob 遍历旧挂载目录");
        assertFalse(yaml.contains("/sandbox/input/<dataset_id>/<dataset_id>.meta.json"),
                "application-agent-llm-prompts.yml 不得出现旧 meta.json 路径");
        assertFalse(yaml.contains("scopeHash"),
                "application-agent-llm-prompts.yml 不得出现 scopeHash");
        assertFalse(yaml.contains("originalId"),
                "application-agent-llm-prompts.yml 不得向 LLM 暴露 originalId");
        assertTrue(yaml.contains("run-level"),
                "application-agent-llm-prompts.yml 必须说明 run-level 编号");
        assertTrue(yaml.contains("listMyData"),
                "application-agent-llm-prompts.yml 必须说明 listMyData");
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
