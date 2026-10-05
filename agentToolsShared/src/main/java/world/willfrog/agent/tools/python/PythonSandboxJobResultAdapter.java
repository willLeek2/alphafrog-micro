package world.willfrog.agent.tools.python;

import world.willfrog.agent.platform.finance.FinanceRecordExtractionResult;
import world.willfrog.agent.platform.finance.FinanceRecordProcessingException;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;
import world.willfrog.agent.tools.finance.FinanceResultModelAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxJobResultAdapter;
import world.willfrog.agent.tools.sandboxjob.SandboxTerminalResultView;

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
        return "CANCELED".equals(result.statusName())
                ? "PYTHON_EXECUTION_CANCELED" : "PYTHON_EXECUTION_FAILED";
    }

    @Override
    public String formatTerminalResult(SandboxTerminalResultView result, Object formatContext) {
        FinanceRecordExtractionResult financeResult =
                formatContext == null ? null : (FinanceRecordExtractionResult) formatContext;
        String stdout = financeResult == null
                ? nvl(result.stdout()) : financeResult.ordinaryStdout();

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

        boolean retryable = Boolean.TRUE.equals(result.retryable());
        String errorCode = "CANCELED".equals(result.statusName())
                ? "PYTHON_EXECUTION_CANCELED" : "PYTHON_EXECUTION_FAILED";
        String message = "CANCELED".equals(result.statusName())
                ? "Python 执行已取消" : "Python 执行失败";
        String action = retryable
                ? "根据 stderr 修正代码或输入后重试"
                : "检查输入和资源限制；如问题持续，请联系管理员";
        return financeToolResultFormatter.formatFailure(
                stdout,
                nvl(result.stderr()),
                new FinanceToolResultFormatter.FailureDetail(
                        errorCode, message, retryable, action));
    }

    private static String nvl(String text) {
        return text == null ? "" : text;
    }
}
