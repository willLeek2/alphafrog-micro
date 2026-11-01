package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import world.willfrog.agent.tools.sandboxjob.SandboxJobAdapterRegistry;
import world.willfrog.agent.tools.dataanalysis.SqlQueryTools;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.finance.FinanceRecordChannelConfigLoader;
import world.willfrog.agent.platform.finance.FinanceRecordChannelProcessor;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolJobFinalizerWorkspaceResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final ToolJobUsageHook usageHook = mock(ToolJobUsageHook.class);
    private final ToolJobEventHook eventHook = mock(ToolJobEventHook.class);
    private ToolJobFinalizer finalizer;

    @BeforeEach
    void setUp() {
        SandboxJobAdapterRegistry.clearForTests();
        when(anchorService.updateAnchor(eq("run-a"), any(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(true);
        when(anchorService.updateAnchorAndStatus(eq("run-a"), any(),
                eq(AgentRunStatus.RECEIVED), eq(AgentRunStatus.WAITING_TOOL_JOB))).thenReturn(true);
        when(usageHook.upsertUsage(eq("run-a"), any())).thenReturn(true);
        when(eventHook.emitTerminalEvent(eq("run-a"), any())).thenReturn(true);
        finalizer = ToolJobPromptTestFixture.configure(new ToolJobFinalizer(anchorService, mock(ToolJobRedisCache.class),
                mock(DataAnalysisCapacityService.class), mock(ToolJobResumeService.class),
                mock(ToolJobConfig.class), mock(FinanceRecordChannelProcessor.class),
                mock(FinanceRecordChannelConfigLoader.class),
                ToolJobPromptTestFixture.formatter(), mock(FinanceResultModelAdapter.class)));
        ReflectionTestUtils.setField(finalizer, "usageHook", usageHook);
        ReflectionTestUtils.setField(finalizer, "eventHook", eventHook);
    }

    @AfterEach
    void clearAdapters() {
        SandboxJobAdapterRegistry.clearForTests();
    }






    private static ToolJobAnchor anchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-a:call-b:1");
        anchor.setToolCallId("call-b");
        anchor.setAttempt(1);
        anchor.setTaskId("task-b");
        anchor.setAutoResume(true);
        return anchor;
    }
}
