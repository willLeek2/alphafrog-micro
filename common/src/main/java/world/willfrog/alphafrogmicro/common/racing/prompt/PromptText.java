package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/** 已组装文本及其中的真实指令版本来源；业务数据可以没有指令来源。 */
public record PromptText(String text, List<PromptVersionSource> sources) {

    public PromptText {
        if (text == null || sources == null || sources.stream().anyMatch(s -> s == null)) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "text");
        }
        sources = List.copyOf(sources);
    }

    public static PromptText instruction(String text, PromptVersionSource source) {
        if (source == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "source");
        }
        return new PromptText(text, List.of(source));
    }

    /** 用户原话、工具事实与模型输出按数据传递，不为它们编造指令版本。 */
    public static PromptText runtimeData(String text) {
        return new PromptText(text, List.of());
    }

    /** 按传入顺序拼接，不插入或裁剪字符，来源随相应文本保留。 */
    public static PromptText concat(List<PromptText> parts) {
        if (parts == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "parts");
        }
        StringBuilder text = new StringBuilder();
        List<PromptVersionSource> sources = new ArrayList<>();
        for (PromptText part : parts) {
            if (part == null) {
                throw new ModelSendBlockedException(PROMPT_UNREADABLE, "parts");
            }
            text.append(part.text());
            sources.addAll(part.sources());
        }
        return new PromptText(text.toString(), sources);
    }

    public static PromptText concat(PromptText... parts) {
        if (parts == null) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "parts");
        }
        return concat(Arrays.asList(parts));
    }

    public PromptText append(PromptText other) {
        return concat(this, other);
    }

    @Override
    public String toString() {
        return "PromptText[textLength=" + text.length() + ", sourceCount=" + sources.size() + "]";
    }
}
