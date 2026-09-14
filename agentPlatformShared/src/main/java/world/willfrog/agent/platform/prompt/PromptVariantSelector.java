package world.willfrog.agent.platform.prompt;

/** 单一 Prompt 版本选择接缝；D02 不实现百分比或多桶分流。 */
@FunctionalInterface
public interface PromptVariantSelector {

    SelectedVariant select(PromptSelectionContext context);

    record SelectedVariant(String bundleVersion, String variant) {
        public SelectedVariant {
            if (bundleVersion == null || bundleVersion.isBlank()) {
                throw new IllegalArgumentException("prompt bundleVersion must not be blank");
            }
            if (variant == null || variant.isBlank()) {
                throw new IllegalArgumentException("prompt variant must not be blank");
            }
        }
    }
}
