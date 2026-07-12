package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolJobAnchorServiceTest {

    @Mock
    private AgentRunMapper agentRunMapper;

    private ToolJobAnchorService anchorService;

    @BeforeEach
    void setUp() {
        anchorService = new ToolJobAnchorService(agentRunMapper);
    }

    @Test
    void shouldLoadAnchorFromRun() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");
        anchor.setTaskId("task-123");

        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setStatus(AgentRunStatus.WAITING_TOOL_JOB);
        run.setToolJobAnchorJson(anchor.toJson());

        when(agentRunMapper.findById("run-1")).thenReturn(run);

        ToolJobAnchor loaded = anchorService.loadAnchor("run-1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOperationId()).isEqualTo("run-1:tc-1:1");
        assertThat(loaded.getTaskId()).isEqualTo("task-123");
    }

    @Test
    void shouldReturnNullWhenRunNotFound() {
        when(agentRunMapper.findById("run-1")).thenReturn(null);
        assertThat(anchorService.loadAnchor("run-1")).isNull();
    }

    @Test
    void shouldReturnNullWhenAnchorJsonIsBlank() {
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setToolJobAnchorJson("");

        when(agentRunMapper.findById("run-1")).thenReturn(run);
        assertThat(anchorService.loadAnchor("run-1")).isNull();
    }

    @Test
    void shouldUpdateAnchorWithCasStatus() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:tc-1:1");

        when(agentRunMapper.updateToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(1);

        boolean result = anchorService.updateAnchor("run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalseWhenCasUpdateFails() {
        ToolJobAnchor anchor = new ToolJobAnchor();
        when(agentRunMapper.updateToolJobAnchor(eq("run-1"), anyString(), eq(AgentRunStatus.WAITING_TOOL_JOB)))
                .thenReturn(0);

        boolean result = anchorService.updateAnchor("run-1", anchor, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isFalse();
    }

    @Test
    void shouldCasUpdateStatus() {
        when(agentRunMapper.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB))
                .thenReturn(1);

        boolean result = anchorService.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isTrue();
    }

    @Test
    void shouldReturnFalseWhenCasStatusFails() {
        when(agentRunMapper.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB))
                .thenReturn(0);

        boolean result = anchorService.casUpdateStatus("run-1", AgentRunStatus.RECEIVED, AgentRunStatus.WAITING_TOOL_JOB);
        assertThat(result).isFalse();
    }
}
