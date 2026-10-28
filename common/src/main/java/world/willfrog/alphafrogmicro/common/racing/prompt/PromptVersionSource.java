package world.willfrog.alphafrogmicro.common.racing.prompt;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/**
 * 正文的明确版本及实际选择来源。范围指正文所属范围，非当前调用服务的路由标签。
 * 可选定位保持为空；通用取值来源JSON原样保存，由其所属业务解释。
 */
public record PromptVersionSource(
        String environment,
        String scopeInstanceId,
        String promptId,
        long version,
        String purpose,
        String selectionId,
        String experimentValueId,
        String valueSourceJson,
        String selectionKey,
        PromptSetVersionSource promptSetVersion,
        String fragmentName,
        String experimentId) {

    public PromptVersionSource {
        requireText(environment, "environment");
        requireText(scopeInstanceId, "scopeInstanceId");
        requireText(promptId, "promptId");
        if (version <= 0) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, "version");
        }
        requireText(purpose, "purpose");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ModelSendBlockedException(PROMPT_UNREADABLE, field);
        }
    }
}
