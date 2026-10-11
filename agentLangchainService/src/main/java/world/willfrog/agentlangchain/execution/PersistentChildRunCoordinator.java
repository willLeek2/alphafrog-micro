package world.willfrog.agentlangchain.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.childrun.ChildRunOutboxDelivery;
import world.willfrog.agent.platform.childrun.ChildRunAcceptanceControls;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.service.AgentRunEventService;
import world.willfrog.agent.platform.workitem.SchedulerVersion;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.control.dualpool.RunCoordinationHint;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.common.deployment.DeploymentIdentity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

/** Replays durable child creation requests and re-admits accepted child Runs after process exit. */
@Component
@Slf4j
public class PersistentChildRunCoordinator {
    private final ChildRunIntentStore intentStore;
    private final AgentRunEventService runEventService;
    private final RunOwnershipGateway ownership;
    private final WaitGroupStore waitGroups;
    private final DualPoolRunAdmissionRegistry admissionRegistry;
    private final DualPoolRunPipeline runPipeline;
    private final TransactionTemplate transactions;
    private final String owner = "child-run-outbox-" + UUID.randomUUID();
    private final int batchSize;
    private long launchScanCursor;

    public PersistentChildRunCoordinator(ChildRunIntentStore intentStore,
                                         AgentRunEventService runEventService,
                                         RunOwnershipGateway ownership,
                                         WaitGroupStore waitGroups,
                                         DualPoolRunAdmissionRegistry admissionRegistry,
                                         DualPoolRunPipeline runPipeline,
                                         PlatformTransactionManager transactionManager,
                                         @Value("${agent.langchain.child-run.outbox-batch-size:32}") int batchSize) {
        this.intentStore = intentStore;
        this.runEventService = runEventService;
        this.ownership = ownership;
        this.waitGroups = waitGroups;
        this.admissionRegistry = admissionRegistry;
        this.runPipeline = runPipeline;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, Math.min(batchSize, 256));
    }

    @Scheduled(fixedDelayString = "${agent.langchain.child-run.outbox-poll-ms:1000}")
    public void deliverDueChildren() {
        for (int index = 0; index < batchSize; index++) {
            Optional<ChildRunOutboxDelivery> claimed;
            try {
                OffsetDateTime now = OffsetDateTime.now();
                String token = UUID.randomUUID().toString();
                DeploymentIdentity identity = ownership.requireIdentity();
                claimed = transactions.execute(ignored -> intentStore.claimDueOutboxForDeployment(
                        owner, token, now, now.plusSeconds(30),
                        identity.deploymentId(), identity.generationId()));
            } catch (RuntimeException failure) {
                log.error("子 Run 创建请求领取失败，保留数据库投递记录等待重试", failure);
                return;
            }
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            ChildRunOutboxDelivery delivery = claimed.get();
            try {
                AgentRun child = transactions.execute(ignored -> acceptAndCreate(delivery));
                if (child != null) {
                    launch(child);
                }
            } catch (RuntimeException failure) {
                // CLAIMED lease expires. The next delivery uses the same intent and child id.
                log.error("子 Run 创建未完成，将按持久投递租期重试: intentId={} childRunId={}",
                        delivery.intentId(), delivery.childRunId(), failure);
            }
        }
    }

    private AgentRun acceptAndCreate(ChildRunOutboxDelivery delivery) {
        AgentRun parent = ownership.findOwnedRun(delivery.parentRunId());
        if (parent == null) {
            throw new IllegalStateException("子 Run 创建时父 Run 不属于当前部署代际");
        }
        if (!intentStore.markAccepted(delivery.outboxId(), delivery.claimToken())) {
            intentStore.cancelUnacceptedIfParentChanged(delivery.intentId());
            return null;
        }
        if (!delivery.parentSchedulerVersion().equals(parent.getSchedulerVersion())
                || !delivery.parentDeploymentId().equals(parent.getDeploymentId())
                || !delivery.parentDeploymentGenerationId().equals(parent.getDeploymentGenerationId())) {
            throw new IllegalStateException("子 Run 创建时父级冻结身份不一致");
        }
        WaitGroup group = waitGroups.findGroup(delivery.parentWaitGroupId())
                .orElseThrow(() -> new IllegalStateException("子 Run 创建时父等待组不存在"));
        WaitMember member = waitGroups.findMemberByIdentity(delivery.parentWaitGroupId(),
                        delivery.parentMemberIdentity())
                .orElseThrow(() -> new IllegalStateException("子 Run 创建时父工具成员不存在"));
        ChildRunAcceptanceControls.MemberIdentity bindingIdentity =
                ChildRunAcceptanceControls.requireMemberIdentity(delivery, group, member);
        return runEventService.createChildRun(parent, delivery.childRunId(), delivery.rootRunId(),
                delivery.goal(), delivery.context(), delivery.childModelName(),
                delivery.childEndpointName(), delivery.childMaxSteps(), bindingIdentity);
    }

    private void launch(AgentRun child) {
        if (ownership.findOwnedRun(child.getId()) == null) {
            log.warn("子 Run 不属于当前部署代际，停止投递: runId={}", child.getId());
            return;
        }
        if (!admissionRegistry.isAdmitted(child.getId())
                && !admissionRegistry.admitNewRun(child.getId(), SchedulerVersion.DUAL_POOL_V2.name())) {
            log.warn("子 Run 已持久受理，当前进程尚未取得执行资格，等待扫描补投: runId={}", child.getId());
            return;
        }
        try {
            runPipeline.enqueue(child, RunCoordinationHint.Reason.NEW_RUN);
        } catch (RuntimeException failure) {
            // The durable child record remains discoverable even if an in-memory hint fails.
            log.warn("子 Run 已受理但执行提示未发出，等待扫描补投: runId={} reason={}",
                    child.getId(), failure.getMessage());
        }
    }

    /** Covers a crash after child creation committed but before in-memory admission or hinting. */
    @Scheduled(fixedDelayString = "${agent.langchain.child-run.reconcile-poll-ms:1000}")
    public synchronized void restoreAcceptedChildren() {
        List<ChildRunIntentView> page;
        try {
            DeploymentIdentity identity = ownership.requireIdentity();
            page = intentStore.listAcceptedChildrenNeedingLaunchForDeployment(
                    launchScanCursor, batchSize, identity.deploymentId(), identity.generationId());
        } catch (RuntimeException failure) {
            log.error("已受理子 Run 补扫失败，保留持久记录重试", failure);
            return;
        }
        if (page.isEmpty()) {
            launchScanCursor = 0L;
            return;
        }
        for (ChildRunIntentView intent : page) {
            launchScanCursor = intent.intentId();
            if (intent.childRunStatus() == null) {
                log.error("子 Run 意图已受理但主记录缺失，停止补投: intentId={} childRunId={}",
                        intent.intentId(), intent.childRunId());
                continue;
            }
            AgentRun parent = ownership.findOwnedRun(intent.parentRunId());
            AgentRun child = ownership.findOwnedRun(intent.childRunId());
            if (parent == null || child == null) {
                log.warn("父或子 Run 不属于当前部署代际，停止补投: intentId={}", intent.intentId());
                continue;
            }
            if (!admissionRegistry.isAdmitted(child.getId())
                    || child.getStatus() == world.willfrog.agent.platform.model.AgentRunStatus.RECEIVED) {
                try {
                    launch(child);
                } catch (RuntimeException failure) {
                    log.warn("子 Run 仍待补投: intentId={} reason={}", intent.intentId(), failure.getMessage());
                }
            }
        }
        if (page.size() < batchSize) {
            launchScanCursor = 0L;
        }
    }
}
