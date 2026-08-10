package world.willfrog.agent.platform.service;

import world.willfrog.agent.platform.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.exception.RunBudgetException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunBudgetServiceTest {

    @Mock
    private AgentRunStateStore stateStore;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentLlmLocalConfigLoader localConfigLoader;
    @Mock
    private AgentPromptService promptService;

    private AgentRunBudgetService service;

    @BeforeEach
    void setUp() {
        AgentLlmProperties llmProperties = new AgentLlmProperties();
        service = new AgentRunBudgetService(
                stateStore, eventService, new ObjectMapper(), llmProperties, promptService);
        lenient().when(promptService.budgetLastMileStageInstruction(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> "[Stage: BUDGET_LAST_MILE] "
                        + invocation.getArgument(0) + " " + invocation.getArgument(1)
                        + "/" + invocation.getArgument(2) + " " + invocation.getArgument(3) + "%");
        ReflectionTestUtils.setField(service, "localConfigLoader", localConfigLoader);
        ReflectionTestUtils.setField(service, "defaultMaxWallClockMs", 600000L);
        ReflectionTestUtils.setField(service, "defaultMaxLlmCalls", 50L);
        ReflectionTestUtils.setField(service, "defaultMaxToolCalls", 30L);
        ReflectionTestUtils.setField(service, "defaultMaxTokens", 300000L);
        ReflectionTestUtils.setField(service, "defaultMaxHttpAttemptsPerLogicalCall", 3);
        AgentContext.setRunId("run-1");
        AgentContext.setUserId("user-1");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void effectiveConfig_shouldUseApplicationDefaultsWhenNoDynamicConfig() {
        when(localConfigLoader.current()).thenReturn(Optional.empty());

        AgentRunBudgetService.EffectiveRunBudget budget = service.effectiveConfig();

        assertEquals(600000L, budget.maxWallClockMs());
        assertEquals(50L, budget.maxLlmCalls());
        assertEquals(30L, budget.maxToolCalls());
        assertEquals(300000L, budget.maxTokens());
        assertEquals(3, budget.maxHttpAttemptsPerLogicalCall());
    }

    @Test
    void effectiveConfig_shouldPreferLocalConfigOverApplicationDefaults() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(80L);
        runBudget.setMaxLlmCalls(100L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));

        AgentRunBudgetService.EffectiveRunBudget budget = service.effectiveConfig();

        assertEquals(80L, budget.maxToolCalls());
        assertEquals(100L, budget.maxLlmCalls());
        assertEquals(300000L, budget.maxTokens());
    }

    @Test
    void checkBeforeToolCall_shouldUseUpdatedLocalBudgetWithoutRestart() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(2L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        lenient().when(stateStore.loadObservability("run-1")).thenReturn(Optional.of("""
                {"summary":{"startedAtMillis":%d,"toolCalls":2,"llmCalls":0,"totalTokens":0}}
                """.formatted(System.currentTimeMillis())));

        IllegalStateException ex = assertThrows(IllegalStateException.class, service::checkBeforeToolCall);

        assertTrue(ex instanceof RunBudgetException);
        RunBudgetException rbe = (RunBudgetException) ex;
        assertEquals("tool_calls", rbe.getDimension());
        assertEquals(2L, rbe.getActual());
        assertEquals(2L, rbe.getLimit());
        assertEquals(1.0, rbe.getRatio(), 0.001);
        assertFalse(rbe.isPartial());
        assertEquals("RUN_BUDGET_EXCEEDED:tool_calls:2/2", ex.getMessage());
    }

    @Test
    void check_shouldEmitBudgetProgressEventWhenUsageCrosses80Pct() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(8L, 0L, 0L);
        when(stateStore.tryMarkBudgetProgressWarned("run-1", "tool_calls")).thenReturn(true);

        service.checkBeforeToolCall();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-1"), eq("user-1"), eq("BUDGET_PROGRESS"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("tool_calls", payload.get("dimension"));
        assertEquals(8L, payload.get("actual"));
        assertEquals(10L, payload.get("limit"));
        assertEquals(0.8, ((Number) payload.get("ratio")).doubleValue(), 0.001);
        verify(stateStore).tryMarkBudgetProgressWarned("run-1", "tool_calls");
    }

    @Test
    void check_shouldNotEmitBudgetProgressBelowThreshold() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(5L, 0L, 0L);

        service.checkBeforeToolCall();

        verify(eventService, never()).append(eq("run-1"), eq("user-1"), eq("BUDGET_PROGRESS"), any(Map.class));
        verify(stateStore, never()).tryMarkBudgetProgressWarned(any(), any());
    }

    @Test
    void check_shouldNotEmitWhenAtomicGateReturnsFalse() {
        // 模拟并发场景：另一个线程抢先 SADD 成功，本线程拿不到原子 gate → 不发事件
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(8L, 0L, 0L);
        when(stateStore.tryMarkBudgetProgressWarned("run-1", "tool_calls")).thenReturn(false);

        service.checkBeforeToolCall();

        verify(eventService, never()).append(eq("run-1"), eq("user-1"), eq("BUDGET_PROGRESS"), any(Map.class));
        verify(stateStore).tryMarkBudgetProgressWarned("run-1", "tool_calls");
    }

    @Test
    void check_shouldNotEmitBudgetProgressWhenAlreadyExceeded() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(10L, 0L, 0L);

        assertThrows(RunBudgetException.class, service::checkBeforeToolCall);

        verify(eventService, never()).append(eq("run-1"), eq("user-1"), eq("BUDGET_PROGRESS"), any(Map.class));
        verify(eventService).append(eq("run-1"), eq("user-1"), eq("RUN_BUDGET_EXCEEDED"), any(Map.class));
    }

    @Test
    void check_shouldEmitBudgetLastMileEventAndHintWhenUsageCrosses90Pct() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(9L, 0L, 0L);
        when(stateStore.tryMarkBudgetLastMileWarned("run-1", "tool_calls")).thenReturn(true);

        service.checkBeforeToolCall();

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-1"), eq("user-1"), eq("BUDGET_LAST_MILE"), captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("tool_calls", payload.get("dimension"));
        assertEquals(9L, payload.get("actual"));
        assertEquals(10L, payload.get("limit"));
        assertEquals(0.9, ((Number) payload.get("ratio")).doubleValue(), 0.001);
        assertEquals(90L, payload.get("ratioPct"));
        verify(stateStore).tryMarkBudgetLastMileWarned("run-1", "tool_calls");

        // hint 必须落到 AgentContext ThreadLocal,供下次 chatRequestTransformer 读取
        String hint = AgentContext.getLastMileHint();
        assertTrue(hint != null && hint.contains("[Stage: BUDGET_LAST_MILE]") && hint.contains("tool_calls")
                && hint.contains("9/10") && hint.contains("90%"));
    }

    @Test
    void check_shouldNotEmitBudgetLastMileBelowThreshold() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(7L, 0L, 0L);

        service.checkBeforeToolCall();

        verify(eventService, never()).append(eq("run-1"), eq("user-1"), eq("BUDGET_LAST_MILE"), any(Map.class));
        verify(stateStore, never()).tryMarkBudgetLastMileWarned(any(), any());
        // 90% 以下 hint 不应写入,避免提前限制 LLM 输出
        assertTrue(AgentContext.getLastMileHint() == null);
    }

    @Test
    void check_shouldNotEmitBudgetLastMileWhenAtomicGateReturnsFalse() {
        // 并发场景：另一个线程抢先 SADD 成功，本线程拿不到原子 gate → 不发事件 + 不写 hint
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(9L, 0L, 0L);
        when(stateStore.tryMarkBudgetLastMileWarned("run-1", "tool_calls")).thenReturn(false);

        service.checkBeforeToolCall();

        verify(eventService, never()).append(eq("run-1"), eq("user-1"), eq("BUDGET_LAST_MILE"), any(Map.class));
        verify(stateStore).tryMarkBudgetLastMileWarned("run-1", "tool_calls");
        // gate false 时 hint 也不应写入
        assertTrue(AgentContext.getLastMileHint() == null);
    }

    @Test
    void check_shouldNotEmitBudgetLastMileWhenAlreadyExceeded() {
        AgentLlmProperties local = new AgentLlmProperties();
        AgentLlmProperties.RunBudget runBudget = new AgentLlmProperties.RunBudget();
        runBudget.setMaxToolCalls(10L);
        local.getRuntime().setRunBudget(runBudget);
        when(localConfigLoader.current()).thenReturn(Optional.of(local));
        stubObservability(10L, 0L, 0L);

        assertThrows(RunBudgetException.class, service::checkBeforeToolCall);

        verify(eventService, never()).append(eq("run-1"), eq("user-1"), eq("BUDGET_LAST_MILE"), any(Map.class));
        // 100% 已超限：避免覆盖 RUN_BUDGET_EXCEEDED 语义，hint 也不应再写
        verify(eventService).append(eq("run-1"), eq("user-1"), eq("RUN_BUDGET_EXCEEDED"), any(Map.class));
        assertTrue(AgentContext.getLastMileHint() == null);
    }

    private void stubObservability(long toolCalls, long llmCalls, long totalTokens) {
        String json = """
                {"summary":{"startedAtMillis":%d,"toolCalls":%d,"llmCalls":%d,"totalTokens":%d}}
                """.formatted(System.currentTimeMillis(), toolCalls, llmCalls, totalTokens);
        lenient().when(stateStore.loadObservability("run-1")).thenReturn(Optional.of(json));
    }
}
