package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

/**
 * run 终态事件监听器。
 *
 * <p>订阅 {@link AgentRunFinalizedEvent} 后调 {@link WorkspaceDumpScheduler} 异步触发 workspace dump。
 * 事件必须统一由 AgentRunFinalizationService 构造，使 EXPIRED-only conservative 语义与
 * WorkspacePollingObserver 保持一致；本监听器只转发该规范化结果。
 *
 * @author wang
 */
@Component
// 260814 scheduler-03: workspace export 总开关默认关闭；关闭时本监听器不注册，
// Run 终态不会触发任何 workspace dump 副作用。
@ConditionalOnProperty(name = "agent.workspace.export-enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFinalizedEventListener {

    private final WorkspaceDumpScheduler dumpScheduler;

    /**
     * 接收 run 终态事件，触发异步 dump。
     *
     * @param event run 终态事件
     */
    @EventListener
    public void onRunFinalized(AgentRunFinalizedEvent event) {
        if (event == null) {
            log.warn("WorkspaceFinalizedEventListener received null event, ignore");
            return;
        }
        log.info("WorkspaceFinalizedEventListener received: runId={} userId={} status={} conservative={}",
                event.runId(), event.userId(), event.terminalStatus(), event.conservative());
        dumpScheduler.enqueueDumpAsync(event.runId(), event.conservative());
    }
}
