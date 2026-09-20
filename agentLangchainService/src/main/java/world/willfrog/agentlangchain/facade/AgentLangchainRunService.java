package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentCreditService;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.agentlangchain.control.LangchainRunConcurrencyScheduler;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.gateway.LaneScopeGateway;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.common.dao.user.UserDao;
import world.willfrog.alphafrogmicro.common.pojo.user.User;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentLangchainRunService {

    private static final int ADMIN_USER_TYPE = 1127;

    private final ObjectProvider<AgentRunEventService> agentEventServiceProvider;
    private final ObjectProvider<LangchainLinearRunPipeline> linearRunPipelineProvider;
    private final LangchainRunConcurrencyScheduler runConcurrencyScheduler;
    private final AgentRunMapper runMapper;
    private final AgentCreditService creditService;
    private final UserDao userDao;
    private final RunOwnershipGateway ownershipGateway;
    private final SchedulerVersionPolicy schedulerVersionPolicy;
    private final DualPoolRunAdmissionRegistry dualPoolRunAdmissionRegistry;

    public AgentRunMessage createRun(CreateAgentRunRequest request) {
        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        DeploymentIdentity deploymentIdentity = ownershipGateway.requireIdentity();
        if (!isAdminUser(userId) && !creditService.hasPositiveCredit(userId)) {
            throw new IllegalStateException("credit 余额不足，无法创建新任务");
        }

        AgentRunEventService agentEventService = agentEventServiceProvider.getIfAvailable();
        if (agentEventService == null) {
            throw new IllegalStateException("agent_event_service_unavailable");
        }

        LangchainLinearRunPipeline pipeline = linearRunPipelineProvider.getIfAvailable();
        LangchainRunConcurrencyScheduler.Reservation reservation = null;
        AgentRun run = null;
        // 版本在创建前选择并写进 Run。只有旧版本预占旧调度器名额；双池版本只在数据库
        // 记录写稳后补发提示，提示丢失由扫描恢复。
        String schedulerVersion = schedulerVersionPolicy.versionForNewRun();
        if (SchedulerVersionPolicy.DUAL_POOL_V1.equals(schedulerVersion)
                && dualPoolRunAdmissionRegistry.startupResidueBlocked()) {
            throw new world.willfrog.agentlangchain.control.LangchainRunRejectedException(
                    "dual_pool_startup_residue_blocked", "startup_residue_blocked");
        }
        if (pipeline != null && SchedulerVersionPolicy.LEGACY.equals(schedulerVersion)) {
            reservation = runConcurrencyScheduler.reserve();
        }
        try {
            run = agentEventService.createRun(
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
                    deploymentIdentity.deploymentId(),
                    deploymentIdentity.generationId(),
                    LaneScopeGateway.currentLaneTag(),
                    schedulerVersion,
                    request.getGenerateArtifacts(),
                    isAdminUser(userId)
            );

            if (pipeline != null) {
                if (SchedulerVersionPolicy.DUAL_POOL_V1.equals(schedulerVersion)) {
                    // 只把当前进程新建且已经落库的 Run 加入双池；该集合不跨重启恢复。
                    if (!dualPoolRunAdmissionRegistry.admitNewRun(run.getId())) {
                        throw new world.willfrog.agentlangchain.control.LangchainRunRejectedException(
                                "dual_pool_business_admission_full", "business_admission_full");
                    }
                }
                log.info("Launching langchain pipeline for run {} with schedulerVersion={}",
                        run.getId(), schedulerVersion);
                pipeline.launchAsync(run, reservation);
                reservation = null;
            } else {
                log.warn("Langchain run pipeline not registered; run {} created but not executed", run.getId());
            }
            return AgentLangchainRunMessageMapper.toRunMessage(run);
        } catch (RuntimeException e) {
            if (reservation != null) {
                runConcurrencyScheduler.release(reservation);
            }
            if (run != null) {
                if (SchedulerVersionPolicy.DUAL_POOL_V1.equals(schedulerVersion)) {
                    dualPoolRunAdmissionRegistry.forgetFailedAdmission(run.getId());
                }
                markEnqueueFailed(agentEventService, run, e);
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

    private void markEnqueueFailed(AgentRunEventService agentEventService, AgentRun run, RuntimeException error) {
        try {
            agentEventService.append(run.getId(), run.getUserId(), "RUN_ENQUEUE_FAILED", Map.of(
                    "engine", "agentLangchainService",
                    "reason", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()
            ));
            runMapper.updateStatus(
                    run.getId(), run.getUserId(), run.getStatus(), AgentRunStatus.FAILED);
        } catch (Exception markError) {
            log.warn("Failed to mark langchain run enqueue failure: runId={}, error={}",
                    run.getId(), markError.getMessage());
        }
    }
}
