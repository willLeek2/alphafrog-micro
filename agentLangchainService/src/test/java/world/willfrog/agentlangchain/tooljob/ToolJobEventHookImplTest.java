package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentRunEventService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolJobEventHookImplTest {

    @Test
    void emitsStableLogicalTerminalKeyAndTreatsDuplicateAsSuccess() {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setUserId("user-1");
        when(mapper.findById("run-1")).thenReturn(run);
        when(events.appendOnce(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setToolCallId("tc-1");
        anchor.setAttempt(2);
        anchor.setTaskId("task-1");
        anchor.setOperationId("run-1:tc-1:2");
        anchor.setTerminalStatus("SUCCEEDED");
        anchor.setTerminalResultPreview("preview");
        ToolJobEventHookImpl hook = new ToolJobEventHookImpl(mapper, events);

        assertThat(hook.emitTerminalEvent("run-1", anchor)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).appendOnce(eq("run-1"), eq("user-1"), eq("TOOL_CALL_FINISHED"),
                eq("run-1:tc-1:logical_terminal"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("tool_call_id", "tc-1")
                .containsEntry("attempt", 2)
                .containsEntry("success", true);
    }
}
