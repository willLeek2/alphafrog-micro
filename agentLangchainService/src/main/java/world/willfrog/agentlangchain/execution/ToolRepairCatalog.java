package world.willfrog.agentlangchain.execution;

import org.springframework.stereotype.Component;
import world.willfrog.agentlangchain.tooljob.ToolJobResumeContext;

import java.util.List;
import java.util.Optional;

/**
 * 当前工具的显式修复策略清单，与 {@link ToolRetrySafetyCatalog} 对称。
 *
 * <p>这里逐项登记已经确认的修复处理器。新增工具如果没有同步登记，失败终态
 * 会自然落到「不修复、按普通工具失败处理」。</p>
 */
@Component
public class ToolRepairCatalog {

    private final List<ToolRepairHandler> handlers;

    public ToolRepairCatalog(List<ToolRepairHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public static ToolRepairCatalog pythonDefaults() {
        return new ToolRepairCatalog(List.of(new PythonSandboxRepairHandler()));
    }

    public Optional<ToolRepairHandler> handlerForFailure(ToolJobResumeContext failure) {
        if (failure == null) {
            return Optional.empty();
        }
        return handlers.stream().filter(handler -> handler.supports(failure)).findFirst();
    }

    public Optional<ToolRepairHandler> handlerForRepairRound(ToolJobResumeContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return handlers.stream().filter(handler -> handler.isRepairRound(context)).findFirst();
    }

    public Optional<ToolRepairHandler> handlerForTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return handlers.stream().filter(handler -> toolName.equals(handler.toolName())).findFirst();
    }

    public List<ToolRepairHandler> handlers() {
        return handlers;
    }
}
