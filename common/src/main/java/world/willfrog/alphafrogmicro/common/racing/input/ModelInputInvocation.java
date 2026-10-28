package world.willfrog.alphafrogmicro.common.racing.input;

import java.util.Objects;
import java.util.UUID;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;

/** 一次逻辑调用的不可变输入上下文；每次HTTP发送各自保存独立尝试。 */
public record ModelInputInvocation(UUID logicalCallId, String endpointName,
                                   ModelInputSources sources, ModelInputStore store) {
    public ModelInputInvocation {
        Objects.requireNonNull(logicalCallId, "logicalCallId");
        Objects.requireNonNull(endpointName, "endpointName");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(store, "store");
    }

    public static ModelInputInvocation start(String endpointName, ModelInputSources sources,
                                              ModelInputStore store) {
        return new ModelInputInvocation(UUID.randomUUID(), endpointName, sources, store);
    }

    public ModelInputInvocation nextLogicalCall() {
        return start(endpointName, sources, store);
    }

    public ModelInputStore.Confirmation beforeSend(String finalJson) {
        ModelInputAttempt attempt;
        ModelInputStore.Confirmation saved;
        try {
            attempt = ModelInputAttempt.create(logicalCallId, endpointName, finalJson, sources);
            saved = store.storeConfirmed(attempt);
        } catch (RuntimeException failure) {
            ModelSendBlocked original = ModelSendBlocks.find(failure).orElse(null);
            if (original != null) {
                throw new ModelSendBlockedException(original.code(), original.reason(), original.racingFailure());
            }
            // 不把远程异常及正文带入模型库的自动重试和异常映射。
            throw unconfirmed();
        }
        if (saved == null || saved.recordId() == null || saved.recordId().isBlank()
                || !attempt.attemptId().equals(saved.attemptId())
                || !attempt.sha256().equals(saved.sha256()) || attempt.utf8Length() != saved.utf8Length()) {
            throw unconfirmed();
        }
        return saved;
    }

    private static ModelSendBlockedException unconfirmed() {
        return new ModelSendBlockedException(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                "本次实际模型输入尚未确认保存");
    }
}
