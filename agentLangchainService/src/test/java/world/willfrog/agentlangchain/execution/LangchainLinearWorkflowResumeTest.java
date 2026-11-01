package world.willfrog.agentlangchain.execution;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.CodeRefineProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.CompletedTodoRecord;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.CodeRefineLocalConfigLoader;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;

class LangchainLinearWorkflowResumeTest {



















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
        context.setTerminalPromptSourcesJson(world.willfrog.agentlangchain.tooljob.BackgroundPromptResults.encode(
                world.willfrog.alphafrogmicro.common.racing.prompt.PromptText.runtimeData("loaded five datasets")));
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
                nodeExecutor, guard, mock(AgentRunEventService.class));
        ToolJobResumeContext context = failedPythonContext(true, 1);
        context.setTerminalExitReason(exitReason);
        context.setPythonRepairExhausted(exhausted);

        LangchainWorkflowResult result = executor.resumePlanned(
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
            AgentRunEventService events) {
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

    private static LangchainWorkflowRequest request() {
        return LangchainWorkflowRequest.builder()
                .runId("run-1")
                .userId("user-1")
                .userGoal("goal")
                .model(mock(ChatModel.class))
                .build();
    }
}
