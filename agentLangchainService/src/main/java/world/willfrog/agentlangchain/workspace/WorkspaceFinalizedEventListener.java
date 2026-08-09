package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;
import world.willfrog.agent.platform.model.AgentRunStatus;

import java.util.Set;

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

    private static final Set<AgentRunStatus> TERMINAL_STATUSES = Set.of(
            AgentRunStatus.COMPLETED,
            AgentRunStatus.PARTIAL,
            AgentRunStatus.FAILED,
            AgentRunStatus.CANCELED,
            AgentRunStatus.EXPIRED);

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
        String runId = event.runId();
        AgentRunStatus terminalStatus = parseTerminalStatus(event.terminalStatus());
        if (runId == null || runId.isBlank() || terminalStatus == null) {
            log.warn("WorkspaceFinalizedEventListener ignored malformed event: runId={} status={}",
                    runId, event.terminalStatus());
            return;
        }
        // conservative 是终态的派生属性，不能信任任意事件生产者传入的布尔值。
        // 只有 EXPIRED 允许减量写；CANCELED 等其他终态必须保持完整 dump。
        boolean conservative = terminalStatus == AgentRunStatus.EXPIRED;
        if (event.conservative() != conservative) {
            log.warn("WorkspaceFinalizedEventListener corrected conservative flag: "
                            + "runId={} status={} incoming={} canonical={}",
                    runId, terminalStatus, event.conservative(), conservative);
        }
        log.info("WorkspaceFinalizedEventListener received: runId={} userId={} status={} conservative={}",
                runId, event.userId(), terminalStatus, conservative);
        dumpScheduler.enqueueDumpAsync(runId, conservative);
    }

    private static AgentRunStatus parseTerminalStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            AgentRunStatus status = AgentRunStatus.valueOf(rawStatus.trim());
            return TERMINAL_STATUSES.contains(status) ? status : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
