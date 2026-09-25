package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceUsage;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 等待成员的收尾：把一条已经拿到结论的后台作业，从「名额还占着」走到「名额还回去、用量记下来」。
 *
 * <p>后台作业执行期间，它占的那份名额一直挂在账上；结果接回来之后必须还回去，否则跑过的作业越多、
 * 账上越满。用量是另一样东西：这次调用实际用了多少资源要落成一条记录，供观测与对账看。两件事都靠
 * 派发证明里那份名额凭证（含任务号与预估）与结果体，所以接结果的那一路把证明带到收尾这里。</p>
 *
 * <p>顺序照搬同步执行那条已经验过的路：把凭证规范成「终态已确认」→ 组织一份终态信封（含预估值、
 * 名额凭证、实际用量、结果摘要）→ 用这份信封当凭证把名额还回去 → 把用量记录幂等写进去。按这个
 * 顺序做，进程在任意一步之间退出，下一轮拿同一份证明重做一遍就行：还名额是幂等的（已经还过的返回
 * 「早就还了」），用量记录也是幂等的（同样的内容返回「早就在了」）。</p>
 *
 * <p>还名额与写用量都不成功时不吞掉：返回没成的原因，由调用方把这条成员按退避推后、下一轮重来。
 * 成员行上的终态因此只在账目落定之后才写出去——先写成员终态再收尾的话，进程在两步之间退出就再也
 * 没人回来收尾了（那条成员已经不在「执行中」的扫描口径里）。</p>
 */
@Service
@Slf4j
public class WaitMemberSettlement {

    /** 结果摘要写进信封时的上限：信封自己要求不超过 16 KB。 */
    private static final int MAX_PREVIEW_BYTES = DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES;

    private final DataAnalysisCapacityService capacityService;
    private final DataAnalysisTerminalRecorder terminalRecorder;
    private final ObjectMapper objectMapper;

    public WaitMemberSettlement(DataAnalysisCapacityService capacityService,
                                DataAnalysisTerminalRecorder terminalRecorder,
                                ObjectMapper objectMapper) {
        this.capacityService = capacityService;
        this.terminalRecorder = terminalRecorder;
        this.objectMapper = objectMapper;
    }

    /** 一次收尾的结果：成了没有；没成时给一句能查的原因。 */
    public record Outcome(boolean ok, String reason) {

        static Outcome success() {
            return new Outcome(true, null);
        }

        static Outcome blocked(String reason) {
            return new Outcome(false, reason);
        }
    }

    /**
     * 收尾一条已经拿到结论的成员。
     *
     * @param member   成员行（取工具名、调用身份等身份字段）
     * @param proof    派发证明（取名额凭证与预估）
     * @param statusName 沙箱给的终态名
     * @param result   沙箱的终态结果体
     * @param preview  交给模型的那份结果正文（写进信封时按上限截断）
     * @param finishedAt Sandbox 为该任务保存的终态时刻；重试时必须保持相同
     */
    public Outcome settle(WaitMember member,
                          WaitMemberDispatchProof proof,
                          String statusName,
                          TaskResultResponse result,
                          String preview,
                          String finishedAt) {
        DataAnalysisReservation stored;
        DataAnalysisEstimate estimate;
        try {
            stored = objectMapper.readValue(proof.reservationJson(), DataAnalysisReservation.class);
            estimate = objectMapper.readValue(proof.estimateJson(), DataAnalysisEstimate.class);
        } catch (Exception e) {
            return Outcome.blocked("reservation_unreadable");
        }
        if (!stored.operationId().equals(proof.operationId())
                || !stored.identity().runId().equals(member.getRunId())
                || !stored.identity().toolCallId().equals(member.getToolCallId())
                || !proof.operationId().equals(member.getExternalOperationId())) {
            return Outcome.blocked("reservation_identity_mismatch");
        }
        if (statusName == null || result == null) {
            return Outcome.blocked("terminal_result_missing");
        }
        return releaseWithUsage(member, stored, estimate, statusName, result, preview, finishedAt);
    }

