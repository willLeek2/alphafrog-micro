package world.willfrog.agent.platform.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.config.AgentLlmProperties;

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

    @Test
    void setAndGetDataFreshness_shouldReturnTheSameCopy() {
        AgentLlmProperties.DataFreshness src = freshness("2020-01-01", "2026-06-24", "2026-06-24", "desc");
        AgentContext.setDataFreshness(src);

        AgentLlmProperties.DataFreshness got = AgentContext.getDataFreshness();
        assertNotNull(got);
        assertEquals("2020-01-01", got.getStartDate());
        assertEquals("2026-06-24", got.getEndDate());

        // defensive copy: mutate src should not affect holder
        src.setEndDate("2099-12-31");
        assertEquals("2026-06-24", AgentContext.getDataFreshness().getEndDate());
    }

    @Test
    void setDataFreshnessNull_shouldRemoveHolder() {
        AgentContext.setDataFreshness(freshness("a", "b", "c", "d"));
        AgentContext.setDataFreshness(null);
        assertNull(AgentContext.getDataFreshness());
    }

    @Test
    void clear_shouldRemoveDataFreshness() {
        AgentContext.setDataFreshness(freshness("a", "b", "c", "d"));
        AgentContext.clear();
        assertNull(AgentContext.getDataFreshness());
    }

    // ── capture → restore (simulates DAG parent → child thread) ──

    @Test
    void captureAndRestore_shouldPreserveDataFreshnessInChildThread() throws Exception {
        AgentLlmProperties.DataFreshness parentFreshness = freshness("2020-01-01", "2026-06-24", "2026-06-24", "parent");
        AgentContext.setDataFreshness(parentFreshness);
        AgentContext.setRunId("run-1");

        AgentContext.ContextSnapshot snapshot = AgentContext.captureRunContext();
        assertNotNull(snapshot.dataFreshness());
        assertEquals("2026-06-24", snapshot.dataFreshness().getEndDate());

        // simulate child thread
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> childEndDate = new AtomicReference<>();
        new Thread(() -> {
            AgentContext.restoreRunContext(snapshot);
            childEndDate.set(AgentContext.getDataFreshness().getEndDate());
            AgentContext.clear();
            latch.countDown();
        }).start();

        latch.await();
        assertEquals("2026-06-24", childEndDate.get());

        // parent still has its own value
        assertEquals("2026-06-24", AgentContext.getDataFreshness().getEndDate());
    }

    @Test
    void restoreNullSnapshot_shouldNotThrow() {
        AgentContext.setDataFreshness(freshness("a", "b", "c", "d"));
        AgentContext.restoreRunContext(null);
        // should not throw; existing holder value should be untouched
        assertNotNull(AgentContext.getDataFreshness());
    }

    // ── snapshot with null field ──

    @Test
    void snapshotWithoutDataFreshness_shouldRestoreAsNull() throws Exception {
        AgentContext.setRunId("run-nodf");
        AgentContext.setDataFreshness(null);

        AgentContext.ContextSnapshot snapshot = AgentContext.captureRunContext();
        assertNull(snapshot.dataFreshness());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> childHasFreshness = new AtomicReference<>(true);
        new Thread(() -> {
            AgentContext.restoreRunContext(snapshot);
            childHasFreshness.set(AgentContext.getDataFreshness() != null);
            AgentContext.clear();
            latch.countDown();
        }).start();

        latch.await();
        assertFalse(childHasFreshness.get());
    }

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
