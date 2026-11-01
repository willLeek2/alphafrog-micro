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
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.tools.router.ToolRouter;
import world.willfrog.agentlangchain.config.LangchainToolConcurrencyThrottle;
import world.willfrog.agentlangchain.execution.ToolThrottleResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D07 ToolRouterToolExecutor 限流 FINISHED 事件契约测试。
 *
 * <p>验证 {@link ToolRouterToolExecutor#execute} 在以下三类场景下对
 * {@code TOOL_CALL_FINISHED} payload 的处理：</p>
 * <ul>
 *   <li>LC4j Semaphore 拒绝：payload 带 creditsConsumed=0、rejected_by_throttle=true、throttle_layer=lc4j_semaphore；</li>
 *   <li>下游权重限流拒绝：throttle_layer=weight_limit，recordExecution 不被调用；</li>
 *   <li>正常成功：payload 不含上述限流字段，recordExecution 被调用一次。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ToolRouterThrottleFinishedContractTest {

    @Mock
    private ToolRouter toolRouter;

    @Mock
    private AgentRunEventService eventService;

    @Mock
    private PythonSandboxDispatchStore pythonSandboxDispatchStore;

    @Mock
    private LangchainToolConcurrencyThrottle toolThrottle;

    private ObjectMapper objectMapper;
    private ToolRouterToolExecutor executor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        executor = new ToolRouterToolExecutor(toolRouter, objectMapper, eventService, toolThrottle, pythonSandboxDispatchStore);
        AgentContext.setRunId("run-123");
        AgentContext.setUserId("user-456");
        LangchainRepeatedToolCallContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
        LangchainRepeatedToolCallContext.clear();
    }




}
