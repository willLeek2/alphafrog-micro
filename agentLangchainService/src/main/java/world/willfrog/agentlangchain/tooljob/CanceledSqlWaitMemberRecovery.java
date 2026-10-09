package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.CanonicalSandboxCreateSpec;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisObservabilityCall;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.event.AgentRunFinalizationService;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.wait.WaitGroupStore;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.agent.platform.wait.WaitMemberStopStore;
import world.willfrog.agent.platform.wait.WaitMemberStopTask;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;

import java.util.Objects;
import java.util.Set;

/**
 * 取消SQL等待成员独占终态记账；Run锚点只是查询会话锁。
 * 在线对账和启动恢复共用这份判断，不能把成员已经释放的预约再交给Run级收尾器。
 */
@Service
@Slf4j
public class CanceledSqlWaitMemberRecovery {
    public enum Ownership { NOT_APPLICABLE, MEMBER_OWNED, INVALID_PROOF }

    private final AgentRunMapper runs;
    private final WaitGroupStore groups;
    private final WaitMemberStopStore stops;
    private final AgentRunFinalizationService finalization;
    private final ObjectMapper json;

    public CanceledSqlWaitMemberRecovery(AgentRunMapper runs, WaitGroupStore groups,
                                        WaitMemberStopStore stops,
                                        AgentRunFinalizationService finalization, ObjectMapper json) {
        this.runs = runs;
        this.groups = groups;
        this.stops = stops;
        this.finalization = finalization;
        this.json = json;
    }

    public Ownership ownership(String runId, ToolJobAnchor anchor) {
        return inspect(runId, anchor).ownership();
    }

    /**
     * 停止和账目全部已确认后，用一条有条件的写入关闭Run并清原会话锁。
     * 返回false时仍由现有锚点扫描重试，不能绕过成员停止责任。
     */
    public boolean complete(String runId, ToolJobAnchor anchor) {
        Inspection inspected = inspect(runId, anchor);
        if (inspected.ownership() != Ownership.MEMBER_OWNED) return false;
        long afterGroupId = 0;
        while (true) {
            var open = groups.listOpenGroupsByRun(runId, afterGroupId, 100);
            if (open.isEmpty()) break;
            for (var group : open) {
                groups.cancelChain(group.getId());
                afterGroupId = group.getId();
            }
        }
        if (Set.of("PENDING", "RUNNING").contains(inspected.member().getState())) {
            // 控制接口可能已写取消意图而等待链暂未写成；复用原事务补足停止责任。
            groups.cancelChain(inspected.member().getGroupId());
            return false;
        }
        if (inspected.stop() == null) {
            groups.ensureCanceledMemberStopTasks(inspected.member().getGroupId());
            return false;
        }
        if (!"CONFIRMED".equals(inspected.stop().getState())) return false;
        int rows = runs.completeCanceledSqlWaitMember(runId, anchor.getOperationId(),
                anchor.getRequestFingerprint(), inspected.member().getId(),
                inspected.member().getDispatchProofJson(), anchor.getReservationJson(),
                inspected.terminalReservationJson());
        if (rows != 1) return false;
        AgentRun closed = runs.findById(runId);
        // 已落业务终态保持原值；事件监听失败由现有终态数据库补扫接管。
        if (closed != null && closed.getStatus() != null) {
            try {
                finalization.publishFinalizedEvent(runId, closed.getUserId(), closed.getStatus().name());
            } catch (RuntimeException unavailable) {
                log.warn("取消SQL成员资源已收尾，Run终态通知暂不可用：run={}", runId, unavailable);
            }
        }
        return true;
    }

