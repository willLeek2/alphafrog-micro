package world.willfrog.agent.platform.dataanalysis;

import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocks;

/** 终态资源清理仍可继续；不可交付的模型正文不生成来源或替代建议。 */
public final class TerminalModelSendBlock {
    public static final String FIELD = "terminalModelSendBlock";
    private TerminalModelSendBlock() { }

    public static ModelSendBlockedException read(ToolJobAnchor anchor) {
        return anchor == null ? null : ModelSendBlocks.fromFailureMetadata(anchor.getTerminalModelSendBlock()).orElse(null);
    }

    public static void record(ToolJobAnchor anchor, ModelSendBlocked blocked) {
        anchor.setTerminalModelSendBlock(ModelSendBlocks.failureMetadata(blocked));
        anchor.setTerminalBusinessSuccess(false);
        anchor.setTerminalErrorCode(blocked.code());
        anchor.setFinalizerError(blocked.code());
        anchor.setTerminalResultPreview(null);
        anchor.setTerminalPromptSourcesJson(null);
    }
}
