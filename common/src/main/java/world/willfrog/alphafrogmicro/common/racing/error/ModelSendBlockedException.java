package world.willfrog.alphafrogmicro.common.racing.error;

/** 发送前检查或输入留存未确认时，终止当前模型调用。 */
public final class ModelSendBlockedException extends RuntimeException implements ModelSendBlocked {

    private final String code;
    private final String reason;

    /**
     * @param code 稳定原因码
     * @param reason 已排除正文、凭据与变量值的业务说明；异常消息只暴露原因码
     */
    public ModelSendBlockedException(String code, String reason) {
        super(requireCode(code));
        this.code = code;
        String safeReason = reason == null || reason.isBlank() ? "本次模型发送已停止" : reason;
        // 成员结果保存到 PostgreSQL jsonb；错误说明同样不能携带 NUL。
        this.reason = safeReason.replace("\0", "");
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String reason() {
        return reason;
    }

    private static String requireCode(String code) {
        if (!ModelSendBlocks.isBlockedCode(code)) {
            throw new IllegalArgumentException("未知的模型发送阻止原因码");
        }
        return code;
    }
}
