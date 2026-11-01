package world.willfrog.agentlangchain.tooljob;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.dataanalysis.DataAnalysisCapacityService;
import world.willfrog.agent.platform.dataanalysis.ToolJobAnchor;
import world.willfrog.agent.platform.finance.*;
import world.willfrog.agent.platform.model.AgentRunStatus;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.alphafrogmicro.sandbox.idl.SandboxResourceUsage;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies all terminal states flow through the shared formatter per MethodSpec V5.
 *
 * <p>Matrix:
 * <ul>
 *   <li>SUCCEEDED no finance → formatSuccess(raw stdout, [], [])</li>
 *   <li>SUCCEEDED + finance → processor → adapter.project → formatSuccess(ordinaryStdout, projectedResults, projectedNotices)</li>
 *   <li>FAILED/CANCELED no finance → formatFailure(raw stdout, stderr, failureDetail)</li>
 *   <li>FAILED/CANCELED + finance → snapshot guard → processor de-marker → formatFailure(clean stdout, sanitized stderr, failureDetail)</li>
 *   <li>RESULT_LOST → formatFailure("", "", PYTHON_RESULT_LOST)</li>
 *   <li>Snapshot missing with finance data → fail-closed (finance_snapshot_missing)</li>
 *   <li>Processor/adapter exception → fail-closed (finance_processing_failed)</li>
 *   <li>Stderr marker lines → sanitized before formatFailure</li>
 *   <li>ENVELOPE reentry → processor + adapter + formatter called idempotently</li>
 * </ul>
 */
class ToolJobFinalizerFinanceRecordTest {

    private static final String FROZEN_SNAPSHOT = """
            {"effectiveFinanceRecordConfig":{"enabled":true,"recordCountMax":10,\
            "recordMaxBytes":4096,"recordChannelMaxBytes":16384,"stdoutMaxBytes":65536,\
            "stderrMaxBytes":65536,"targetEnvironmentId":"env-1"},\
            "targetEnvironment":{"environmentId":"env-1","imageDigest":"sha256:abc",\
            "librarySetDigest":"sha256:def","packageApis":[{"name":"numpy","version":"1.24",\
            "apiVersion":"1"}],"inventoryComplete":true},\
            "sourceRevision":"sha256:xyz","limitsClamped":false}""";

    private static final String SUCCESS_JSON = """
            {"ok":true,"tool":"executePython","data":{"stdout":"rows=5\\nTotal return=0.1247\\n"},\
            "error":null}""";

    private static final String FAILURE_JSON = """
            {"ok":false,"tool":"executePython","data":{"stdout":"Traceback...","stderr":""},\
            "error":{"code":"PYTHON_EXECUTION_FAILED","message":"Sandbox FAILED",\
            "retryable":true,"action":"检查代码后重试"}}""";

    private static final String RESULT_LOST_JSON = """
            {"ok":false,"tool":"executePython","data":{},"error":{\
            "code":"PYTHON_RESULT_LOST","message":"沙箱结果永久丢失","retryable":false,\
            "action":"重新提交计算任务"}}""";













    // ---- helpers ----

    private static ToolJobFinalizer finalizerWithFinance(
            ToolJobAnchorService anchorService,
            FinanceRecordChannelProcessor processor,
            FinanceRecordChannelConfigLoader configLoader,
            FinanceToolResultFormatter formatter,
            FinanceResultModelAdapter adapter) throws Exception {
        ToolJobFinalizer f = ToolJobPromptTestFixture.configure(new ToolJobFinalizer(anchorService,
                mock(ToolJobRedisCache.class), mock(DataAnalysisCapacityService.class),
                mock(ToolJobResumeService.class), mock(ToolJobConfig.class),
                processor, configLoader, formatter, adapter));
        inject(f, "usageHook", usageHook());
        inject(f, "eventHook", eventHook());
        return f;
    }

    private static ToolJobUsageHook usageHook() {
        ToolJobUsageHook hook = mock(ToolJobUsageHook.class);
        when(hook.upsertUsage(any(), any())).thenReturn(true);
        return hook;
    }

    private static ToolJobEventHook eventHook() {
        ToolJobEventHook hook = mock(ToolJobEventHook.class);
        when(hook.emitTerminalEvent(any(), any())).thenReturn(true);
        return hook;
    }

    private static ToolJobAnchor basicAnchor(String runId, String toolCallId,
                                              int attempt, String taskId,
                                              boolean autoResume) {
        ToolJobAnchor a = new ToolJobAnchor();
        a.setOperationId(runId + ":" + toolCallId + ":" + attempt);
        a.setToolCallId(toolCallId);
        a.setAttempt(attempt);
        a.setTaskId(taskId);
        a.setAutoResume(autoResume);
        return a;
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
