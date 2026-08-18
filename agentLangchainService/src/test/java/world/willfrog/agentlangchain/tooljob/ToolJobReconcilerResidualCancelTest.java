package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.alphafrogmicro.sandbox.idl.GetTaskStatusRequest;
import world.willfrog.alphafrogmicro.sandbox.idl.PythonSandboxService;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskStatusResponse;

import java.lang.reflect.Field;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 260819（grace must-fix-1）：终态取消残留的直接清理入口。
 *
 * <p>Redis due 丢失/重启后由 60s 补扫重建索引（listActiveToolJobAnchors 第三支放行
 * 终态 + runDisposition=CANCELED）；processItem 对这类锚点先走数据库语句清理，
 * 不查 Sandbox——旧任务结果过保存期时结果拉取链永远到不了 finalizer 的
 * CANCELED 分支。清理返回 0（Run 非终态，正常取消收口中）时继续 Sandbox 轮询。</p>
 */
class ToolJobReconcilerResidualCancelTest {

    @Test
    void terminalCanceledResidualClosesWithoutSandboxLookup() throws Exception {
        ToolJobAnchor anchor = residualAnchor();
        Fixture fixture = fixture(anchor);
        when(fixture.anchorService.closeResidualCanceledAnchor(
                "run-res", "run-res:call-1:1")).thenReturn(true);

        fixture.reconciler.reconcileFromDue();

        verify(fixture.anchorService).closeResidualCanceledAnchor(
                "run-res", "run-res:call-1:1");
        verify(fixture.redisCache).removeDue("run-res");
        verify(fixture.redisCache).deletePendingCache("run-res");
        verifyNoInteractions(fixture.sandbox);
        verifyNoInteractions(fixture.finalizer);
    }

    @Test
    void nonTerminalCancelFallsThroughToSandboxPolling() throws Exception {
        // 数据库清理返回 0 行（Run 仍 WAITING_TOOL_JOB/EXECUTING，正常取消收口中）
        // → 必须继续走 Sandbox 终态确认，不能被直接入口吞掉。
        ToolJobAnchor anchor = residualAnchor();
        Fixture fixture = fixture(anchor);
        when(fixture.anchorService.closeResidualCanceledAnchor(
                "run-res", "run-res:call-1:1")).thenReturn(false);
        when(fixture.sandbox.getTaskStatus(any(GetTaskStatusRequest.class))).thenReturn(
                TaskStatusResponse.newBuilder().setStatus("RUNNING").build());

        fixture.reconciler.reconcileFromDue();

        verify(fixture.sandbox).getTaskStatus(any(GetTaskStatusRequest.class));
        verify(fixture.redisCache, never()).removeDue("run-res");
    }

    private ToolJobAnchor residualAnchor() {
        // e572 签名：ACCEPTED+consumed、autoResume=false、CANCELED 处置、全步骤完成。
        ToolJobAnchor anchor = new ToolJobAnchor();
        anchor.setOperationId("run-res:call-1:1");
        anchor.setToolCallId("call-1");
        anchor.setAttempt(1);
        anchor.setTaskId("task-res");
        anchor.setAnchorState("TERMINAL");
        anchor.setResumeState("ACCEPTED");
        anchor.setResumeToken("tok-res");
        anchor.setResumeLeaseVersion(5);
        anchor.setResultConsumed(true);
        anchor.setAutoResume(false);
        anchor.setRunDisposition("CANCELED");
        anchor.setFinalizerStep("RESUME_READY");
        return anchor;
    }

    private Fixture fixture(ToolJobAnchor anchor) throws Exception {
        ToolJobRedisCache redisCache = mock(ToolJobRedisCache.class);
        ToolJobAnchorService anchorService = mock(ToolJobAnchorService.class);
        ToolJobFinalizer finalizer = mock(ToolJobFinalizer.class);
        ToolJobResumeService resumeService = mock(ToolJobResumeService.class);
        ToolJobConfig config = new ToolJobConfig();
        DataAnalysisCapacityService capacityService =
                mock(DataAnalysisCapacityService.class);
        PythonSandboxService sandbox = mock(PythonSandboxService.class);
        when(redisCache.fetchDue(20)).thenReturn(Set.of("run-res"));
        when(anchorService.loadAnchor("run-res")).thenReturn(anchor);

        ToolJobReconciler reconciler = new ToolJobReconciler(
                redisCache, anchorService, finalizer, resumeService, config, capacityService);
        inject(reconciler, "sandboxService", sandbox);
        return new Fixture(reconciler, redisCache, anchorService, finalizer, sandbox);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(
            ToolJobReconciler reconciler,
            ToolJobRedisCache redisCache,
            ToolJobAnchorService anchorService,
            ToolJobFinalizer finalizer,
            PythonSandboxService sandbox) {
    }
}
