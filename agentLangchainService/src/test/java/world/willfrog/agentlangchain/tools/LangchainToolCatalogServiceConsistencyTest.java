package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader;
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
import world.willfrog.alphafrogmicro.agent.idl.AgentToolMessage;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 对外 API 目录（LangchainToolCatalogService）与运行时共享构建路径的一致性契约测试。
 */
class LangchainToolCatalogServiceConsistencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LangchainToolCatalogService service;
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
                mock(AgentLlmLocalConfigLoader.class),
                new AgentLlmProperties(),
                objectMapper
        );
        ragTools = new RagTools(objectMapper);
        searchTools = new SearchTools(objectMapper, mock(SearchEvidenceJudgeService.class));
        pythonSandboxTools = new PythonSandboxTools(objectMapper);
        listMyDataTool = new ListMyDataTool(objectMapper);
        loadToolGuideTool = new LoadToolGuideTool(objectMapper);
        rereadToolHandler = new RereadToolHandler(mock(ToolOutputRefService.class), objectMapper);

        service = new LangchainToolCatalogService(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                listMyDataTool,
                loadToolGuideTool,
                rereadToolHandler,
                objectMapper
        );
    }

    @Test
    void apiCatalogNamesEqualRuntimeBuilderWithAllGatesOn() {
        Set<String> apiNames = service.listToolMessages().stream()
                .map(AgentToolMessage::getName)
                .collect(Collectors.toSet());

        Set<String> runtimeNames = ToolCatalogBuilder.buildSpecifications(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                listMyDataTool,
                loadToolGuideTool,
                rereadToolHandler,
                true,
                true
        ).stream()
                .map(dev.langchain4j.agent.tool.ToolSpecification::name)
                .collect(Collectors.toSet());

        assertEquals(runtimeNames, apiNames,
                "API 目录应与运行时 builder 在能力门控全开时的结果一致");
        assertEquals(AgentToolRegistry.declaredToolNames(), apiNames,
                "API 全量视图应包含注册表全部 25 个声明");
    }

    @Test
    void apiCatalogContainsLoadToolGuideAndRereadToolResult() {
        Set<String> apiNames = service.listToolMessages().stream()
                .map(AgentToolMessage::getName)
                .collect(Collectors.toSet());

        assertTrue(apiNames.contains("loadToolGuide"),
                "D05 后 API 目录应包含 loadToolGuide");
        assertTrue(apiNames.contains("rereadToolResult"),
                "D05 后 API 目录应包含 rereadToolResult");
    }

    @Test
    void apiCatalogKeepsCanonicalMergedAdvancedSchemas() throws Exception {
        List<AgentToolMessage> tools = service.listToolMessages();

        AgentToolMessage searchIndex = tools.stream()
                .filter(tool -> "searchIndex".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode searchIndexProps = objectMapper.readTree(searchIndex.getParametersJson()).path("properties");
        assertTrue(searchIndexProps.has("keyword"));
        assertTrue(searchIndexProps.has("mode"));
        assertTrue(searchIndexProps.has("advancedQuery"));
        assertTrue(searchIndexProps.path("advancedQuery").path("properties").has("conditions"));

        AgentToolMessage searchAssetInfo = tools.stream()
                .filter(tool -> "searchAssetInfo".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode searchAssetProps = objectMapper.readTree(searchAssetInfo.getParametersJson()).path("properties");
        assertTrue(searchAssetProps.has("query"));
        assertTrue(searchAssetProps.has("advancedQuery"));
        assertTrue(searchAssetProps.path("advancedQuery").path("properties").has("asset_type"));
        assertTrue(searchAssetProps.path("advancedQuery").path("properties").has("conditions"));
        assertTrue(searchAssetProps.has("assetTypes"));
        assertTrue(searchAssetProps.has("mode"));

        AgentToolMessage getExchangeAssetDaily = tools.stream()
                .filter(tool -> "getExchangeAssetDaily".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        JsonNode exchangeProps = objectMapper.readTree(getExchangeAssetDaily.getParametersJson()).path("properties");
        assertTrue(exchangeProps.has("tsCode"));
        assertTrue(exchangeProps.has("advancedQuery"));
    }

    @Test
    void apiCatalogContainsResolveFinanceMethodsAndCheckParallelLimits() {
        Set<String> apiNames = service.listToolMessages().stream()
                .map(AgentToolMessage::getName)
                .collect(Collectors.toSet());

        assertTrue(apiNames.contains("resolveFinanceMethods"),
                "API 目录应始终包含 resolveFinanceMethods");
        assertTrue(apiNames.contains("checkParallelLimits"),
                "API 目录应包含 canonical 覆盖后的 checkParallelLimits");
    }
}
