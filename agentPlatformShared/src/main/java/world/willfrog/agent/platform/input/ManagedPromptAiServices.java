package world.willfrog.agent.platform.input;

import dev.langchain4j.service.AiServices;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptText;

import java.util.Objects;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_SOURCE_MISSING;

/**
 * 将已渲染正文装配到未声明SystemMessage注解的托管接口，跳过SDK的再次模板解析。
 * 调用方另外登记同一份正文的输入来源并执行发送前保存，本类只负责系统消息装配。
 */
public final class ManagedPromptAiServices {
    private ManagedPromptAiServices() {
    }

    /** 保留现有请求转换；SDK在系统消息转换后直接使用SystemMessage.from构造消息。 */
    public static <T> AiServices<T> withSystemMessage(AiServices<T> builder, PromptText alreadyRendered) {
        if (alreadyRendered == null || alreadyRendered.sources() == null || alreadyRendered.sources().isEmpty()) {
            throw new ModelSendBlockedException(PROMPT_SOURCE_MISSING, "托管系统正文缺少明确版本来源");
        }
        Objects.requireNonNull(builder, "builder");
        String text = alreadyRendered.text();
        return builder.systemMessageProvider(ignored -> null)
                .systemMessageTransformer(ignored -> text);
    }
}
