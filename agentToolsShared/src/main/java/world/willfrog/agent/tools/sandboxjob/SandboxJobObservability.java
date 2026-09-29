package world.willfrog.agent.tools.sandboxjob;

import org.apache.dubbo.rpc.RpcContext;
import world.willfrog.agent.platform.context.AgentContext;
import world.willfrog.agent.platform.debug.DebugObservabilityRpcKeys;
import world.willfrog.agent.platform.debug.DebugObservabilityService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱长任务生命周期的调试观测出口（工具中立）。
 *
 * <p>事件名、字段名与主机快照格式整体从 executePython 的既有实现原样搬入，
 * 事件流的外形不因生命周期下沉而变化。未启用 {@link DebugObservabilityService} 时全部静默。</p>
 */
public final class SandboxJobObservability {

    private final DebugObservabilityService debugObservabilityService;

    public SandboxJobObservability(DebugObservabilityService debugObservabilityService) {
        this.debugObservabilityService = debugObservabilityService;
    }

    /**
     * 向调试观测服务发送结构化事件。
     *
     * @param eventType 事件类型，如 {@code sandbox_poll}、{@code sandbox_create_task}
     * @param fields 与 eventType 配套的键值对，方法内会追加 {@code eventType} 字段
     */
    public void emit(String eventType, Map<String, Object> fields) {
        if (debugObservabilityService == null || !debugObservabilityService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(fields);
            payload.put("eventType", eventType);
            debugObservabilityService.emit(payload);
        } catch (Exception ignored) {
            // 调试观测路径上的异常不应影响工具主流程
        }
    }

    /**
     * 在发起 Dubbo 调用前，把调试会话 id、run id、会话目录写入 RpcContext attachment，
     * 以便沙箱服务侧把日志与产物归档到同一调试目录。
     */
    public void installDebugRpcAttachments() {
        if (debugObservabilityService == null || !debugObservabilityService.isEnabled()) {
            return;
        }
        try {
            String sessionId = AgentContext.getDebugObservabilitySessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            RpcContext.getClientAttachment().setAttachment(DebugObservabilityRpcKeys.SESSION_ID, sessionId);
            String runId = AgentContext.getRunId();
            if (runId != null && !runId.isBlank()) {
                RpcContext.getClientAttachment().setAttachment(DebugObservabilityRpcKeys.RUN_ID, runId);
            }
            String sessionDir = debugObservabilityService.sessionDirFor(sessionId);
            if (sessionDir != null) {
                RpcContext.getClientAttachment().setAttachment(DebugObservabilityRpcKeys.SESSION_DIR, sessionDir);
            }
        } catch (Exception ignored) {
            // 调试观测路径上的异常不应影响工具主流程
        }
    }

    /** 工具调用结束时发送汇总事件，附带总耗时、终态 status，以及可选的主机堆内存快照。 */
    public void emitToolTotal(long toolStartMs, String status, String errorCategory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", System.currentTimeMillis() - toolStartMs);
        payload.put("status", status);
        if (errorCategory != null && !errorCategory.isBlank()) {
            payload.put("errorCategory", errorCategory);
        }
        appendHostSnapshot(payload);
        emit("sandbox_tool_total", payload);
    }

    /** 把当前 JVM 堆使用与 OS 负载写入 payload，供性能排查时对照沙箱耗时。 */
    private void appendHostSnapshot(Map<String, Object> payload) {
        try {
            Runtime runtime = Runtime.getRuntime();
            payload.put("heapFreeBytes", runtime.freeMemory());
            payload.put("heapTotalBytes", runtime.totalMemory());
            payload.put("heapMaxBytes", runtime.maxMemory());
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            payload.put("systemLoadAverage", osBean.getSystemLoadAverage());
            payload.put("availableProcessors", osBean.getAvailableProcessors());
        } catch (Exception ignored) {
            // 主机资源快照为可选项，采集失败时忽略
        }
    }
}
