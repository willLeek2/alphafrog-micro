package world.willfrog.agentlangchain.tooljob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipelineImpl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Production token/version-deduplicated resume launcher. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolJobResumeLauncherImpl implements ToolJobResumeLauncher {

    private final AgentRunMapper runMapper;
    private final LangchainLinearRunPipelineImpl pipeline;
    private final ObjectProvider<ToolJobResumeService> resumeServiceProvider;
    private final ConcurrentMap<ClaimKey, Boolean> activeClaims = new ConcurrentHashMap<>();

    @Override
    public boolean launch(String runId, ToolJobResumeContext context) {
        if (!valid(runId, context)) {
            return false;
        }
        ClaimKey key = new ClaimKey(runId, context.getResumeToken(), context.getResumeLeaseVersion());
        if (activeClaims.putIfAbsent(key, Boolean.TRUE) != null) {
            // Same logical claim was already accepted. Returning true is the
            // idempotent acknowledgement; no second pipeline task is submitted.
            return true;
        }
        AgentRun run = runMapper.findById(runId);
        if (run == null) {
            activeClaims.remove(key);
            return false;
        }
        try {
            boolean accepted = pipeline.launchResumedAsync(
                    run,
                    context,
                    () -> {
                        ToolJobResumeService service = resumeServiceProvider.getIfAvailable();
                        return service != null && service.markHandoffAccepted(runId, context);
                    },
                    durable -> {
                        try {
                            ToolJobResumeService service = resumeServiceProvider.getIfAvailable();
                            if (durable && service != null) {
                                service.completeHandoff(runId, context.getResumeToken(),
                                        context.getResumeLeaseVersion());
                            }
                        } finally {
                            activeClaims.remove(key);
                        }
                    });
            if (!accepted) {
                activeClaims.remove(key);
            }
            return accepted;
        } catch (Exception e) {
            activeClaims.remove(key);
            log.warn("Resume launch rejected runId={} token={} version={}: {}",
                    runId, context.getResumeToken(), context.getResumeLeaseVersion(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isActive(String runId, String token, long version) {
        if (runId == null || token == null) {
            return false;
        }
        return activeClaims.containsKey(new ClaimKey(runId, token, version));
    }

    private boolean valid(String runId, ToolJobResumeContext context) {
        return runId != null && !runId.isBlank()
                && context != null
                && runId.equals(context.getRunId())
                && context.getResumeToken() != null
                && !context.getResumeToken().isBlank()
                && context.getResumeLeaseVersion() > 0
                && context.getTodoId() != null
                && !context.getTodoId().isBlank();
    }

    private record ClaimKey(String runId, String token, long version) {}
}
