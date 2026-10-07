package world.willfrog.agent.tools.sandboxjob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.PythonSandboxDispatchStore;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private SandboxToolJobLifecycle.PrepareDispatchRequest requestPython() {
        return request(ToolJobAnchor.EXECUTE_PYTHON_TOOL);
    }

    private SandboxToolJobLifecycle.PrepareDispatchRequest requestQuery() {
        return request(ToolJobAnchor.EXECUTE_QUERY_TOOL);
    }

    private SandboxToolJobLifecycle.PrepareDispatchRequest request(String toolName) {
        return new SandboxToolJobLifecycle.PrepareDispatchRequest(
                "run-1", toolName, "tool-call-1", 1, 2,
                "run-1:tool-call-1:1", "fp-1",
                "{}", "{\"code\":\"print(1)\"}",
                "LINEAR_SUSPEND", true, true,
                "{}", "{}", "{}", "digest-1",
                30_000, 1_000);
    }

    @Test
    void freshPathAssemblesExecutePythonAnchorWithoutRenamingTool() {
        FakeDispatchStore store = new FakeDispatchStore();
        AtomicBoolean extrasApplied = new AtomicBoolean(false);
        SandboxToolJobLifecycle.PrepareDispatchResult result =
                SandboxToolJobLifecycle.prepareDispatch(store, requestPython(), a -> extrasApplied.set(true));

        assertTrue(result.persisted());
        assertTrue(store.freshCalled);
        ToolJobAnchor anchor = result.anchor();
        assertEquals("PREPARING", anchor.getAnchorState());
        assertEquals(ToolJobAnchor.EXECUTE_PYTHON_TOOL, anchor.getToolName());
        assertEquals("run-1:tool-call-1:1", anchor.getOperationId());
        assertEquals("fp-1", anchor.getRequestFingerprint());
        assertEquals(2, anchor.getSchemaVersion());
        assertNotNull(anchor.getTimeoutAt());
        assertNotNull(anchor.getNextPollAt());
        assertTrue(extrasApplied.get());
    }

    @Test
    void freshPathAssemblesExecuteQueryAnchorWithQueryToolName() {
        FakeDispatchStore store = new FakeDispatchStore();
        SandboxToolJobLifecycle.PrepareDispatchResult result =
                SandboxToolJobLifecycle.prepareDispatch(store, requestQuery(), null);

        assertTrue(result.persisted());
        assertEquals(ToolJobAnchor.EXECUTE_QUERY_TOOL, result.anchor().getToolName());
    }

    @Test
    void casFailureReturnsNotPersistedForExecutePython() {
        FakeDispatchStore store = new FakeDispatchStore();
        store.persistResult = false;
        SandboxToolJobLifecycle.PrepareDispatchResult result =
                SandboxToolJobLifecycle.prepareDispatch(store, requestPython(), null);
        assertFalse(result.persisted());
        assertEquals(ToolJobAnchor.EXECUTE_PYTHON_TOOL, result.anchor().getToolName());
    }

    @Test
    void casFailureReturnsNotPersistedForExecuteQuery() {
        FakeDispatchStore store = new FakeDispatchStore();
        store.persistResult = false;
        SandboxToolJobLifecycle.PrepareDispatchResult result =
                SandboxToolJobLifecycle.prepareDispatch(store, requestQuery(), null);
        assertFalse(result.persisted());
        assertEquals(ToolJobAnchor.EXECUTE_QUERY_TOOL, result.anchor().getToolName());
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

    @Test
    @SuppressWarnings("unchecked")
    void pythonMemberPersistsCompleteRequestBeforeCreateAndPreservesWorkspaceRefusal() {
        ObjectMapper mapper = new ObjectMapper();
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-1", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1, DataAnalysisReservationState.PREPARING,
                null, Instant.now());
        DataAnalysisEstimate estimate = new DataAnalysisEstimate(0, 0, 0, 0, 0,
                List.of(), DataAnalysisResourceClass.STANDARD, 1);
        String digest = "sha256:" + "a".repeat(64);
        CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(
                CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION, identity.operationId(),
                digest, digest, DataAnalysisResourceClass.STANDARD, 1024, 30_000,
                "python-test", digest, digest);
        DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        WaitGroupStore members = mock(WaitGroupStore.class);
        SandboxJobRunnerAdapter<String, String, String> runner = mock(SandboxJobRunnerAdapter.class);
        when(capacity.reserve(identity, estimate)).thenReturn(reservation);
        when(members.recordMemberPreparing(eq(1L), eq("member-1"),
                eq(identity.operationId()), anyString())).thenReturn(true);
        when(members.recordMemberWorkspaceRefusal(eq(1L), eq("member-1"),
                eq(identity.operationId()), eq(spec.requestFingerprint()), anyString())).thenReturn(true);
        when(runner.createTask("complete-request")).thenReturn("refused");
        when(runner.workspaceRefusalCodeOf("refused")).thenReturn("WORKSPACE_DIRTY");
        when(runner.lookupRaw(identity.operationId())).thenReturn("absent");
        when(runner.verdictFromLookupForMemberPath("absent", spec.requestFingerprint(), ""))
                .thenReturn(new SandboxCreateVerdict.Absent("not found"));
        SandboxJobRequestAdapter<String> requestAdapter = new StubRequestAdapter("executePython") {
            @Override public String durableCreateRequestJson(String request) { return request; }
            @Override public String enrichWithCapacity(String request, DataAnalysisReservation ignored,
                    DataAnalysisEstimate ignoredEstimate, CanonicalSandboxCreateSpec ignoredSpec) {
                return "complete-request";
            }
        };
        var deps = new SandboxToolJobLifecycle.LifecycleDeps(
                null, capacity, null, members, null, new SandboxJobObservability(null),
                mapper, 1000, 500, 1500);
        var member = new WaitGroupMemberExecutionContext.Snapshot(
                "run-1", 1L, "member-1", 0, "call-1", identity.operationId(), "segment");

        WaitGroupMemberPendingException pending = assertThrows(WaitGroupMemberPendingException.class,
                () -> SandboxToolJobLifecycle.dispatchWaitGroupMember(deps,
                        new SandboxToolJobLifecycle.WaitGroupDispatchRequest<>(
                                member, identity, spec, estimate, "base", requestAdapter, runner,
                                "executePython", System.currentTimeMillis(), null)));

        WaitMemberDispatchProof proof = pending.getProof();
        assertEquals(WaitMemberDispatchProof.REPLAYABLE_SCHEMA_VERSION, proof.schemaVersion());
        assertEquals("complete-request", proof.createRequestJson());
        assertEquals("WORKSPACE_DIRTY", proof.workspaceRefusalCode());
        var order = inOrder(members, runner);
        order.verify(members).recordMemberPreparing(eq(1L), eq("member-1"),
                eq(identity.operationId()), anyString());
        order.verify(runner).createTask("complete-request");
        verify(members).recordMemberWorkspaceRefusal(eq(1L), eq("member-1"),
                eq(identity.operationId()), eq(spec.requestFingerprint()), anyString());
    }

    private static class StubRequestAdapter implements SandboxJobRequestAdapter<String> {
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
