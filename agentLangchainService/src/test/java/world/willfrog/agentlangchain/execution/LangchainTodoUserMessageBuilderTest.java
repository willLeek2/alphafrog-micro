package world.willfrog.agentlangchain.execution;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.service.AgentPromptService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static world.willfrog.agentlangchain.support.LangchainTestFixtures.promptService;

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
        assertTrue(message.contains("已有原始数据引用"));
        assertTrue(message.contains("raw-dataset-abc"));
        // 以下 executePython 使用指导不再出现在 prompt 构建阶段
        assertFalse(message.contains("run-level dataset_ids/manifest_ids"));
        assertFalse(message.contains("listMyData"));
        assertFalse(message.contains("query_type=dataset"));
        assertFalse(message.contains("query_type=manifest"));
        assertFalse(message.contains("整数 dataset_ids / manifest_ids"));
        assertFalse(message.contains("已有数据集 (可用于 dataset_ids 参数)"));
        assertFalse(message.contains("必须将上述 dataset ID 通过 dataset_ids 参数传入"));
    }

    @Test
    void buildTodoUserMessage_shouldPlaceActualToolCapabilitiesInUserStageOnly() {
        AgentPromptService promptService = mock(AgentPromptService.class);
        when(promptService.dynamicContextPrefix()).thenReturn("dynamic-context");
        when(promptService.renderToolCapabilities(any()))
                .thenReturn("- listMyData: list current run data");
        when(promptService.dagReactStageInstruction(anyString()))
                .thenAnswer(invocation -> "[Stage: TODO_EXECUTION]\n" + invocation.getArgument(0));

        String message = LangchainTodoUserMessageBuilder.buildTodoUserMessage(
                promptService,
                "analyze market data",
                List.of(),
                Map.of(),
                "inspect available datasets",
                List.of(ToolSpecification.builder()
                        .name("listMyData")
                        .description("list current run data")
                        .build()));

        assertTrue(message.startsWith("[Stage: TODO_EXECUTION]"));
        assertTrue(message.contains("- listMyData: list current run data"));
        assertTrue(message.contains("当前可用工具：listMyData"));
        assertFalse(message.contains("searchWeb"));
    }

    @Test
    void buildTodoUserMessage_withClosedCapabilities_shouldNotLeakToolNamesAcrossSystemAndUser() {
        AgentPromptService promptService = promptService();

        String fullPrompt = promptService.reactSystemPrompt() + "\n\n"
                + LangchainTodoUserMessageBuilder.buildTodoUserMessage(
                promptService,
                "summarize the supplied facts",
                List.of(),
                Map.of(),
                "write the summary",
                List.of());

        for (String closedTool : List.of(
                "executePython", "listMyData", "searchWeb", "checkParallelLimits",
                "rereadToolResult", "loadDocument")) {
            assertFalse(fullPrompt.contains(closedTool),
                    () -> "closed capability leaked into complete prompt: " + closedTool);
        }
    }
}