    /** 有结论的作业：名额还回去、用量记下来。两步都幂等。 */
    private Outcome releaseWithUsage(WaitMember member,
                                     DataAnalysisReservation stored,
                                     DataAnalysisEstimate estimate,
                                     String statusName,
                                     TaskResultResponse result,
                                     String preview,
                                     String finishedAt) {
        String taskId = result.getTaskId();
        if (taskId == null || taskId.isBlank()) {
            return Outcome.blocked("terminal_without_task_id");
        }
        DataAnalysisReservation attached = stored.state() == DataAnalysisReservationState.PREPARING
                ? transition(stored, DataAnalysisReservationState.TASK_ATTACHED, taskId)
                : stored;
        DataAnalysisReservation confirmed = transition(attached,
                DataAnalysisReservationState.TERMINAL_CONFIRMED, taskId);
        // 信封只组织一次、只用这一份：它既是还名额的凭证，也是写用量的那一份。
        // 信封要求名额处在「终态已确认」——还完名额之后再拿「已释放」去组织会被它自己拒掉，
        // 所以两份用途共用同一个对象，顺序上先当凭证、后当记录。
        DataAnalysisTerminalEnvelope envelope = envelope(member, confirmed, estimate,
                statusName, result, preview, finishedAt);
        if (envelope == null) {
            return Outcome.blocked("envelope_unbuildable");
        }
        DataAnalysisReleaseRequest releaseRequest = new DataAnalysisReleaseRequest(confirmed,
                new DataAnalysisReleaseProof.Terminal(envelope),
                DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
        if (stored.state() == DataAnalysisReservationState.PREPARING) {
            // 创建前请求证明只含 PREPARING。Sandbox 后来确认了任务（包括取消墓碑）时，
            // 容量账本仍须按真实状态机先绑 taskId，再确认终态。
            DataAnalysisRestoreOutcome attachedOutcome;
            try {
                attachedOutcome = capacityService.restoreReservation(attached);
            } catch (RuntimeException e) {
                log.warn("成员任务附着凭证无法恢复：member={} operation={} reason={}",
                        member.getMemberIdentity(), confirmed.operationId(), e.getMessage());
                return Outcome.blocked("restore_attached_error");
            }
            if (attachedOutcome == DataAnalysisRestoreOutcome.CONFLICT) {
                return releasedEarlierOrBlocked(member, confirmed, envelope, releaseRequest);
            }
        }
        DataAnalysisRestoreOutcome restored;
        try {
            restored = capacityService.restoreReservation(confirmed);
        } catch (RuntimeException e) {
            log.warn("成员的名额凭证没法交给容量账本，这一轮不下结论：member={} operation={} reason={}",
                    member.getMemberIdentity(), confirmed.operationId(), e.getMessage());
            return Outcome.blocked("restore_error");
        }
        if (restored == DataAnalysisRestoreOutcome.CONFLICT) {
            return releasedEarlierOrBlocked(member, confirmed, envelope, releaseRequest);
        } else {
            DataAnalysisReleaseOutcome outcome = capacityService.releaseReservation(releaseRequest);
            if (outcome != DataAnalysisReleaseOutcome.RELEASED
                    && outcome != DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
                log.warn("成员的名额没有还回去，这一轮不下结论：member={} operation={} outcome={}",
                        member.getMemberIdentity(), confirmed.operationId(), outcome);
                return Outcome.blocked("release:" + outcome);
            }
        }
        return recordUsage(member, confirmed, envelope);
    }

    /** 用量落库失败后的重试可能看见已释放账本；只凭同一终态释放证明认作完成。 */
    private Outcome releasedEarlierOrBlocked(WaitMember member,
                                             DataAnalysisReservation confirmed,
                                             DataAnalysisTerminalEnvelope envelope,
                                             DataAnalysisReleaseRequest request) {
        DataAnalysisReleaseOutcome released = capacityService.releaseReservation(request);
        if (released == DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
            return recordUsage(member, confirmed, envelope);
        }
        log.error("成员名额凭证与容量账本冲突且无同任务的已释放凭证：member={} operation={} outcome={}",
                member.getMemberIdentity(), confirmed.operationId(), released);
        return Outcome.blocked("capacity_reservation_conflict");
    }

    /** 用量记录：按操作身份幂等写入，同样的内容重复写返回「早就在了」。 */
    private Outcome recordUsage(WaitMember member,
                                DataAnalysisReservation reservation,
                                DataAnalysisTerminalEnvelope envelope) {
        try {
            DataAnalysisUpsertOutcome outcome = terminalRecorder.upsert(envelope);
            if (outcome == DataAnalysisUpsertOutcome.INSERTED
                    || outcome == DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME) {
                return Outcome.success();
            }
            log.warn("成员的用量记录没有写进去，这一轮不下结论：member={} operation={} outcome={}",
                    member.getMemberIdentity(), reservation.operationId(), outcome);
            return Outcome.blocked("usage:" + outcome);
        } catch (RuntimeException e) {
            log.warn("成员的用量记录写入出错，这一轮不下结论：member={} operation={} reason={}",
                    member.getMemberIdentity(), reservation.operationId(), e.getMessage());
            return Outcome.blocked("usage_error");
        }
    }

    /**
     * 组织终态信封：预估值、名额凭证、实际用量、结果摘要一起构成这次调用的账。
     *
     * <p>摘要按信封自己的上限截断：信封要的是「能看个大概」的预览，整份正文在成员行的结果里。</p>
     */
    private DataAnalysisTerminalEnvelope envelope(WaitMember member,
                                                  DataAnalysisReservation reservation,
                                                  DataAnalysisEstimate estimate,
                                                  String statusName,
                                                  TaskResultResponse result,
                                                  String preview,
                                                  String finishedAt) {
        try {
            Instant terminalAt = parseSandboxFinishedAt(finishedAt);
            DataAnalysisResourceUsage usage = usageOf(reservation, result);
            boolean success = "SUCCEEDED".equals(statusName) && result.getExitCode() == 0;
            String rawRef = blankToNull(result.getDatasetDir());
            String errorCode = blankToNull(result.getError());
            if (!success && errorCode == null) {
                errorCode = statusName;
            }
            String trimmedPreview = trimPreview(preview);
            if (success && trimmedPreview == null && rawRef == null) {
                trimmedPreview = "(preview unavailable)";
            }
            return new DataAnalysisTerminalEnvelope(
                    member.getRunId(),
                    reservation.identity().toolCallId(),
                    reservation.identity().attempt(),
                    reservation.operationId(),
                    reservation.taskId(),
                    statusName,
                    success,
                    trimmedPreview,
                    rawRef,
                    errorCode,
                    success ? null : "sandbox " + statusName,
                    result.getRetryable(),
                    estimate,
                    reservation,
                    usage,
                    terminalAt,
                    true);
        } catch (Exception e) {
            log.warn("成员的终态信封组织不出来：member={} reason={}", member.getMemberIdentity(), e.getMessage());
            return null;
        }
    }

    private static Instant parseSandboxFinishedAt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sandbox 终态缺少完成时刻");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException withOffset) {
            // Python Sandbox 使用 UTC datetime.utcnow()，旧 HTTP 生产者可能不给时区后缀。
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
    }

