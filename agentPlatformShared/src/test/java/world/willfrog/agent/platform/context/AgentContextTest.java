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

    @Test
    void captureAndRestore_shouldPreservePromptRunSelectionInChildThread() throws Exception {
        PromptRunSelection selection = new PromptRunSelection(
                PromptRunSelection.SCHEMA_VERSION,
                "default-v1", "control", "bundle-digest", "capability-digest",
                LocalDate.of(2025, 2, 3));
        AgentContext.setPromptRunSelection(selection);
        AgentContext.ContextSnapshot snapshot = AgentContext.captureRunContext();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PromptRunSelection> childSelection = new AtomicReference<>();
        new Thread(() -> {
            AgentContext.restoreRunContext(snapshot);
            childSelection.set(AgentContext.getPromptRunSelection());
            AgentContext.clear();
            latch.countDown();
        }).start();

        latch.await();
        assertEquals(selection, childSelection.get());
        AgentContext.clear();
        assertNull(AgentContext.getPromptRunSelection());
    }

    // ── debugObservabilitySessionId (task #62 A) ──

    @Test
    void captureAndRestore_shouldPreserveDebugObservabilitySessionIdInChildThread() throws Exception {
        AgentContext.setRunId("run-1");
        AgentContext.setDebugObservabilitySessionId("debug-session-abc");
        AgentContext.ContextSnapshot snapshot = AgentContext.captureRunContext();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> childSessionId = new AtomicReference<>();
        new Thread(() -> {
            AgentContext.restoreRunContext(snapshot);
            childSessionId.set(AgentContext.getDebugObservabilitySessionId());
            AgentContext.clear();
            latch.countDown();
        }).start();

        latch.await();
        assertEquals("debug-session-abc", childSessionId.get());
    }

    // ── lastMileHint (Phase 3.2 A2: 90% budget progress) ──

    @Test
    void setAndGetLastMileHint_shouldReturnSameText() {
        AgentContext.setLastMileHint("[last_mile_hint] 90% tool_calls budget");
        assertEquals("[last_mile_hint] 90% tool_calls budget", AgentContext.getLastMileHint());
    }

    @Test
    void setLastMileHintNullOrBlank_shouldRemoveHolder() {
        AgentContext.setLastMileHint("anything");
        AgentContext.setLastMileHint(null);
        assertNull(AgentContext.getLastMileHint());

        AgentContext.setLastMileHint("anything");
        AgentContext.setLastMileHint("   ");
        assertNull(AgentContext.getLastMileHint());
    }

    @Test
    void clear_shouldRemoveLastMileHint() {
        AgentContext.setLastMileHint("hint");
        AgentContext.clear();
        assertNull(AgentContext.getLastMileHint());
    }

    @Test
    void captureAndRestore_shouldPreserveLastMileHintInChildThread() throws Exception {
        AgentContext.setRunId("run-1");
        AgentContext.setLastMileHint("[last_mile_hint] 92% tokens");
        AgentContext.ContextSnapshot snapshot = AgentContext.captureRunContext();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> childHint = new AtomicReference<>();
        new Thread(() -> {
            AgentContext.restoreRunContext(snapshot);
            childHint.set(AgentContext.getLastMileHint());
            AgentContext.clear();
            latch.countDown();
        }).start();

        latch.await();
        assertEquals("[last_mile_hint] 92% tokens", childHint.get());
    }

    @Test
    void captureRestoreAndClearShouldPreserveResumeHandoffIdentity() throws Exception {
        AgentContext.setToolJobResumeHandoff("resume-token", 6L);
        AgentContext.ContextSnapshot snapshot = AgentContext.captureRunContext();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> childToken = new AtomicReference<>();
        AtomicReference<Long> childVersion = new AtomicReference<>();
        new Thread(() -> {
            AgentContext.restoreRunContext(snapshot);
            childToken.set(AgentContext.getToolJobResumeToken());
            childVersion.set(AgentContext.getToolJobResumeLeaseVersion());
            AgentContext.clear();
            latch.countDown();
        }).start();

        latch.await();
        assertEquals("resume-token", childToken.get());
        assertEquals(6L, childVersion.get());
        AgentContext.clear();
        assertNull(AgentContext.getToolJobResumeToken());
        assertNull(AgentContext.getToolJobResumeLeaseVersion());
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
