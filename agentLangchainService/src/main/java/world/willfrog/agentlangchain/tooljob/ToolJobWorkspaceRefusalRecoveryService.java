package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;

/**
 * 沙箱按持久工作区身份明确拒绝创建、且原操作号确认没有任务后的收口。
 * 数据库拒绝标记是恢复真相源；进程重启不再向沙箱重发该创建请求。
 */
final class ToolJobWorkspaceRefusalRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(
            ToolJobWorkspaceRefusalRecoveryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    enum Outcome { COMPLETE, RETRYABLE, INVALID_EVIDENCE }

    Outcome recover(String runId, ToolJobAnchor anchor,
                    DataAnalysisCapacityService capacityService,
                    ToolJobAnchorService anchorService) {
        if (runId == null || runId.isBlank() || anchor == null
                || !"WORKSPACE_REFUSED".equals(anchor.getAnchorState())
                || !WaitMemberDispatchProof.isWorkspaceRefusalCode(
                        anchor.getWorkspaceRefusalCode())
                || anchor.getOperationId() == null || anchor.getOperationId().isBlank()
                || anchor.getRequestFingerprint() == null
                || anchor.getRequestFingerprint().isBlank()
                || anchor.getTaskId() != null && !anchor.getTaskId().isBlank()
                || capacityService == null || anchorService == null) {
            return Outcome.INVALID_EVIDENCE;
        }
        DataAnalysisReservation reservation;
        try {
            reservation = MAPPER.readValue(
                    anchor.getReservationJson(), DataAnalysisReservation.class);
        } catch (Exception invalidReservation) {
            return Outcome.INVALID_EVIDENCE;
        }
        if (reservation == null || reservation.identity() == null
                || !runId.equals(reservation.identity().runId())
                || !anchor.getOperationId().equals(reservation.operationId())
                || reservation.taskId() != null
                || reservation.state() != DataAnalysisReservationState.PREPARING
                   && reservation.state() != DataAnalysisReservationState.RELEASED) {
            return Outcome.INVALID_EVIDENCE;
        }

        if (reservation.state() == DataAnalysisReservationState.PREPARING) {
            DataAnalysisReleaseRequest releaseRequest = new DataAnalysisReleaseRequest(
                    reservation,
                    new DataAnalysisReleaseProof.WorkspaceRefusal(
                            reservation.identity(), anchor.getWorkspaceRefusalCode()),
                    DataAnalysisReleaseReason.WORKSPACE_CREATE_REFUSED);
            try {
                DataAnalysisRestoreOutcome restored =
                        capacityService.restoreReservation(reservation);
                DataAnalysisReleaseOutcome released =
                        capacityService.releaseReservation(releaseRequest);
                if (restored == DataAnalysisRestoreOutcome.CONFLICT
                        && released != DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
                    return Outcome.RETRYABLE;
                }
                if (released != DataAnalysisReleaseOutcome.RELEASED
                        && released != DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
                    return Outcome.RETRYABLE;
                }
                DataAnalysisReservation durableReleased = new DataAnalysisReservation(
                        reservation.reservationId(), reservation.identity(),
                        reservation.resourceClass(), reservation.capacityUnits(),
                        DataAnalysisReservationState.RELEASED, null,
                        reservation.acquiredAt());
                anchor.setReservationJson(MAPPER.writeValueAsString(durableReleased));
                if (!anchorService.recordWorkspaceRefusalReleased(runId, anchor)) {
                    return Outcome.RETRYABLE;
                }
            } catch (Exception transientFailure) {
                log.warn("工作区拒绝后的名额收口暂不可用：run={} operation={}",
                        runId, anchor.getOperationId(), transientFailure);
                return Outcome.RETRYABLE;
            }
        }
        try {
            return anchorService.completeWorkspaceRefusal(
                    runId, anchor.getOperationId(), anchor.getRequestFingerprint(),
                    anchor.getWorkspaceRefusalCode())
                    ? Outcome.COMPLETE : Outcome.RETRYABLE;
        } catch (Exception transientFailure) {
            log.warn("工作区拒绝后的 Run 收口暂不可用：run={} operation={}",
                    runId, anchor.getOperationId(), transientFailure);
            return Outcome.RETRYABLE;
        }
    }
}
