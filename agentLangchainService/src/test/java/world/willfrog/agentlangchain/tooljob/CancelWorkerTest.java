package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dag.CancelBucket;
import world.willfrog.agent.platform.dag.ExhaustedAdvance;
import world.willfrog.agent.platform.mapper.AgentRunDagNodeMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancelWorkerTest {

    private AgentRunDagNodeMapper dagNodeMapper;
    private CancelWorker cancelWorker;

    @BeforeEach
    void setUp() {
        dagNodeMapper = mock(AgentRunDagNodeMapper.class);
        cancelWorker = new CancelWorker(dagNodeMapper, null);
    }

    // ===== RECOVERY 在 D11 RPC 未就绪时不调 atomicTerminalLost =====

    @Test
    void recoveryBucketNoOpsWhenRpcUnavailable() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.RECOVERY, 1L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        verify(dagNodeMapper, never()).atomicTerminalLost(
                anyString(), anyInt(), anyString(), anyInt(), anyString(), anyLong());
    }

    // ===== PREPARING/FIRST/RETRY 不写任何计数 =====

    @Test
    void preparingBucketWritesNoCountsWhenRpcUnavailable() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.PREPARING, 1L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        verify(dagNodeMapper, never()).incrementCancelRpcRetryCount(
                anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyString(), anyLong());
        verify(dagNodeMapper, never()).incrementCancelNotfoundRetryCount(
                anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyString(), anyLong());
        verify(dagNodeMapper, never()).writeChildFinalizedTombstone(
                anyString(), anyInt(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyString());
        verify(dagNodeMapper, never()).writePreparingToCreatedCancel(
                anyString(), anyInt(), anyString(), anyLong(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void firstBucketWritesNoCountsWhenRpcUnavailable() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.FIRST, 1L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        verify(dagNodeMapper, never()).incrementCancelRpcRetryCount(
                anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyString(), anyLong());
    }

    @Test
    void retryBucketWritesNoCountsWhenRpcUnavailable() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.RETRY, 1L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        verify(dagNodeMapper, never()).incrementCancelRpcRetryCount(
                anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyString(), anyLong());
    }

    // ===== 非 RPC 桶正常处理 =====

    @Test
    void preparingStuckBucketProcessesNormally() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.PREPARING_STUCK, 1L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);
        when(dagNodeMapper.writePreparingStuck(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(new ExhaustedAdvance(2L));

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        verify(dagNodeMapper).writePreparingStuck(eq("run-1"), eq(2), eq("node-1"), eq("req-a"), eq(1L));
    }

    @Test
    void rpcExhaustedBucketProcessesNormally() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.RPC_EXHAUSTED, 1L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);
        when(dagNodeMapper.writeRpcExhausted(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(new ExhaustedAdvance(2L));

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        verify(dagNodeMapper).writeRpcExhausted(eq("run-1"), eq(2), eq("node-1"), eq("req-a"), eq(1L));
    }

    // ===== 混合桶：每 child 独立处理，不重复查询 =====

    @Test
    void mixedBucketsProcessesAllChildrenInSingleBatch() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.RECOVERY, 1L));
        children.add(makeChild("node-2", CancelBucket.PREPARING_STUCK, 2L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);
        when(dagNodeMapper.writePreparingStuck(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(new ExhaustedAdvance(3L));

        cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5);

        // PREPARING_STUCK processed
        verify(dagNodeMapper).writePreparingStuck(eq("run-1"), eq(2), eq("node-2"), eq("req-a"), eq(2L));
        // RECOVERY no-op
        verify(dagNodeMapper, never()).atomicTerminalLost(
                anyString(), anyInt(), anyString(), anyInt(), anyString(), anyLong());
        // 单批只查询一次
        verify(dagNodeMapper).selectCancelDueChildren(eq("run-1"), eq(2), eq("req-a"), eq(5));
    }

    // ===== 空扫描直接退出 =====

    @Test
    void emptyScanDoesNotCrash() {
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertThatCode(() -> cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5))
                .doesNotThrowAnyException();
    }

    // ===== 异常不终止 worker =====

    @Test
    void childExceptionDoesNotCrashWorker() {
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(makeChild("node-1", CancelBucket.PREPARING_STUCK, 1L));
        children.add(makeChild("node-2", CancelBucket.RPC_EXHAUSTED, 2L));
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(children);
        when(dagNodeMapper.writePreparingStuck(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("db error"));
        when(dagNodeMapper.writeRpcExhausted(anyString(), anyInt(), anyString(), anyString(), anyLong()))
                .thenReturn(new ExhaustedAdvance(3L));

        assertThatCode(() -> cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5))
                .doesNotThrowAnyException();

        // node-1 threw exception but node-2 still processed
        verify(dagNodeMapper).writeRpcExhausted(eq("run-1"), eq(2), eq("node-2"), eq("req-a"), eq(2L));
    }

    // ===== null children 不崩 =====

    @Test
    void nullChildrenDoesNotCrash() {
        when(dagNodeMapper.selectCancelDueChildren(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(null);

        assertThatCode(() -> cancelWorker.runCancelWorker("run-1", 2, "req-a", 60, 5))
                .doesNotThrowAnyException();
    }

    // ===== 辅助方法 =====

    private Map<String, Object> makeChild(String nodeId, CancelBucket bucket, long nodeVersion) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("nodeId", nodeId);
        row.put("cancelBucket", bucket.name());
        row.put("nodeVersion", nodeVersion);
        row.put("operationId", "op-" + nodeId);
        row.put("toolCallId", "tc-" + nodeId);
        row.put("attempt", 1);
        row.put("requestDigest", "0000000000000000000000000000000000000000000000000000000000000001");
        row.put("taskId", "task-" + nodeId);
        row.put("cancelNotfoundRetryCount", 0);
        row.put("cancelRpcRetryCount", 0);
        return row;
    }
}
