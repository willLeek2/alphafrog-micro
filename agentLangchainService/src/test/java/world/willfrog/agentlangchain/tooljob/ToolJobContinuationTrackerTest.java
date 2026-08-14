package world.willfrog.agentlangchain.tooljob;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.orchestration.scheduler.LangchainSchedulerMetrics;
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

    @Test
    void terminalStatusHandsOffToFinalizerAndRecordsRequeueMetric() {
        ToolJobAnchor anchor = pendingAnchor();
        ToolJobAnchor after = pendingAnchor();
        after.setResumeState("LAUNCHING");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor, after);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandboxService.getTaskResult(any(GetTaskResultRequest.class)))
                .thenReturn(TaskResultResponse.newBuilder()
                        .setTaskId("task-1")
                        .setStatus("SUCCEEDED")
                        .setStdout("done")
                        .build());

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        tracker.pollPending();

        verify(finalizer).handleTerminal(eq("run-1"), eq(anchor), eq("SUCCEEDED"),
                any(TaskResultResponse.class), eq(true));
        assertThat(tracker.registeredCount()).isZero();
        assertThat(registry.counter("alphafrog.scheduler.continuation.requeued.total")
                .count()).isEqualTo(1.0);
    }

    @Test
    void cancelDispositionPropagatesCancelRpcThenTerminalWithoutResume() {
        ToolJobAnchor anchor = pendingAnchor();
        anchor.setRunDisposition("CANCELED");
        anchor.setAutoResume(false);
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.cancelTask(any(CancelTaskRequest.class)))
                .thenReturn(CancelTaskResponse.newBuilder().build());
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("CANCELED").build());
        when(sandboxService.getTaskResult(any(GetTaskResultRequest.class)))
                .thenReturn(TaskResultResponse.newBuilder()
                        .setTaskId("task-1")
                        .setStatus("CANCELED")
                        .setError("cancelled by user")
                        .build());

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        tracker.pollPending();

        verify(sandboxService).cancelTask(argThat(request ->
                request.hasByTaskId() && "task-1".equals(request.getByTaskId().getTaskId())));
        verify(finalizer).handleTerminal(eq("run-1"), eq(anchor), eq("CANCELED"),
                any(TaskResultResponse.class), eq(false));
        assertThat(tracker.registeredCount()).isZero();
        assertThat(registry.counter("alphafrog.scheduler.cancelled.total", "stage", "running")
                .count()).isEqualTo(1.0);
    }

    @Test
    void toolTimeoutTriggersCancelRpcAndKeepsPolling() {
        ToolJobAnchor anchor = pendingAnchor();
        anchor.setTimeoutAt(Instant.now().minusSeconds(60));
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.cancelTask(any(CancelTaskRequest.class)))
                .thenReturn(CancelTaskResponse.newBuilder().build());
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        tracker.pollPending();

        verify(sandboxService).cancelTask(any(CancelTaskRequest.class));
        verify(finalizer, never()).handleTerminal(any(), any(), any(), any(), anyBoolean());
        // 非终态：登记项保留，继续下一轮轮询。
        assertThat(tracker.registeredCount()).isEqualTo(1);
        assertThat(registry.counter("alphafrog.scheduler.cancelled.total", "stage", "running")
                .count()).isEqualTo(1.0);
    }

    @Test
    void rpcFailureBudgetExhaustionFinalizesResultLost() {
        ToolJobAnchor anchor = pendingAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenThrow(new RuntimeException("sandbox unreachable"));

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        for (int i = 0; i < config.getContinuationMaxConsecutivePollFailures(); i++) {
            tracker.pollPending();
        }

        verify(finalizer).handleTerminal(eq("run-1"), eq(anchor), eq("RESULT_LOST"),
                isNull(), eq(true));
        assertThat(tracker.registeredCount()).isZero();
    }

    @Test
    void cancelWindowExpiryFinalizesResultLostInsteadOfHanging() {
        // 取消后迟迟看不到终态：给 cancel 一个收口窗口，超窗按 RESULT_LOST。
        config.setTerminalRetentionSeconds(0);
        ToolJobAnchor anchor = pendingAnchor();
        anchor.setRunDisposition("CANCELED");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.cancelTask(any(CancelTaskRequest.class)))
                .thenReturn(CancelTaskResponse.newBuilder().build());
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        // 第一轮发出 cancel 请求；第二轮才进入收口窗口判定。
        tracker.pollPending();
        tracker.pollPending();

        verify(sandboxService).cancelTask(any(CancelTaskRequest.class));
        verify(finalizer).handleTerminal(eq("run-1"), eq(anchor), eq("RESULT_LOST"),
                isNull(), eq(true));
        assertThat(tracker.registeredCount()).isZero();
    }

    @Test
    void anchorGoneUnregistersWithoutSandboxLookup() {
        when(anchorService.loadAnchor("run-1")).thenReturn(null);

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", pendingAnchor());
        tracker.pollPending();

        assertThat(tracker.registeredCount()).isZero();
        verifyNoInteractions(sandboxService);
        verify(finalizer, never()).handleTerminal(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void replacedOperationUnregistersWithoutSandboxLookup() {
        ToolJobAnchor replaced = pendingAnchor();
        replaced.setOperationId("op-replaced");
        when(anchorService.loadAnchor("run-1")).thenReturn(replaced);

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", pendingAnchor());
        tracker.pollPending();

        assertThat(tracker.registeredCount()).isZero();
        verifyNoInteractions(sandboxService);
    }

    @Test
    void resumeStateAlreadyAdvancedUnregisters() {
        ToolJobAnchor anchor = pendingAnchor();
        anchor.setResumeState("LAUNCHING");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        tracker.pollPending();

        assertThat(tracker.registeredCount()).isZero();
        verifyNoInteractions(sandboxService);
    }

    @Test
    void registerSkipsAnchorMissingIdentity() {
        ToolJobAnchor noTaskId = pendingAnchor();
        noTaskId.setTaskId(null);
        ToolJobAnchor noOperation = pendingAnchor();
        noOperation.setOperationId(" ");

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", noTaskId);
        tracker.register("run-2", noOperation);

        assertThat(tracker.registeredCount()).isZero();
    }

    @Test
    void nonTerminalStatusKeepsEntryRegistered() {
        ToolJobAnchor anchor = pendingAnchor();
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        tracker.pollPending();

        assertThat(tracker.registeredCount()).isEqualTo(1);
        verify(finalizer, never()).handleTerminal(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void cancelRpcFailureBudgetExhaustionFinalizesResultLost() {
        // cancel RPC 永远失败但状态可读：不能无限轮询，必须按失败预算收口。
        ToolJobAnchor anchor = pendingAnchor();
        anchor.setRunDisposition("CANCELED");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.cancelTask(any(CancelTaskRequest.class)))
                .thenThrow(new RuntimeException("cancel rpc down"));
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        for (int i = 0; i < config.getContinuationMaxConsecutivePollFailures(); i++) {
            tracker.pollPending();
        }

        verify(sandboxService, times(config.getContinuationMaxConsecutivePollFailures()))
                .cancelTask(any(CancelTaskRequest.class));
        verify(finalizer).handleTerminal(eq("run-1"), eq(anchor), eq("RESULT_LOST"),
                isNull(), eq(true));
        assertThat(tracker.registeredCount()).isZero();
    }

    @Test
    void finalizerFailureKeepsRegistrationAndRetriesNextPoll() {
        // finalizer 首次失败（数据库写异常）：登记必须保留，下一轮重试且只形成一个最终结果。
        ToolJobAnchor anchor = pendingAnchor();
        ToolJobAnchor after = pendingAnchor();
        after.setResumeState("LAUNCHING");
        when(anchorService.loadAnchor("run-1")).thenReturn(anchor, anchor, after);
        when(runMapper.findById("run-1")).thenReturn(null);
        when(sandboxService.getTaskStatus(any(GetTaskStatusRequest.class)))
                .thenReturn(TaskStatusResponse.newBuilder().setStatus("SUCCEEDED").build());
        when(sandboxService.getTaskResult(any(GetTaskResultRequest.class)))
                .thenReturn(TaskResultResponse.newBuilder()
                        .setTaskId("task-1")
                        .setStatus("SUCCEEDED")
                        .setStdout("done")
                        .build());
        doThrow(new RuntimeException("db write failed"))
                .doNothing()
                .when(finalizer).handleTerminal(
                        eq("run-1"), eq(anchor), eq("SUCCEEDED"), any(), eq(true));

        ToolJobContinuationTracker tracker = tracker();
        tracker.register("run-1", anchor);
        tracker.pollPending();
        // 第一次 finalizer 失败：登记保留，Run 不会停在 WAITING_TOOL_JOB 无人发现。
        assertThat(tracker.registeredCount()).isEqualTo(1);

        tracker.pollPending();
        // 第二轮成功收口；之后登记已移除，不再产生任何新的终态调用。
        verify(finalizer, times(2)).handleTerminal(
                eq("run-1"), eq(anchor), eq("SUCCEEDED"), any(), eq(true));
        assertThat(tracker.registeredCount()).isZero();

        tracker.pollPending();
        verify(finalizer, times(2)).handleTerminal(any(), any(), any(), any(), anyBoolean());
    }
}
