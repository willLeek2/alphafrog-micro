package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.python.DataAnalysisCapacityProperties;
import world.willfrog.agentlangchain.gateway.RunOwnershipGateway;
import world.willfrog.alphafrogmicro.sandbox.idl.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实在线/启动恢复与SQL用户守卫连用；数据库条件及事务竞争仍在mapper边界模拟。 */
class ToolJobSqlPreparingReplayAdmissionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String FP = "sha256:" + "a".repeat(64);

    @ParameterizedTest
    @CsvSource({"false,true,false", "true,true,false", "false,false,false", "true,false,false",
            "false,false,true", "true,false,true"})
    void oldPreparingReplayWaitsForOtherQueryAndOnlyCreatesAfterRenewal(
            boolean startup, boolean busy, boolean cancelDuringLookup) throws Exception {
        Fixture fixture = fixture(busy, cancelDuringLookup, false);
        String originalAnchor = fixture.run.getToolJobAnchorJson();

        fixture.exercise(startup);

        boolean created = !busy && !cancelDuringLookup;
        verify(fixture.sandbox, created ? times(1) : never()).createTask(fixture.request);
        verify(fixture.sandbox, never()).cancelTask(any());
        if (created) {
            var order = inOrder(fixture.mapper, fixture.sandbox);
            order.verify(fixture.mapper).renewExecuteQueryPreparingReplayClaim(eq("run-1"),
                    eq("run-1:call-1:1"), eq(FP), eq(JsonFormat.printer().print(fixture.request)),
                    anyString(), eq(2), eq(5L));
            order.verify(fixture.sandbox).createTask(fixture.request);
            assertThat(fixture.run.getUpdatedAt()).isAfter(OffsetDateTime.now().minusSeconds(1));
        } else {
            assertThat(fixture.run.getToolJobAnchorJson()).isEqualTo(originalAnchor);
            verify(fixture.redis).upsertDue(eq("run-1"), argThat(anchor -> anchor.getNextPollAt() != null));
        }
        if (startup) {
            @SuppressWarnings("unchecked")
            var reservations = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(fixture.capacity).recover(reservations.capture(), anyInt(), anyInt());
            assertThat(reservations.getValue()).singleElement().satisfies(value -> {
                DataAnalysisReservation reservation = (DataAnalysisReservation) value;
                assertThat(reservation.operationId()).isEqualTo("run-1:call-1:1");
                assertThat(reservation.state()).isEqualTo(created
                        ? DataAnalysisReservationState.TASK_ATTACHED : DataAnalysisReservationState.PREPARING);
            });
        }
        verify(fixture.capacity, never()).releaseReservation(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void alreadyAcceptedSqlTaskIsAttachedWithoutRenewalOrSecondCreate(boolean startup) throws Exception {
        Fixture fixture = fixture(true, false, true);

        fixture.exercise(startup);

        verify(fixture.mapper, never()).renewExecuteQueryPreparingReplayClaim(anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(fixture.sandbox, never()).createTask(any());
        assertThat(ToolJobAnchor.fromJson(fixture.run.getToolJobAnchorJson()).getTaskId()).isEqualTo("accepted-task");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlVersionChangedBeforeCasStopsCreateAndLeavesOriginalProofForFreshRead(boolean startup) throws Exception {
        Fixture fixture = fixture(false, false, false);
        String original = fixture.run.getToolJobAnchorJson();
        when(fixture.mapper.renewExecuteQueryPreparingReplayClaim(eq("run-1"), anyString(), anyString(),
                anyString(), anyString(), eq(2), eq(5L))).thenAnswer(call -> {
            fixture.run.setRunControlVersion(6L);
            return 0;
        });

        fixture.exercise(startup);

        verify(fixture.sandbox, never()).createTask(any());
        assertThat(fixture.run.getRunControlVersion()).isEqualTo(6L);
        assertThat(fixture.run.getToolJobAnchorJson()).isEqualTo(original);
        verify(fixture.redis).upsertDue(eq("run-1"), any());
        if (startup) verify(fixture.mapper, atLeast(3)).findById("run-1");
    }

    @org.junit.jupiter.api.Test
    void busyStartupStillRestoresProvenCapacityWhenDueIndexIsUnavailable() throws Exception {
        Fixture fixture = fixture(true, false, false);
        doThrow(new IllegalStateException("redis unavailable")).when(fixture.redis).upsertDue(eq("run-1"), any());

        fixture.exercise(true);

        verify(fixture.capacity).recover(anyList(), anyInt(), anyInt());
        verify(fixture.sandbox, never()).createTask(any());
    }

    private Fixture fixture(boolean busy, boolean canceled, boolean found) throws Exception {
        AgentRunMapper mapper = mock(AgentRunMapper.class);
        ToolJobAnchorService anchors = new ToolJobAnchorService(mapper);
        var sandbox = mock(PythonSandboxService.class);
        var redis = mock(ToolJobRedisCache.class);
        var capacity = mock(DataAnalysisCapacityService.class);
        var ownership = mock(RunOwnershipGateway.class);
        var finalizer = mock(ToolJobFinalizer.class);
        var resume = mock(ToolJobResumeService.class);
        var config = new ToolJobConfig();
        var properties = new DataAnalysisCapacityProperties();
        DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run-1", "call-1", 1);
        DataAnalysisReservation reservation = new DataAnalysisReservation(identity.reservationId(), identity,
                DataAnalysisResourceClass.STANDARD, 1, DataAnalysisReservationState.PREPARING, null, Instant.now());
        ExecuteRequest request = ExecuteRequest.newBuilder().setOperationId(identity.operationId())
                .setRequestFingerprint(FP).setCode("print('original query')").build();
        ToolJobAnchor original = new ToolJobAnchor();
        original.setToolName("executeQuery"); original.setAnchorState("PREPARING");
        original.setOperationId(identity.operationId()); original.setRequestFingerprint(FP);
        original.setCreateRequestJson(JsonFormat.printer().print(request));
        original.setReservationJson(JSON.writeValueAsString(reservation));
        AgentRun run = new AgentRun();
        run.setId("run-1"); run.setUserId("user-1"); run.setStatus(AgentRunStatus.EXECUTING);
        run.setPlanGeneration(2); run.setRunControlVersion(5L); run.setSchedulerVersion(world.willfrog.agent.platform.workitem.SchedulerVersion.LEGACY.name());
        run.setToolJobAnchorJson(original.toJson()); run.setUpdatedAt(OffsetDateTime.now().minusSeconds(601));
        AgentRun other = new AgentRun();
        other.setId("other-run"); other.setUserId("user-1"); other.setStatus(AgentRunStatus.EXECUTING);
        other.setUpdatedAt(OffsetDateTime.now());
        Map<String, AgentRun> rows = Map.of("run-1", run, "other-run", other);
        when(mapper.findById(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        // 年龄直接驱动600秒过滤，另一Run通过同一真实守卫获得名额，不硬编码busy计数。
        when(mapper.countInFlightExecuteQueryByUser(anyString(), anyString(), eq("executeQuery"), eq(600)))
                .thenAnswer(call -> (int) rows.values().stream()
                        .filter(row -> !row.getId().equals(call.getArgument(1)))
                        .filter(row -> row.getStatus() == AgentRunStatus.EXECUTING)
                        .filter(row -> row.getToolJobAnchorJson() != null)
                        .filter(row -> row.getUpdatedAt().isAfter(OffsetDateTime.now().minusSeconds(600))).count());
        when(mapper.claimPreparingToolJobAnchor(eq("other-run"), anyString(), eq(AgentRunStatus.EXECUTING)))
                .thenAnswer(call -> { other.setToolJobAnchorJson(call.getArgument(1)); return 1; });
        if (busy) {
            ToolJobAnchor competing = new ToolJobAnchor(); competing.setToolName("executeQuery");
            competing.setOperationId("other-op"); competing.setAnchorState("PREPARING");
            assertThat(anchors.claimPreparing("other-run", competing, AgentRunStatus.EXECUTING)).isTrue();
        }
        assertThat(run.getUpdatedAt()).isBefore(OffsetDateTime.now().minusSeconds(600));
        when(mapper.renewExecuteQueryPreparingReplayClaim(eq("run-1"), eq(identity.operationId()), eq(FP),
                eq(original.getCreateRequestJson()), eq(original.getReservationJson()), eq(2), eq(5L)))
                .thenAnswer(call -> { run.setUpdatedAt(OffsetDateTime.now()); return 1; });
        when(mapper.updateActiveToolJobAnchor(eq("run-1"), anyString(), any(), eq(identity.operationId())))
                .thenAnswer(call -> { run.setToolJobAnchorJson(call.getArgument(1)); return 1; });
        when(sandbox.getTaskByOperationId(any())).thenAnswer(call -> {
            if (canceled) run.setStatus(AgentRunStatus.CANCELED);
            return found ? GetTaskByOperationIdResponse.newBuilder().setFound(true).setTaskId("accepted-task")
                    .setRequestFingerprint(FP).build() : GetTaskByOperationIdResponse.getDefaultInstance();
        });
        when(sandbox.createTask(any())).thenReturn(ExecuteResponse.newBuilder().setTaskId("replayed-task")
                .setRequestFingerprint(FP).build());
        when(capacity.restoreReservation(any())).thenReturn(DataAnalysisRestoreOutcome.ADDED);
        when(capacity.recover(anyList(), anyInt(), anyInt())).thenReturn(new DataAnalysisCapacityRecoveryReport(
                1, 1, 0, 1, properties.getMaxUnits(), properties.getMaxHeavyActive(), false, false,
                List.of(), DataAnalysisAdmissionState.OPEN));
        when(ownership.findOwnedRun("run-1")).thenReturn(run);
        when(ownership.owns("run-1")).thenReturn(true);
        when(ownership.listActiveAnchors(200)).thenReturn(List.of(run));
        when(redis.fetchDue(20)).thenReturn(Set.of("run-1"));
        var startup = new ToolJobStartupRecovery(anchors, redis, capacity, properties, finalizer, resume, config, ownership);
        ReflectionTestUtils.setField(startup, "sandboxService", sandbox);
        var online = new ToolJobReconciler(redis, anchors, finalizer, resume, config, capacity, ownership);
        ReflectionTestUtils.setField(online, "sandboxService", sandbox);
        return new Fixture(run, mapper, sandbox, redis, capacity, request, startup, online);
    }

    private record Fixture(AgentRun run, AgentRunMapper mapper, PythonSandboxService sandbox,
                           ToolJobRedisCache redis, DataAnalysisCapacityService capacity, ExecuteRequest request,
                           ToolJobStartupRecovery startup, ToolJobReconciler online) {
        void exercise(boolean atStartup) { if (atStartup) startup.onReady(); else online.reconcileFromDue(); }
    }
}
