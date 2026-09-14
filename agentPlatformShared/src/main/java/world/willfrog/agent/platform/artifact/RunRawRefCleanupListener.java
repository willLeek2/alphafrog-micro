package world.willfrog.agent.platform.artifact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.event.AgentRunFinalizedEvent;

/**
 * 260814 scheduler-03: terminal-state cleanup for run-scoped rawRef storage.
 *
 * <p>The finalization event fires for terminal transitions only
 * (COMPLETED / PARTIAL / FAILED / CANCELED / EXPIRED); at that point the Run
 * will never read its rawRefs again, so the whole run directory is deleted.
 * TTL-expiry and the startup sweep remain as backstops for Runs that never
 * emitted an event (e.g. a crash between terminal write and event publish).</p>
 *
 * <p>This listener is independent of the Workspace export switch: rawRef is
 * the primary storage for big tool outputs in the current round, not an
 * export feature.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunRawRefCleanupListener {

    private final RunRawRefLocalStore localStore;

    @EventListener
    public void onRunFinalized(AgentRunFinalizedEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        try {
            localStore.cleanupRun(event.runId());
        } catch (RuntimeException e) {
            log.warn("rawRef cleanup failed for runId={}: {}", event.runId(), e.getMessage());
        }
    }
}
