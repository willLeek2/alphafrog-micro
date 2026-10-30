package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

/** 实际集合版本定位，资源范围可以与正文所属范围不同。 */
public record PromptSetVersionSource(String environment, String scopeInstanceId,
                                      String promptSetId, long version) {
    public PromptSetVersionSource {
        if (environment == null || environment.isBlank() || scopeInstanceId == null
                || scopeInstanceId.isBlank() || promptSetId == null || promptSetId.isBlank() || version <= 0) {
            throw new ModelSendBlockedException(ModelSendBlocked.PROMPT_UNREADABLE,
                    "集合版本定位不完整");
        }
    }
}
