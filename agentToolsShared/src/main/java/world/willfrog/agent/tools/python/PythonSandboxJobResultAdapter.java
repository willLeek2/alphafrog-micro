package world.willfrog.agent.tools.python;

import world.willfrog.agent.platform.finance.FinanceRecordDecoder;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionResult;
import world.willfrog.agent.platform.finance.FinanceRecordProcessingException;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResultAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;
import world.willfrog.alphafrogmicro.sandbox.idl.TaskResultResponse;

import java.util.List;

/**
 * executePython 的结果适配器：终态结果到模型文本的唯一出口。
 *
 * <p>格式化逻辑从原 {@code PythonSandboxTools.formatResult} 逐字搬入：进程 exit code 为 0
 * 时走成功包装；非零时仍附带 stdout/stderr 到失败详情，方便模型读取输出内容的同时
 * 识别执行失败。finance 记录通道的提取结果经 formatContext 传入（没有通道时为 null）。</p>
 */
public final class PythonSandboxJobResultAdapter implements SandboxJobResultAdapter {

    private final FinanceToolResultFormatter financeToolResultFormatter;
    private final FinanceResultModelAdapter financeResultModelAdapter;

    public PythonSandboxJobResultAdapter(
            FinanceToolResultFormatter financeToolResultFormatter,
            FinanceResultModelAdapter financeResultModelAdapter) {
        this.financeToolResultFormatter = financeToolResultFormatter;
        this.financeResultModelAdapter = financeResultModelAdapter;
    }

    /** 业务成功判定：SUCCEEDED 且退出码为 0（与结果接收侧的既有口径一致）。 */
    @Override
    public boolean isSuccess(SandboxTerminalResultView result) {
        return "SUCCEEDED".equals(result.statusName())
                && result.exitCode() != null && result.exitCode() == 0;
    }

    @Override
    public String errorCodeOf(SandboxTerminalResultView result) {
        if (isWorkspaceDirty(result.statusName(), result.nativePayload())) {
            return "WORKSPACE_DIRTY";
        }
        if ("RESULT_LOST".equals(result.statusName())) {
            return "PYTHON_RESULT_LOST";
        }
        return "CANCELED".equals(result.statusName())
                ? "PYTHON_EXECUTION_CANCELED" : "PYTHON_EXECUTION_FAILED";
    }

    /** 排队任务因同一工作区前一个任务失败而被跳过时，沙箱在资源用量中给出明确原因。 */
    public static boolean isWorkspaceDirty(String statusName, Object nativePayload) {
        return "FAILED".equals(statusName)
                && nativePayload instanceof TaskResultResponse response
                && response.hasResourceUsage()
                && "WORKSPACE_DIRTY".equals(response.getResourceUsage().getExitReason());
    }

    @Override
    public String formatTerminalResult(SandboxTerminalResultView result, Object formatContext) {
        // 普通收尾保留既有失败提示；分类始终由本适配器决定，提示不能覆盖错误码。
        FinanceRecordExtractionResult financeResult = formatContext instanceof FinanceRecordExtractionResult extraction
                ? extraction : null;
        FinanceToolResultFormatter.FailureDetail guidance = formatContext instanceof FinanceToolResultFormatter.FailureDetail detail
                ? detail : null;
        if (formatContext != null && financeResult == null && guidance == null) {
            throw new IllegalArgumentException("Unsupported Python result format context");
        }
        String stdout = financeResult == null
                ? nvl(result.stdout()) : financeResult.ordinaryStdout();
        // stderr 的标记隔离单点：financeResult 非空时用 processor 的剥离结果；
        // 为空时按标记族前缀静态剥离——report() 的返回值可能被用户代码再打印到 stderr，
        // 那种形态标准输出干净、不会进入提取流程，这里只做隔离兜底，不做提取。
        // 剥离方法 null 进 null 出，这里包 nvl 保持与旧口径一致（null 归一成空串）。
        String stderr = financeResult != null
                ? financeResult.ordinaryStderr()
                : nvl(FinanceRecordDecoder.stripMarkerLines(result.stderr()));

        if ("SUCCEEDED".equals(result.statusName())
                && result.exitCode() != null && result.exitCode() == 0) {
            if (financeResult == null) {
                return financeToolResultFormatter.formatSuccess(stdout, List.of(), List.of());
            }
            if (financeResultModelAdapter == null) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RESULT_PROJECTOR_UNAVAILABLE",
                        "Finance result projector is unavailable");
            }
            FinanceResultModelAdapter.ProjectionBatch projection =
                    financeResultModelAdapter.project(financeResult);
            return financeToolResultFormatter.formatSuccess(
                    stdout, projection.results(), projection.notices());
        }

        boolean dirty = isWorkspaceDirty(result.statusName(), result.nativePayload());
        boolean resultLost = "RESULT_LOST".equals(result.statusName());
        boolean retryable = !dirty && !resultLost && Boolean.TRUE.equals(result.retryable());
        String errorCode = errorCodeOf(result);
        String message = "RESULT_LOST".equals(result.statusName()) ? "沙箱结果永久丢失"
                : dirty ? "同一 Run 的前一个任务失败，工作区已标脏；本任务未执行"
                : "CANCELED".equals(result.statusName())
                ? "Python 执行已取消" : "Python 执行失败";
        String action = resultLost ? "重新提交计算任务"
                : dirty ? "检查前一个任务的失败原因后，使用新的 Run 重新执行"
                : retryable ? "根据 stderr 修正代码或输入后重试"
                : "检查输入和资源限制；如问题持续，请联系管理员";
        return financeToolResultFormatter.formatFailure(
                stdout,
                stderr,
                new FinanceToolResultFormatter.FailureDetail(errorCode,
                        guidance == null ? message : guidance.message(),
                        retryable, guidance == null ? action : guidance.action()));
    }

    private static String nvl(String text) {
        return text == null ? "" : text;
    }
}
