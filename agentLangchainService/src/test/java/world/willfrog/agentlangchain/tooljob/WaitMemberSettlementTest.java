package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisEstimate;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisOperationIdentity;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseProof;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseReason;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReleaseRequest;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservation;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisReservationState;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisResourceClass;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisRestoreOutcome;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalEnvelope;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisTerminalRecorder;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisUpsertOutcome;
import world.willfrog.agent.platform.wait.WaitMember;
import world.willfrog.agent.platform.wait.WaitMemberDispatchProof;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 等待成员的收尾：名额还回去、用量记下来。
 *
 * <p>这里量四件事：正常终态走「终态已确认 → 还名额 → 写用量」，还名额时带的是那份终态信封；
 * 账本说已经还过（或者挂着别的名额）时不重复还、但用量照记；任何一步没成都不下结论（返回原因、
 * 由调用方推后重来）；「后台作业不存在」那一条走建任务被否定那条路、没有用量可记。</p>
 */
class WaitMemberSettlementTest {

    private static final String RUN_ID = "run-settlement";
    private static final String TOOL_CALL_ID = "call-1";
    private static final int ATTEMPT = 1;
    private static final String TASK_ID = "task-9";

    /** 与生产同一套模块：凭证里带时刻，读它要认 Java 时间类型。 */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private DataAnalysisCapacityService capacityService;
    private DataAnalysisTerminalRecorder terminalRecorder;
    private WaitMemberSettlement settlement;
    private WaitMember member;
    private DataAnalysisReservation reservation;

    @BeforeEach
    void setUp() {
        capacityService = Mockito.mock(DataAnalysisCapacityService.class);
        terminalRecorder = Mockito.mock(DataAnalysisTerminalRecorder.class);
        settlement = new WaitMemberSettlement(capacityService, terminalRecorder, objectMapper);
        member = new WaitMember();
        member.setId(41L);
        member.setRunId(RUN_ID);
        member.setGroupId(7L);
        member.setMemberIdentity("node-1:s1:call-1");
        member.setToolName("executePython");
        member.setToolCallId(TOOL_CALL_ID);
        member.setExternalOperationId(operationId());
        reservation = new DataAnalysisReservation(operationId(),
                new DataAnalysisOperationIdentity(RUN_ID, TOOL_CALL_ID, ATTEMPT),
                DataAnalysisResourceClass.STANDARD, 2,
                DataAnalysisReservationState.TASK_ATTACHED, TASK_ID, Instant.now());
    }

    /** 正常终态：还名额带的是终态信封，用量按同一份信封幂等写进去。 */
    @Test
    void aTerminalResultReleasesTheCapacityAndRecordsUsage() throws Exception {
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(terminalRecorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);

        WaitMemberSettlement.Outcome outcome = settlement.settle(member, proof(reservation),
                "SUCCEEDED", result("SUCCEEDED", 0, "done", null), "{\"stdout\":\"done\"}");

        assertThat(outcome.ok()).isTrue();

        ArgumentCaptor<DataAnalysisReleaseRequest> release =
                ArgumentCaptor.forClass(DataAnalysisReleaseRequest.class);
        verify(capacityService).releaseReservation(release.capture());
        assertThat(release.getValue().reason())
                .isEqualTo(DataAnalysisReleaseReason.SANDBOX_TERMINAL_CONFIRMED);
        DataAnalysisReleaseProof proof = release.getValue().proof();
        assertThat(proof).isInstanceOf(DataAnalysisReleaseProof.Terminal.class);
        DataAnalysisTerminalEnvelope envelope = ((DataAnalysisReleaseProof.Terminal) proof).envelope();
        assertThat(envelope.reservation().state())
                .as("还名额的凭证必须处在终态已确认")
                .isEqualTo(DataAnalysisReservationState.TERMINAL_CONFIRMED);
        assertThat(envelope.taskId()).isEqualTo(TASK_ID);
        assertThat(envelope.operationId()).isEqualTo(operationId());
        assertThat(envelope.success()).isTrue();
        assertThat(envelope.background()).as("这是后台作业的结果接回").isTrue();

        ArgumentCaptor<DataAnalysisTerminalEnvelope> recorded =
                ArgumentCaptor.forClass(DataAnalysisTerminalEnvelope.class);
        verify(terminalRecorder).upsert(recorded.capture());
        assertThat(recorded.getValue().operationId()).isEqualTo(operationId());
    }

    /** 账本说这次操作挂着另一份名额（或者早就还过）：不重复还，用量照记。 */
    @Test
    void aLedgerConflictSkipsOnlyTheRelease() {
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.CONFLICT);
        when(terminalRecorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.ALREADY_PRESENT_SAME);

        assertThat(settlement.settle(member, proof(reservation), "SUCCEEDED",
                result("SUCCEEDED", 0, "done", null), "{\"stdout\":\"done\"}").ok()).isTrue();

