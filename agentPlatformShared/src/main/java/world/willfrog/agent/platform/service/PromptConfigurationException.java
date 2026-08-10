package world.willfrog.agent.platform.service;

/**
 * Prompt 权威正文或投影配置不可用时的稳定异常。
 *
 * <p>异常消息使用稳定前缀，方便 Run 统一失败出口和日志/指标把 Prompt 配置失败
 * 与普通模型错误区分开；正文中不放完整 Prompt，避免日志泄露和高基数。</p>
 */
final class PromptConfigurationException extends IllegalStateException {

    private final String reason;

    PromptConfigurationException(String reason, String detail) {
        super("PROMPT_CONFIGURATION_INVALID[" + reason + "]: " + detail);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
