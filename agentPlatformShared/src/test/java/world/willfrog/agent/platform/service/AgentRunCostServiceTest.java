package world.willfrog.agent.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunCostServiceTest {

    private final AgentRunMapper runMapper = mock(AgentRunMapper.class);
    private final AgentRunCostService service = new AgentRunCostService(runMapper, new ObjectMapper());

    @Test
    void buildProjectsPerCallCostsAndTotals() {
        AgentRun run = run();

        var response = service.build(run, observabilityJson(), false);

        assertEquals("r1", response.getId());
        assertTrue(response.getHasTotalCost());
        assertEquals(0.003, response.getTotalCost(), 0.000000001);
        assertTrue(response.getHasUpstreamInferenceCost());
        assertEquals(0.0022, response.getUpstreamInferenceCost(), 0.000000001);
        assertEquals(2, response.getTotalCallCount());
        assertEquals(2, response.getCostedCallCount());
        assertTrue(response.getComplete());
        assertEquals(2, response.getCallsCount());
        assertEquals("gen-1", response.getCalls(0).getGenerationId());
        assertTrue(response.getCalls(0).getHasIsByok());
        assertTrue(response.getCalls(0).getIsByok());
    }

    @Test
    void buildAndPersistWritesRunTotalIntoExtJson() {
        AgentRun run = run();
        when(runMapper.updateExt(eq("r1"), eq("u1"), argThat(ext ->
                ext.contains("\"openrouter_run_cost\"")
                        && ext.contains("\"total_cost\":0.003")
                        && ext.contains("\"costed_call_count\":2")))).thenReturn(1);

        var response = service.buildAndPersist(run, observabilityJson());

        assertTrue(response.getPersisted());
        verify(runMapper).updateExt(eq("r1"), eq("u1"), argThat(ext ->
                ext.contains("\"openrouter_run_cost\"")
                        && ext.contains("\"total_cost\":0.003")
                        && ext.contains("\"costed_call_count\":2")));
    }

    @Test
    void oldRunWithoutCostDoesNotPretendTotalsExist() {
        AgentRun run = run();

        var response = service.buildAndPersist(run, "{\"diagnostics\":{\"llmTraces\":[{\"traceId\":\"t1\"}]}}");

        assertFalse(response.getHasTotalCost());
        assertFalse(response.getPersisted());
        assertEquals(1, response.getTotalCallCount());
        assertEquals(0, response.getCostedCallCount());
        assertFalse(response.getComplete());
    }

    @Test
    void buildAndPersistSkipsDbUpdateWhenExistingExtIsInvalidJson() {
        AgentRun run = run();
        run.setExt("{not-json");

        var response = service.buildAndPersist(run, observabilityJson());

        assertTrue(response.getHasTotalCost());
        assertFalse(response.getPersisted());
        verify(runMapper, never()).updateExt(eq("r1"), eq("u1"), org.mockito.ArgumentMatchers.anyString());
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setExt("{\"title\":\"hello\"}");
        return run;
    }

    private String observabilityJson() {
        return """
                {
                  "diagnostics": {
                    "llmTraces": [
                      {
                        "traceId": "trace-1",
                        "generationId": "gen-1",
                        "phase": "planning",
                        "todoId": "todo_1",
                        "endpoint": "openrouter",
                        "model": "moonshotai/kimi-k2.6",
                        "actualCost": 0.001,
                        "upstreamCost": 0.0008,
                        "cacheDiscount": 0.0001,
                        "isByok": true,
                        "startedAtMillis": 100,
                        "completedAtMillis": 200
                      },
                      {
                        "traceId": "trace-2",
                        "generationId": "gen-2",
                        "phase": "summarizing",
                        "endpoint": "openrouter",
                        "model": "moonshotai/kimi-k2.6",
                        "actualCost": 0.002,
                        "upstreamCost": 0.0014
                      }
                    ]
                  }
                }
                """;
    }
}
