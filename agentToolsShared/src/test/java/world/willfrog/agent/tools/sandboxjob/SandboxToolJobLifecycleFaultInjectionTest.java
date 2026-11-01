package world.willfrog.agent.tools.sandboxjob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.ToolJobFaultInjector;
import world.willfrog.agent.platform.dataanalysis.ToolJobInjectedInterruption;
import world.willfrog.agent.platform.wait.WaitGroupMemberExecutionContext;
import world.willfrog.agent.platform.wait.WaitGroupMemberPendingException;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 成员派发的两个崩溃窗口；使用中断异常验证顺序，不终止测试进程。 */
class SandboxToolJobLifecycleFaultInjectionTest {








    private static final class Fixture {
        private static final String COMPLETE_REQUEST = "{\"code\":\"print(1)\"}";
        private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        private final DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-1", "call-1", 1);
        private final DataAnalysisReservation reservation = new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1, DataAnalysisReservationState.PREPARING, null, Instant.now());
        private final DataAnalysisEstimate estimate = new DataAnalysisEstimate(0, 0, 0, 0, 0,
                List.of(), DataAnalysisResourceClass.STANDARD, 1);
        private final CanonicalSandboxCreateSpec spec;
        private final DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
        private final WaitGroupStore members = mock(WaitGroupStore.class);
        private final ToolJobFaultInjector faults = mock(ToolJobFaultInjector.class);
        private final SandboxJobRunnerAdapter<String, String, String> runner;
        private final SandboxJobRequestAdapter<String> requestAdapter;
        private final SandboxToolJobLifecycle.LifecycleDeps deps;
        private WaitMemberDispatchProof storedProof;

        Fixture() { this(true); }

        @SuppressWarnings("unchecked")
        Fixture(boolean injectorPresent) {
            String digest = "sha256:" + "a".repeat(64);
            spec = new CanonicalSandboxCreateSpec(CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION,
                    identity.operationId(), digest, digest, DataAnalysisResourceClass.STANDARD,
                    1024, 30_000, "python-test", digest, digest);
            runner = mock(SandboxJobRunnerAdapter.class);
            requestAdapter = mock(SandboxJobRequestAdapter.class);
            when(capacity.reserve(identity, estimate)).thenReturn(reservation);
            when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ALREADY_PRESENT_SAME);
            when(requestAdapter.enrichWithCapacity("base", reservation, estimate, spec)).thenReturn(COMPLETE_REQUEST);
            when(requestAdapter.durableCreateRequestJson(COMPLETE_REQUEST)).thenReturn(COMPLETE_REQUEST);
            when(members.recordMemberPreparing(eq(1L), eq("member-1"), eq(identity.operationId()), anyString()))
                    .thenAnswer(call -> {
                        storedProof = WaitMemberDispatchProof.fromJson(mapper, call.getArgument(3)).orElseThrow();
                        return true;
                    });
            deps = new SandboxToolJobLifecycle.LifecycleDeps(null, capacity, null, members,
                    injectorPresent ? faults : null, new SandboxJobObservability(null), mapper, 1000, 500, 1500);
        }

        void confirmCreation() {
            when(runner.createTask(COMPLETE_REQUEST)).thenReturn("accepted");
            when(runner.verdictOf("accepted", COMPLETE_REQUEST)).thenReturn(new SandboxCreateVerdict.Confirmed("task-1"));
        }

        ToolJobInjectedInterruption interruptAt(String checkpoint) {
            ToolJobInjectedInterruption interruption = new ToolJobInjectedInterruption("scenario", checkpoint);
            doAnswer(call -> {
                assertStoredPreparingRequest();
                throw interruption;
            }).when(faults).hit("run-1", checkpoint);
            return interruption;
        }

        void assertStoredPreparingRequest() {
            assertNotNull(storedProof, "命中检查点之前必须已经保存完整准备请求");
            assertEquals(COMPLETE_REQUEST, storedProof.createRequestJson());
            assertEquals(identity.operationId(), storedProof.operationId());
            assertEquals(spec.requestFingerprint(), storedProof.requestFingerprint());
            assertNull(storedProof.taskId());
            DataAnalysisReservation stored = assertDoesNotThrow(() ->
                    mapper.readValue(storedProof.reservationJson(), DataAnalysisReservation.class));
            assertEquals(DataAnalysisReservationState.PREPARING, stored.state());
            assertNull(stored.taskId());
        }

        String dispatch() {
            var member = new WaitGroupMemberExecutionContext.Snapshot("run-1", 1L, "member-1", 0,
                    "call-1", identity.operationId(), "segment");
            return SandboxToolJobLifecycle.dispatchWaitGroupMember(deps,
                    new SandboxToolJobLifecycle.WaitGroupDispatchRequest<>(member, identity, spec, estimate,
                            "base", requestAdapter, runner, "executePython", System.currentTimeMillis(), null));
        }
    }
}
