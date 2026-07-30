package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolJobDagCleanupAnchorServiceTest {

    @Test
    void failAndClearBindsExecutingOperationAndDiagnostic() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        when(mapper.failDagBlockingAndClearToolJobAnchor(
                "run-1",
                AgentRunStatus.EXECUTING,
                "run-1:call-1:1",
                "DAG_BLOCKING_WORKER_LOST")).thenReturn(1);
        ToolJobAnchorService service = new ToolJobAnchorService(mapper);

        boolean updated = service.failDagBlockingAndClear(
                "run-1",
                AgentRunStatus.EXECUTING,
                "run-1:call-1:1",
                "DAG_BLOCKING_WORKER_LOST");

        assertThat(updated).isTrue();
        verify(mapper).failDagBlockingAndClearToolJobAnchor(
                "run-1",
                AgentRunStatus.EXECUTING,
                "run-1:call-1:1",
                "DAG_BLOCKING_WORKER_LOST");
    }
}
