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

/** 把后台工具终态转换为唯一一条 {@code TOOL_CALL_FINISHED} 事件。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolJobEventHookImpl implements ToolJobEventHook {

    private final AgentRunMapper runMapper;
    private final AgentEventService agentEventService;

    @Override
    public boolean emitTerminalEvent(String runId, ToolJobAnchor anchor) {
        // 事件至少需要 Run 和 toolCall 身份；缺失时阻塞 finalizer 而不是发匿名事件。
        if (isBlank(runId) || anchor == null || isBlank(anchor.getToolCallId())) {
            return false;
        }
        // userId 从数据库 Run 读取，避免信任旧 worker 的 ThreadLocal。
        AgentRun run = runMapper.findById(runId);
        if (run == null || isBlank(run.getUserId())) {
            return false;
        }
        // 同一逻辑工具调用只有一个终态事件，finalizer 重入复用该 key。
        String dedupeKey = runId + ":" + anchor.getToolCallId() + ":logical_terminal";
        // LinkedHashMap 保持诊断输出字段顺序稳定。
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
            // appendOnce 若发现同一 key 已存在仍是幂等成功，不阻塞 finalizer 后续恢复。
            agentEventService.appendOnce(runId, run.getUserId(), "TOOL_CALL_FINISHED", dedupeKey, payload);
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
