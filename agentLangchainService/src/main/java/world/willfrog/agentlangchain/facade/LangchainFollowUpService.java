package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.entity.AgentRunMessage;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.service.AgentMessageService;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agentlangchain.execution.LangchainLinearRunPipeline;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentityProvider;
import world.willfrog.agentlangchain.deployment.DeploymentGenerationRetirementService;

import java.util.Map;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class LangchainFollowUpService {

    private final LangchainRunReadService runReadService;
    private final AgentRunMapper runMapper;
    private final AgentRunEventService agentEventService;
    private final AgentMessageService messageService;
    private final AgentRunStateStore stateStore;
    private final LangchainLinearRunPipeline pipeline;
    private final DeploymentIdentityProvider deploymentIdentityProvider;

    @Autowired(required = false)
    private DeploymentGenerationRetirementService retirementService;
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public SendAgentMessageResponse sendMessage(SendAgentMessageRequest request) {
        if (retirementService == null) {
            return sendMessageWhileActive(request);
        }
        try {
            return retirementService.executeWhileActive(() -> sendMessageWhileActive(request));
        } catch (IllegalStateException e) {
            if ("deployment_generation_inactive".equals(e.getMessage())) {
                return rejectedInactiveDeployment();
            }
            throw e;
        }
    }

    private <T> T executeAdmissionTransaction(Supplier<T> operation) {
        if (transactionManager == null) {
            return operation.get();
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // 退役的进程内串行区间要覆盖数据库提交，因此不能加入外层事务后延迟提交。
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> operation.get());
    }

    private SendAgentMessageResponse sendMessageWhileActive(SendAgentMessageRequest request) {
        String userId = requireNonBlank(request.getUserId(), "user_id is required");
        String runId = requireNonBlank(request.getRunId(), "run_id is required");
        String content = requireNonBlank(request.getContent(), "content is required");

        DeploymentIdentity localIdentity = deploymentIdentityProvider.current();
        try {
            localIdentity.requireExactMatch(
                    request.getDeploymentId(), request.getDeploymentGenerationId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return rejectedInactiveDeployment();
        }

        AgentRun ownedRun = runMapper.findByIdAndUserForDeployment(
                runId, userId, localIdentity.deploymentId(), localIdentity.generationId());
        if (ownedRun == null) {
            return rejectedInactiveDeployment();
        }
        // 先用带身份的 SQL 确认归属，再复用现有读取服务的过期收敛逻辑。
        // 部署身份由数据库触发器保持不可变，因此后续读取不会转移到其他代际。
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
            runMapper.updateStatusForDeployment(
                    runId, userId, localIdentity.deploymentId(),
                    localIdentity.generationId(), AgentRunStatus.COMPLETED, AgentRunStatus.EXPIRED);
            agentEventService.append(runId, userId, "RUN_EXPIRED", Map.of(
                    "run_id", runId,
                    "expired_at", java.time.OffsetDateTime.now().toString()));
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run expired")
                    .setRunStatus(AgentRunStatus.EXPIRED.name())
                    .build();
        }

        AgentRunMessage userMessage = executeAdmissionTransaction(() -> {
            if (runMapper.admitFollowUpForDeployment(
                    runId, userId, localIdentity.deploymentId(), localIdentity.generationId(),
                    agentEventService.nextTtlExpiresAt()) != 1) {
                return null;
            }
            String metaJson = messageService.buildMetaJson(null, null, null, null);
            return messageService.createUserMessage(runId, content, metaJson);
        });
        if (userMessage == null) {
            return rejectedInactiveDeployment();
        }

        // Run 重置与用户消息已经提交，调度线程现在能读到 RECEIVED 及新消息。
        agentEventService.append(runId, userId, "FOLLOW_UP_RECEIVED", Map.of(
                "seq", userMessage.getSeq(),
                "content_preview", preview(content, 200),
                "message_id", userMessage.getId()));

        stateStore.clearPlanCache(runId);
        stateStore.clearTasks(runId);
        agentEventService.append(runId, userId, "WORKFLOW_RESUMED", Map.of(
                "run_id", runId,
                "reason", "follow_up",
                "message_seq", userMessage.getSeq(),
                "engine", "agentLangchainService"));
        stateStore.markRunStatus(runId, AgentRunStatus.RECEIVED.name());

        AgentRun refreshed = runMapper.findByIdAndUserForDeployment(
                runId, userId, localIdentity.deploymentId(), localIdentity.generationId());
        if (refreshed == null) {
            throw new IllegalStateException("追问准入后无法读取同一部署身份的 Run");
        }
        pipeline.launchAsync(refreshed);

        return SendAgentMessageResponse.newBuilder()
                .setMessageId(userMessage.getId())
                .setSeq(userMessage.getSeq())
                .setStatus("accepted")
                .setRunStatus(AgentRunStatus.RECEIVED.name())
                .build();
    }

    private static SendAgentMessageResponse rejectedInactiveDeployment() {
        return SendAgentMessageResponse.newBuilder()
                .setStatus("rejected")
                .setRejectReason("原测试部署已停用")
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
