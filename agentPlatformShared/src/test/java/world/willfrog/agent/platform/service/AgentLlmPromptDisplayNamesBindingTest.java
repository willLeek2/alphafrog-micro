package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prompts.displayNames 的 JSON 绑定行为测试：走 {@link AgentLlmLocalConfigLoader} 的真实
 * 加载路径（与主代码同一套 Jackson 绑定和 sanitize 流程），断言 displayNames 对象能绑进
 * AgentLlmProperties，且配置里没写该键时得到的是空 Map 而不是 null。
 *
 * <p>测试配置里只放 displayNames 一个 prompts 键：提示词正文字段如果显式配置，会触发
 * PromptAuthority 的投影校验（正文必须与 classpath 权威资源逐字一致），与本测试无关。</p>
 */
class AgentLlmPromptDisplayNamesBindingTest {

    @TempDir
    Path tempDir;

    @Test
    void loadShouldBindPromptDisplayNamesObjectIntoProperties() throws Exception {
        Path configFile = tempDir.resolve("agent-llm.local.json");
        Files.writeString(configFile, """
                {
                  "prompts": {
                    "displayNames": {
                      "todoRetryContextInstruction": "待办重试说明（测试值）",
                      "pythonRepairStageInstruction": "代码修复阶段（测试值）"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentLlmProperties.Prompts prompts = loadPrompts(configFile);

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("todoRetryContextInstruction", "待办重试说明（测试值）");
        expected.put("pythonRepairStageInstruction", "代码修复阶段（测试值）");
        assertEquals(expected, prompts.getDisplayNames(),
                "JSON 里 prompts.displayNames 的键值应原样绑进 getDisplayNames()");
    }

    @Test
    void loadShouldKeepDisplayNamesEmptyMapWhenConfigOmitsIt() throws Exception {
        // 场景一：有 prompts 节但没写 displayNames
        Path withPromptsOnly = tempDir.resolve("agent-llm.local.with-prompts.json");
        Files.writeString(withPromptsOnly, """
                {"prompts": {}}
                """, StandardCharsets.UTF_8);
        AgentLlmProperties.Prompts prompts = loadPrompts(withPromptsOnly);
        assertNotNull(prompts.getDisplayNames(), "没写 displayNames 时应为空 Map 而不是 null");
        assertTrue(prompts.getDisplayNames().isEmpty());

        // 场景二：整个配置都没有 prompts 节，Prompts 走字段初始化器的默认值
        Path withoutPrompts = tempDir.resolve("agent-llm.local.without-prompts.json");
        Files.writeString(withoutPrompts, """
                {"models": ["demo-provider/demo-model"]}
                """, StandardCharsets.UTF_8);
        AgentLlmProperties.Prompts defaultPrompts = loadPrompts(withoutPrompts);
        assertNotNull(defaultPrompts.getDisplayNames(), "没有 prompts 节时 displayNames 也应为空 Map");
        assertTrue(defaultPrompts.getDisplayNames().isEmpty());
    }

    /** 与既有 AgentLlmLocalConfigLoaderTest 相同的加载方式：临时配置文件 + 反射注入路径。 */
    private AgentLlmProperties.Prompts loadPrompts(Path configFile) {
        AgentLlmLocalConfigLoader loader = new AgentLlmLocalConfigLoader(new ObjectMapper());
        ReflectionTestUtils.setField(loader, "configFile", configFile.toString());
        loader.load();
        return loader.current().orElseThrow().getPrompts();
    }
}
