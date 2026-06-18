package world.willfrog.agent.service.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

/**
 * run 终态事件监听器。
 *
 * <p>订阅 {@link AgentRunFinalizedEvent} 后调 {@link WorkspaceDumpScheduler} 异步触发 workspace dump。
 *
 * @author wang
 */
@Component
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
