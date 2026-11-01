package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import world.willfrog.agent.platform.dataanalysis.DagBlockingWorkerLease;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.platform.workitem.NodeWorkItemIdentity;
import world.willfrog.agent.platform.workitem.NodeWorkItemVersions;
import world.willfrog.agentlangchain.control.dualpool.DualPoolToolJobExecutionContext;
import world.willfrog.agentlangchain.control.scheduler.LangchainSchedulerMetrics;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PythonSandboxDispatchStoreImplTest {

    private final ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
    private final ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
    private final ToolJobConfig config = new ToolJobConfig();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ToolJobContinuationTracker> trackerProvider =
            mock(ObjectProvider.class);
    private final LangchainSchedulerMetrics metrics =
            new LangchainSchedulerMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final PythonSandboxDispatchStoreImpl store =
            new PythonSandboxDispatchStoreImpl(
                    anchorService, redisCache, config, trackerProvider, metrics);

    {
        // 本测试类覆盖 durable Redis 派生路径；进程内 tracker 路径由专门测试覆盖。
        config.setDurableRecoveryEnabled(true);
        when(anchorService.loadSchedulerVersion(any())).thenReturn("LEGACY");
    }





















    private ToolJobAnchor anchor(String state) {
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-1:call-1:1");
        anchor.setAnchorState(state);
        return anchor;
    }

    private ToolJobAnchor liveDagAnchor(String state) {
        ToolJobAnchor anchor = anchor(state);
        anchor.setRunDisposition("DAG_BLOCKING_NO_RESUME");
        anchor.setAutoResume(false);
        anchor.setBlockingOwnerId("worker-a");
        anchor.setBlockingLeaseUntil(Instant.parse("2099-01-01T00:00:00Z"));
        return anchor;
    }
}
