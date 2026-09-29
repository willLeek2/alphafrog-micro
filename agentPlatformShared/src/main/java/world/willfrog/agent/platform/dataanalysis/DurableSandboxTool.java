package world.willfrog.agent.platform.dataanalysis;

import java.util.Optional;

/**
 * 沙箱后台长工具的描述符注册表。
 *
 * <p>后台长工具（当前只有 executePython）与普通同步工具的区别在于：调用前要确认
 * Run 已有持久化的节点身份、终态事件要走幂等去重、同步完成时要清理派发凭证。
 * 工具路由层原来用字符串相等判断写死 executePython；新增工具（如 SQL 取数工具）
 * 时在这里登记，路由层改为按描述符查询，不再新增硬编码。</p>
 */
public enum DurableSandboxTool {

    EXECUTE_PYTHON(ToolJobAnchor.EXECUTE_PYTHON_TOOL);

    private final String toolName;

    DurableSandboxTool(String toolName) {
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }

    /** 按工具名查描述符；查不到表示该工具不是沙箱后台长工具。 */
    public static Optional<DurableSandboxTool> fromToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        for (DurableSandboxTool tool : values()) {
            if (tool.toolName.equals(toolName)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }
}
