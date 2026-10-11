package world.willfrog.agentlangchain.facade;

import lombok.RequiredArgsConstructor;
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
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.SchedulerVersionPolicy;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageRequest;
import world.willfrog.alphafrogmicro.agent.idl.SendAgentMessageResponse;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;

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
    private final RunOwnershipGateway ownershipGateway;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SchedulerVersionPolicy schedulerVersionPolicy;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DualPoolRunAdmissionRegistry dualPoolRunAdmissionRegistry;

    public SendAgentMessageResponse sendMessage(SendAgentMessageRequest request) {
        return sendMessageWhileActive(request);
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

        // 归属判定在 gateway 的受理入口完成：只受理本部署代际的 Run。
        // Run 上的部署身份由数据库触发器保持不可变，判定结论在本次处理内保持有效。
        if (ownershipGateway.findOwnedRunForUser(runId, userId) == null) {
            return rejectedInactiveDeployment();
        }
        AgentRun run = runReadService.requireWritableRun(runId, userId);
        if (run.getStatus() != AgentRunStatus.COMPLETED) {
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run not completed, current status: "
                            + run.getStatus().name() + ", please wait or create a new run")
                    .setRunStatus(run.getStatus().name())
                    .build();
        }
        if (!dualPoolFollowUpAllowed(run)) {
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("双池 Run 来自本次进程启动之前，不能自动接续；请新建 Run")
                    .setRunStatus(run.getStatus().name())
                    .build();
        }
        if (agentEventService.shouldMarkExpired(run)) {
            runMapper.updateStatus(
                    runId, userId, AgentRunStatus.COMPLETED, AgentRunStatus.EXPIRED);
            agentEventService.append(runId, userId, "RUN_EXPIRED", Map.of(
                    "run_id", runId,
                    "expired_at", java.time.OffsetDateTime.now().toString()));
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("run expired")
                    .setRunStatus(AgentRunStatus.EXPIRED.name())
                    .build();
        }
        DualPoolRunAdmissionRegistry.Admission dualPoolReservation = reserveDualPoolRun(run);
        if (schedulerVersionPolicy != null && schedulerVersionPolicy.isDualPool(run)
                && (dualPoolReservation == null || !dualPoolReservation.admitted())) {
            return SendAgentMessageResponse.newBuilder()
                    .setStatus("rejected")
                    .setRejectReason("双池业务准入名额已满，请稍后重试")
                    .setRunStatus(run.getStatus().name())
                    .build();
        }

        AgentRunMessage userMessage;
        boolean durableReceived = false;
        try {
            userMessage = executeAdmissionTransaction(() -> {
                if (ownershipGateway.admitFollowUp(
                        runId, userId, agentEventService.nextTtlExpiresAt()) != 1) {
                    return null;
                }
                String metaJson = messageService.buildMetaJson(null, null, null, null);
                return messageService.createUserMessage(runId, content, metaJson);
            });
            if (userMessage == null) {
                releaseDualPoolReservation(run, dualPoolReservation);
                return rejectedInactiveDeployment();
            }
            if (dualPoolReservation != null && dualPoolReservation.admitted()
                    && !dualPoolRunAdmissionRegistry.activateReservedAdmission(
                    run.getId(), dualPoolReservation)) {
                throw new IllegalStateException("追问已落库，但双池准入预留无法激活");
            }
            // 这里之后 Run 已经持久化为 RECEIVED。后续事件、缓存或提示投递失败时，
            // 必须保留业务名额，让数据库扫描继续发现它；否则会留下永远无人消费的 Run。
            durableReceived = true;

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

            AgentRun refreshed = runMapper.findByIdAndUser(runId, userId);
            if (refreshed == null) {
                throw new IllegalStateException("追问准入后无法读取 Run");
            }
            pipeline.launchAsync(refreshed);

            return SendAgentMessageResponse.newBuilder()
                    .setMessageId(userMessage.getId())
                    .setSeq(userMessage.getSeq())
                    .setStatus("accepted")
                    .setRunStatus(AgentRunStatus.RECEIVED.name())
                    .build();
        } catch (RuntimeException e) {
            if (!durableReceived) {
                releaseDualPoolReservation(run, dualPoolReservation);
            }
            throw e;
        }
    }

    private static SendAgentMessageResponse rejectedInactiveDeployment() {
        return SendAgentMessageResponse.newBuilder()
                .setStatus("rejected")
                .setRejectReason("原测试部署已停用")
                .build();
    }

    private boolean dualPoolFollowUpAllowed(AgentRun run) {
        if (schedulerVersionPolicy == null || dualPoolRunAdmissionRegistry == null) {
            return true;
        }
        return !schedulerVersionPolicy.isDualPool(run)
                || dualPoolRunAdmissionRegistry.isKnownInCurrentProcess(run.getId());
    }

    private DualPoolRunAdmissionRegistry.Admission reserveDualPoolRun(AgentRun run) {
        if (schedulerVersionPolicy == null || dualPoolRunAdmissionRegistry == null
                || !schedulerVersionPolicy.isDualPool(run)) {
            return null;
        }
        return dualPoolRunAdmissionRegistry.admitExistingRunWithLease(run.getId());
    }

    private void releaseDualPoolReservation(
            AgentRun run, DualPoolRunAdmissionRegistry.Admission reservation) {
        if (reservation != null && reservation.admitted()
                && schedulerVersionPolicy != null && schedulerVersionPolicy.isDualPool(run)) {
            dualPoolRunAdmissionRegistry.rollbackReservedAdmission(run.getId(), reservation);
        }
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
