package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.dataanalysis.ToolJobRunDisposition;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskByOperationIdResponse;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;

/**
 * 恢复 PREPARING dispatch 的共享解析器。
 *
 * <p>调用方必须区分远端暂不可决与 durable 证据损坏。前者可以按同一 operationId
 * 在线重试；后者不能继续查询或重放，以免把错误任务附着到 Run。</p>
 */
final class ToolJobPreparingDispatchResolver {

    private static final Logger log =
            LoggerFactory.getLogger(ToolJobPreparingDispatchResolver.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();

    private ToolJobPreparingDispatchResolver() {
    }

    enum Outcome {
        RESOLVED,
        REMOTE_UNAVAILABLE,
        INVALID_EVIDENCE,
        DURABLE_WRITE_UNCERTAIN,
        OWNERSHIP_LOST
    }

    record Resolution(Outcome outcome, DataAnalysisReservation reservation) {

        static Resolution resolved(DataAnalysisReservation reservation) {
            return new Resolution(Outcome.RESOLVED, reservation);
        }

        static Resolution remoteUnavailable() {
            return new Resolution(Outcome.REMOTE_UNAVAILABLE, null);
        }

        static Resolution invalidEvidence() {
            return new Resolution(Outcome.INVALID_EVIDENCE, null);
        }

        static Resolution durableWriteUncertain() {
            return new Resolution(Outcome.DURABLE_WRITE_UNCERTAIN, null);
        }

        static Resolution ownershipLost() {
            return new Resolution(Outcome.OWNERSHIP_LOST, null);
        }
    }

    static Resolution resolve(
            String runId,
            ToolJobAnchor anchor,
            DataAnalysisReservation preparing,
            PythonSandboxService sandboxService,
            ToolJobAnchorService anchorService) {
        if (!hasValidDurableIdentity(anchor, preparing)) {
            return Resolution.invalidEvidence();
        }

        GetTaskByOperationIdResponse lookup;
        try {
            lookup = sandboxService.getTaskByOperationId(
                    GetTaskByOperationIdRequest.newBuilder()
                            .setOperationId(anchor.getOperationId())
                            .build());
        } catch (Exception remoteFailure) {
            log.warn("PREPARING lookup temporarily unavailable for run={}, operationId={}",
                    runId, anchor.getOperationId(), remoteFailure);
            return Resolution.remoteUnavailable();
        }
        if (lookup == null || !lookup.getError().isBlank()) {
            return Resolution.remoteUnavailable();
        }

        String taskId;
        String fingerprint;
        if (lookup.getFound()) {
            taskId = lookup.getTaskId();
            fingerprint = lookup.getRequestFingerprint();
            if (!hasMatchingTaskIdentity(anchor, taskId, fingerprint)) {
                return Resolution.invalidEvidence();
            }
        } else {
            ExecuteRequest request = parseDurableCreateRequest(anchor);
            if (request == null) {
                return Resolution.invalidEvidence();
            }
            ExecuteResponse created;
            try {
                created = sandboxService.createTask(request);
            } catch (Exception remoteFailure) {
                log.warn("PREPARING replay temporarily unavailable for run={}, operationId={}",
                        runId, anchor.getOperationId(), remoteFailure);
                return Resolution.remoteUnavailable();
            }
            /*
             * create 已到达 Sandbox 但响应丢失、报错或身份不完整时，下一轮必须先按
             * operationId 再查，不能把这种不确定结果当作 durable 身份损坏。
             * taskId/fingerprint 都非空却与 durable fingerprint 明确矛盾则不同：
             * 这是可判定的错误证据，必须隔离，不能无限重放同一矛盾响应。
             */
            if (created == null
                    || !created.getError().isBlank()
                    || created.getTaskId().isBlank()
                    || created.getRequestFingerprint().isBlank()) {
                return Resolution.remoteUnavailable();
            }
            if (!anchor.getRequestFingerprint().equals(created.getRequestFingerprint())) {
                return Resolution.invalidEvidence();
            }
            taskId = created.getTaskId();
            fingerprint = created.getRequestFingerprint();
        }

        DataAnalysisReservation attached = new DataAnalysisReservation(
                preparing.reservationId(),
                preparing.identity(),
                preparing.resourceClass(),
                preparing.capacityUnits(),
                DataAnalysisReservationState.TASK_ATTACHED,
                taskId,
                preparing.acquiredAt());
        String attachedJson;
        try {
            attachedJson = MAPPER.writeValueAsString(attached);
        } catch (Exception invalidReservation) {
            log.error("PREPARING reservation cannot be serialized for run={}", runId,
                    invalidReservation);
            return Resolution.invalidEvidence();
        }

        String previousTaskId = anchor.getTaskId();
        String previousAnchorState = anchor.getAnchorState();
        String previousReservationJson = anchor.getReservationJson();
        anchor.setTaskId(taskId);
        anchor.setAnchorState("ATTACHED");
        anchor.setReservationJson(attachedJson);
        try {
            boolean persisted;
            if (ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())) {
                persisted = anchorService.updateDagCleanupPreparing(
                        runId,
                        anchor,
                        anchor.getOperationId(),
                        anchor.getBlockingOwnerId(),
                        anchor.getRequestFingerprint());
            } else {
                persisted = anchorService.updateActive(
                        runId,
                        anchor,
                        AgentRunStatus.EXECUTING,
                        anchor.getOperationId());
            }
            if (!persisted) {
                restorePreparingAnchor(
                        anchor, previousTaskId, previousAnchorState, previousReservationJson);
                return Resolution.ownershipLost();
            }
            return Resolution.resolved(attached);
        } catch (Exception persistenceFailure) {
            restorePreparingAnchor(
                    anchor, previousTaskId, previousAnchorState, previousReservationJson);
            log.warn("PREPARING attachment persistence temporarily unavailable for run={}", runId,
                    persistenceFailure);
            /*
             * SQL 可能已提交但响应丢失。调用方只能补 runId due 并重读 PG，
             * 不能再把本地 PREPARING 快照写回覆盖可能已提交的 ATTACHED。
             */
            return Resolution.durableWriteUncertain();
        }
    }

