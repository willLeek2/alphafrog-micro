package world.willfrog.alphafrogmicro.common.racing.input;

import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

/** 一个实际发送尝试的原始输入；保存确认重试必须复用这个对象。 */
public record ModelInputAttempt(UUID logicalCallId, UUID attemptId, String endpointName,
                                String bodyJson, long utf8Length, String sha256,
                                ModelInputSources sources) {
    public ModelInputAttempt {
        Objects.requireNonNull(logicalCallId, "logicalCallId");
        Objects.requireNonNull(attemptId, "attemptId");
        if (endpointName == null || endpointName.isBlank()) {
            throw new IllegalArgumentException("必须声明模型端点名称");
        }
        Objects.requireNonNull(bodyJson, "bodyJson");
        Objects.requireNonNull(sources, "sources");
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(bodyJson));
        } catch (CharacterCodingException failure) {
            throw new ModelSendBlockedException(ModelSendBlocked.MODEL_INPUT_UNCONFIRMED,
                    "模型输入包含无法按UTF-8保存的字符");
        }
        byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        if (utf8Length != bytes.length || !digest(bytes).equals(sha256)) {
            throw new IllegalArgumentException("模型输入长度或摘要不符");
        }
        sources.validate(bodyJson);
    }

    public static ModelInputAttempt create(UUID logicalCallId, String endpointName,
                                           String bodyJson, ModelInputSources sources) {
        byte[] bytes = Objects.requireNonNull(bodyJson, "bodyJson").getBytes(StandardCharsets.UTF_8);
        return new ModelInputAttempt(logicalCallId, UUID.randomUUID(), endpointName, bodyJson,
                bytes.length, digest(bytes), sources);
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256", impossible);
        }
    }

    @Override
    public String toString() {
        return "ModelInputAttempt[logicalCallId=" + logicalCallId + ", attemptId=" + attemptId
                + ", utf8Length=" + utf8Length + ", sha256=" + sha256 + "]";
    }
}
