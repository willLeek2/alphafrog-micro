package world.willfrog.alphafrogmicro.common.racing.input;

import java.util.List;
import java.util.Objects;
import world.willfrog.alphafrogmicro.common.racing.prompt.PromptVersionSource;

/** 实际请求中一个消息、工具定义或输出结构的来源；位置采用 JSON Pointer。 */
public record ModelInputSource(String jsonPointer, Origin origin, List<PromptVersionSource> versions) {
    public enum Origin { APPLICATION_INSTRUCTION, RUNTIME_DATA }

    public ModelInputSource {
        if (jsonPointer == null || !jsonPointer.startsWith("/")) {
            throw new IllegalArgumentException("来源必须包含请求中的明确位置");
        }
        Objects.requireNonNull(origin, "origin");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        if (origin == Origin.APPLICATION_INSTRUCTION && versions.isEmpty()) {
            throw new IllegalArgumentException("应用指令必须包含具体版本来源");
        }
        if (origin == Origin.RUNTIME_DATA && !versions.isEmpty()) {
            throw new IllegalArgumentException("运行数据不能登记为应用指令版本");
        }
    }
}
