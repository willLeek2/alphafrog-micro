package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.config.CodeRefineProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agent.platform.service.CodeRefineLocalConfigLoader;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LangchainLinearWorkflowResumeTest {

    @Test
    void resumeSkipsPriorTodosAndInjectsCurrentResultWithoutExtraToolCall() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        AgentEventService events = mock(AgentEventService.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        AtomicReference<String> resumeTokenSeenByNextTodo = new AtomicReference<>();
        AtomicReference<Long> resumeVersionSeenByNextTodo = new AtomicReference<>();
        when(nodeExecutor.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    resumeTokenSeenByNextTodo.set(AgentContext.getToolJobResumeToken());
                    resumeVersionSeenByNextTodo.set(AgentContext.getToolJobResumeLeaseVersion());
                    return LangchainTodoNodeResult.success("todo-3-output", 6);
                });
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final-answer");

        LangchainLinearWorkflowExecutor executor = productionExecutor(nodeExecutor, guard, events);
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-1", 1), item("todo-2", 2), item("todo-3", 3)))
                .build();
        CompletedTodoRecord prior = new CompletedTodoRecord();
        prior.setTodoId("todo-1");
        prior.setSequence(1);
        prior.setDescription("todo-1-description");
        prior.setOutput("prior-output");
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("token-1");
        context.setResumeLeaseVersion(2);
        context.setCompletedTodos(List.of(prior));
        context.setToolCallsUsed(5);
        context.setTerminalSuccess(true);
        context.setTerminalResultPreview("terminal-preview");
        context.setTerminalRawRef("artifact://result-1");
        AtomicInteger consumed = new AtomicInteger();

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> {
                    consumed.incrementAndGet();
                    return true;
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("final-answer");
        assertThat(result.getToolCallsUsed()).isEqualTo(5);
        assertThat(result.getCompletedTodos()).extracting(LangchainCompletedTodo::getTodoId)
                .containsExactly("todo-1", "todo-2", "todo-3");
        assertThat(result.getCompletedTodos().get(1).displayOutput())
                .contains("terminal-preview")
                .doesNotContain("artifact://result-1");
        assertThat(consumed.get()).isEqualTo(1);
        assertThat(resumeTokenSeenByNextTodo.get()).isEqualTo("token-1");
        assertThat(resumeVersionSeenByNextTodo.get()).isEqualTo(2L);

        ArgumentCaptor<TodoItem> executed = ArgumentCaptor.forClass(TodoItem.class);
        verify(nodeExecutor, times(1)).execute(any(), executed.capture(), any(), any(), any());
        assertThat(executed.getValue().getId()).isEqualTo("todo-3");
        verify(nodeExecutor, times(1)).writeFinalAnswer(any(), any());
    }

    @Test
    void terminalResultPreviewPreservesExactWhitespaceAndNewlines() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(nodeExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("todo-3-output", 6));
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final-answer");

        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-1", 1), item("todo-2", 2), item("todo-3", 3)))
                .build();
        CompletedTodoRecord prior = new CompletedTodoRecord();
        prior.setTodoId("todo-1");
        prior.setSequence(1);
        prior.setDescription("todo-1-description");
        prior.setOutput("prior-output");
        // Meaningful leading/trailing whitespace and trailing newline — must survive byte-for-byte.
        String exactPreview = "  {\"ok\":true,\"data\":{\"stdout\":\"rows=5\\n\"}}  \n";
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("token-1");
        context.setResumeLeaseVersion(2);
        context.setCompletedTodos(List.of(prior));
        context.setToolCallsUsed(5);
        context.setTerminalSuccess(true);
        context.setTerminalResultPreview(exactPreview);
        context.setTerminalRawRef("artifact://result-1");

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> true);

        assertThat(result.isSuccess()).isTrue();
        // Exact byte-for-byte equality — no trim, no rewrite, no rawRef appended.
        assertThat(result.getCompletedTodos().get(1).displayOutput()).isEqualTo(exactPreview);
        assertThat(result.getCompletedTodos().get(1).displayOutput())
                .doesNotContain("artifact://result-1");
    }

    @Test
    void consumeFailureStopsBeforeAnyLaterTodoAndRemainsRetryable() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2), item("todo-3", 3)))
                .build();
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setToolCallsUsed(5);
        context.setTerminalSuccess(true);
        context.setTerminalResultPreview("terminal-preview");

        LangchainLinearWorkflowResult result = executor.resumePlanned(request(), plan, context, () -> false);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("resume_result_consume_failed");
        assertThat(result.getToolCallsUsed()).isEqualTo(5);
        verify(nodeExecutor, never()).execute(any(), any(), any(), any(), any());
        verify(nodeExecutor, never()).writeFinalAnswer(any(), any());
    }

    @Test
    void restartAfterAcceptedHandoffDoesNotRepeatCurrentToolCall() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(nodeExecutor.execute(any(), any(), any(), any(), any()))
                .thenReturn(LangchainTodoNodeResult.success("todo-3-output", 6));
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final-answer");
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2), item("todo-3", 3)))
                .build();
        ToolJobResumeContext first = new ToolJobResumeContext();
        first.setRunId("run-1");
        first.setTodoId("todo-2");
        first.setTerminalSuccess(true);
        first.setTerminalResultPreview("terminal-preview");

        LangchainLinearWorkflowResult crashed = executor.resumePlanned(
                request(), plan, first, () -> false);
        assertThat(crashed.getFailureReason()).isEqualTo("resume_result_consume_failed");
        assertThat(first.isResultConsumed()).isTrue();
        assertThat(first.getTodoId()).isEqualTo("todo-3");
        assertThat(first.getCompletedTodos()).extracting(CompletedTodoRecord::getTodoId)
                .containsExactly("todo-2");

        ToolJobResumeContext restarted = new ToolJobResumeContext();
        restarted.setRunId("run-1");
        restarted.setTodoId(first.getTodoId());
        restarted.setTodoSequence(first.getTodoSequence());
        restarted.setCompletedTodos(first.getCompletedTodos());
        restarted.setResultConsumed(true);
        restarted.setTerminalSuccess(true);
        AtomicInteger consumedAgain = new AtomicInteger();
        LangchainLinearWorkflowResult resumed = executor.resumePlanned(
                request(), plan, restarted, () -> {
                    consumedAgain.incrementAndGet();
                    return true;
                });

        assertThat(resumed.isSuccess()).isTrue();
        assertThat(consumedAgain.get()).isZero();
        ArgumentCaptor<TodoItem> executed = ArgumentCaptor.forClass(TodoItem.class);
        verify(nodeExecutor, times(1)).execute(any(), executed.capture(), any(), any(), any());
        assertThat(executed.getValue().getId()).isEqualTo("todo-3");
    }

    @Test
    void consecutiveTwoLongPythonTodosConsumeFirstThenSuspendAtSecondWithSameFence() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        AtomicReference<String> resumeTokenSeenBySecondTool = new AtomicReference<>();
        AtomicReference<Long> resumeVersionSeenBySecondTool = new AtomicReference<>();
        when(nodeExecutor.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    TodoItem todo = invocation.getArgument(1);
                    assertThat(todo.getId()).isEqualTo("todo-3");
                    resumeTokenSeenBySecondTool.set(AgentContext.getToolJobResumeToken());
                    resumeVersionSeenBySecondTool.set(AgentContext.getToolJobResumeLeaseVersion());
                    return LangchainTodoNodeResult.suspended(
                            new ExternalToolJobPendingException(
                                    "run-1", "python-call-2", 1, "second long python pending"));
                });
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-1", 1), item("todo-2", 2), item("todo-3", 3)))
                .build();
        CompletedTodoRecord prior = new CompletedTodoRecord();
        prior.setTodoId("todo-1");
        prior.setSequence(1);
        prior.setDescription("todo-1-description");
        prior.setOutput("prior-output");
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setResumeToken("python-token-1");
        context.setResumeLeaseVersion(11L);
        context.setResumeLauncherOwnerId("owner-1");
        context.setCompletedTodos(List.of(prior));
        context.setToolCallsUsed(5);
        context.setTerminalSuccess(true);
        context.setTerminalResultPreview("first-python-result");
        AtomicInteger accepted = new AtomicInteger();

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> {
                    accepted.incrementAndGet();
                    return true;
                });

        assertThat(result.isSuspended()).isTrue();
        assertThat(result.getSuspendedTodoId()).isEqualTo("todo-3");
        assertThat(result.getPendingToolCallId()).isEqualTo("python-call-2");
        assertThat(result.getPendingAttempt()).isEqualTo(1);
        assertThat(result.getCompletedTodos()).extracting(LangchainCompletedTodo::getTodoId)
                .containsExactly("todo-1", "todo-2");
        assertThat(result.getCompletedTodos().get(1).displayOutput()).contains("first-python-result");
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(context.isResultConsumed()).isTrue();
        assertThat(context.getTodoId()).isEqualTo("todo-3");
        assertThat(resumeTokenSeenBySecondTool.get()).isEqualTo("python-token-1");
        assertThat(resumeVersionSeenBySecondTool.get()).isEqualTo(11L);
        verify(nodeExecutor, times(1)).execute(any(), any(), any(), any(), any());
    }

    @Test
    void userCodeTerminalFailureReentersSameTodoWithDurableRepairContext() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        AtomicReference<ToolJobResumeContext> repairSeen = new AtomicReference<>();
        when(nodeExecutor.execute(any(), any(), any(), any(), any(),
                any(ToolJobResumeContext.class))).thenAnswer(invocation -> {
            repairSeen.set(invocation.getArgument(5));
            assertThat(AgentContext.getToolJobResumeToken()).isEqualTo("repair-token");
            return LangchainTodoNodeResult.success("repaired-output", 1);
        });
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final-after-repair");
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
        ToolJobResumeContext context = failedPythonContext(false, 0);
        AtomicInteger consumed = new AtomicInteger();

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> {
                    consumed.incrementAndGet();
                    return true;
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("final-after-repair");
        assertThat(consumed.get()).isEqualTo(1);
        assertThat(context.isResultConsumed()).isTrue();
        assertThat(context.getTodoId()).isEqualTo("todo-2");
        assertThat(context.getPythonRepairAttempt()).isEqualTo(1);
        assertThat(context.isPythonRepairPending()).isTrue();
        assertThat(repairSeen.get()).isSameAs(context);
        assertThat(result.getCompletedTodos()).extracting(LangchainCompletedTodo::getTodoId)
                .containsExactly("todo-2");
        verify(nodeExecutor).execute(any(), any(), any(), any(), any(), same(context));
    }

    @Test
    void acceptedPythonRepairCrashReentryDoesNotConsumeOrIncrementAgain() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(nodeExecutor.execute(any(), any(), any(), any(), any(),
                any(ToolJobResumeContext.class)))
                .thenReturn(LangchainTodoNodeResult.success("repaired-output", 1));
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final-after-restart");
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
        ToolJobResumeContext context = failedPythonContext(true, 1);
        AtomicInteger consumed = new AtomicInteger();

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> {
                    consumed.incrementAndGet();
                    return true;
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(consumed.get()).isZero();
        assertThat(context.getPythonRepairAttempt()).isEqualTo(1);
        assertThat(context.isPythonRepairPending()).isTrue();
        verify(nodeExecutor).execute(any(), any(), any(), any(), any(), same(context));
    }

    @Test
    void pendingOomCrashReentryFailsClosedWithoutLlmOrTool() {
        assertInvalidPendingRepairFailsClosed("OOM", false, "external_tool_terminal_failure");
    }

    @Test
    void pendingExecutionErrorCrashReentryFailsClosedWithoutLlmOrTool() {
        assertInvalidPendingRepairFailsClosed(
                "EXECUTION_ERROR", false, "external_tool_terminal_failure");
    }

    @Test
    void pendingAndExhaustedCrashReentryFailsClosedWithoutLlmOrTool() {
        assertInvalidPendingRepairFailsClosed("NON_ZERO_EXIT", true, "python_repair_exhausted");
    }

    @Test
    void pythonRepairExhaustionConsumesTerminalAndFailsWithoutAnotherLlmCall() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
        ToolJobResumeContext context = failedPythonContext(false, 2);
        AtomicInteger consumed = new AtomicInteger();

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> {
                    consumed.incrementAndGet();
                    return true;
                });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("python_repair_exhausted");
        assertThat(result.getFailureMetadata())
                .containsEntry("python_repair_exhausted", true)
                .containsEntry("max_attempts", 3);
        assertThat(consumed.get()).isEqualTo(1);
        assertThat(context.isPythonRepairPending()).isFalse();
        assertThat(context.isPythonRepairExhausted()).isTrue();
        verify(nodeExecutor, never()).execute(any(), any(), any(), any(), any());
        verify(nodeExecutor, never()).execute(any(), any(), any(), any(), any(), any());
        verify(nodeExecutor, never()).writeFinalAnswer(any(), any());
    }

    @Test
    void crashAfterPersistedRepairExhaustionFailsDeterministicallyWithoutAnotherLlmCall() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
        ToolJobResumeContext context = failedPythonContext(true, 2);
        context.setPythonRepairPending(false);
        context.setPythonRepairExhausted(true);

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> {
                    throw new AssertionError("terminal must not be consumed twice");
                });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("python_repair_exhausted");
        assertThat(result.getFailureMetadata()).containsEntry("max_attempts", 3);
        verifyNoInteractions(nodeExecutor);
    }

    @Test
    void hotReloadedMaxAttemptsChangesExhaustionGateWithoutRestart() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        AgentEventService events = mock(AgentEventService.class);
        CodeRefineLocalConfigLoader loader = mock(CodeRefineLocalConfigLoader.class);
        CodeRefineProperties startup = attempts(5);
        java.util.concurrent.atomic.AtomicReference<CodeRefineProperties> live =
                new java.util.concurrent.atomic.AtomicReference<>(attempts(2));
        when(loader.current()).thenAnswer(ignored -> Optional.ofNullable(live.get()));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        when(nodeExecutor.execute(any(), any(), any(), any(), any(),
                any(ToolJobResumeContext.class)))
                .thenReturn(LangchainTodoNodeResult.success("repaired-output", 1));
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final-after-repair");
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                nodeExecutor, guard, events, loader, startup);
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();

        ToolJobResumeContext exhaustedAtTwo = failedPythonContext(false, 1);
        LangchainLinearWorkflowResult exhausted = executor.resumePlanned(
                request(), plan, exhaustedAtTwo, () -> true);
        assertThat(exhausted.getFailureReason()).isEqualTo("python_repair_exhausted");
        assertThat(exhausted.getFailureMetadata()).containsEntry("max_attempts", 2);
        verifyNoInteractions(nodeExecutor);

        live.set(attempts(4));
        ToolJobResumeContext allowedAtFour = failedPythonContext(false, 1);
        LangchainLinearWorkflowResult allowed = executor.resumePlanned(
                request(), plan, allowedAtFour, () -> true);
        assertThat(allowed.isSuccess()).isTrue();
        verify(nodeExecutor).execute(any(), any(), any(), any(), any(), same(allowedAtFour));
        verify(nodeExecutor).writeFinalAnswer(any(), any());
    }

    @Test
    void invalidHotReloadedMaxAttemptsUsesSameDefaultAsLoaderSanitizer() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        CodeRefineLocalConfigLoader loader = mock(CodeRefineLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.of(attempts(0)));
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainLinearWorkflowExecutor executor = new LangchainLinearWorkflowExecutor(
                nodeExecutor, guard, mock(AgentEventService.class), loader, attempts(5));
        ToolJobResumeContext context = failedPythonContext(false, 2);

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), singleTodoPlan(), context, () -> true);

        assertThat(result.getFailureReason()).isEqualTo("python_repair_exhausted");
        assertThat(result.getFailureMetadata()).containsEntry("max_attempts", 3);
        verifyNoInteractions(nodeExecutor);
    }

    @Test
    void executionErrorRemainsFailFastInsteadOfEnteringCodeRepair() {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
        ToolJobResumeContext context = failedPythonContext(false, 0);
        context.setTerminalExitReason("EXECUTION_ERROR");

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), plan, context, () -> true);

        assertThat(result.getFailureReason()).isEqualTo("external_tool_terminal_failure");
        verifyNoInteractions(nodeExecutor);
    }

    @Test
    void repairPromptContainsBoundedTerminalDiagnosticsAndNoReplayInstruction() {
        ToolJobResumeContext context = failedPythonContext(true, 1);

        assertThat(LangchainTodoNodeExecutor.buildPythonRepairUserMessage(context))
                .contains("PYTHON_REPAIR_CONTEXT")
                .contains("repair_attempt: 1")
                .contains("exit_reason: NON_ZERO_EXIT")
                .contains("loaded five datasets")
                .contains("Traceback: bad date")
                .contains("print('broken date parser')")
                .contains("禁止原样重放");
    }

    private static ToolJobResumeContext failedPythonContext(boolean consumed, int repairAttempt) {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setRunId("run-1");
        context.setTodoId("todo-2");
        context.setTodoSequence(2);
        context.setResumeToken("repair-token");
        context.setResumeLeaseVersion(7L);
        context.setResumeLauncherOwnerId("owner-1");
        context.setTerminalSuccess(false);
        context.setTerminalStatus("FAILED");
        context.setTerminalRetryable(false);
        context.setTerminalErrorCode("execution_failed");
        context.setTerminalExitReason("NON_ZERO_EXIT");
        context.setTerminalResultPreview("loaded five datasets");
        context.setTerminalStderrPreview("Traceback: bad date");
        context.setPythonFailedCodePreview("print('broken date parser')");
        context.setPythonRepairAttempt(repairAttempt);
        context.setPythonRepairPending(consumed);
        context.setPythonFailedRequestFingerprints(List.of("sha256:failed-code"));
        context.setResultConsumed(consumed);
        return context;
    }

    private static void assertInvalidPendingRepairFailsClosed(
            String exitReason, boolean exhausted, String expectedFailure) {
        LangchainTodoNodeExecutor nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        LangchainLinearWorkflowExecutor executor = productionExecutor(
                nodeExecutor, guard, mock(AgentEventService.class));
        ToolJobResumeContext context = failedPythonContext(true, 1);
        context.setTerminalExitReason(exitReason);
        context.setPythonRepairExhausted(exhausted);

        LangchainLinearWorkflowResult result = executor.resumePlanned(
                request(), singleTodoPlan(), context,
                () -> { throw new AssertionError("accepted handoff must not be consumed twice"); });

        assertThat(result.getFailureReason()).isEqualTo(expectedFailure);
        verifyNoInteractions(nodeExecutor);
    }

    private static CodeRefineProperties attempts(int value) {
        CodeRefineProperties properties = new CodeRefineProperties();
        properties.setMaxAttempts(value);
        return properties;
    }

    private static LangchainLinearWorkflowExecutor productionExecutor(
            LangchainTodoNodeExecutor nodeExecutor,
            LangchainRunExecutionGuard guard,
            AgentEventService events) {
        CodeRefineLocalConfigLoader loader = mock(CodeRefineLocalConfigLoader.class);
        when(loader.current()).thenReturn(Optional.empty());
        return new LangchainLinearWorkflowExecutor(
                nodeExecutor, guard, events, loader, new CodeRefineProperties());
    }

    private static LangchainTodoPlan singleTodoPlan() {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(item("todo-2", 2)))
                .build();
    }

    private static TodoItem item(String id, int sequence) {
        return TodoItem.builder().id(id).sequence(sequence).description(id + "-description").build();
    }

    private static LangchainLinearWorkflowRequest request() {
        return LangchainLinearWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("goal")
                .model(mock(ChatModel.class))
                .build();
    }
}
