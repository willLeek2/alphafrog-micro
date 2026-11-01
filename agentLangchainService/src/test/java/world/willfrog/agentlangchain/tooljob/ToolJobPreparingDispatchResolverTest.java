package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import com.google.protobuf.util.JsonFormat;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxErrorDetail;
import world.willfrog.alphafrogmicro.sandbox.idl.WorkspaceResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobPreparingDispatchResolverTest {

    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    private final PythonSandboxService sandbox = mock(PythonSandboxService.class);
    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final DataAnalysisOperationIdentity identity =
            new DataAnalysisOperationIdentity("run-1", "call-1", 1);
    private final DataAnalysisReservation preparing = new DataAnalysisReservation(
            identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD, 1,
            DataAnalysisReservationState.PREPARING, null, Instant.now());









    private ToolJobAnchor replayablePreparing() throws Exception {
        ToolJobAnchor anchor = canceledPreparing();
        anchor.setRunDisposition(null);
        ExecuteRequest request = ExecuteRequest.newBuilder()
                .setOperationId(identity.operationId())
                .setRequestFingerprint(FINGERPRINT)
                .setRunId("run-1")
                .setWorkspaceId("workspace-1")
                .setWorkspaceGeneration("generation-1")
                .setCode("print(1)")
                .build();
        anchor.setCreateRequestJson(JsonFormat.printer().print(request));
        return anchor;
    }

    private ToolJobAnchor canceledPreparing() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setRequestFingerprint(FINGERPRINT);
        anchor.setAnchorState("PREPARING");
        anchor.setRunDisposition("CANCELED");
        anchor.setAutoResume(false);
        return anchor;
    }
}
