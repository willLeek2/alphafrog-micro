package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunEvent;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditApplicationDao;
import world.willfrog.alphafrogmicro.common.dao.agent.AgentCreditLedgerDao;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * D07 AgentCreditService 限流/元工具 credit 过滤契约测试。
 *
 * <p>验证 {@link AgentCreditService#calculateRunTotalCredits} 对以下事件的计费口径：</p>
 * <ul>
 *   <li>rejected_by_throttle=true 的事件一律 0 credit（优先于显式 creditsConsumed）；</li>
 *   <li>toolName=checkParallelLimits 的元工具事件不计 credit；</li>
 *   <li>显式 creditsConsumed >= 0 时直接使用该值；</li>
 *   <li>cacheHit=true 时 0 credit；</li>
 *   <li>普通工具按 defaultToolCost；executePython 按 executePythonCost。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AgentCreditServiceThrottleFilterTest {

    @Mock
    private UserDao userDao;
    @Mock
    private AgentCreditApplicationDao creditApplicationDao;
    @Mock
    private AgentCreditLedgerDao creditLedgerDao;
    @Mock
    private AgentRunMapper runMapper;
    @Mock
    private AgentEventService eventService;
    @Mock
    private AgentModelCatalogService modelCatalogService;

    private AgentCreditService service;

    @BeforeEach
    void setUp() {
        service = new AgentCreditService(
                userDao,
                creditApplicationDao,
                creditLedgerDao,
                runMapper,
                eventService,
                modelCatalogService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "defaultToolCost", 1);
        ReflectionTestUtils.setField(service, "executePythonCost", 5);
        when(modelCatalogService.resolveBaseRate(anyString())).thenReturn(0.0D);
    }

    @Test
    void throttleRejected_withoutCredits_consumesZero() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"getStockInfo\",\"rejected_by_throttle\":true}");
        assertEquals(0, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void throttleRejected_withExplicitCredits_stillZero() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"getStockInfo\",\"rejected_by_throttle\":true,\"creditsConsumed\":7}");
        assertEquals(0, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void checkParallelLimits_consumesZero() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"checkParallelLimits\",\"cacheHit\":false}");
        assertEquals(0, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void normalTool_consumesDefaultToolCost() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"getStockInfo\",\"cacheHit\":false}");
        assertEquals(1, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void executePython_consumesExecutePythonCost() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"executePython\",\"cacheHit\":false}");
        assertEquals(5, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void cacheHit_consumesZero() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"getStockInfo\",\"cacheHit\":true}");
        assertEquals(0, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void explicitCreditsConsumed_honored() {
        AgentRun run = emptyRun();
        AgentRunEvent event = toolCallFinishedEvent(
                "{\"tool_name\":\"getStockInfo\",\"creditsConsumed\":5}");
        assertEquals(5, service.calculateRunTotalCredits(run, List.of(event), ""));
    }

    @Test
    void mixedEvents_calculatesCorrectTotal() {
        AgentRun run = emptyRun();
        List<AgentRunEvent> events = List.of(
                toolCallFinishedEvent("{\"tool_name\":\"getStockInfo\",\"cacheHit\":false}"),          // 1
                toolCallFinishedEvent("{\"tool_name\":\"executePython\",\"cacheHit\":false}"),          // 5
                toolCallFinishedEvent("{\"tool_name\":\"searchStock\",\"cacheHit\":true}"),            // 0
                toolCallFinishedEvent("{\"tool_name\":\"getStockInfo\",\"rejected_by_throttle\":true}"), // 0
                toolCallFinishedEvent("{\"tool_name\":\"checkParallelLimits\",\"cacheHit\":false}"),    // 0
                toolCallFinishedEvent("{\"tool_name\":\"getIndexInfo\",\"creditsConsumed\":3}")       // 3
        );
        assertEquals(9, service.calculateRunTotalCredits(run, events, ""));
    }

    // ── helpers ──

    private AgentRun emptyRun() {
        AgentRun run = new AgentRun();
        run.setExt(null);
        run.setSnapshotJson(null);
        return run;
    }

    private AgentRunEvent toolCallFinishedEvent(String payloadJson) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventType("TOOL_CALL_FINISHED");
        event.setPayloadJson(payloadJson);
        return event;
    }
}
