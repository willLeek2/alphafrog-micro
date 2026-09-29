package world.willfrog.agent.tools.sandboxjob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 生命周期框架 prepareDispatch 的锚点组装与持久化分流。 */
class SandboxToolJobLifecycleTest {

    /** 记录调用形态的假派发存储。 */
    private static final class FakeDispatchStore implements PythonSandboxDispatchStore {
        boolean freshCalled;
        boolean persistResult = true;

        @Override
        public boolean persistPreparing(String runId, ToolJobAnchor anchor) {
            freshCalled = true;
            return persistResult;
        }

        // 其余接口方法本测试不触达，全部占位。
        @Override public boolean persistAttached(String runId, ToolJobAnchor anchor) { return false; }
        @Override public boolean transferToPending(String runId, ToolJobAnchor anchor) { return false; }
        @Override public boolean clearActive(String runId, String operationId) { return false; }
        @Override public boolean clearSynchronouslyCompleted(String runId, String operationId) { return false; }
        @Override public boolean renewDagBlockingLease(String runId, ToolJobAnchor anchor, java.time.Instant expectedLeaseUntil) { return false; }
        @Override public boolean promoteDagBlockingWorkerLost(String runId, ToolJobAnchor anchor, java.time.Instant expectedLeaseUntil) { return false; }
        @Override public boolean beginDagBlockingPreparingAbort(String runId, ToolJobAnchor anchor, java.time.Instant expectedLeaseUntil) { return false; }
        @Override public boolean completeDagBlockingPreparingAbort(String runId, ToolJobAnchor anchor, java.time.Instant expectedLeaseUntil) { return false; }
    }

    private SandboxToolJobLifecycle.PrepareDispatchRequest request() {
        return new SandboxToolJobLifecycle.PrepareDispatchRequest(
                "run-1", "executePython", "tool-call-1", 1, 2,
                "run-1:tool-call-1:1", "fp-1",
                "{}", "{\"code\":\"print(1)\"}",
                "LINEAR_SUSPEND", true, true,
                "{}", "{}", "{}", "digest-1",
                30_000, 1_000);
    }

    @Test
    void freshPathAssemblesAnchorAndPersists() {
        FakeDispatchStore store = new FakeDispatchStore();
        AtomicBoolean extrasApplied = new AtomicBoolean(false);
        SandboxToolJobLifecycle.PrepareDispatchResult result =
                SandboxToolJobLifecycle.prepareDispatch(store, request(), a -> extrasApplied.set(true));

        assertTrue(result.persisted());
        assertTrue(store.freshCalled);
        ToolJobAnchor anchor = result.anchor();
        assertEquals("PREPARING", anchor.getAnchorState());
        assertEquals("executePython", anchor.getToolName());
        assertEquals("run-1:tool-call-1:1", anchor.getOperationId());
        assertEquals("fp-1", anchor.getRequestFingerprint());
        assertEquals(2, anchor.getSchemaVersion());
        assertNotNull(anchor.getTimeoutAt());
        assertNotNull(anchor.getNextPollAt());
        assertTrue(extrasApplied.get());
    }

    @Test
    void casFailureReturnsNotPersisted() {
        FakeDispatchStore store = new FakeDispatchStore();
        store.persistResult = false;
        SandboxToolJobLifecycle.PrepareDispatchResult result =
                SandboxToolJobLifecycle.prepareDispatch(store, request(), null);
        assertFalse(result.persisted());
    }

    @Test
    void registryRejectsDuplicateDistinctBundle() {
        SandboxJobAdapterRegistry.clearForTests();
        SandboxJobAdapters bundle = new SandboxJobAdapters(
                new StubRequestAdapter("toolA"), null, null, null);
        SandboxJobAdapterRegistry.register(bundle);
        assertTrue(SandboxJobAdapterRegistry.find("toolA").isPresent());
        assertTrue(SandboxJobAdapterRegistry.find("missing").isEmpty());
        SandboxJobAdapters another = new SandboxJobAdapters(
                new StubRequestAdapter("toolA"), null, null, null);
        assertThrows(IllegalStateException.class, () -> SandboxJobAdapterRegistry.register(another));
        SandboxJobAdapterRegistry.clearForTests();
    }

    private static final class StubRequestAdapter implements SandboxJobRequestAdapter<String> {
        private final String toolName;
        StubRequestAdapter(String toolName) { this.toolName = toolName; }
        @Override public String toolName() { return toolName; }
        @Override public world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec buildCanonicalSpec(String request) { return null; }
        @Override public String enrichWithCapacity(String request,
                world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation reservation,
                world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate estimate,
                world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec spec) { return request; }
        @Override public String requestFingerprint(String request) { return "fp"; }
        @Override public String parseStoredCreateRequest(String createRequestJson) { return createRequestJson; }
        @Override public String payloadPreview(String createRequestJson) { return ""; }
    }
}
