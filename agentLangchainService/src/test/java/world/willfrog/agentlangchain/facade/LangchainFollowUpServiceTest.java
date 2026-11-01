package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LangchainFollowUpServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AgentMessageService messageService = mock(AgentMessageService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final LangchainLinearRunPipeline pipeline = mock(LangchainLinearRunPipeline.class);
    private final DeploymentIdentityProvider identityProvider =
            () -> new DeploymentIdentity("stable", GENERATION);
    private final LangchainFollowUpService service = new LangchainFollowUpService(
            readService, runMapper, eventService, messageService, stateStore, pipeline,
            world.willfrog.agentlangchain.gateway.GatewayTestFixtures.
                    withIdentity(runMapper, "stable", GENERATION));






    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId(GENERATION);
        run.setStatus(status);
        run.setExt("{}");
        return run;
    }
}
