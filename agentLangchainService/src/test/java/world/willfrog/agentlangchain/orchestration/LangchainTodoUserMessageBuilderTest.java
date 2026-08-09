package world.willfrog.agentlangchain.orchestration;

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

        assertTrue(message.contains("已有原始数据集 ID"));
        assertTrue(message.contains("raw-dataset-abc"));
        assertTrue(message.contains("run-level dataset_ids/manifest_ids"));
        assertTrue(message.contains("listMyData"));
        assertTrue(message.contains("query_type=dataset"));
        assertTrue(message.contains("query_type=manifest"));
        assertTrue(message.contains("整数 dataset_ids / manifest_ids"));
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
}
