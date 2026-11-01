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
