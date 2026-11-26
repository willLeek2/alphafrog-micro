package world.willfrog.alphafrogmicro.common.racing.input;

import java.util.UUID;

/** 实现绑定已经确认的公共运行上下文，返回提交之后的保存确认。 */
@FunctionalInterface
public interface ModelInputStore {
    Confirmation storeConfirmed(ModelInputAttempt attempt);

    record Confirmation(String recordId, UUID attemptId, String sha256, long utf8Length) { }
}
