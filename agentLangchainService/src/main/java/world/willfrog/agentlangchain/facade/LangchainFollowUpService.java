package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class LangchainFollowUpService {

    private final LangchainRunReadService runReadService;
    private final AgentRunMapper runMapper;
    private final AgentRunEventService agentEventService;
    private final AgentMessageService messageService;
    private final AgentRunStateStore stateStore;
    private final LangchainLinearRunPipeline pipeline;

    public SendAgentMessageResponse sendMessage(SendAgentMessageRequest request) {
        String userId = requireNonBlank(request.getUserId(), "user_id is required");
        String runId = requireNonBlank(request.getRunId(), "run_id is required");
        String content = requireNonBlank(request.getContent(), "content is required");

        AgentRun run = runReadService.requireWritableRun(runId, userId);
        if (run.getStatus() != AgentRunStatus.COMPLETED) {
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run not completed, current status: "
                            + run.getStatus().name() + ", please wait or create a new run")
                    .setRunStatus(run.getStatus().name())
                    .build();
        }
        if (agentEventService.shouldMarkExpired(run)) {
            runMapper.updateStatus(runId, userId, AgentRunStatus.EXPIRED);
            agentEventService.append(runId, userId, "RUN_EXPIRED", Map.of(
                    "run_id", runId,
                    "expired_at", java.time.OffsetDateTime.now().toString()));
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run expired")
                    .setRunStatus(AgentRunStatus.EXPIRED.name())
                    .build();
        }

        String metaJson = messageService.buildMetaJson(null, null, null, null);
        AgentRunMessage userMessage = messageService.createUserMessage(runId, content, metaJson);
        agentEventService.append(runId, userId, "FOLLOW_UP_RECEIVED", Map.of(
                "seq", userMessage.getSeq(),
                "content_preview", preview(content, 200),
                "message_id", userMessage.getId()));

        runMapper.updatePlanJson(runId, userId, "{}");
        stateStore.clearPlanCache(runId);
        stateStore.clearTasks(runId);
        runMapper.resetForResume(runId, userId, agentEventService.nextTtlExpiresAt());
        agentEventService.append(runId, userId, "WORKFLOW_RESUMED", Map.of(
                "run_id", runId,
                "reason", "follow_up",
                "message_seq", userMessage.getSeq(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(runId, AgentRunStatus.RECEIVED.name());

        AgentRun refreshed = runReadService.requireReadableRun(runId, userId);
        pipeline.launchAsync(refreshed);

        return SendAgentMessageResponse.newBuilder()
                .setMessageId(userMessage.getId())
                .setSeq(userMessage.getSeq())
                .setStatus("accepted")
                .setRunStatus(AgentRunStatus.RECEIVED.name())
                .build();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String preview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen);
    }
}
