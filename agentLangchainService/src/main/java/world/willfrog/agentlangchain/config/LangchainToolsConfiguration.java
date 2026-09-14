package world.willfrog.agentlangchain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.ToolProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agent.tools.search.SearchTools;
import world.willfrog.agentlangchain.tools.ToolRouterToolProvider;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;

/**
 * 把基于 {@link ToolRouter} 的 {@link ToolProvider} 注册给 AiServices 使用。
 */
@Configuration
@ConditionalOnBean({ToolRouter.class, MarketDataTools.class, RagTools.class, SearchTools.class,
        PythonSandboxTools.class, ListMyDataTool.class, LoadToolGuideTool.class,
        RereadToolHandler.class, AgentRunEventService.class, PythonSandboxDispatchStore.class})
public class LangchainToolsConfiguration {

    @Bean
    ToolProvider langchainToolProvider(ToolRouter toolRouter,
                                       MarketDataTools marketDataTools,
                                       RagTools ragTools,
                                       SearchTools searchTools,
                                       PythonSandboxTools pythonSandboxTools,
                                       ListMyDataTool listMyDataTool,
                                       LoadToolGuideTool loadToolGuideTool,
                                       RereadToolHandler rereadToolHandler,
                                       ObjectMapper objectMapper,
                                       AgentRunEventService eventService,
                                       LangchainToolConcurrencyThrottle toolThrottle,
                                       PythonSandboxDispatchStore pythonSandboxDispatchStore) {
        return new ToolRouterToolProvider(
                toolRouter,
                marketDataTools,
                ragTools,
                searchTools,
                pythonSandboxTools,
                listMyDataTool,
                loadToolGuideTool,
                rereadToolHandler,
                objectMapper,
                eventService,
                toolThrottle,
                pythonSandboxDispatchStore
        );
    }
}
