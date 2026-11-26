package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/** 按明确版本共同读取正文、变量声明与用途。 */
public record PromptVersionBody(
        PromptVersionSource source,
        PromptBodyType type,
        String body,
        List<PromptVariableDeclaration> variables) {

    public PromptVersionBody {
        if (source == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "source");
        }
        PromptTemplateRenderer.validate(type, body, variables);
        variables = List.copyOf(variables);
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(body));
        } catch (CharacterCodingException e) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "body");
        }
    }

    /** 严格校验UTF-8字节，保留原空白和BOM。 */
    public static PromptVersionBody fromUtf8(PromptVersionSource source, PromptBodyType type,
                                              byte[] body, List<PromptVariableDeclaration> variables) {
        if (body == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "body");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            return new PromptVersionBody(source, type, decoded, variables);
        } catch (CharacterCodingException e) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "body");
        }
    }

    /** 每次返回新字节数组，保留创建版本时的文本及换行。 */
    public byte[] bodyUtf8() {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    public PromptVersionBody requirePurpose(String expectedPurpose) {
        if (!source.purpose().equals(expectedPurpose)) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "purpose");
        }
        return this;
    }

    @Override
    public String toString() {
        return "PromptVersionBody[promptId=" + source.promptId() + ", version=" + source.version()
                + ", type=" + type + ", bodyLength=" + body.length()
                + ", variableCount=" + variables.size() + "]";
    }
}
