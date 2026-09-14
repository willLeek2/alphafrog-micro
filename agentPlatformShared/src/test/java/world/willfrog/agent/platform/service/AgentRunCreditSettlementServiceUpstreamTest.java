package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.credit.EndpointCostAdapterRegistry;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentModelCatalogService;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunCreditSummaryDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentRunLlmCallCreditDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunCreditSummary;
import world.willfrog.alphafrogmicro.common.pojo.agent.AgentRunLlmCallCredit;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证真实 adapter registry 下，OpenRouter BYOK trace（actualCost=0, upstreamCost>0）
 * 在 attempt=1 即可直接 SETTLED，不需要依赖 Generation API 远程 fetch。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunCreditSettlementServiceUpstreamTest {

    @Mock
    private AgentRunObservabilityService observabilityService;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentRunLlmCallCreditDao llmCallCreditDao;
    @Mock
    private AgentRunCreditSummaryDao summaryDao;
    @Mock
    private UserDao userDao;
    @Mock
    private AgentCreditDebitOperator debitOperator;
    @Mock
    private Executor creditSettlementExecutor;
    @Mock
    private ScheduledExecutorService creditSettlementScheduler;
    @Mock
    private AgentModelCatalogService modelCatalogService;

    private AgentRunCreditSettlementService service;
    private final List<AgentRunLlmCallCredit> persisted = new ArrayList<>();

    private static final String RUN_ID = "run-upstream";
    private static final String USER_ID = "42";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentLlmProperties properties = new AgentLlmProperties();
        AgentLlmProperties.Endpoint endpoint = new AgentLlmProperties.Endpoint();
        endpoint.setApiKey("test-key");
        endpoint.setBaseUrl("https://openrouter.ai/api/v1");
        properties.getEndpoints().put("openrouter", endpoint);

        lenient().when(modelCatalogService.resolveBaseRate(anyString())).thenReturn(1.0D);

        EndpointCostAdapterRegistry adapterRegistry = new EndpointCostAdapterRegistry(
                mock(world.willfrog.agent.platform.service.OpenRouterCostService.class),
                mock(world.willfrog.agent.platform.service.AgentLlmLocalConfigLoader.class),
                properties,
                modelCatalogService
        );

        service = new AgentRunCreditSettlementService(
                observabilityService, runMapper, adapterRegistry,
                llmCallCreditDao, summaryDao, userDao, debitOperator, objectMapper,
                creditSettlementExecutor, creditSettlementScheduler);

        lenient().when(llmCallCreditDao.insertIgnoreDuplicate(any()))
                .thenAnswer(invocation -> {
                    AgentRunLlmCallCredit record = invocation.getArgument(0);
                    persisted.add(record);
                    return 1;
                });
        lenient().when(llmCallCreditDao.listByRunId(anyString()))
                .thenAnswer(invocation -> new ArrayList<>(persisted));

        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setUserId(USER_ID);
        run.setSnapshotJson("{}");
        lenient().when(runMapper.findById(RUN_ID)).thenReturn(run);
        lenient().when(observabilityService.loadObservabilityJson(eq(RUN_ID), anyString()))
                .thenReturn(byokTraceObservabilityJson());

        User user = new User();
        user.setUserId(42L);
        user.setUserType(0);
        user.setCredit(new BigDecimal("100.000000"));
        lenient().when(userDao.getUserById(42L)).thenReturn(user);
    }

    @Test
    void byokTraceWithUpstreamCostSettlesImmediatelyWithoutFetch() {
        service.settleOnce(RUN_ID, USER_ID, 1);

        assertEquals(1, persisted.size(), "attempt=1 应该只写 1 条 per-call 记录");
        AgentRunLlmCallCredit record = persisted.get(0);
        assertEquals("SETTLED", record.getSettlementStatus());
        assertEquals(0, new BigDecimal("0.004559").compareTo(record.getCreditDelta()),
                "BYOK 时应使用 upstreamCost 作为 billable cost");
    }

    private String byokTraceObservabilityJson() {
        return "{\"diagnostics\":{\"llmTraces\":["
                + "{\"traceId\":\"call-byok\",\"generationId\":\"call-byok\","
                + "\"endpoint\":\"openrouter\",\"model\":\"deepseek/deepseek-v4-pro\",\"phase\":\"planning\","
                + "\"actualCost\":0,\"upstreamCost\":0.0045588,\"isByok\":true}"
                + "]}}";
    }
}
