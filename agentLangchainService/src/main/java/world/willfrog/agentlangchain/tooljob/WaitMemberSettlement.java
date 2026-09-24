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
 * <p>拿不出结论的那一种（权威地说这个后台作业不存在）走另一条路：名额停在「准备中」，按「建任务
 * 被否定」还回去，没有用量可记。</p>
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
     * @param statusName 沙箱给的终态名；为 {@code null} 表示「权威地说这个后台作业不存在」
     * @param result   沙箱的终态结果体；上面那种情形为 {@code null}
     * @param preview  交给模型的那份结果正文（写进信封时按上限截断）
     */
    public Outcome settle(WaitMember member,
                          WaitMemberDispatchProof proof,
                          String statusName,
                          TaskResultResponse result,
                          String preview) {
        DataAnalysisReservation stored;
        DataAnalysisEstimate estimate;
        try {
            stored = objectMapper.readValue(proof.reservationJson(), DataAnalysisReservation.class);
            estimate = objectMapper.readValue(proof.estimateJson(), DataAnalysisEstimate.class);
        } catch (Exception e) {
            return Outcome.blocked("reservation_unreadable");
        }
        if (statusName == null) {
            return releaseNotCreated(stored, member);
        }
        if (result == null) {
            return Outcome.blocked("terminal_result_missing");
        }
        return releaseWithUsage(member, stored, estimate, statusName, result, preview);
    }

    /** 权威地说「这个后台作业不存在」：名额停在准备中，按建任务被否定还回去。 */
    private Outcome releaseNotCreated(DataAnalysisReservation stored, WaitMember member) {
        if (stored.state() != DataAnalysisReservationState.PREPARING) {
            // 证明里的名额已经绑在某个任务上，说明这次不是「没建出来」——两种事实对不上，不能猜。
            log.warn("成员按「任务不存在」收尾，但名额凭证不是准备中，先不释放：member={} state={}",
                    member.getMemberIdentity(), stored.state());
            return Outcome.blocked("reservation_state_mismatch:" + stored.state());
        }
        DataAnalysisReleaseOutcome outcome = capacityService.releaseReservation(
                new DataAnalysisReleaseRequest(stored,
                        new DataAnalysisReleaseProof.PreDispatchAbort(stored.identity()),
                        DataAnalysisReleaseReason.CREATE_NOT_STARTED));
        if (outcome == DataAnalysisReleaseOutcome.RELEASED
                || outcome == DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
            log.info("成员的后台作业不存在，名额已还回去：member={} operation={}",
                    member.getMemberIdentity(), stored.operationId());
            return Outcome.success();
        }
        log.warn("成员的后台作业不存在，但名额没有还回去：member={} operation={} outcome={}",
                member.getMemberIdentity(), stored.operationId(), outcome);
        return Outcome.blocked("release_not_created:" + outcome);
    }

    /** 有结论的作业：名额还回去、用量记下来。两步都幂等。 */
    private Outcome releaseWithUsage(WaitMember member,
                                     DataAnalysisReservation stored,
                                     DataAnalysisEstimate estimate,
                                     String statusName,
                                     TaskResultResponse result,
                                     String preview) {
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
                statusName, result, preview);
        if (envelope == null) {
            return Outcome.blocked("envelope_unbuildable");
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
            // 用量落库不能代替容量账本的释放证明。冲突时保留等待责任，避免外部任务虽停了、
            // 根树许可却先还回去，随后又让新任务占进来。
            log.error("成员的名额凭证与容量账本冲突，保留收尾责任：member={} operation={}",
                    member.getMemberIdentity(), confirmed.operationId());
            return Outcome.blocked("capacity_reservation_conflict");
        } else {
            DataAnalysisReleaseOutcome outcome = capacityService.releaseReservation(
                    new DataAnalysisReleaseRequest(confirmed,
                            new DataAnalysisReleaseProof.Terminal(envelope),
                            DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED));
            if (outcome != DataAnalysisReleaseOutcome.RELEASED
                    && outcome != DataAnalysisReleaseOutcome.ALREADY_RELEASED) {
                log.warn("成员的名额没有还回去，这一轮不下结论：member={} operation={} outcome={}",
                        member.getMemberIdentity(), confirmed.operationId(), outcome);
                return Outcome.blocked("release:" + outcome);
            }
        }
        return recordUsage(member, confirmed, envelope);
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
                                                  String preview) {
        try {
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
                    Instant.now(),
                    true);
        } catch (Exception e) {
            log.warn("成员的终态信封组织不出来：member={} reason={}", member.getMemberIdentity(), e.getMessage());
            return null;
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
