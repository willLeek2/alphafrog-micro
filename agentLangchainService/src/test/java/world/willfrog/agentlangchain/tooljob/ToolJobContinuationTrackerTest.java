package world.willfrog.agentlangchain.tooljob;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskResultRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 进程内长工具续接跟踪器：终态交接、取消传播、超时、RPC 失败预算与去注册。
 */
class ToolJobContinuationTrackerTest {

    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final ToolJobConfig config = new ToolJobConfig();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LangchainSchedulerMetrics metrics = new LangchainSchedulerMetrics(registry);
    private final PythonSandboxService sandboxService = mock(PythonSandboxService.class);

    private ToolJobContinuationTracker tracker() {
        ToolJobContinuationTracker tracker = new ToolJobContinuationTracker(
                anchorService, finalizer, runMapper, config, metrics);
        ReflectionTestUtils.setField(tracker, "sandboxService", sandboxService);
        return tracker;
    }

    @org.junit.jupiter.api.BeforeEach
    void stubFinalizerDone() {
        // finalizer 现在显式返回做没做完；默认桩为「做完」，要测保留登记的路径请覆盖本桩。
        org.mockito.Mockito.lenient()
                .when(finalizer.handleTerminal(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(ToolJobFinalizer.FinalizerOutcome.completed());
    }

    private ToolJobAnchor pendingAnchor() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setAnchorState("PENDING");
        anchor.setTaskId("task-1");
        anchor.setOperationId("op-1");
        anchor.setTodoId("todo-1");
        anchor.setTimeoutAt(Instant.now().plusSeconds(600));
        anchor.setAutoResume(true);
        return anchor;
    }













}
