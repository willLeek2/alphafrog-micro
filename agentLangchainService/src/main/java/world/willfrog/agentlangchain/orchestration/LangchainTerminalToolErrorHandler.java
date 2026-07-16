package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import world.willfrog.agent.platform.dataanalysis.ExternalToolJobPendingException;

/**
 * LangChain4j 工具错误处理器中的“控制信号逃逸口”。
 *
 * <p>普通工具异常会转成文本交给模型自我修复；{@link ExternalToolJobPendingException} 却不是工具失败，
 * 而是“检查点即将落库、当前 worker 应退出”的栈展开信号。如果这里把它转成文本，模型会在旧 worker
 * 上继续推理，pipeline 永远收不到 suspended 结果，也就无法安全释放并发槽位。</p>
 */
final class LangchainTerminalToolErrorHandler {

    private LangchainTerminalToolErrorHandler() {
    }

    static ToolErrorHandlerResult handle(Throwable throwable, ToolErrorContext ignored) {
        // 先识别不能被模型消费的 Run 控制信号；它们必须沿 Java 调用栈回到 todo/pipeline 边界。
        if (isTerminalSignal(throwable)) {
            // 保留原 RuntimeException 身份，pending 异常中的 run/toolCall/attempt 才不会丢失。
            throw asRuntimeException(throwable);
        }
        // 只有可恢复的普通工具错误才降级成文本，允许 LLM 决定是否改参重试。
        return ToolErrorHandlerResult.text(firstNonBlankMessage(throwable));
    }

    static boolean isTerminalSignal(Throwable throwable) {
        // 框架反射层可能把真实异常包在多层 RuntimeException 中，因此逐层检查 cause。
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ExternalToolJobPendingException) {
                // 命中即停止，不再让下游 handler 将 pending 误包装成 tool error JSON。
                // 异常对象本身还携带三个恢复关联字段：runId、toolCallId、attempt。
                // todo executor 会把这些字段复制到 workflow result；pipeline 再与数据库 anchor
                // 交叉校验，而不是信任某一层单独提供的身份。多层校验可以阻止旧 attempt 的
                // 迟到异常为新任务写入错误检查点。
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && (message.startsWith("RUN_BUDGET_EXCEEDED:")
                    || message.startsWith("RUN_INTERRUPTED:"))) {
                // 预算与人工控制信号同样要求栈展开，但它们不会进入长工具 resume 状态机。
                // 保留在同一入口是为了确保所有“本轮 Run 必须停止”的信号都绕开 LLM 错误修复。
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
