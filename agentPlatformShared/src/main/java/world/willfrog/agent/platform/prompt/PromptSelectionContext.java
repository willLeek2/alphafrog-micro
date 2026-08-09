package world.willfrog.agent.platform.prompt;

/**
 * D02 预留的 Prompt 版本选择输入。当前默认选择器不分流，但未来实现也只能在 Run 创建时调用一次。
 */
public record PromptSelectionContext(String runId, String userId, String experimentContext) {
}
