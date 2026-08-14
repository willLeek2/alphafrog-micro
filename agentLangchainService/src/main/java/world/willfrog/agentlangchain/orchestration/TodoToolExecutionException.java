package world.willfrog.agentlangchain.orchestration;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 普通工具执行失败从 LC4j 工具循环逃逸到 Todo 边界时携带的稳定上下文。
 */
final class TodoToolExecutionException extends RuntimeException {

    private final String toolName;
    private final String toolArguments;

    TodoToolExecutionException(Throwable cause, ToolExecutionRequest request) {
        super(firstNonBlankMessage(cause), cause);
        this.toolName = request == null ? null : request.name();
        this.toolArguments = request == null ? null : request.arguments();
    }

    String getToolName() {
        return toolName;
    }

    String getToolArguments() {
        return toolArguments;
    }

    private static String firstNonBlankMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "tool_execution_failed";
    }
}
