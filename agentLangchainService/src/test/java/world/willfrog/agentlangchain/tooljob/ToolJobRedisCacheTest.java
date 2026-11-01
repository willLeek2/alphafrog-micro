package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobRedisCacheTest {







    private static ToolJobAnchor abortingAnchor(
            String operationId,
            String ownerId,
            Instant leaseUntil) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId(operationId);
        anchor.setAnchorState("ABORTING");
        anchor.setRunDisposition(ToolJobRunDisposition.DAG_BLOCKING_PREPARING_ABORT);
        anchor.setBlockingOwnerId(ownerId);
        anchor.setBlockingLeaseUntil(leaseUntil);
        anchor.setTimeoutAt(leaseUntil.plusSeconds(300));
        return anchor;
    }

    private static ToolJobAnchor clearingAnchor(
            ToolJobAnchor expected,
            String ownerId,
            Instant leaseUntil) {
        ToolJobAnchor cleanup = ToolJobAnchor.fromJson(expected.toJson());
        cleanup.setCleanupSourceOwnerId(expected.getBlockingOwnerId());
        cleanup.setCleanupSourceLeaseUntil(expected.getBlockingLeaseUntil());
        cleanup.setAnchorState("CLEARING");
        cleanup.setBlockingOwnerId(ownerId);
        cleanup.setBlockingLeaseUntil(leaseUntil);
        return cleanup;
    }
}