        verify(capacityService, never()).releaseReservation(any());
        verify(terminalRecorder).upsert(any());
    }

    /** 名额没还回去：不下结论，等下一轮重来。 */
    @Test
    void aFailedReleaseBlocksTheSettlement() {
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.NOT_FOUND);

        WaitMemberSettlement.Outcome outcome = settlement.settle(member, proof(reservation),
                "SUCCEEDED", result("SUCCEEDED", 0, "done", null), "out");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).startsWith("release:");
        verify(terminalRecorder, never()).upsert(any());
    }

    /** 账本把这份凭证拒了（容量恢复中之类的）：不下结论。 */
    @Test
    void aRejectedRestoreBlocksTheSettlement() {
        when(capacityService.restoreReservation(any()))
                .thenThrow(new IllegalStateException("容量账本正在恢复"));

        WaitMemberSettlement.Outcome outcome = settlement.settle(member, proof(reservation),
                "SUCCEEDED", result("SUCCEEDED", 0, "done", null), "out");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).isEqualTo("restore_error");
        verify(capacityService, never()).releaseReservation(any());
    }

    /** 用量记录没写进去：不下结论（名额已经还了没关系，下一轮重做是幂等的）。 */
    @Test
    void aFailedUsageRecordBlocksTheSettlement() {
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(terminalRecorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.CONFLICT);

        WaitMemberSettlement.Outcome outcome = settlement.settle(member, proof(reservation),
                "SUCCEEDED", result("SUCCEEDED", 0, "done", null), "out");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).startsWith("usage:");
    }

    /** 权威地说这个后台作业不存在：按建任务被否定还名额，没有用量可记。 */
    @Test
    void aTaskThatWasNeverCreatedIsReleasedWithoutUsage() {
        DataAnalysisReservation preparing = new DataAnalysisReservation(operationId(),
                reservation.identity(), DataAnalysisResourceClass.STANDARD, 2,
                DataAnalysisReservationState.PREPARING, null, Instant.now());
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);

        WaitMemberSettlement.Outcome outcome = settlement.settle(member, proof(preparing), null, null, "");

        assertThat(outcome.ok()).isTrue();
        ArgumentCaptor<DataAnalysisReleaseRequest> release =
                ArgumentCaptor.forClass(DataAnalysisReleaseRequest.class);
        verify(capacityService).releaseReservation(release.capture());
        assertThat(release.getValue().reason())
                .isEqualTo(DataAnalysisReleaseReason.CREATE_NOT_STARTED);
        assertThat(release.getValue().proof()).isInstanceOf(DataAnalysisReleaseProof.PreDispatchAbort.class);
        verify(terminalRecorder, never()).upsert(any());
    }

    /** 凭证与结论对不上（说不存在、凭证却绑着任务）：不猜，退回重来。 */
    @Test
    void aStateMismatchIsNotGuessed() {
        WaitMemberSettlement.Outcome outcome = settlement.settle(member, proof(reservation), null, null, "");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).contains("reservation_state_mismatch");
        verify(capacityService, never()).releaseReservation(any());
    }

    /** 结果正文超过信封上限：摘要按上限截断，收尾照样完成。 */
    @Test
    void anOversizedPreviewIsTrimmedToTheEnvelopeLimit() {
        when(capacityService.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacityService.releaseReservation(any())).thenReturn(DataAnalysisReleaseOutcome.RELEASED);
        when(terminalRecorder.upsert(any())).thenReturn(DataAnalysisUpsertOutcome.INSERTED);
        String huge = "x".repeat(DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES + 5000);

        assertThat(settlement.settle(member, proof(reservation), "SUCCEEDED",
                result("SUCCEEDED", 0, "done", null), huge).ok()).isTrue();

        ArgumentCaptor<DataAnalysisTerminalEnvelope> recorded =
                ArgumentCaptor.forClass(DataAnalysisTerminalEnvelope.class);
        verify(terminalRecorder).upsert(recorded.capture());
        assertThat(recorded.getValue().resultPreview().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length)
                .isLessThanOrEqualTo(DataAnalysisTerminalEnvelope.MAX_RESULT_PREVIEW_BYTES);
    }

    /** 证明里的凭证读不出来：不猜、不释放，退回重来。 */
    @Test
    void anUnreadableProofBlocksTheSettlement() {
        WaitMemberDispatchProof broken = new WaitMemberDispatchProof(
                WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION, operationId(), TASK_ID,
                "sha256:fingerprint", "{}", "{}", "{不是 JSON",
                OffsetDateTime.now().toString());

        WaitMemberSettlement.Outcome outcome = settlement.settle(member, broken, "SUCCEEDED",
                result("SUCCEEDED", 0, "done", null), "out");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.reason()).isEqualTo("reservation_unreadable");
    }

    // ===== 造数据 =====

    private String operationId() {
        return new DataAnalysisOperationIdentity(RUN_ID, TOOL_CALL_ID, ATTEMPT).operationId();
    }

    private WaitMemberDispatchProof proof(DataAnalysisReservation stored) {
        return new WaitMemberDispatchProof(WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                operationId(), stored.taskId(), "sha256:fingerprint",
                "{\"code\":\"print(1)\"}",
                toJson(new DataAnalysisEstimate(1L, 1024L, 1, 1.0d, 0, List.of(),
                        DataAnalysisResourceClass.STANDARD, 2)),
                toJson(stored), OffsetDateTime.now().toString());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TaskResultResponse result(String status, int exitCode, String stdout, String error) {
        TaskResultResponse.Builder builder = TaskResultResponse.newBuilder()
                .setTaskId(TASK_ID)
                .setStatus(status)
                .setExitCode(exitCode)
                .setStdout(stdout);
        if (error != null) {
            builder.setError(error);
        }
        return builder.build();
    }
}
