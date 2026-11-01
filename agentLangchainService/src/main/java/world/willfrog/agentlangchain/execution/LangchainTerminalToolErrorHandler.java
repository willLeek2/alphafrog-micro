package world.willfrog.agentlangchain.execution;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import world.willfrog.agent.platform.exception.AgentRunControlSignal;
import world.willfrog.agent.platform.exception.RunBudgetException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;

/**
 * 保留控制、预算及模型发送阻止异常，交由节点或工作流处理。
 * 普通工具异常转成文本供模型纠正；控制信号沿调用栈返回，使工作进程按挂起或中断状态退出。
 */
final class LangchainTerminalToolErrorHandler {

    private LangchainTerminalToolErrorHandler() {
    }

    static ToolErrorHandlerResult handle(Throwable throwable, ToolErrorContext context) {
        // 控制、预算和发送阻止异常沿调用栈返回节点或工作流。
        if (isTerminalSignal(throwable)) {
            // 保留原 RuntimeException 身份，pending 异常中的 run/toolCall/attempt 才不会丢失。
            throw asRuntimeException(throwable);
        }
        // 普通工具错误必须先回到 Todo 边界，由失败分类和工具安全声明共同决定是否允许一次语义重试。
        // 不能在 LC4j 内部悄悄无限改参，否则重试次数和副作用都无法审计。
        throw new TodoToolExecutionException(
                throwable, context == null ? null : context.toolExecutionRequest());
    }

    static boolean isTerminalSignal(Throwable throwable) {
        if (ModelSendBlocks.isBlocked(throwable)) {
            return true;
        }
        // 框架反射层可能把真实异常包在多层 RuntimeException 中，因此逐层检查 cause。
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AgentRunControlSignal) {
                // 控制流信号：转后台、取消、暂停。命中即停止，不能交给模型当工具错误文本。
                return true;
            }
            if (current instanceof RunBudgetException) {
                // 额度不足是资源信号，同样要求栈展开，但不进控制流家族。
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && (message.startsWith("RUN_BUDGET_EXCEEDED:")
                    || message.startsWith("RUN_INTERRUPTED:"))) {
                // 兼容仍按消息前缀抛出的旧包装。
                return true;
            }
            current = current.getCause();
        }
        // 没有控制信号时才允许普通错误文本化。
        return false;
    }

    private static RuntimeException asRuntimeException(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(firstNonBlankMessage(throwable), throwable);
    }

    private static String firstNonBlankMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return throwable == null ? "tool_execution_failed" : throwable.getClass().getSimpleName();
    }
}
