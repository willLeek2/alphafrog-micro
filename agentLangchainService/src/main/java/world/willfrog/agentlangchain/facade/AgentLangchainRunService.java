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
import world.willfrog.agentlangchain.acceptance.AcceptanceFixtureGate;
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
    private final AcceptanceFixtureGate acceptanceFixtureGate;

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

        // 验收夹具的门在所有写库动作之前：上下文里带了夹具编号，就要么按这条夹具跑，
        // 要么当场报错，不存在「夹具不可用就照普通请求跑一遍」这条路。不带编号的请求
        // 在这里连夹具表都不会查，行为与从前一致。夹具内容本身由执行层从 Run 的 ext 里
        // 读回来（请求上下文原样存在那里），这里只负责确认这次请求可以用它。
        acceptanceFixtureGate.admitRequestContext(
                request.getContextJson(), deploymentIdentity.deploymentId(), deploymentIdentity.generationId());

        LangchainLinearRunPipeline pipeline = linearRunPipelineProvider.getIfAvailable();
        LangchainRunConcurrencyScheduler.Reservation reservation = null;
        AgentRun run = null;
        boolean createdNow = false;
        // 版本在创建前选择并写进 Run。只有旧版本预占旧调度器名额；双池版本只在数据库
        // 记录写稳后补发提示，提示丢失由扫描恢复。
        String schedulerVersion = schedulerVersionPolicy.versionForNewRun();
        boolean dualPoolFamily = schedulerVersionPolicy.isDualPoolFamily(schedulerVersion);
        if (dualPoolFamily && dualPoolRunAdmissionRegistry.startupResidueBlockedFor(schedulerVersion)) {
            throw new world.willfrog.agentlangchain.control.LangchainRunRejectedException(
                    "dual_pool_startup_residue_blocked", "startup_residue_blocked");
        }
        if (pipeline != null && SchedulerVersionPolicy.LEGACY.equals(schedulerVersion)) {
            reservation = runConcurrencyScheduler.reserve();
        }
        try {
            AgentRunEventService.RunCreation creation = agentEventService.createRun(
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
            run = creation.run();
            createdNow = creation.created();
            if (!createdNow) {
                // 幂等键命中：这条 Run 早就建好、也早就交给执行入口了，这次只是一次重复提交。
                // 既不预留名额，也不启动 pipeline；本次为它临时占下的名额当场还回去。
                log.info("幂等键命中，本次不启动 pipeline: runId={} schedulerVersion={}",
                        run.getId(), schedulerVersion);
                if (reservation != null) {
                    runConcurrencyScheduler.release(reservation);
                    reservation = null;
                }
                return AgentLangchainRunMessageMapper.toRunMessage(run);
            }

            if (pipeline != null) {
                if (dualPoolFamily) {
                    // 持久交接凭据不在这里补写：协调资格由创建那一条事务一起写（见
                    // AgentRunEventService.createRun 里 Run 主记录、协调资格、接收事实同一次提交），
                    // 所以「Run 已经落库、却没有资格记录」这个窗口不存在，再写一次只会多出一个
                    // 看起来像是保证来源的第二次写入。
                    // 这里只把当前进程新建且已经落库的 Run 加入双池；该集合不跨重启恢复。
                    if (!dualPoolRunAdmissionRegistry.admitNewRun(run.getId(), schedulerVersion)) {
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
            log.error("创建后启动失败: runId={} schedulerVersion={} createdNow={}",
                    run == null ? null : run.getId(), schedulerVersion, createdNow, e);
            if (reservation != null) {
                runConcurrencyScheduler.release(reservation);
            }
            // 只有这次真的新建了 Run 才收尾：幂等读回的旧 Run 属于别人的执行流程，
            // 一次重复提交的失败不该把它改成失败态。
            if (run != null && createdNow) {
                if (dualPoolFamily) {
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
                    "reason", describeFailure(error)
            ));
            runMapper.updateStatus(
                    run.getId(), run.getUserId(), run.getStatus(), AgentRunStatus.FAILED);
        } catch (Exception markError) {
            log.warn("Failed to mark langchain run enqueue failure: runId={}, error={}",
                    run.getId(), markError.getMessage());
        }
    }

    /**
     * 排队失败事件要能从库里直接读出下一层原因。Spring 包过的数据库访问异常经常把
     * {@code getMessage()} 留空，真正的语句错误在 cause 上。
     */
    static String describeFailure(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 8) {
            String message = current.getMessage();
            String piece = (message == null || message.isBlank())
                    ? current.getClass().getSimpleName()
                    : current.getClass().getSimpleName() + ": " + message;
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(piece);
            current = current.getCause();
            depth++;
        }
        return text.toString();
    }
}
