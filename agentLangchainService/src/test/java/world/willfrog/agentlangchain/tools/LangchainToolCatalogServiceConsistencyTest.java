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
import world.willfrog.agent.tools.dataset.PromoteDatasetTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
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
                mock(AgentLlmLocalConfigLoader.class),
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

        service = new LangchainToolCatalogService(
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                sqlQueryTools,
                listMyDataTool,
                promoteDatasetTool,
                loadToolGuideTool,
                rereadToolHandler,
                objectMapper
        );
    }




}
