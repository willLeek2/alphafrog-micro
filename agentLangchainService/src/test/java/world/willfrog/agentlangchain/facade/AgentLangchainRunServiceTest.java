package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.acceptance.AcceptanceControlGate;
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureGate;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.LangchainRunRejectedException;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.alphafrogmicro.common.lane.LaneContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentLangchainRunServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Mock
    private ObjectProvider<AgentRunEventService> eventServiceProvider;
    @Mock
    private ObjectProvider<LangchainLinearRunPipeline> pipelineProvider;
    @Mock
    private AgentRunEventService eventService;
    @Mock
    private LangchainLinearRunPipeline pipeline;
    @Mock
    private LangchainRunConcurrencyScheduler scheduler;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentCreditService creditService;
    @Mock
    private UserDao userDao;
    @Mock
    private DeploymentIdentityProvider deploymentIdentityProvider;
    @Mock
    private SchedulerVersionPolicy schedulerVersionPolicy;
    @Mock
    private DualPoolRunAdmissionRegistry dualPoolRunAdmissionRegistry;
    @Mock
    private AcceptanceFixtureGate acceptanceFixtureGate;
    @Mock
    private AcceptanceControlGate acceptanceControlGate;

    private AgentLangchainRunService runService;

    @BeforeEach
    void setUp() {
        runService = new AgentLangchainRunService(eventServiceProvider, pipelineProvider, scheduler, runMapper,
                creditService, userDao,
                world.willfrog.agentlangchain.gateway.GatewayTestFixtures.
                        withIdentity(runMapper, "stable", GENERATION),
                schedulerVersionPolicy, dualPoolRunAdmissionRegistry, acceptanceFixtureGate,
                acceptanceControlGate);
        lenient().when(schedulerVersionPolicy.versionForNewRun())
                .thenReturn(SchedulerVersionPolicy.LEGACY);
        // 版本家族判断按版本名如实回答：假的策略不能把双池版本说成不是双池，否则这里测的就不是创建路径了。
        lenient().when(schedulerVersionPolicy.isDualPoolFamily(anyString()))
                .thenAnswer(invocation -> {
                    String version = invocation.getArgument(0);
                    return SchedulerVersionPolicy.DUAL_POOL_V1.equals(version)
                            || SchedulerVersionPolicy.DUAL_POOL_V2.equals(version);
                });
        lenient().when(creditService.hasPositiveCredit(anyString())).thenReturn(true);
        lenient().when(deploymentIdentityProvider.current())
                .thenReturn(new DeploymentIdentity("stable", GENERATION));
    }









}
