package world.willfrog.agentlangchain.execution;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import world.willfrog.agent.platform.exception.AgentRunControlSignal;
import world.willfrog.agent.platform.exception.RunBudgetException;

/**
 * LangChain4j 工具错误处理器中的“控制信号逃逸口”。
 *
 * <p>普通工具异常会转成文本交给模型自我修复；{@link AgentRunControlSignal} 不是工具失败，
 * 而是“当前 worker 应退出”的栈展开信号。额度超限同样要绕开模型修复，但走资源类型，不进这个家族。
 * 如果这里把信号转成文本，模型会在旧 worker 上继续推理，pipeline 收不到挂起或中断结果。</p>
 */
final class LangchainTerminalToolErrorHandler {

    private LangchainTerminalToolErrorHandler() {
    }

    static ToolErrorHandlerResult handle(Throwable throwable, ToolErrorContext context) {
        // 先识别不能被模型消费的 Run 控制信号；它们必须沿 Java 调用栈回到 todo/pipeline 边界。
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
