package world.willfrog.agentlangchain.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.gateway.GatewayTestFixtures;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowStartupRecoveryTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    private AgentRunMapper runMapper;
    private LangchainLinearRunPipeline pipeline;
    private AgentRunEventService eventService;
    private AgentRunFinalizationService finalizationService;
    private LangchainSchedulerMetrics schedulerMetrics;
    private SchedulerVersionPolicy schedulerVersionPolicy;
    private WorkflowStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentRunMapper.class);
        pipeline = mock(LangchainLinearRunPipeline.class);
        eventService = mock(AgentRunEventService.class);
        finalizationService = mock(AgentRunFinalizationService.class);
        schedulerMetrics = mock(LangchainSchedulerMetrics.class);
        schedulerVersionPolicy = mock(SchedulerVersionPolicy.class);
        // 归属判定由 gateway 承担；这里用真实判定 + 测试固定身份，扫描/认领语句参数保持原样。
        recovery = new WorkflowStartupRecovery(
                runMapper, pipeline, eventService, finalizationService,
                GatewayTestFixtures.withIdentity(runMapper, "stable", GENERATION),
                schedulerVersionPolicy);
        ReflectionTestUtils.setField(recovery, "schedulerMetrics", schedulerMetrics);
        ReflectionTestUtils.setField(recovery, "maxRestartAttempts", 1);
        ReflectionTestUtils.setField(recovery, "scanLimit", 100);
    }





    private AgentRun run(AgentRunStatus status, int restartAttempt, String planJson) {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId(GENERATION);
        run.setStatus(status);
        run.setRestartAttempt(restartAttempt);
        run.setPlanJson(planJson);
        return run;
    }
}
