package world.willfrog.agent.platform.service;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.util.PromptFileLoader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 260623-harness-optimization-03: 回归测试 —— system prompt / tool description 不再暴露旧 3 层路径，
 * 并正确说明 run-level 编号机制。
 */
class AgentPromptServiceRunLevelPromptTest {

    @Test
    void agentRunSystemPromptClasspathFile_shouldContainRunLevelInstructions() {
        String prompt = PromptFileLoader.load("prompts/agent/agent_run_system.txt");
        assertFalse(prompt.isBlank(), "classpath agent_run_system.txt 必须可加载");
        assertTrue(prompt.contains("run 内为每个 dataset / manifest 分配一个从 1 开始递增的整数编号"),
                "system prompt 必须说明 run-level 编号机制");
        assertTrue(prompt.contains("优先传 manifest 编号"),
                "system prompt 必须说明优先使用 manifest 编号");
        assertTrue(prompt.contains("不要传入原始 dataset_id、文件路径、scope hash 或 runId"),
                "system prompt 必须禁止传入旧式 id/路径");
        assertTrue(prompt.contains("paths_dataset.csv"),
                "system prompt 必须说明 paths_dataset.csv");
        assertTrue(prompt.contains("path_manifest.csv"),
                "system prompt 必须说明 path_manifest.csv");
        assertTrue(prompt.contains("UNCERTAIN"),
                "system prompt 必须说明 UNCERTAIN 语义");
        assertTrue(prompt.contains("listMyData"),
                "system prompt 必须说明 listMyData 恢复路径");
        assertTrue(prompt.contains("连续失败 2 次"),
                "system prompt 必须说明连续失败停止策略");
    }

    @Test
    void agentRunSystemPromptClasspathFile_shouldNotContainOldPaths() {
        String prompt = PromptFileLoader.load("prompts/agent/agent_run_system.txt");
        assertFalse(prompt.contains("/sandbox/input/"),
                "system prompt 不得出现旧 /sandbox/input/ 路径");
        assertFalse(prompt.contains("data.dataset_id"),
                "system prompt 不得出现旧 data.dataset_id 引用");
        assertFalse(prompt.contains("scopeHash"),
                "system prompt 不得出现 scopeHash");
        assertFalse(prompt.contains("dataset_id directory not found"),
                "system prompt 不得出现旧失败模式文案");
    }

    @Test
    void executePythonToolDescriptionClasspathFile_shouldContainRunLevelInstructions() {
        String desc = PromptFileLoader.load("prompts/python/execute_python_tool_description.txt");
        assertFalse(desc.isBlank(), "classpath execute_python_tool_description.txt 必须可加载");
        assertTrue(desc.contains("agent run-level numbers"),
                "tool description 必须说明 agent run-level 编号");
        assertTrue(desc.contains("paths_dataset.csv"),
                "tool description 必须说明 paths_dataset.csv");
        assertTrue(desc.contains("path_manifest.csv"),
                "tool description 必须说明 path_manifest.csv");
        assertTrue(desc.contains("listMyData"),
                "tool description 必须说明 listMyData 恢复路径");
        assertTrue(desc.contains("UNCERTAIN"),
                "tool description 必须说明 UNCERTAIN 语义");
        assertTrue(desc.contains("ILLEGAL_RUN_LEVEL_IDS"),
                "tool description 必须说明非法编号错误");
    }

    @Test
    void executePythonToolDescriptionClasspathFile_shouldNotContainOldPaths() {
        String desc = PromptFileLoader.load("prompts/python/execute_python_tool_description.txt");
        assertFalse(desc.contains("/sandbox/input/<dataset_id>"),
                "tool description 不得出现旧 /sandbox/input/<dataset_id> 路径");
        assertFalse(desc.contains("originalId"),
                "tool description 不得向 LLM 暴露 originalId");
        assertFalse(desc.contains("scopeHash"),
                "tool description 不得出现 scopeHash");
        assertFalse(desc.contains("dataset_id directory not found"),
                "tool description 不得出现旧失败模式文案");
    }
}
