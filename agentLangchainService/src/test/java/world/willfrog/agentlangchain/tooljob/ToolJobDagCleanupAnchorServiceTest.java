package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.mapper.AgentRunMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobDagCleanupAnchorServiceTest {

    @Test
    void cleanupOperationsBindOperationAndBlockingOwner() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.promoteExpiredDagBlockingWorkerLost(
                eq("run-1"),
                anyString(),
                eq("run-1:call-1:1"),
                eq("owner-1"))).thenReturn(1);
        when(mapper.updateDagCleanupToolJobAnchor(
                eq("run-1"), anyString(),
                eq("run-1:call-1:1"), eq("owner-1"))).thenReturn(1);
        when(mapper.updateDagCleanupPreparingToolJobAnchor(
                eq("run-1"), anyString(),
                eq("run-1:call-1:1"), eq("owner-1"), eq("sha256:request")))
                .thenReturn(1);
        when(mapper.completeDagCleanupAndClearToolJobAnchor(
                "run-1", "run-1:call-1:1", "owner-1",
                "DAG_BLOCKING_WORKER_LOST")).thenReturn(1);
        ToolJobAnchorService service = new ToolJobAnchorService(mapper);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setBlockingOwnerId("owner-1");
        anchor.setRequestFingerprint("sha256:request");

        assertThat(service.promoteExpiredDagBlockingWorkerLost(
                "run-1", anchor, "run-1:call-1:1", "owner-1")).isTrue();
        assertThat(service.updateDagCleanup(
                "run-1", anchor, "run-1:call-1:1", "owner-1")).isTrue();
        assertThat(service.updateDagCleanupPreparing(
                "run-1", anchor, "run-1:call-1:1", "owner-1",
                "sha256:request")).isTrue();
        assertThat(service.completeDagCleanupAndClear(
                "run-1", "run-1:call-1:1", "owner-1",
                "DAG_BLOCKING_WORKER_LOST")).isTrue();

        verify(mapper).promoteExpiredDagBlockingWorkerLost(
                "run-1", anchor.toJson(), "run-1:call-1:1", "owner-1");
        verify(mapper).updateDagCleanupToolJobAnchor(
                "run-1", anchor.toJson(), "run-1:call-1:1", "owner-1");
        verify(mapper).updateDagCleanupPreparingToolJobAnchor(
                "run-1", anchor.toJson(), "run-1:call-1:1", "owner-1",
                "sha256:request");
        verify(mapper).completeDagCleanupAndClearToolJobAnchor(
                "run-1",
                "run-1:call-1:1",
                "owner-1",
                "DAG_BLOCKING_WORKER_LOST");
    }
}
