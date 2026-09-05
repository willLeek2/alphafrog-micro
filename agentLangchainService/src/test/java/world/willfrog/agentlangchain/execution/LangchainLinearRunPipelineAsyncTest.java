package world.willfrog.agentlangchain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
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
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

class LangchainLinearRunPipelineAsyncTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    @Test
    void launchAsync_shouldSubmitWithoutBlockingCaller() throws Exception {
        CountDownLatch workflowEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkflow = new CountDownLatch(1);
        AtomicBoolean callerReturned = new AtomicBoolean(false);

        LangchainLinearRunPipelineImpl pipeline = new LangchainLinearRunPipelineImpl(
                mock(world.willfrog.agentlangchain.planning.LangchainAiPlanner.class),
                mock(LangchainLinearWorkflowExecutor.class),
                mock(world.willfrog.agentlangchain.execution.dag.LangchainDagWorkflowExecutor.class),
                mock(LangchainRunStageModelResolver.class),
                mock(world.willfrog.agent.platform.mapper.AgentRunMapper.class),
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

    @Test
    void launchAsync_rejectsRunFromAnotherGenerationBeforeScheduling() {
        AtomicBoolean workflowEntered = new AtomicBoolean(false);
        world.willfrog.agent.platform.mapper.AgentRunMapper runMapper =
                mock(world.willfrog.agent.platform.mapper.AgentRunMapper.class);
        LangchainLinearRunPipelineImpl pipeline = pipelineThatRecordsExecution(workflowEntered, runMapper);
        ReflectionTestUtils.setField(pipeline, "deploymentIdentityProvider",
                (world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider)
                        () -> new DeploymentIdentity("beta-a", GENERATION));

        AgentRun run = new AgentRun();
        run.setId("run-other-generation");
        run.setUserId("u1");
        run.setDeploymentId("beta-a");
        run.setDeploymentGenerationId("gen-" + "b".repeat(64));

        pipeline.launchAsync(run);

        assertThat(workflowEntered).isFalse();
        verifyNoInteractions(runMapper);
    }

    private LangchainLinearRunPipelineImpl pipelineThatRecordsExecution(
            AtomicBoolean workflowEntered,
            world.willfrog.agent.platform.mapper.AgentRunMapper runMapper) {
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
                mock(ObjectProvider.class)) {
            @Override
            void executeRun(AgentRun initialRun) {
                workflowEntered.set(true);
            }
        };
    }
}
