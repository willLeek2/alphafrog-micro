package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.service.SearchEvidenceJudgeService;
import world.willfrog.agent.platform.service.ToolDescriptionTexts;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.DatasetRegistry;
import world.willfrog.agent.tools.dataset.DatasetWriter;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.dataset.ManifestWriter;
import world.willfrog.agent.tools.dataset.PromoteDatasetTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
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
    private SqlQueryTools sqlQueryTools;
    private ListMyDataTool listMyDataTool;
    private PromoteDatasetTool promoteDatasetTool;
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
        sqlQueryTools = new SqlQueryTools(objectMapper);
        listMyDataTool = new ListMyDataTool(objectMapper);
        promoteDatasetTool = new PromoteDatasetTool(objectMapper);
        loadToolGuideTool = new LoadToolGuideTool(objectMapper);
        rereadToolHandler = new RereadToolHandler(mock(ToolOutputRefService.class), objectMapper);
    }







    private Set<String> names(List<ToolSpecification> specifications) {
        return specifications.stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
