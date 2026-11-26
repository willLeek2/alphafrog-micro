package world.willfrog.agentlangchain.tooljob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentRunEventService;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把后台工具终态转换为唯一一条 {@code TOOL_CALL_FINISHED} 事件。 */
@Service
@Slf4j
public class ToolJobEventHookImpl implements ToolJobEventHook {

    private final AgentRunMapper runMapper;
    private final AgentRunEventService agentEventService;
    private final ToolResultRecordedEventPublisher resultEventPublisher;

    public ToolJobEventHookImpl(AgentRunMapper runMapper, AgentRunEventService agentEventService) {
        this(runMapper, agentEventService, null);
    }

    @Autowired
    public ToolJobEventHookImpl(AgentRunMapper runMapper, AgentRunEventService agentEventService,
                               ToolResultRecordedEventPublisher resultEventPublisher) {
        this.runMapper = runMapper;
        this.agentEventService = agentEventService;
        this.resultEventPublisher = resultEventPublisher;
    }

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
        // 计价按事件里的工具名分价，缺失时下游只能按默认值 1 降级。
        put(payload, "tool_name", anchor.getToolName());
        payload.put("attempt", anchor.getAttempt());
        put(payload, "operation_id", anchor.getOperationId());
        put(payload, "task_id", anchor.getTaskId());
        put(payload, "status", anchor.getTerminalStatus());
        payload.put("success", anchor.getTerminalModelSendBlock() == null
                && (anchor.getTerminalBusinessSuccess() != null ? anchor.getTerminalBusinessSuccess()
                    : "SUCCEEDED".equals(anchor.getTerminalStatus())));
        if (anchor.getTerminalModelSendBlock() != null) payload.putAll(anchor.getTerminalModelSendBlock());
        put(payload, "result_preview", anchor.getTerminalResultPreview());
        put(payload, "raw_ref", anchor.getTerminalRawRef());
        put(payload, "error_code", anchor.getTerminalErrorCode());
        put(payload, "resource_usage", anchor.getTerminalUsageJson());
        try {
            // appendOnce 若发现同一 key 已存在仍是幂等成功，不阻塞 finalizer 后续恢复。
            agentEventService.appendOnce(runId, run.getUserId(), "TOOL_CALL_FINISHED", dedupeKey, payload);
        } catch (Exception e) {
            log.warn("Failed to append logical terminal event runId={} toolCallId={}: {}",
                    runId, anchor.getToolCallId(), e.getMessage());
            return false;
        }
        try {
            if (resultEventPublisher != null) {
                resultEventPublisher.publishAnchor(runId, run.getUserId(), anchor);
            }
        } catch (Exception e) {
            log.warn("锚点展示通知失败: runId={} exception={}", runId, e.getClass().getSimpleName());
        }
        return true;
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
