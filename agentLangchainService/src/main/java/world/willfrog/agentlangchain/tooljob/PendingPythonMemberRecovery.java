package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.wait.WaitGroup;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.workitem.NodeWorkItem;
import world.willfrog.agent.platform.workitem.NodeWorkItemStore;
import world.willfrog.agent.platform.workitem.NodeWorkerProcessProof;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelOutcome;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.CancelTaskResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.OperationCancelTarget;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

import java.util.function.Predicate;

/**
 * 旧派发调用栈退出后，把创建前已落库、但还没记下 createTask 结果的成员接回。
 *
 * <p>完整创建参数不能从成员证明重放。因此用固定取消身份在 Sandbox 建立同一 operationId 的
 * 持久墓碑：它会拦住旧 createTask 的迟到请求，再由普通结果接收器按 operationId 收到取消终态。
 * 只有原执行者的持久领取身份属于当前容器，且该代 ACTIVE_NODE 额度已经归还时才发取消请求。
 * 额度的归还只发生在 Java 工具调用栈退出后，或旧进程的退出被证实后；RPC 结果不确定时保留
 * PENDING 和容量，下一轮使用相同 cancelRequestId 重试。</p>
 */
@Component
@Slf4j
public class PendingPythonMemberRecovery {
    private final WaitGroupStore groups;
    private final NodeWorkItemStore workItems;
    private final PythonSandboxService sandbox;
    private final ObjectMapper objectMapper;
    private final RunOwnershipGateway ownership;
    private final Predicate<String> recognizedClaimant;
    private long scanCursor;

    @Autowired
    public PendingPythonMemberRecovery(WaitGroupStore groups, NodeWorkItemStore workItems,
                                       PythonSandboxService sandbox, ObjectMapper objectMapper,
                                       RunOwnershipGateway ownership) {
        this(groups, workItems, sandbox, objectMapper, ownership,
                NodeWorkerProcessProof::recognizesClaimant);
    }

    PendingPythonMemberRecovery(WaitGroupStore groups, NodeWorkItemStore workItems,
                                PythonSandboxService sandbox, ObjectMapper objectMapper,
                                RunOwnershipGateway ownership, Predicate<String> recognizedClaimant) {
        this.groups = groups;
        this.workItems = workItems;
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
        this.ownership = ownership;
        this.recognizedClaimant = recognizedClaimant;
    }

    @Scheduled(initialDelayString = "${agent.langchain.wait-member.pending-recovery-initial-ms:10000}",
            fixedDelayString = "${agent.langchain.wait-member.pending-recovery-interval-ms:30000}")
    public void reconcile() {
        var identity = ownership.requireIdentity();
        var candidates = groups.scanPendingPythonMembersWithProof(
                identity.deploymentId(), identity.generationId(), scanCursor, 100);
        if (candidates.isEmpty()) {
            scanCursor = 0;
            return;
        }
        for (WaitMember member : candidates) {
            scanCursor = member.getId();
            try {
                recover(member);
            } catch (RuntimeException e) {
                log.warn("待派发 Python 成员暂不能恢复：member={} reason={}", member.getId(), e.getMessage());
            }
        }
    }

    private void recover(WaitMember member) {
        WaitMemberDispatchProof proof = WaitMemberDispatchProof.fromJson(
                objectMapper, member.getDispatchProofJson()).orElse(null);
        if (proof == null || proof.taskConfirmed() || member.getId() == null
                || !proof.operationId().equals(member.getExternalOperationId())) {
            log.error("待派发 Python 成员的持久请求身份不完整：member={}", member.getId());
            return;
        }
        try {
            DataAnalysisReservation reservation = objectMapper.readValue(
                    proof.reservationJson(), DataAnalysisReservation.class);
            objectMapper.readValue(proof.estimateJson(), DataAnalysisEstimate.class);
            if (reservation.state() != DataAnalysisReservationState.PREPARING
                    || reservation.taskId() != null
                    || !reservation.operationId().equals(proof.operationId())
                    || !reservation.identity().runId().equals(member.getRunId())
                    || !reservation.identity().toolCallId().equals(member.getToolCallId())) {
                log.error("待派发 Python 成员容量凭证身份不一致：member={}", member.getId());
                return;
            }
        } catch (Exception e) {
            log.error("待派发 Python 成员容量凭证不可读取：member={}", member.getId());
            return;
        }
        WaitGroup group = groups.findGroup(member.getGroupId()).orElse(null);
        if (group == null || !group.getRunId().equals(member.getRunId())) return;
        NodeWorkItem item = workItems.findByIdentity(group.identity().segment()).orElse(null);
        if (item == null || item.getId() == null || item.getClaimEpoch() == null
                || item.getClaimedBy() == null
                || !recognizedClaimant.test(item.getClaimedBy())) return;
        if (!groups.safeToRecoverPendingPython(member.getId(), item.getId(),
                item.getClaimEpoch(), item.getClaimedBy(), proof.operationId(),
                proof.requestFingerprint())) return;

        // 同一个成员 ID 只对应这一次请求；崩溃与多个恢复器并发都使用相同取消身份。
        CancelTaskResponse cancel = sandbox.cancelTask(CancelTaskRequest.newBuilder()
                .setByOperation(OperationCancelTarget.newBuilder()
                        .setOperationId(proof.operationId())
                        .setRequestFingerprint(proof.requestFingerprint()))
                .setCancelRequestId("wait-member-" + member.getId())
                .setReason("DISPATCH_WORKER_EXITED")
                .build());
        if (cancel == null || cancel.hasErrorDetail() || !cancel.getError().isBlank()
                || cancel.getOutcome() == CancelOutcome.NOT_FOUND
                || cancel.getOutcome() == CancelOutcome.CANCEL_OUTCOME_UNSPECIFIED
                || cancel.getTaskId().isBlank()) return;

        GetTaskByOperationIdResponse lookup = sandbox.getTaskByOperationId(
                GetTaskByOperationIdRequest.newBuilder().setOperationId(proof.operationId()).build());
        if (lookup == null || lookup.hasErrorDetail() || !lookup.getError().isBlank()
                || !lookup.getFound() || !cancel.getTaskId().equals(lookup.getTaskId())
                || !proof.requestFingerprint().equals(lookup.getRequestFingerprint())) return;

        if (groups.recoverPendingPythonMember(member.getId(), item.getId(),
                item.getClaimEpoch(), item.getClaimedBy(), proof.operationId(),
                proof.requestFingerprint())) {
            log.info("已把旧进程遗留的待派发 Python 成员交给结果接收器：member={} task={}",
                    member.getId(), lookup.getTaskId());
        }
    }
}
