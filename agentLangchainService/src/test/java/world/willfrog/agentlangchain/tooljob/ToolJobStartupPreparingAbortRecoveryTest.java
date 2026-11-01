package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisAdmissionState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityRecoveryReport;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ToolJobStartupPreparingAbortRecoveryTest {

    private final world.willfrog.agentlangchain.gateway.RunOwnershipGateway ownershipGateway =
            org.mockito.Mockito.mock(world.willfrog.agentlangchain.gateway.RunOwnershipGateway.class);




    private static ToolJobAnchor abortingAnchor() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity("run-abort", "call-1", 1);
        DataAnalysisReservation released = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.RELEASED,
                null,
                Instant.parse("2026-07-30T07:00:00Z"));
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(
                ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-old");
        anchor.setBlockingLeaseUntil(
                Instant.parse("2026-07-30T07:30:00Z"));
        anchor.setReservationJson(mapper.writeValueAsString(released));
        return anchor;
    }

    private static void inject(
            Object target,
            String fieldName,
            Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
