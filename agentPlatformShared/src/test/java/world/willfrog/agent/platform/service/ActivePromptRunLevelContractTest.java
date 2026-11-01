package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.util.PromptFileLoader;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