    private static boolean hasValidDurableIdentity(
            ToolJobAnchor anchor,
            DataAnalysisReservation preparing) {
        if (anchor == null
                || preparing == null
                || preparing.state() != DataAnalysisReservationState.PREPARING
                || preparing.identity() == null
                || anchor.getOperationId() == null
                || anchor.getOperationId().isBlank()
                || !anchor.getOperationId().equals(preparing.identity().operationId())
                || anchor.getRequestFingerprint() == null
                || anchor.getRequestFingerprint().isBlank()) {
            return false;
        }
        return !ToolJobRunDisposition.isDagCleanupOnly(anchor.getRunDisposition())
                || (anchor.getBlockingOwnerId() != null
                && !anchor.getBlockingOwnerId().isBlank());
    }

    private static ExecuteRequest parseDurableCreateRequest(ToolJobAnchor anchor) {
        if (anchor.getCreateRequestJson() == null || anchor.getCreateRequestJson().isBlank()) {
            return null;
        }
        try {
            ExecuteRequest.Builder builder = ExecuteRequest.newBuilder();
            JsonFormat.parser().merge(anchor.getCreateRequestJson(), builder);
            ExecuteRequest request = builder.build();
            if (!anchor.getOperationId().equals(request.getOperationId())
                    || !anchor.getRequestFingerprint().equals(request.getRequestFingerprint())) {
                return null;
            }
            return request;
        } catch (Exception invalidJson) {
            return null;
        }
    }

    private static boolean hasMatchingTaskIdentity(
            ToolJobAnchor anchor,
            String taskId,
            String fingerprint) {
        return taskId != null
                && !taskId.isBlank()
                && fingerprint != null
                && !fingerprint.isBlank()
                && anchor.getRequestFingerprint().equals(fingerprint);
    }

    private static void restorePreparingAnchor(
            ToolJobAnchor anchor,
            String taskId,
            String anchorState,
            String reservationJson) {
        anchor.setTaskId(taskId);
        anchor.setAnchorState(anchorState);
        anchor.setReservationJson(reservationJson);
    }
}
