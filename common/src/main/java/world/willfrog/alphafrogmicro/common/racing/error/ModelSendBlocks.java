package world.willfrog.alphafrogmicro.common.racing.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 按明确的异常身份识别发送阻止原因，不从普通模型错误的文字推断。 */
public final class ModelSendBlocks {

    private ModelSendBlocks() {
    }

    /** 查找业务、反射或客户端包装之内的共同发送阻止原因。 */
    public static Optional<ModelSendBlocked> find(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (current instanceof ModelSendBlocked blocked) {
                return Optional.of(blocked);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    public static boolean isBlocked(Throwable throwable) {
        return find(throwable).isPresent();
    }

    /** 保留客户端最外层不可重试类型，其他包装转换回共同异常。 */
    public static void rethrowIfBlocked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException && failure instanceof ModelSendBlocked) {
            throw runtimeException;
        }
        ModelSendBlocked blocked = find(failure).orElse(null);
        if (blocked != null) {
            throw new ModelSendBlockedException(blocked.code(), blocked.reason());
        }
    }

    /** 节点与工作流使用相同字段保存发送被阻止的事实。 */
    public static Map<String, Object> failureMetadata(ModelSendBlocked blocked) {
        ModelSendBlocked safe = new ModelSendBlockedException(blocked.code(), blocked.reason());
        return Map.of("model_send_blocked", true,
                "model_send_block_code", safe.code(), "model_send_block_reason", safe.reason());
    }

    /** 持久结果使用同一组稳定码，恢复时不把普通工具错误当成发送阻止。 */
    public static boolean isBlockedCode(String code) {
        if (code == null) {
            return false;
        }
        return switch (code) {
            case ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                    ModelSendBlocked.PROMPT_UNREADABLE,
                    ModelSendBlocked.PROMPT_VARIABLE_INVALID,
                    ModelSendBlocked.PROMPT_SOURCE_MISSING -> true;
            default -> false;
        };
    }
}
