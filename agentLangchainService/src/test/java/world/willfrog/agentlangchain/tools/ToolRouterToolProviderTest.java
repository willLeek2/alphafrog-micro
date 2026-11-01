package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.artifact.ToolOutputRefService;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentPromptService;
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
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.execution.PersistentSubAgentToolBridge;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolRouterToolProviderTest {

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private AgentRunEventService eventService;

    @Mock
    private AgentPromptService promptService;

    private ToolRouterToolProvider provider;
    private ObjectMapper objectMapper;
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
        objectMapper = new ObjectMapper();
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

        provider = new ToolRouterToolProvider(
                toolRouter,
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                sqlQueryTools,
                listMyDataTool,
                promoteDatasetTool,
                loadToolGuideTool,
                rereadToolHandler,
                objectMapper,
                eventService,
                new LangchainToolConcurrencyThrottle(false, 20, 60),
                mock(world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore.class),
                null,
                promptService
        );
        lenient().when(promptService.subAgentEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        LangchainDatasetRefContext.clear();
        LangchainRepeatedToolCallContext.clear();
    }















    private static ToolProviderRequest request(Map<String, Object> params) {
        return ToolProviderRequest.builder()
                .userMessage(UserMessage.from("test"))
                .invocationContext(InvocationContext.builder()
                        .userMessage(UserMessage.from("test"))
                        .invocationParameters(InvocationParameters.from(params))
                        .timestampNow()
                        .build())
                .build();
    }
}
