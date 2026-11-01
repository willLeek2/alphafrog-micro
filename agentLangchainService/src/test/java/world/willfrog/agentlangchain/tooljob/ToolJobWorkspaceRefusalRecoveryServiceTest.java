package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobWorkspaceRefusalRecoveryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final DataAnalysisCapacityService capacity = mock(DataAnalysisCapacityService.class);
    private final ToolJobAnchorService anchors = mock(ToolJobAnchorService.class);
    private final ToolJobWorkspaceRefusalRecoveryService recovery =
            new ToolJobWorkspaceRefusalRecoveryService();
    private final DataAnalysisOperationIdentity identity =
            new DataAnalysisOperationIdentity("run-1", "call-1", 1);




    private ToolJobAnchor anchor(DataAnalysisReservationState state) throws Exception {
        DataAnalysisReservation reservation = new DataAnalysisReservation(
                identity.reservationId(), identity, DataAnalysisResourceClass.STANDARD,
                1, state, null, Instant.now());
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(identity.operationId());
        anchor.setRequestFingerprint("sha256:" + "a".repeat(64));
        anchor.setAnchorState("WORKSPACE_REFUSED");
        anchor.setWorkspaceRefusalCode("WORKSPACE_DIRTY");
        anchor.setReservationJson(MAPPER.writeValueAsString(reservation));
        return anchor;
    }
}
