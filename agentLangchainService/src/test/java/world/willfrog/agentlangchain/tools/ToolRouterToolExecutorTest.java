package world.willfrog.agentlangchain.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.dataanalysis.PythonRiskReplayEvidenceMissingException;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ToolRouterToolExecutor} 的单元测试：验证 tool call 事件发射行为。
 */
@ExtendWith(MockitoExtension.class)
class ToolRouterToolExecutorTest {

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private AgentRunEventService eventService;

    @Mock
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

    private ObjectMapper objectMapper;
    private ToolRouterToolExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new ToolRouterToolExecutor(toolRouter, objectMapper, eventService,
                new LangchainToolConcurrencyThrottle(false, 20, 60),
                pythonSandboxDispatchStore);
        // 设置 AgentContext
        AgentContext.setRunId("run-123");
        AgentContext.setUserId("user-456");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }












}
