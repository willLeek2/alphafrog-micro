package world.willfrog.agent.tools.sandboxjob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
