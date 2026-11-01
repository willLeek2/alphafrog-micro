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
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agent.tools.python.DataAnalysisCapacityServiceImpl;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.service.AgentRunStateStore;
import world.willfrog.agent.platform.service.DataAnalysisObservabilityService;
import world.willfrog.agentlangchain.tools.DurableToolCallIds;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 等待成员的收尾：名额还回去、用量记下来。
 *
 * <p>这里量四件事：正常终态走「终态已确认 → 还名额 → 写用量」，还名额时带的是那份终态信封；
 * 账本说已经还过（或者挂着别的名额）时不重复还、但用量照记；任何一步没成都不下结论（返回原因、
 * 由调用方推后重来）；创建前取消墓碑也有 Sandbox 终态，按同一路径记录用量。</p>
 */
class WaitMemberSettlementTest {

    private static final String RUN_ID = "run-settlement";
    private static final String TOOL_CALL_ID = "call-1";
    private static final int ATTEMPT = 1;
    private static final String TASK_ID = "task-9";
    private static final String FINISHED_AT = "2026-09-25T02:03:04.123456";

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
















    // ===== 造数据 =====

    private String operationId() {
        return new DataAnalysisOperationIdentity(RUN_ID, TOOL_CALL_ID, ATTEMPT).operationId();
    }

    private WaitMemberDispatchProof proof(DataAnalysisReservation stored) {
        return new WaitMemberDispatchProof(WaitMemberDispatchProof.CURRENT_SCHEMA_VERSION,
                stored.operationId(), stored.taskId(), "sha256:fingerprint",
                "{\"code\":\"print(1)\"}",
                toJson(new DataAnalysisEstimate(1L, 1024L, 1, 1.0d, 0, List.of(),
                        DataAnalysisResourceClass.STANDARD, 2)),
                toJson(stored), OffsetDateTime.now().toString());
    }

    private DataAnalysisReservation reservationFor(String durableCallId, String taskId) {
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity(RUN_ID, durableCallId, ATTEMPT);
        return new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 2, DataAnalysisReservationState.TASK_ATTACHED,
                taskId, Instant.now());
    }

    private WaitMember memberFor(String rawCallId, String externalOperationId, long id, long groupId) {
        WaitMember candidate = new WaitMember();
        candidate.setId(id);
        candidate.setGroupId(groupId);
        candidate.setRunId(RUN_ID);
        candidate.setMemberIdentity("node-1:" + groupId + ":" + rawCallId);
        candidate.setToolName("executePython");
        candidate.setToolCallId(rawCallId);
        candidate.setExternalOperationId(externalOperationId);
        return candidate;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TaskResultResponse result(String status, int exitCode, String stdout, String error) {
        return result(TASK_ID, status, exitCode, stdout, error);
    }

    private static TaskResultResponse result(String taskId, String status, int exitCode, String stdout,
                                             String error) {
        TaskResultResponse.Builder builder = TaskResultResponse.newBuilder()
                .setTaskId(taskId)
                .setStatus(status)
                .setExitCode(exitCode)
                .setStdout(stdout);
        if (error != null) {
            builder.setError(error);
        }
        return builder.build();
    }
}
