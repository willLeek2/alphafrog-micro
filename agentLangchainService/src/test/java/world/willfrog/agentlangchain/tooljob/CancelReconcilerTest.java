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







    // ===== 多实例 claim 互斥 =====




    // ===== 旧扫描快照不能 claim 新 generation =====


    // ===== release 异常不影响流程 =====


    private Map<String, Object> cancellingRun(String runId, int generation, String cancelRequestId) {
        Map<String, Object> row = new HashMap<>();
        row.put("runId", runId);
        row.put("generation", generation);
        row.put("cancelRequestId", cancelRequestId);
        return row;
    }
}
