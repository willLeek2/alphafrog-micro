package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowStartupRecoveryTest {

    private AgentRunMapper runMapper;
    private LangchainLinearRunPipeline pipeline;
    private AgentEventService eventService;
    private AgentRunFinalizationService finalizationService;
    private LangchainSchedulerMetrics schedulerMetrics;
    private WorkflowStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        pipeline = mock(LangchainLinearRunPipeline.class);
        eventService = mock(AgentEventService.class);
        finalizationService = mock(AgentRunFinalizationService.class);
        schedulerMetrics = mock(LangchainSchedulerMetrics.class);
        recovery = new WorkflowStartupRecovery(runMapper, pipeline, eventService, finalizationService);
        ReflectionTestUtils.setField(recovery, "schedulerMetrics", schedulerMetrics);
        ReflectionTestUtils.setField(recovery, "maxRestartAttempts", 1);
        ReflectionTestUtils.setField(recovery, "scanLimit", 100);
    }

    @Test
    void frozenPlanIsClaimedOnceAndQueuedWithoutReplanning() {
        AgentRun candidate = run(AgentRunStatus.EXECUTING, 0, "{\"items\":[]}");
        AgentRun claimed = run(AgentRunStatus.RECEIVED, 1, candidate.getPlanJson());
        when(runMapper.claimStartupRestart("run-1", AgentRunStatus.EXECUTING, 0, 1)).thenReturn(1);
        when(runMapper.findById("run-1")).thenReturn(claimed);
        when(pipeline.launchRestartedAsync(claimed)).thenReturn(true);

        recovery.recoverOne(candidate);

        verify(pipeline).launchRestartedAsync(claimed);
        verify(pipeline, never()).launchAsync(claimed);
        verify(eventService).append(eq("run-1"), eq("user-1"),
                eq("WORKFLOW_RESTART_QUEUED"), anyMap());
    }

    @Test
    void restartAttemptLimitFailsClearlyWithoutScheduling() {
        AgentRun candidate = run(AgentRunStatus.EXECUTING, 1, "{\"items\":[]}");
        when(runMapper.failStartupRecovery(
                "run-1", AgentRunStatus.EXECUTING, "workflow_restart_attempts_exhausted")).thenReturn(1);

        recovery.recoverOne(candidate);

        verify(pipeline, never()).launchRestartedAsync(candidate);
        verify(eventService).append(eq("run-1"), eq("user-1"),
                eq("WORKFLOW_RESTART_REJECTED"), anyMap());
        verify(finalizationService).publishFinalizedEvent("run-1", "user-1", "FAILED");
        verify(schedulerMetrics).recordCompletion(AgentRunStatus.FAILED);
    }

    @Test
    void cancelingRunIsFinishedAsCanceledAndNeverQueued() {
        AgentRun candidate = run(AgentRunStatus.CANCELING, 0, "{}");
        when(runMapper.completeStartupCancellation("run-1")).thenReturn(1);

        recovery.recoverOne(candidate);

        verify(pipeline, never()).launchRestartedAsync(candidate);
        verify(finalizationService).publishFinalizedEvent("run-1", "user-1", "CANCELED");
        verify(schedulerMetrics).recordCompletion(AgentRunStatus.CANCELED);
    }

    private AgentRun run(AgentRunStatus status, int restartAttempt, String planJson) {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setStatus(status);
        run.setRestartAttempt(restartAttempt);
        run.setPlanJson(planJson);
        return run;
    }
}
