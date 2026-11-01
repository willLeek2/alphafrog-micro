package world.willfrog.agent.platform.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;
import world.willfrog.agent.platform.prompt.PromptRunSelection;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentContext} dataFreshness ThreadLocal lifecycle:
 * set → get → capture → restore → clear.
 */
class AgentContextTest {

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    // ── basic set/get/clear ──




    // ── capture → restore (simulates DAG parent → child thread) ──



    // ── snapshot with null field ──



    // ── debugObservabilitySessionId (task #62 A) ──


    // ── lastMileHint (Phase 3.2 A2: 90% budget progress) ──






    // ── helper ──

    private static AgentLlmProperties.DataFreshness freshness(String start, String end, String asOf, String desc) {
        AgentLlmProperties.DataFreshness f = new AgentLlmProperties.DataFreshness();
        f.setStartDate(start);
        f.setEndDate(end);
        f.setAsOfDate(asOf);
        f.setDescription(desc);
        return f;
    }
}
