package world.willfrog.agentlangchain.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static world.willfrog.agentlangchain.orchestration.LangchainRunSchedulerTestSupport.immediateScheduler;

class LangchainLinearRunPipelineAsyncTest {

    @Test
    void launchAsync_shouldSubmitWithoutBlockingCaller() throws Exception {
        CountDownLatch workflowEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkflow = new CountDownLatch(1);
        AtomicBoolean callerReturned = new AtomicBoolean(false);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                mock(world.willfrog.agentlangchain.planning.LangchainAiPlanner.class),
                mock(LangchainLinearWorkflowExecutor.class),
                mock(world.willfrog.agentlangchain.orchestration.dag.LangchainDagWorkflowExecutor.class),
                mock(LangchainRunStageModelResolver.class),
                mock(world.willfrog.agent.platform.mapper.AgentRunMapper.class),
                mock(AgentEventService.class),
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
                mock(ObjectProvider.class)
        ) {
            @Override
            void executeRun(AgentRun initialRun) {
                workflowEntered.countDown();
                try {
                    releaseWorkflow.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        AgentRun run = new AgentRun();
        run.setId("run-async-1");
        run.setUserId("u1");

        pipeline.launchAsync(run);
        callerReturned.set(true);

        assertThat(callerReturned).isTrue();
        assertThat(workflowEntered.await(2, TimeUnit.SECONDS)).isTrue();
        releaseWorkflow.countDown();
    }
}
