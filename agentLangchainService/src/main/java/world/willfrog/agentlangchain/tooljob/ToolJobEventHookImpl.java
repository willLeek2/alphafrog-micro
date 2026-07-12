package world.willfrog.agentlangchain.tooljob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentEventService;

import java.util.LinkedHashMap;
import java.util.Map;

/** Emits the logical external-tool terminal event exactly once. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolJobEventHookImpl implements ToolJobEventHook {

    private final AgentRunMapper runMapper;
    private final AgentEventService eventService;

    @Override
    public boolean emitTerminalEvent(String runId, ToolJobAnchor anchor) {
        if (isBlank(runId) || anchor == null || isBlank(anchor.getToolCallId())) {
            return false;
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null || isBlank(run.getUserId())) {
            return false;
        }
        String dedupeKey = runId + ":" + anchor.getToolCallId() + ":logical_terminal";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run_id", runId);
        payload.put("tool_call_id", anchor.getToolCallId());
        payload.put("attempt", anchor.getAttempt());
        put(payload, "operation_id", anchor.getOperationId());
        put(payload, "task_id", anchor.getTaskId());
        put(payload, "status", anchor.getTerminalStatus());
        payload.put("success", "SUCCEEDED".equals(anchor.getTerminalStatus()));
        put(payload, "result_preview", anchor.getTerminalResultPreview());
        put(payload, "raw_ref", anchor.getTerminalRawRef());
        put(payload, "error_code", anchor.getTerminalErrorCode());
        put(payload, "resource_usage", anchor.getTerminalUsageJson());
        try {
            // false means the same logical event already exists; that is still a
            // successful idempotent hook outcome and must not block the finalizer.
            eventService.appendOnce(runId, run.getUserId(), "TOOL_CALL_FINISHED", dedupeKey, payload);
            return true;
        } catch (Exception e) {
            log.warn("Failed to append logical terminal event runId={} toolCallId={}: {}",
                    runId, anchor.getToolCallId(), e.getMessage());
            return false;
        }
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (!isBlank(value)) {
            payload.put(key, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
