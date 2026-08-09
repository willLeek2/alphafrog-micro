package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.SearchEvidenceJudgeService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.registry.AgentToolRegistry;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ToolCatalogBuilder 与 AgentToolRegistry 的契约测试。
 *
 * <p>保证：能力门控关闭时按注册表元数据精确移除；能力门控开启时目录与注册表声明一一对应；
 * 任何构建出来的工具名都必须是已声明的（fail-closed）。</p>
 */
class ToolCatalogBuilderRegistryContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MarketDataTools marketDataTools;
    private RagTools ragTools;
    private SearchTools searchTools;
    private PythonSandboxTools pythonSandboxTools;
    private ListMyDataTool listMyDataTool;
    private LoadToolGuideTool loadToolGuideTool;
    private RereadToolHandler rereadToolHandler;

    @BeforeEach
    void setUp() {
        marketDataTools = new MarketDataTools(
                mock(DatasetWriter.class),
                mock(DatasetRegistry.class),
                mock(ManifestWriter.class),
                null,
                new AgentLlmProperties(),
                objectMapper
        );
        ragTools = new RagTools(objectMapper);
        searchTools = new SearchTools(objectMapper, mock(SearchEvidenceJudgeService.class));
        pythonSandboxTools = new PythonSandboxTools(objectMapper);
        listMyDataTool = new ListMyDataTool(objectMapper);
        loadToolGuideTool = new LoadToolGuideTool(objectMapper);
        rereadToolHandler = new RereadToolHandler(mock(ToolOutputRefService.class), objectMapper);
    }

    @Test
    void withAllGatesOn_catalogEqualsDeclaredToolNames() {
        Set<String> built = names(ToolCatalogBuilder.buildSpecifications(
                marketDataTools, ragTools, searchTools, pythonSandboxTools, listMyDataTool,
                loadToolGuideTool, rereadToolHandler, true, true));

        assertEquals(AgentToolRegistry.declaredToolNames(), built,
                "两个门控都开启时，构建出的目录应与注册表声明完全一致");
        assertEquals(25, built.size(), "当前注册表声明为 25 个工具");
    }

    @Test
    void withWebSearchOff_searchWebAbsentAndOthersPresent() {
        Set<String> built = names(ToolCatalogBuilder.buildSpecifications(
                marketDataTools, ragTools, searchTools, pythonSandboxTools, listMyDataTool,
                loadToolGuideTool, rereadToolHandler, false, true));

        assertFalse(built.contains("searchWeb"), "webSearch 关闭时应移除 searchWeb");
        Set<String> expected = AgentToolRegistry.declaredToolNames().stream()
                .filter(n -> !"searchWeb".equals(n))
                .collect(Collectors.toSet());
        assertEquals(expected, built, "其余工具应保持不变");
    }

    @Test
    void withCodeInterpreterOff_executePythonAbsentAndOthersPresent() {
        Set<String> built = names(ToolCatalogBuilder.buildSpecifications(
                marketDataTools, ragTools, searchTools, pythonSandboxTools, listMyDataTool,
                loadToolGuideTool, rereadToolHandler, true, false));

        assertFalse(built.contains("executePython"), "codeInterpreter 关闭时应移除 executePython");
        Set<String> expected = AgentToolRegistry.declaredToolNames().stream()
                .filter(n -> !"executePython".equals(n))
                .collect(Collectors.toSet());
        assertEquals(expected, built, "其余工具应保持不变");
    }

    @Test
    void withBothGatesOff_searchWebAndExecutePythonAbsentAndOthersPresent() {
        Set<String> built = names(ToolCatalogBuilder.buildSpecifications(
                marketDataTools, ragTools, searchTools, pythonSandboxTools, listMyDataTool,
                loadToolGuideTool, rereadToolHandler, false, false));

        assertFalse(built.contains("searchWeb"), "webSearch 关闭时应移除 searchWeb");
        assertFalse(built.contains("executePython"), "codeInterpreter 关闭时应移除 executePython");
        Set<String> expected = AgentToolRegistry.declaredToolNames().stream()
                .filter(n -> !"searchWeb".equals(n) && !"executePython".equals(n))
                .collect(Collectors.toSet());
        assertEquals(expected, built, "其余工具应保持不变");
    }

    @Test
    void everyBuiltToolNameIsDeclared_failClosedProof() {
        for (boolean webSearch : List.of(true, false)) {
            for (boolean codeInterpreter : List.of(true, false)) {
                Set<String> built = names(ToolCatalogBuilder.buildSpecifications(
                        marketDataTools, ragTools, searchTools, pythonSandboxTools, listMyDataTool,
                        loadToolGuideTool, rereadToolHandler, webSearch, codeInterpreter));

                assertTrue(AgentToolRegistry.declaredToolNames().containsAll(built),
                        "任意门控组合下，构建出的名字都必须是注册表已声明的（fail-closed）");
            }
        }
    }

    private Set<String> names(List<ToolSpecification> specifications) {
        return specifications.stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
