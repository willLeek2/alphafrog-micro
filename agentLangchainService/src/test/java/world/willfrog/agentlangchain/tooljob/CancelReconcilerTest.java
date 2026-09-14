package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import world.willfrog.agent.platform.mapper.AgentRunDagNodeMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelReconcilerTest {

    private AgentRunDagNodeMapper dagNodeMapper;
    private CancelWorker cancelWorker;
    private CancelReconciler reconciler;

    @BeforeEach
    void setUp() {
        dagNodeMapper = mock(AgentRunDagNodeMapper.class);
        cancelWorker = mock(CancelWorker.class);
        reconciler = new CancelReconciler(dagNodeMapper, cancelWorker);
        ReflectionTestUtils.setField(reconciler, "batchSize", 5);
        ReflectionTestUtils.setField(reconciler, "initialBackoffSeconds", 60);
        ReflectionTestUtils.setField(reconciler, "leaseSeconds", 120L);
    }

    // ===== 单实例扫描 =====

    @Test
    void emptyScanDoesNotCallWorker() {
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(List.of());

        reconciler.reconcile();

        verify(dagNodeMapper).selectCANCELLINGRuns(5);
        verify(dagNodeMapper, never()).claimReconcilerLease(
                anyString(), anyInt(), anyString(), anyString(), anyLong());
        verify(cancelWorker, never()).runCancelWorker(
                anyString(), anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    void nullScanDoesNotCallWorker() {
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(null);

        reconciler.reconcile();

        verify(cancelWorker, never()).runCancelWorker(
                anyString(), anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    void dispatchesWorkerForClaimedRuns() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "req-a"));
        candidates.add(cancellingRun("run-2", 1, "req-b"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(1);
        when(dagNodeMapper.releaseReconcilerLease(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);

        reconciler.reconcile();

        verify(dagNodeMapper).claimReconcilerLease(eq("run-1"), eq(2), eq("req-a"), anyString(), eq(120L));
        verify(dagNodeMapper).claimReconcilerLease(eq("run-2"), eq(1), eq("req-b"), anyString(), eq(120L));
        verify(cancelWorker).runCancelWorker(eq("run-1"), eq(2), eq("req-a"), eq(60), eq(5));
        verify(cancelWorker).runCancelWorker(eq("run-2"), eq(1), eq("req-b"), eq(60), eq(5));
        // 验证 release 被调用
        verify(dagNodeMapper).releaseReconcilerLease(eq("run-1"), eq(2), eq("req-a"), anyString());
        verify(dagNodeMapper).releaseReconcilerLease(eq("run-2"), eq(1), eq("req-b"), anyString());
    }

    @Test
    void singleRunExceptionIsolatesOtherRuns() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "req-a"));
        candidates.add(cancellingRun("run-2", 1, "req-b"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(1);
        when(dagNodeMapper.releaseReconcilerLease(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);
        doThrow(new RuntimeException("worker crash"))
                .when(cancelWorker).runCancelWorker(eq("run-1"), eq(2), anyString(), anyInt(), anyInt());

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();

        verify(cancelWorker).runCancelWorker(eq("run-1"), eq(2), eq("req-a"), eq(60), eq(5));
        verify(cancelWorker).runCancelWorker(eq("run-2"), eq(1), eq("req-b"), eq(60), eq(5));
        // run-1 虽然 worker 异常，但 release 仍在 finally 中执行
        verify(dagNodeMapper).releaseReconcilerLease(eq("run-1"), eq(2), eq("req-a"), anyString());
    }

    @Test
    void scanExceptionDoesNotCrash() {
        when(dagNodeMapper.selectCANCELLINGRuns(5))
                .thenThrow(new RuntimeException("db error"));

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();
        verify(cancelWorker, never()).runCancelWorker(
                anyString(), anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    void nullRunIdSkipsRow() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Object> badRow = new HashMap<>();
        badRow.put("runId", null);
        badRow.put("generation", 1);
        badRow.put("cancelRequestId", "req-x");
        candidates.add(badRow);
        candidates.add(cancellingRun("run-2", 1, "req-b"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(1);
        when(dagNodeMapper.releaseReconcilerLease(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);

        reconciler.reconcile();

        verify(dagNodeMapper, never()).claimReconcilerLease(eq(null), anyInt(), anyString(), anyString(), anyLong());
        verify(cancelWorker).runCancelWorker(eq("run-2"), eq(1), eq("req-b"), eq(60), eq(5));
    }

    // ===== 多实例 claim 互斥 =====

    @Test
    void claimFailureSkipsRun() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "req-a"));
        candidates.add(cancellingRun("run-2", 1, "req-b"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(eq("run-1"), eq(2), eq("req-a"), anyString(), anyLong()))
                .thenReturn(0);
        when(dagNodeMapper.claimReconcilerLease(eq("run-2"), eq(1), eq("req-b"), anyString(), anyLong()))
                .thenReturn(1);
        when(dagNodeMapper.releaseReconcilerLease(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);

        reconciler.reconcile();

        verify(cancelWorker, never()).runCancelWorker(eq("run-1"), anyInt(), anyString(), anyInt(), anyInt());
        verify(cancelWorker).runCancelWorker(eq("run-2"), eq(1), eq("req-b"), eq(60), eq(5));
        verify(dagNodeMapper, never()).releaseReconcilerLease(eq("run-1"), anyInt(), anyString(), anyString());
    }

    @Test
    void claimExceptionSkipsRun() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "req-a"));
        candidates.add(cancellingRun("run-2", 1, "req-b"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(eq("run-1"), eq(2), eq("req-a"), anyString(), anyLong()))
                .thenThrow(new RuntimeException("db error"));
        when(dagNodeMapper.claimReconcilerLease(eq("run-2"), eq(1), eq("req-b"), anyString(), anyLong()))
                .thenReturn(1);
        when(dagNodeMapper.releaseReconcilerLease(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(1);

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();

        verify(cancelWorker, never()).runCancelWorker(eq("run-1"), anyInt(), anyString(), anyInt(), anyInt());
        verify(cancelWorker).runCancelWorker(eq("run-2"), eq(1), eq("req-b"), eq(60), eq(5));
    }

    @Test
    void allClaimsFailNoWorkerCalled() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "req-a"));
        candidates.add(cancellingRun("run-2", 1, "req-b"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(0);

        reconciler.reconcile();

        verify(cancelWorker, never()).runCancelWorker(
                anyString(), anyInt(), anyString(), anyInt(), anyInt());
    }

    // ===== 旧扫描快照不能 claim 新 generation =====

    @Test
    void oldScanCannotClaimNewGeneration() {
        // 模拟旧扫描快照：generation=2, cancelRequestId=old-req
        // 但数据库里 run 已经进入 generation=3, cancelRequestId=new-req
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "old-req"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        // claim 失败：generation/cancelRequestId 不匹配
        when(dagNodeMapper.claimReconcilerLease(eq("run-1"), eq(2), eq("old-req"), anyString(), anyLong()))
                .thenReturn(0);

        reconciler.reconcile();

        verify(cancelWorker, never()).runCancelWorker(
                anyString(), anyInt(), anyString(), anyInt(), anyInt());
    }

    // ===== release 异常不影响流程 =====

    @Test
    void releaseExceptionDoesNotCrash() {
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(cancellingRun("run-1", 2, "req-a"));
        when(dagNodeMapper.selectCANCELLINGRuns(5)).thenReturn(candidates);
        when(dagNodeMapper.claimReconcilerLease(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(1);
        when(dagNodeMapper.releaseReconcilerLease(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("release failed"));

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();

        verify(cancelWorker).runCancelWorker(eq("run-1"), eq(2), eq("req-a"), eq(60), eq(5));
        verify(dagNodeMapper).releaseReconcilerLease(eq("run-1"), eq(2), eq("req-a"), anyString());
    }

    private Map<String, Object> cancellingRun(String runId, int generation, String cancelRequestId) {
        Map<String, Object> row = new HashMap<>();
        row.put("runId", runId);
        row.put("generation", generation);
        row.put("cancelRequestId", cancelRequestId);
        return row;
    }
}
