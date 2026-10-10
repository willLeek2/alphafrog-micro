package world.willfrog.agentlangchain.tooljob;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import world.willfrog.agent.platform.dataanalysis.*;
import world.willfrog.agent.platform.entity.AgentRun;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.wait.*;
import world.willfrog.alphafrogmicro.sandbox.idl.ExecuteRequest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用项目实际Spring事务代理验证两次写入全成或全退；数据库行由事务参与者模拟。 */
class SqlAtomicMemberPreparingTest {
    @Test void commitsAnchorAndFullMemberProofTogether() throws Exception {
        Fixture f = new Fixture();
        assertThat(f.claim()).isTrue();
        assertThat(f.transactions.commits).isEqualTo(1);
        assertThat(f.transactions.rollbacks).isZero();
        assertThat(f.run.getToolJobAnchorJson()).isEqualTo(f.anchor.toJson());
        assertThat(f.member.getDispatchProofJson()).isEqualTo(f.proof);
        assertThat(ToolJobAnchor.fromJson(f.run.getToolJobAnchorJson()).getFinanceRecordLimitsJson())
                .isEqualTo("{\"enabled\":true}");
    }

    @Test void secondWriteZeroRollsBackFirstWriteThroughSpringProxy() throws Exception {
        Fixture f = new Fixture();
        doReturn(0).when(f.members).recordMemberPreparing(anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(f::claim).isInstanceOf(ToolJobAnchorService.MemberPreparingRollback.class);
        f.assertNeitherWritten();
        assertThat(f.transactions.rollbacks).isEqualTo(1);
    }

    @Test void secondWriteExceptionAlsoRollsBackFirstWrite() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("database write failed")).when(f.members)
                .recordMemberPreparing(anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(f::claim).isInstanceOf(IllegalStateException.class);
        f.assertNeitherWritten();
        assertThat(f.transactions.rollbacks).isEqualTo(1);
    }