    private Inspection inspect(String runId, ToolJobAnchor anchor) {
        if (anchor == null || !ToolJobAnchor.EXECUTE_QUERY_TOOL.equals(anchor.getToolName())
                || !"CANCELED".equals(anchor.getRunDisposition()) || anchor.isAutoResume()) {
            return Inspection.notApplicable();
        }
        AgentRun run = runs.findById(runId);
        if (run == null || !"DUAL_POOL_V2".equals(run.getSchedulerVersion())) {
            return Inspection.notApplicable();
        }
        ToolJobAnchor current = ToolJobAnchor.fromJson(run.getToolJobAnchorJson());
        boolean alreadyClosed = (current == null || current.getOperationId() == null)
                && Set.of("FAILED", "CANCELED", "COMPLETED", "PARTIAL", "EXPIRED")
                .contains(run.getStatus().name());
        if (!alreadyClosed && (current == null || !Objects.equals(current.getOperationId(), anchor.getOperationId())
                || !Objects.equals(current.getRequestFingerprint(), anchor.getRequestFingerprint())
                || !Objects.equals(current.getReservationJson(), anchor.getReservationJson())
                || !"CANCELED".equals(current.getRunDisposition()) || current.isAutoResume())) {
            return Inspection.invalid();
        }
        WaitMember member = groups.findMemberByOperation(runId, anchor.getOperationId()).orElse(null);
        // 本次V2 SQL必有成员；缺失/矛盾不能退回另一套终态记账。
        if (member == null || !ToolJobAnchor.EXECUTE_QUERY_TOOL.equals(member.getToolName())
                || !Set.of("CANCELED", "LATE", "PENDING", "RUNNING").contains(member.getState())) {
            return Inspection.invalid();
        }
        try {
            WaitMemberDispatchProof proof = WaitMemberDispatchProof.fromJson(json,
                    member.getDispatchProofJson()).orElseThrow();
            if (!WaitMemberDurableRequestResolver.hasValidRequest(proof)
                    || !Objects.equals(runId, member.getRunId())
                    || !Objects.equals(anchor.getOperationId(), member.getExternalOperationId())
                    || !Objects.equals(anchor.getOperationId(), proof.operationId())
                    || !Objects.equals(anchor.getRequestFingerprint(), proof.requestFingerprint())) {
                return Inspection.invalid();
            }
            var group = groups.findGroup(member.getGroupId()).orElse(null);
            if (group == null || !runId.equals(group.getRunId())
                    || !"DUAL_POOL_V2".equals(group.getSchedulerVersion())) return Inspection.invalid();
            String durableCall = DurableToolCallIds.forTool(member.getToolName(), member.getToolCallId(),
                    group.identity().segment());
            CanonicalSandboxCreateSpec spec = json.readValue(proof.canonicalCreateSpecJson(),
                    CanonicalSandboxCreateSpec.class);
            if (!proof.operationId().equals(spec.operationId())
                    || !proof.requestFingerprint().equals(spec.requestFingerprint())) return Inspection.invalid();
            DataAnalysisReservation original = json.readValue(proof.reservationJson(), DataAnalysisReservation.class);
            DataAnalysisReservation session = json.readValue(anchor.getReservationJson(), DataAnalysisReservation.class);
            if (!original.identity().runId().equals(runId)
                    || !original.identity().toolCallId().equals(durableCall)
                    || !proof.operationId().equals(original.operationId())
                    || !original.identity().equals(session.identity())
                    || !original.reservationId().equals(session.reservationId())
                    || original.capacityUnits() != session.capacityUnits()
                    || original.resourceClass() != session.resourceClass()
                    || !original.acquiredAt().equals(session.acquiredAt())
                    || disagrees(proof.taskId(), anchor.getTaskId())
                    || disagrees(original.taskId(), anchor.getTaskId())
                    || disagrees(session.taskId(), anchor.getTaskId())) return Inspection.invalid();
            WaitMemberStopTask stop = stops.findByWaitMemberId(member.getId()).orElse(null);
            if (stop != null && (!Objects.equals(stop.getWaitMemberId(), member.getId())
                    || !Objects.equals(stop.getGroupId(), member.getGroupId())
                    || !runId.equals(stop.getRunId()) || !proof.operationId().equals(stop.getOperationId())
                    || !proof.requestFingerprint().equals(stop.getRequestFingerprint())
                    || disagrees(proof.taskId(), stop.getTaskId())
                    || disagrees(anchor.getTaskId(), stop.getTaskId()))) return Inspection.invalid();
            String terminalReservationJson = null;
            if (alreadyClosed && (stop == null || !"CONFIRMED".equals(stop.getState()))) return Inspection.invalid();
            if (stop != null && "CONFIRMED".equals(stop.getState())) {
                if (stop.getTerminalTaskId() == null || stop.getTerminalTaskId().isBlank()
                        || !Objects.equals(stop.getTaskId(), stop.getTerminalTaskId())
                        || !Set.of("SUCCEEDED", "FAILED", "CANCELED").contains(stop.getTerminalStatus())
                        || disagrees(proof.taskId(), stop.getTerminalTaskId())
                        || disagrees(anchor.getTaskId(), stop.getTerminalTaskId())) return Inspection.invalid();
                boolean matchingUsage = false;
                for (var item : json.readTree(run.getSnapshotJson())
                        .path("data_analysis_observability").path("calls")) {
                    DataAnalysisObservabilityCall call = json.treeToValue(item, DataAnalysisObservabilityCall.class);
                    if (proof.operationId().equals(call.operationId())
                            && stop.getTerminalTaskId().equals(call.taskId())
                            && stop.getTerminalStatus().equals(call.terminalStatus())
                            && call.terminalAt() != null
                            && call.reservation().state() == DataAnalysisReservationState.TERMINAL_CONFIRMED
                            && call.reservation().identity().equals(original.identity())
                            && call.reservation().reservationId().equals(original.reservationId())
                            && call.reservation().capacityUnits() == original.capacityUnits()
                            && call.reservation().resourceClass() == original.resourceClass()
                            && call.reservation().acquiredAt().equals(original.acquiredAt())) {
                        matchingUsage = true;
                        terminalReservationJson = json.writeValueAsString(call.reservation());
                    }
                }
                if (!matchingUsage) return Inspection.invalid();
            }
            return new Inspection(Ownership.MEMBER_OWNED, member, stop, terminalReservationJson);
        } catch (Exception invalid) {
            log.warn("取消SQL会话的成员证明不完整，保留原资源责任：run={}", runId);
            return Inspection.invalid();
        }
    }

    private static boolean disagrees(String first, String second) {
        return first != null && !first.isBlank() && second != null && !second.isBlank()
                && !first.equals(second);
    }

    private record Inspection(Ownership ownership, WaitMember member, WaitMemberStopTask stop,
                              String terminalReservationJson) {
        static Inspection notApplicable() { return new Inspection(Ownership.NOT_APPLICABLE, null, null, null); }
        static Inspection invalid() { return new Inspection(Ownership.INVALID_PROOF, null, null, null); }
    }
}
