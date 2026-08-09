package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentEventService;
import world.willfrog.agentlangchain.orchestration.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.orchestration.LangchainRunConcurrencyScheduler;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentLangchainRunService {

    private static final int ADMIN_USER_TYPE = 1127;

    private final ObjectProvider<AgentEventService> eventServiceProvider;
    private final ObjectProvider<LangchainLinearRunPipeline> linearRunPipelineProvider;
    private final LangchainRunConcurrencyScheduler runConcurrencyScheduler;
    private final AgentRunMapper runMapper;
    private final AgentCreditService creditService;
    private final UserDao userDao;

    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        if (!isAdminUser(userId) && !creditService.hasPositiveCredit(userId)) {
            throw new IllegalStateException("credit 余额不足，无法创建新任务");
        }

        AgentEventService eventService = eventServiceProvider.getIfAvailable();
        if (eventService == null) {
            throw new IllegalStateException("agent_event_service_unavailable");
        }

        LangchainLinearRunPipeline pipeline = linearRunPipelineProvider.getIfAvailable();
        LangchainRunConcurrencyScheduler.Reservation reservation = null;
        AgentRun run = null;
        if (pipeline != null) {
            reservation = runConcurrencyScheduler.reserve();
        }
        try {
            run = eventService.createRun(
                    userId,
                    message,
                    request.getContextJson(),
                    request.getIdempotencyKey(),
                    request.getModelName(),
                    request.getEndpointName(),
                    request.getCaptureLlmRequests(),
                    request.getProvider(),
                    request.getPlannerCandidateCount(),
                    request.getDebugMode(),
                    request.getStageConfigJson(),
                    isAdminUser(userId)
            );

            if (pipeline != null) {
                log.info("Launching langchain linear pipeline for run {}", run.getId());
                pipeline.launchAsync(run, reservation);
                reservation = null;
            } else {
                log.warn("LangchainLinearRunPipeline not registered; run {} created but not executed", run.getId());
            }
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        } catch (RuntimeException e) {
            if (reservation != null) {
                runConcurrencyScheduler.release(reservation);
            }
            if (run != null) {
                markEnqueueFailed(eventService, run, e);
            }
            throw e;
        }
    }

    private boolean isAdminUser(String userId) {
        Long userIdLong;
        try {
            userIdLong = Long.parseLong(userId.trim());
        } catch (Exception e) {
            return false;
        }
        User user = userDao.getUserById(userIdLong);
        return user != null && user.getUserType() != null && user.getUserType() == ADMIN_USER_TYPE;
    }

    private void markEnqueueFailed(AgentEventService eventService, AgentRun run, RuntimeException error) {
        try {
            eventService.append(run.getId(), run.getUserId(), "RUN_ENQUEUE_FAILED", Map.of(
                    "engine", "agentLangchainService",
                    "reason", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
            ));
            runMapper.updateStatus(run.getId(), run.getUserId(), AgentRunStatus.FAILED);
        } catch (Exception markError) {
            log.warn("Failed to mark langchain run enqueue failure: runId={}, error={}",
                    run.getId(), markError.getMessage());
        }
    }
}
