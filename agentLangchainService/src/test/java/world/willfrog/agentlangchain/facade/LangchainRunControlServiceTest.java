package world.willfrog.agentlangchain.facade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentRunObservabilityService;
import world.willfrog.agent.platform.service.AgentRunCreditSettlementService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.tooljob.ToolJobAnchorService;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DeleteAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.PauseAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.ResumeAgentRunRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class LangchainRunControlServiceTest {

    private static final String GENERATION = "gen-" + "a".repeat(64);

    private final LangchainRunReadService readService = mock(LangchainRunReadService.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunEventService eventService = mock(AgentRunEventService.class);
    private final AgentRunStateStore stateStore = mock(AgentRunStateStore.class);
    private final AgentRunObservabilityService observabilityService = mock(AgentRunObservabilityService.class);
    private final LangchainLinearRunPipeline pipeline = mock(LangchainLinearRunPipeline.class);
    private final AgentRunCreditSettlementService creditSettlementService = mock(AgentRunCreditSettlementService.class);
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);
    private final LangchainRunControlService service = new LangchainRunControlService(
            readService, runMapper, eventService, stateStore, observabilityService, pipeline,
            creditSettlementService, anchorService, finalizationService,
            world.willfrog.agentlangchain.gateway.GatewayTestFixtures.
                    withIdentity(runMapper, "stable", GENERATION));

    @BeforeEach
    void defaultDeploymentOwnedRunAndPlainBusinessWrites() {
        lenient().when(runMapper.findByIdAndUserForDeployment("r1", "u1", "stable", GENERATION))
                .thenReturn(run(AgentRunStatus.EXECUTING));
        lenient().when(runMapper.cancelTerminalSnapshotWithTtl(
                eq("r1"), eq("u1"), anyString(), any()))
                .thenReturn(1);
        lenient().when(runMapper.updateSnapshotIfStatus(
                eq("r1"), eq("u1"), any(), anyString()))
                .thenReturn(1);
        lenient().when(runMapper.pauseSnapshotWithTtl(
                eq("r1"), eq("u1"), any(), anyString(), any()))
                .thenReturn(1);
    }












    private ToolJobAnchor pausedAnchor(String operationId, String terminalStatus, String finalizerStep) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setRunDisposition(world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition.PAUSED);
        anchor.setAutoResume(false);
        anchor.setTerminalStatus(terminalStatus);
        anchor.setFinalizerStep(finalizerStep);
        return anchor;
    }
















    // 取消写入与并发第二工具/取消失败的对称交错



    private AgentRun run(AgentRunStatus status) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setDeploymentId("stable");
        run.setDeploymentGenerationId(GENERATION);
        run.setStatus(status);
        run.setSnapshotJson("{}");
        return run;
    }

    private ResumeAgentRunRequest resumeRequest() {
        return ResumeAgentRunRequest.newBuilder()
                .setUserId("u1")
                .setId("r1")
                .setDeploymentId("stable")
                .setDeploymentGenerationId(GENERATION)
                .build();
    }
}