    private DataAnalysisResourceUsage usageOf(DataAnalysisReservation reservation,
                                              TaskResultResponse result) throws Exception {
        if (!result.hasResourceUsage()) {
            return DataAnalysisResourceUsage.missing(reservation.resourceClass());
        }
        return ToolJobResourceUsageParser.parse(objectMapper, reservation.resourceClass(),
                JsonFormat.printer().omittingInsignificantWhitespace()
                        .print(result.getResourceUsage()));
    }

    private static DataAnalysisReservation transition(DataAnalysisReservation current,
                                                      DataAnalysisReservationState state,
                                                      String taskId) {
        return new DataAnalysisReservation(current.reservationId(), current.identity(),
                current.resourceClass(), current.capacityUnits(), state, taskId, current.acquiredAt());
    }

    /** 摘要按信封上限（16 KB）截断：按字节算，宁可少留一段，也不能让信封校验不过。 */
    private static String trimPreview(String preview) {
        if (preview == null) {
            return null;
        }
        byte[] bytes = preview.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PREVIEW_BYTES) {
            return preview;
        }
        String suffix = "…(截断)";
        int budget = MAX_PREVIEW_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
        int end = budget;
        while (end > 0 && new String(bytes, 0, end, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8).length > budget) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8) + suffix;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
