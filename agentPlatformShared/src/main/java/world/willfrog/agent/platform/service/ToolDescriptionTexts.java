package world.willfrog.agent.platform.service;

/**
 * 工具说明权威正文的对外入口。目录构建只从这里取描述，Java 注解不再承载写给模型的段落。
 */
public final class ToolDescriptionTexts {

    private ToolDescriptionTexts() {
    }

    public static String require(String toolName) {
        return PromptAuthority.shared().requireToolDescription(toolName);
    }
}