    @Test void changedWorkerOrControlVersionRollsBackAnchorWithoutRecordingMember() throws Exception {
        Fixture f = new Fixture();
        when(f.members.countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        assertThatThrownBy(f::claim).isInstanceOf(ToolJobAnchorService.MemberPreparingRollback.class);
        f.assertNeitherWritten();
        verify(f.members, never()).recordMemberPreparing(anyLong(), anyString(), anyString(), anyString());
    }

    @Test void busyIsRetryableAndWritesNeitherFact() throws Exception {
        Fixture f = new Fixture();
        when(f.runs.countInFlightExecuteQueryByUser(anyString(), anyString(), anyString(), anyInt())).thenReturn(1);
        assertThatThrownBy(f::claim).isInstanceOfSatisfying(SessionQueryAdmissionException.class, failure -> {
            assertThat(failure.code()).isEqualTo("SESSION_QUERY_IN_PROGRESS");
            assertThat(failure.retryable()).isTrue();
        });
        f.assertNeitherWritten();
        verify(f.runs, never()).claimPreparingToolJobAnchor(anyString(), anyString(), any());
    }

    @Test void resumeHandoffUsesOriginalTokenAndDoesNotLoseMemberOrFinanceProof() throws Exception {
        Fixture f = new Fixture();
        when(f.runs.claimPreparingToolJobAnchorFromResume(anyString(), anyString(), eq("token"), eq(3L)))
                .thenAnswer(call -> { f.run.setToolJobAnchorJson(call.getArgument(1)); return 1; });
        assertThat(f.service.claimPreparingWaitMember("run", f.anchor, 7, "member", f.proof, "token", 3L)).isTrue();
        verify(f.runs, never()).claimPreparingToolJobAnchor(anyString(), anyString(), any());
        assertThat(f.member.getDispatchProofJson()).isEqualTo(f.proof);
    }

    @Test void lockedReadbackRequiresBothExactFactsAndOriginalWorker() throws Exception {
        Fixture f = new Fixture();
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.NOT_WRITTEN);
        f.claim();
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.COMMITTED);
        when(f.members.countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.COMMITTED_DEFERRED);
        verify(f.runs, times(3)).findByIdForUpdate("run");
    }

    @Test void committedReadbackAfterStaleWindowWaitsForAnotherUserQueryWithoutRenewal() throws Exception {
        Fixture f = new Fixture();
        f.claim();
        f.run.setUpdatedAt(java.time.OffsetDateTime.now().minusSeconds(601));
        AgentRun other = new AgentRun(); other.setId("other"); other.setUserId("user");
        other.setStatus(AgentRunStatus.EXECUTING);
        ToolJobAnchor otherAnchor = ToolJobAnchor.fromJson(f.anchor.toJson());
        otherAnchor.setOperationId("other:call:1");
        when(f.runs.findById("other")).thenReturn(other);
        // 实际用户守卫计数：旧查询早于600秒，另一Run能够取得名额。
        when(f.runs.countInFlightExecuteQueryByUser(eq("user"), eq("other"), eq("executeQuery"), eq(600)))
                .thenAnswer(call -> f.run.getUpdatedAt().isBefore(java.time.OffsetDateTime.now().minusSeconds(600)) ? 0 : 1);
        doAnswer(call -> { other.setToolJobAnchorJson(call.getArgument(1)); return 1; }).when(f.runs)
                .claimPreparingToolJobAnchor(eq("other"), anyString(), any());
        assertThat(f.service.claimPreparing("other", otherAnchor, AgentRunStatus.EXECUTING)).isTrue();
        other.setToolJobAnchorJson(otherAnchor.toJson()); other.setUpdatedAt(java.time.OffsetDateTime.now());
        when(f.runs.countInFlightExecuteQueryByUser(eq("user"), eq("run"), eq("executeQuery"), eq(600)))
                .thenAnswer(call -> other.getUpdatedAt().isAfter(java.time.OffsetDateTime.now().minusSeconds(600)) ? 1 : 0);
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.COMMITTED_DEFERRED);
        verify(f.runs, never()).renewExecuteQueryReplayClaim(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyLong(), anyLong());
        assertThat(f.member.getDispatchProofJson()).isEqualTo(f.proof);
    }

    @Test void canceledOrChangedPlanWithNoWritesReturnsOnlyTheUndurableLocalReservation() throws Exception {
        Fixture f = new Fixture();
        f.run.setStatus(AgentRunStatus.CANCELED); f.run.setPlanGeneration(3); f.run.setRunControlVersion(6L);
        when(f.members.countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(0);
        when(f.members.lockPreparingSqlMemberContext("run", 7, "member")).thenReturn("CANCELED");
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.NOT_WRITTEN);
        f.assertNeitherWritten();
    }

    @Test void changedClaimWithNewProofOrSameOperationAnchorNeverReleasesTheTakenOverReservation() throws Exception {
        Fixture f = new Fixture();
        when(f.members.countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(0);
        f.run.setToolJobAnchorJson(f.anchor.toJson());
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.OWNERSHIP_LOST);
        f.member.setDispatchProofJson(f.proof);
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.COMMITTED_DEFERRED);
        f.member.setDispatchProofJson("{}");
        assertThat(f.readback()).isEqualTo(ToolJobAnchorService.MemberPreparingReadback.OWNERSHIP_LOST);
    }

    static final class Fixture {
        final AgentRunMapper runs = mock(AgentRunMapper.class);
        final WaitGroupMapper members = mock(WaitGroupMapper.class);
        final AgentRun run = new AgentRun();
        final WaitMember member = new WaitMember();
        final ToolJobAnchor anchor = new ToolJobAnchor();
        final String proof;
        final RowTransactions transactions = new RowTransactions(run, member);
        final ToolJobAnchorService service;
        Fixture() throws Exception {
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            DataAnalysisOperationIdentity identity = new DataAnalysisOperationIdentity("run", "call", 1);
            CanonicalSandboxCreateSpec spec = new CanonicalSandboxCreateSpec(CanonicalSandboxCreateSpec.CURRENT_SCHEMA_VERSION, identity.operationId(),
                    "a".repeat(64), "b".repeat(64), DataAnalysisResourceClass.STANDARD,
                    1000L, 1000L, "environment", "c".repeat(64), "d".repeat(64));
            ExecuteRequest request = ExecuteRequest.newBuilder().setOperationId(identity.operationId())
                    .setRequestFingerprint(spec.requestFingerprint()).build();
            DataAnalysisReservation reservation = new DataAnalysisReservation(identity.reservationId(), identity,
                    DataAnalysisResourceClass.STANDARD, 1, DataAnalysisReservationState.PREPARING, null, Instant.now());
            String specJson = json.writeValueAsString(spec);
            String reservationJson = json.writeValueAsString(reservation);
            String requestJson = JsonFormat.printer().print(request);
            anchor.setToolName("executeQuery"); anchor.setToolCallId("call"); anchor.setOperationId(identity.operationId());
            anchor.setAnchorState("PREPARING"); anchor.setRequestFingerprint(spec.requestFingerprint());
            anchor.setCreateRequestJson(requestJson); anchor.setCanonicalCreateSpecJson(specJson);
            anchor.setReservationJson(reservationJson); anchor.setEstimateJson("{}");
            anchor.setFinanceRecordLimitsJson("{\"enabled\":true}");
            anchor.setWorkItemPlanGeneration(2); anchor.setWorkItemNodeId("node"); anchor.setWorkItemNodeAttempt(1);
            anchor.setWorkItemSegmentSequence(0); anchor.setWorkItemRunControlVersion(5L);
            anchor.setWorkItemContextVersion(4L); anchor.setWorkItemClaimEpoch(1); anchor.setWorkItemClaimedBy("worker");
            proof = new WaitMemberDispatchProof(2, identity.operationId(), null, spec.requestFingerprint(),
                    specJson, "{}", reservationJson, Instant.now().toString(), requestJson).toJson(json);
            run.setId("run"); run.setUserId("user"); run.setStatus(AgentRunStatus.EXECUTING);
            run.setPlanGeneration(2); run.setRunControlVersion(5L);
            member.setRunId("run"); member.setGroupId(7L); member.setMemberIdentity("member");
            member.setToolName("executeQuery"); member.setExternalOperationId(identity.operationId()); member.setState("PENDING");
            when(runs.findById("run")).thenReturn(run);
            when(runs.findByIdForUpdate("run")).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); return run;
            });
            when(runs.claimPreparingToolJobAnchor(anyString(), anyString(), any())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                run.setToolJobAnchorJson(call.getArgument(1)); return 1;
            });
            when(runs.renewExecuteQueryReplayClaim(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(1);
            when(members.countPreparingSqlMemberOwner(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
            when(members.lockPreparingSqlMemberContext("run", 7, "member")).thenReturn("RESULT_COMMITTED");
            when(members.findMemberByIdentity(7, "member")).thenReturn(member);
            when(members.recordMemberPreparing(anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                member.setDispatchProofJson(call.getArgument(3)); return 1;
            });
            ToolJobAnchorService target = new ToolJobAnchorService(runs);
            ReflectionTestUtils.setField(target, "waitGroupMapper", members);
            ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            service = (ToolJobAnchorService) proxy.getProxy();
        }
        boolean claim() { return service.claimPreparingWaitMember("run", anchor, 7, "member", proof, null, null); }
        ToolJobAnchorService.MemberPreparingReadback readback() {
            return service.readPreparingWaitMember("run", anchor, 7, "member", proof, null, null);
        }
        void assertNeitherWritten() {
            assertThat(run.getToolJobAnchorJson()).isNull(); assertThat(member.getDispatchProofJson()).isNull();
        }
    }

    /** Spring决定commit/rollback，模拟参与同一事务的两行，避免仅断言mapper调用顺序。 */
    static final class RowTransactions extends AbstractPlatformTransactionManager {
        final AgentRun run; final WaitMember member;
        String beforeAnchor; String beforeProof; int commits; int rollbacks;
        RowTransactions(AgentRun run, WaitMember member) { this.run = run; this.member = member; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            beforeAnchor = run.getToolJobAnchorJson(); beforeProof = member.getDispatchProofJson();
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++; run.setToolJobAnchorJson(beforeAnchor); member.setDispatchProofJson(beforeProof);
        }
    }
}
