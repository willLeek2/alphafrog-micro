package world.willfrog.agentlangchain.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.agent.platform.childrun.ChildRunIntentStore;
import world.willfrog.agent.platform.childrun.ChildRunIntentView;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agentlangchain.control.dualpool.DualPoolRunAdmissionRegistry;
import world.willfrog.agentlangchain.facade.LangchainRunControlService;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;

import java.util.List;
import java.util.Objects;

/** Repairs child terminal facts and propagates parent cancellation after crashes. */
@Component
@Slf4j
public class PersistentChildRunLifecycleReconciler {
    private static final int PAGE_SIZE = 100;

    private final ChildRunIntentStore intents;
    private final AgentRunMapper runs;
    private final LangchainRunControlService controls;
    private final DualPoolRunAdmissionRegistry admissions;
    private final TransactionTemplate transactions;

    public PersistentChildRunLifecycleReconciler(ChildRunIntentStore intents,
                                                 AgentRunMapper runs,
                                                 LangchainRunControlService controls,
                                                 DualPoolRunAdmissionRegistry admissions,
                                                 PlatformTransactionManager transactionManager) {
        this.intents = intents;
        this.runs = runs;
        this.controls = controls;
        this.admissions = admissions;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${agent.langchain.child-run.lifecycle-poll-ms:2000}")
    public void reconcile() {
        String afterRoot = null;
        for (int rootPage = 0; rootPage < 10; rootPage++) {
            List<String> roots;
            try {
                roots = intents.listReservedRootRunIds(afterRoot, PAGE_SIZE);
            } catch (RuntimeException failure) {
                log.error("子 Run 根树收尾扫描失败，保留持久容量待重试", failure);
                return;
            }
            if (roots.isEmpty()) {
                return;
            }
            for (String root : roots) {
                reconcileRoot(root);
                afterRoot = root;
            }
            if (roots.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    private void reconcileRoot(String root) {
        long afterIntent = 0L;
        for (int page = 0; page < 10; page++) {
            List<ChildRunIntentView> children;
            try {
                children = intents.listUnsettledByRoot(root, afterIntent, PAGE_SIZE);
            } catch (RuntimeException failure) {
                log.error("根树中的子 Run 收尾扫描失败: rootRunId={}", root, failure);
                return;
            }
            if (children.isEmpty()) {
                break;
            }
            for (ChildRunIntentView child : children) {
                afterIntent = child.intentId();
                try {
                    reconcileChild(child);
                } catch (RuntimeException failure) {
                    log.error("子 Run 收尾仍待重试: intentId={} childRunId={}",
                            child.intentId(), child.childRunId(), failure);
                }
            }
            if (children.size() < PAGE_SIZE) {
                break;
            }
        }
        admissions.reconcileRootPermit(root);
    }

    private void reconcileChild(ChildRunIntentView intent) {
        AgentRun parent = runs.findById(intent.parentRunId());
        if (parent == null) {
            throw new IllegalStateException("parent Run missing for child intent");
        }
        boolean parentStopped = terminal(parent.getStatus())
                || parent.getStatus() == AgentRunStatus.CANCELING
                || !Objects.equals(parent.getRunControlVersion(), intent.parentControlVersion())
                || !Objects.equals(parent.getPlanGeneration(), intent.planGeneration());
        if (intent.acceptedAt() == null) {
            if (parentStopped) {
                transactions.execute(ignored -> intents.cancelUnacceptedIfParentChanged(intent.intentId()));
            }
            return;
        }
        AgentRun child = runs.findById(intent.childRunId());
        if (child == null) {
            throw new IllegalStateException("accepted child intent has no Run record");
        }
        if (terminal(child.getStatus())) {
            transactions.execute(ignored -> {
                intents.markChildTerminal(child.getId());
                intents.markPhysicalStopped(child.getId());
                return null;
            });
            return;
        }
        if (parentStopped) {
            transactions.execute(ignored -> intents.requestCancellation(child.getId()));
            controls.cancelRun(CancelAgentRunRequest.newBuilder()
                    .setId(child.getId()).setUserId(child.getUserId()).build());
        }
    }

    private static boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.PARTIAL
                || status == AgentRunStatus.FAILED || status == AgentRunStatus.CANCELED
                || status == AgentRunStatus.EXPIRED;
    }
}
