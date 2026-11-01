package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static world.willfrog.agentlangchain.control.LangchainRunSchedulerTestSupport.immediateScheduler;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

class LangchainLinearRunPipelineAsyncTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);



    private LangchainLinearRunPipelineImpl pipelineThatRecordsExecution(
            AtomicBoolean workflowEntered,
            world.willfrog.agent.platform.mapper.AgentRunMapper runMapper,
            world.willfrog.agentlangchain.gateway.RunOwnershipGateway ownershipGateway) {
        return new LangchainLinearRunPipelineImpl(
                mock(world.willfrog.agentlangchain.planning.LangchainAiPlanner.class),
                mock(LangchainLinearWorkflowExecutor.class),
                mock(world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor.class),
                mock(LangchainRunStageModelResolver.class),
                runMapper,
                mock(AgentRunEventService.class),
                mock(ObjectMapper.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new world.willfrog.agentlangchain.failure.LangchainFailureMapper(),
                mock(LangchainFollowUpContextSupport.class),
                mock(world.willfrog.agent.platform.service.AgentMessageService.class),
                mock(LangchainRunExecutionGuard.class),
                immediateScheduler(),
                mock(AgentCreditService.class),
                mock(AgentRunCreditSettlementService.class),
                mock(world.willfrog.agent.platform.event.AgentRunFinalizationService.class),
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                ownershipGateway) {
            @Override
            void executeRun(AgentRun initialRun) {
                workflowEntered.set(true);
            }
        };
    }
}
