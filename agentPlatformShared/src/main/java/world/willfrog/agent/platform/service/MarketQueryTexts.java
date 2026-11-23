package world.willfrog.agent.platform.service;

/**
 * 非管理员运行对模型隐藏 executeQuery。
 * searchStock 仍保留，但说明里不再指向这个工具。
 * 管理员运行继续使用工具名对应的权威说明。
 */
public final class MarketQueryTexts {

    private MarketQueryTexts() {
    }

    public static boolean omitted(String toolName) {
        return "executeQuery".equals(toolName);
    }

    public static boolean rewrites(String toolName) {
        return "searchStock".equals(toolName);
    }

    public static String hiddenDescription(String toolName) {
        return PromptAuthority.shared().readAuthorityText(
                "prompts/tools/market-query-hidden/descriptions/" + toolName + ".txt");
    }

    public static String hiddenCapability(String toolName) {
        return PromptAuthority.shared().readAuthorityText(
                "prompts/tools/market-query-hidden/capabilities/" + toolName + ".txt");
    }
}
