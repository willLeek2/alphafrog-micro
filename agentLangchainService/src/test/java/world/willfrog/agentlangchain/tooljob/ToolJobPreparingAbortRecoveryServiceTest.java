package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolJobPreparingAbortRecoveryServiceTest {

    private static final String RUN_ID = "run-1";
    private static final String OPERATION_ID = "run-1:call-1:1";
    private static final Instant LEASE =
            Instant.parse("2026-07-30T08:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

    private final DataAnalysisCapacityService capacityService =
            mock(DataAnalysisCapacityService.class);
    private final ToolJobAnchorService anchorService =
            mock(ToolJobAnchorService.class);
    private final ToolJobRedisCache redisCache =
            mock(ToolJobRedisCache.class);
    private final ToolJobPreparingAbortRecoveryService service =
            new ToolJobPreparingAbortRecoveryService();










    private void stubRedisCleanup() {
        when(redisCache.claimPreparingAbortCleanupIndexes(
                eq(RUN_ID), any(ToolJobAnchor.class), any(ToolJobAnchor.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexClaimResult.CLAIMED);
        when(redisCache.removePendingAndDueIfMatches(
                eq(RUN_ID),
                eq(OPERATION_ID),
                eq(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT),
                contains("/abort-cleanup/"),
                any(Instant.class)))
                .thenReturn(ToolJobRedisCache.OwnedIndexDeleteResult.REMOVED);
    }

    private static ToolJobAnchor abortingAnchor() throws Exception {
        DataAnalysisOperationIdentity identity =
                new DataAnalysisOperationIdentity(RUN_ID, "call-1", 1);
        DataAnalysisReservation released = new DataAnalysisReservation(
                identity.reservationId(),
                identity,
                DataAnalysisResourceClass.STANDARD,
                1,
                DataAnalysisReservationState.RELEASED,
                null,
                Instant.parse("2026-07-30T07:00:00Z"));
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(OPERATION_ID);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(
                ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-a");
        anchor.setBlockingLeaseUntil(LEASE);
        anchor.setReservationJson(
                OBJECT_MAPPER.writeValueAsString(released));
        return anchor;
    }
}
