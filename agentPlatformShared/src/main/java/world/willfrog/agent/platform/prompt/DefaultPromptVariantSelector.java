package world.willfrog.agent.platform.prompt;

import org.springframework.stereotype.Component;

/** D02 默认单版本实现：没有流量百分比、实验桶或在线切换。 */
@Component
public final class DefaultPromptVariantSelector implements PromptVariantSelector {

    public static final String BUNDLE_VERSION = "default-v1";
    public static final String VARIANT = "control";

    @Override
    public SelectedVariant select(PromptSelectionContext context) {
        return new SelectedVariant(BUNDLE_VERSION, VARIANT);
    }
}
