package world.willfrog.agent.platform.input;

import dev.langchain4j.exception.NonRetriableException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;

/** 保持最外层不可重试类型，避免SDK沿原HTTP错误重新分类。 */
public final class BlockedModelSendException extends NonRetriableException implements ModelSendBlocked {
    private final String code;
    private final String reason;

    public BlockedModelSendException(String code, String reason) {
        super(code);
        if (!ModelSendBlocks.isBlockedCode(code)) {
            throw new IllegalArgumentException("未知模型发送阻止原因");
        }
        this.code = code;
        this.reason = reason;
    }

    public static BlockedModelSendException from(RuntimeException failure) {
        ModelSendBlocked blocked = ModelSendBlocks.find(failure).orElse(null);
        return blocked == null
                ? new BlockedModelSendException(MODEL_INPUT_UNCONFIRMED, "本次实际模型输入尚未确认保存")
                : new BlockedModelSendException(blocked.code(), blocked.reason());
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String reason() {
        return reason;
    }
}
