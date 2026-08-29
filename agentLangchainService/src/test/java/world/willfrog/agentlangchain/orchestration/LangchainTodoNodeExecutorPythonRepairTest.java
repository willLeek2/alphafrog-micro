package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.support.LangchainTestFixtures;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainTodoNodeExecutorPythonRepairTest {

    private static final ToolSpecification EXECUTE_PYTHON = ToolSpecification.builder()
            .name("executePython")
            .description("execute corrected Python code")
            .build();

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    @Test
    void repairFailsWhenModelReturnsPlainTextWithoutExecutePython() {
        ScriptedChatModel model = new ScriptedChatModel(
                AiMessage.from("I would change the date parser, but I will not execute it."));

        LangchainTodoNodeResult result = executeRepair(model, null, new AtomicInteger());

        assertPostconditionFailure(result);
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void repairFailsWhenModelReturnsCodeJsonWithoutExecutePython() {
        ScriptedChatModel model = new ScriptedChatModel(
                AiMessage.from("{\"code\":\"print('fixed')\"}"));

        LangchainTodoNodeResult result = executeRepair(model, null, new AtomicInteger());

        assertPostconditionFailure(result);
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void repairFailsWhenOriginalReplayIsRejectedAndModelOnlyExplains() {
        ToolExecutionRequest replay = ToolExecutionRequest.builder()
                .id("repair-call-1")
                .name("executePython")
                .arguments("{\"code\":\"print('same broken code')\",\"dataset_ids\":\"1\"}")
                .build();
        ScriptedChatModel model = new ScriptedChatModel(
                AiMessage.from(replay),
                AiMessage.from("The request was already tried, so I will stop here."));
        ToolProvider provider = ignored -> ToolProviderResult.builder()
                .add(EXECUTE_PYTHON, (request, memoryId) ->
                        "{\"ok\":false,\"tool\":\"executePython\",\"error\":{"
                                + "\"code\":\"REPEATED_FAILED_PYTHON_ATTEMPT\"}}")
                .build();
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executeRepair(model, provider, toolCalls);

        assertPostconditionFailure(result);
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void repairFailsWhenOnlyAnotherToolSucceeds() {
        ToolSpecification otherTool = ToolSpecification.builder()
                .name("listMyData")
                .description("list datasets")
                .build();
        ToolExecutionRequest otherRequest = ToolExecutionRequest.builder()
                .id("other-call-1")
                .name("listMyData")
                .arguments("{}")
                .build();
        ScriptedChatModel model = new ScriptedChatModel(
                AiMessage.from(otherRequest),
                AiMessage.from("Datasets are available, so I will stop."));
        ToolProvider provider = ignored -> ToolProviderResult.builder()
                .add(otherTool, (request, memoryId) -> "{\"ok\":true}")
                .build();
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executeRepair(model, provider, toolCalls);

        assertPostconditionFailure(result);
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void repairSucceedsOnlyAfterAcceptedExecutePythonCompletes() {
        ToolExecutionRequest corrected = ToolExecutionRequest.builder()
                .id("repair-call-2")
                .name("executePython")
                .arguments("{\"code\":\"print('fixed parser')\",\"dataset_ids\":\"1\"}")
                .build();
        ScriptedChatModel model = new ScriptedChatModel(
                AiMessage.from(corrected),
                AiMessage.from("Corrected execution completed successfully."));
        ToolProvider provider = ignored -> ToolProviderResult.builder()
                .add(EXECUTE_PYTHON,
                        (request, memoryId) -> "{\"ok\":true,\"tool\":\"executePython\"}")
                .build();
        AtomicInteger toolCalls = new AtomicInteger();

        LangchainTodoNodeResult result = executeRepair(model, provider, toolCalls);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Corrected execution completed successfully.");
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void repairReturnsSuspendedWhenCorrectedExecutePythonIsDurablyAccepted() {
        ToolExecutionRequest corrected = ToolExecutionRequest.builder()
                .id("repair-call-pending")
                .name("executePython")
                .arguments("{\"code\":\"print('fixed async parser')\",\"dataset_ids\":\"1\"}")
                .build();
        ScriptedChatModel model = new ScriptedChatModel(AiMessage.from(corrected));
        ToolProvider provider = ignored -> ToolProviderResult.builder()
                .add(EXECUTE_PYTHON, (request, memoryId) -> {
                    throw new ExternalToolJobPendingException(
                            "run-repair", "repair-call-pending", 1, "corrected task pending");
                })
                .build();

        LangchainTodoNodeResult result = executeRepair(model, provider, new AtomicInteger());

        assertThat(result.isSuspended()).isTrue();
        assertThat(result.getPendingRunId()).isEqualTo("run-repair");
        assertThat(result.getPendingToolCallId()).isEqualTo("repair-call-pending");
        assertThat(model.requests()).hasSize(1);
    }

    private static LangchainTodoNodeResult executeRepair(
            ChatModel model, ToolProvider provider, AtomicInteger toolCalls) {
        LangchainTodoNodeExecutor executor = LangchainTestFixtures.todoNodeExecutor(
                Optional.ofNullable(provider));
        LangchainWorkflowRequest request = LangchainWorkflowRequest.builder()
                .runId("run-repair")
                .userId("user-repair")
                .userGoal("fix the failed Python analysis")
                .model(model)
                .toolSpecifications(List.of(EXECUTE_PYTHON))
                .maxToolRoundTrips(4)
                .build();
        TodoItem item = TodoItem.builder()
                .id("todo-python")
                .sequence(1)
                .description("run corrected analysis")
                .build();
        return executor.executeRepairRound(
                request, item, List.of(), new LinkedHashMap<>(), toolCalls, repairContext());
    }

    private static ToolJobResumeContext repairContext() {
        ToolJobResumeContext context = new ToolJobResumeContext();
        context.setTerminalSuccess(false);
        context.setTerminalStatus("FAILED");
        context.setTerminalRetryable(false);
        context.setTerminalExitReason("NON_ZERO_EXIT");
        context.setTerminalStderrPreview("bad date parser");
        context.setPythonFailedCodePreview("print('same broken code')");
        context.setPythonRepairAttempt(1);
        context.setPythonRepairPending(true);
        context.setPythonFailedRequestFingerprints(List.of("sha256:failed-request"));
        context.setResultConsumed(true);
        return context;
    }

    private static void assertPostconditionFailure(LangchainTodoNodeResult result) {
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("python_repair_execute_required");
        assertThat(result.getFailureMetadata())
                .containsEntry("python_repair_postcondition_failed", true)
                .containsEntry("required_tool", "executePython")
                .containsEntry("repair_attempt", 1);
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<AiMessage> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index;

        private ScriptedChatModel(AiMessage... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            AiMessage response = index < responses.size()
                    ? responses.get(index++)
                    : AiMessage.from("unexpected extra model call");
            return ChatResponse.builder().aiMessage(response).build();
        }

        private List<ChatRequest> requests() {
            return requests;
        }
    }
}
