package world.willfrog.agent.tools.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.config.StressTestProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.tools.compaction.RereadToolHandler;
import world.willfrog.agent.tools.docs.LoadToolGuideTool;
import world.willfrog.agent.tools.dataset.ListMyDataTool;
import world.willfrog.agent.tools.finance.FinanceMethodTools;
import world.willfrog.agent.tools.market.MarketDataTools;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import world.willfrog.agent.tools.python.PythonSandboxTools;
import world.willfrog.agent.tools.rag.RagTools;
import world.willfrog.agent.tools.search.SearchTools;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 260623-harness-optimization-02 MF-new-1（Cindy round 2 review 拍板）：ToolRouter listMyData
 * 路由应到达 {@link ListMyDataTool} 的统一 8 形参公开方法，并支持 raw file content grep。
 *
 * <p>本测试不重复覆盖 ListMyDataTool 内部语义（ListMyDataToolTest 已覆盖），只验证
 * ToolRouter 路由层：参数别名 / 位置参数 arg0..arg7 → 8 形参 listMyData 方法对应形参。
 */
class ToolRouterListMyDataRouteTest {







    private AgentLlmProperties llmPropertiesWithStaticPrecheck(boolean enabled) {
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Runtime runtime = new AgentLlmProperties.Runtime();
        AgentLlmProperties.Execution execution = new AgentLlmProperties.Execution();
        execution.setStaticPrecheckEnabled(enabled);
        runtime.setExecution(execution);
        properties.setRuntime(runtime);
        return properties;
    }
}
