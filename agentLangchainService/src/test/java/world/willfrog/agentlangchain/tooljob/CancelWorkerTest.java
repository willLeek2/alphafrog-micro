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


    // ===== PREPARING/FIRST/RETRY 不写任何计数 =====




    // ===== 非 RPC 桶正常处理 =====



    // ===== 混合桶：每 child 独立处理，不重复查询 =====


    // ===== 空扫描直接退出 =====


    // ===== 异常不终止 worker =====


    // ===== null children 不崩 =====


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
