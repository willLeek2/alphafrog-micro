package world.willfrog.agentlangchain.execution.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.control.LangchainRunExecutionGuard;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeExecutor;
import world.willfrog.agentlangchain.execution.LangchainTodoNodeResult;
import world.willfrog.agentlangchain.execution.LangchainWorkflowRequest;
import world.willfrog.agentlangchain.execution.LangchainWorkflowResult;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangchainDagWorkflowExecutorObservabilityTest {

    private static final AttributeKey<String> GEN_AI_OPERATION =
            AttributeKey.stringKey("gen_ai.operation.name");
    private static final AttributeKey<String> GEN_AI_AGENT_NAME =
            AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> GEN_AI_AGENT_ID =
            AttributeKey.stringKey("gen_ai.agent.id");

    private AgentRunEventService eventService;
    private LangchainTodoNodeExecutor nodeExecutor;
    private LangchainDagWorkflowExecutor executor;
    private SimpleMeterRegistry meterRegistry;
    private InMemorySpanExporter spanExporter;

    @BeforeEach
    void setUp() {
        AgentContext.setRunId("run-test");
        AgentContext.setUserId("user-test");
        eventService = mock(AgentRunEventService.class);
        nodeExecutor = mock(LangchainTodoNodeExecutor.class);
        LangchainRunExecutionGuard guard = mock(LangchainRunExecutionGuard.class);
        when(guard.stopReason(any(), any())).thenReturn(Optional.empty());
        executor = new LangchainDagWorkflowExecutor(
                nodeExecutor,
                mock(LangchainDagStateRecorder.class),
                eventService,
                guard,
                mock(world.willfrog.agent.platform.service.AgentPromptService.class),
                new ObjectMapper(),
                new EmptyStateStoreProvider());
        ReflectionTestUtils.setField(executor, "dagThreadPoolSize", 2);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(executor, "dagMetrics", new LangchainDagMetrics(meterRegistry));

        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);
    }

    @AfterEach
    void tearDown() {
        GlobalOpenTelemetry.resetForTest();
        AgentContext.clear();
    }

    @Test
    void executePlanned_shouldEmitScheduleLifecycleEventsAndReuseExistingTerminalNames() {
        when(nodeExecutor.execute(any(), any(), any(), any(), any(AtomicInteger.class)))
                .thenAnswer(invocation -> {
                    TodoItem item = invocation.getArgument(1);
                    if ("t1".equals(item.getId())) {
                        return LangchainTodoNodeResult.failure("t1 failed", null);
                    }
                    return LangchainTodoNodeResult.success("done:" + item.getId(), 0);
                });
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("unused");

        LangchainWorkflowResult result = executor.executePlanned(
                request("run-dag-obs-events"),
                plan(
                        item("t1", 1, List.of()),
                        item("t2", 2, List.of("t1"))));

        assertThat(result.isSuccess()).isFalse();
        List<String> types = capturedEventTypes("run-dag-obs-events");
        assertThat(types).contains(
                LangchainDagScheduleEvents.REGISTERED,
                LangchainDagScheduleEvents.WAITING,
                LangchainDagScheduleEvents.SUBMITTED,
                LangchainDagScheduleEvents.STARTED,
                "DAG_NODE_FAILED",
                "DAG_NODE_SKIPPED");
        assertThat(types).doesNotContain("DAG_NODE_REGISTERED");

        List<EventRecord> records = capturedEvents("run-dag-obs-events");
        assertThat(todoIds(records, LangchainDagScheduleEvents.STARTED)).containsExactly("t1");
        assertThat(todoIds(records, "DAG_NODE_SKIPPED")).containsExactly("t2");
        assertThat(todoIds(records, LangchainDagScheduleEvents.REGISTERED)).containsExactlyInAnyOrder("t1", "t2");
        assertThat(todoIds(records, LangchainDagScheduleEvents.WAITING)).containsExactlyInAnyOrder("t1", "t2");
        assertThat(todoIds(records, LangchainDagScheduleEvents.SUBMITTED)).containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void executePlanned_shouldRecordFiveDagMetricsAndInvokeAgentSpans() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        when(nodeExecutor.execute(any(), any(), any(), any(), any(AtomicInteger.class)))
                .thenAnswer(invocation -> {
                    bothEntered.countDown();
                    assertThat(bothEntered.await(2, TimeUnit.SECONDS)).isTrue();
                    TodoItem item = invocation.getArgument(1);
                    return LangchainTodoNodeResult.success("done:" + item.getId(), 0);
                });
        when(nodeExecutor.writeFinalAnswer(any(), any())).thenReturn("final answer");

        LangchainWorkflowResult result = executor.executePlanned(
                request("run-dag-obs-metrics"),
                plan(
                        item("t1", 1, List.of()),
                        item("t2", 2, List.of())));

        assertThat(result.isSuccess()).isTrue();
        DistributionSummary nodeCount = meterRegistry.get(LangchainDagMetrics.PREFIX + ".node.count").summary();
        DistributionSummary depth = meterRegistry.get(LangchainDagMetrics.PREFIX + ".dependency.depth.max").summary();
        assertThat(nodeCount.totalAmount()).isEqualTo(2);
        assertThat(depth.totalAmount()).isEqualTo(1);
        assertThat(meterRegistry.get(LangchainDagMetrics.PREFIX + ".schedule.duration").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get(LangchainDagMetrics.PREFIX + ".parallelism.max").summary().max()).isGreaterThanOrEqualTo(2);
        assertThat(meterRegistry.get(LangchainDagMetrics.PREFIX + ".queue.depth.max").summary()).isNotNull();
        assertThat(meterRegistry.get(LangchainDagMetrics.PREFIX + ".parallelism").gauge().value()).isZero();
        assertThat(meterRegistry.get(LangchainDagMetrics.PREFIX + ".queue.depth").gauge().value()).isZero();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).allMatch(span ->
                LangchainDagScheduleEvents.TRACE_OPERATION_INVOKE_AGENT.equals(span.getName()));
        assertThat(spans).allMatch(span ->
                LangchainDagScheduleEvents.TRACE_OPERATION_INVOKE_AGENT.equals(
                        span.getAttributes().get(GEN_AI_OPERATION)));
        SpanData dagSpan = spans.stream()
                .filter(span -> "dag".equals(span.getAttributes().get(GEN_AI_AGENT_NAME)))
                .findFirst()
                .orElseThrow();
        List<SpanData> todoSpans = spans.stream()
                .filter(span -> "todo".equals(span.getAttributes().get(GEN_AI_AGENT_NAME)))
                .toList();
        assertThat(todoSpans).hasSize(2);
        assertThat(todoSpans).allMatch(span ->
                dagSpan.getSpanContext().getTraceId().equals(span.getSpanContext().getTraceId()));
        assertThat(todoSpans).allMatch(span ->
                dagSpan.getSpanContext().getSpanId().equals(span.getParentSpanContext().getSpanId()));
        assertThat(todoSpans.stream().map(span -> span.getAttributes().get(GEN_AI_AGENT_ID)))
                .containsExactlyInAnyOrder("t1", "t2");
    }

    private List<String> capturedEventTypes(String runId) {
        return capturedEvents(runId).stream().map(EventRecord::type).toList();
    }

    private List<EventRecord> capturedEvents(String runId) {
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(eventService, atLeastOnce())
                .append(eq(runId), eq("user-1"), typeCaptor.capture(), payloadCaptor.capture());
        List<String> types = typeCaptor.getAllValues();
        List<Map<String, Object>> payloads = payloadCaptor.getAllValues();
        List<EventRecord> records = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            records.add(new EventRecord(types.get(i), payloads.get(i)));
        }
        return records;
    }

    private static List<String> todoIds(List<EventRecord> records, String eventType) {
        return records.stream()
                .filter(record -> eventType.equals(record.type()))
                .map(record -> String.valueOf(record.payload().get("todo_id")))
                .toList();
    }

    private static LangchainWorkflowRequest request(String runId) {
        return LangchainWorkflowRequest.builder()
                .runId(runId)
                .userId("user-1")
                .userGoal("observe dag")
                .executionModel(mock(ChatModel.class))
                .finalAnswerModel(mock(ChatModel.class))
                .build();
    }

    private static LangchainTodoPlan plan(TodoItem... items) {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(items))
                .extractedEntities(List.of())
                .build();
    }

    private static TodoItem item(String id, int sequence, List<String> dependsOn) {
        return TodoItem.builder()
                .id(id)
                .sequence(sequence)
                .description(id)
                .dependsOn(dependsOn)
                .build();
    }

    private record EventRecord(String type, Map<String, Object> payload) {
    }

    private static class EmptyStateStoreProvider
            implements ObjectProvider<world.willfrog.agent.platform.service.AgentRunStateStore> {
        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getObject() {
            return null;
        }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getObject(Object... args) {
            return null;
        }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getIfAvailable() {
            return null;
        }

        @Override
        public world.willfrog.agent.platform.service.AgentRunStateStore getIfUnique() {
            return null;
        }
    }
}
