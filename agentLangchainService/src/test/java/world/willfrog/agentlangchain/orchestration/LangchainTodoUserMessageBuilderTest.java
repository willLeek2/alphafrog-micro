package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.service.AgentPromptService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainTodoUserMessageBuilderTest {

    @Test
    void buildTodoUserMessage_withDatasetRefs_guidesModelToResolveRunLevelIds() {
        AgentPromptService promptService = mock(AgentPromptService.class);
        when(promptService.dynamicContextPrefix()).thenReturn("dynamic-context");
        Map<String, String> datasetRefs = new LinkedHashMap<>();
        datasetRefs.put("raw-dataset-abc", "previous tool output");

        String message = LangchainTodoUserMessageBuilder.buildTodoUserMessage(
                promptService,
                "analyze market data",
                List.of(),
                datasetRefs,
                "run python analysis",
                List.of());

        // D10: Site A 常驻 executePython 提示已删除，只保留 ID 列表。
        // 运行时 hint 由 ToolRouterToolExecutor.appendDatasetRetryHintIfNeeded 承担。
        assertTrue(message.contains("已有原始数据集 ID"));
        assertTrue(message.contains("raw-dataset-abc"));
        // 以下 executePython 使用指导不再出现在 prompt 构建阶段
        assertFalse(message.contains("run-level dataset_ids/manifest_ids"));
        assertFalse(message.contains("listMyData"));
        assertFalse(message.contains("query_type=dataset"));
        assertFalse(message.contains("query_type=manifest"));
        assertFalse(message.contains("整数 dataset_ids / manifest_ids"));
    }
}
