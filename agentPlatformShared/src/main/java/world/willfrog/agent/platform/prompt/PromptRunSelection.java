package world.willfrog.agent.platform.prompt;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 Agent Run 冻结的 Prompt 选择。正文不落库，只保存低体量身份和摘要。
 */
public record PromptRunSelection(
        int schemaVersion,
        String bundleVersion,
        String variant,
        String bundleDigest,
        String capabilityCatalogDigest,
        LocalDate referenceDate) {

    public static final int SCHEMA_VERSION = 1;

    public PromptRunSelection {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported prompt selection schema: " + schemaVersion);
        }
        require(bundleVersion, "bundleVersion");
        require(variant, "variant");
        require(bundleDigest, "bundleDigest");
        require(capabilityCatalogDigest, "capabilityCatalogDigest");
        if (referenceDate == null) {
            throw new IllegalArgumentException("prompt referenceDate must not be null");
        }
    }

    public Map<String, Object> toExtMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", schemaVersion);
        out.put("bundle_version", bundleVersion);
        out.put("variant", variant);
        out.put("bundle_digest", bundleDigest);
        out.put("capability_catalog_digest", capabilityCatalogDigest);
        out.put("reference_date", referenceDate.toString());
        return out;
    }

    public Map<String, Object> toObservationMap() {
        return Map.of(
                "prompt_bundle_version", bundleVersion,
                "prompt_variant", variant,
                "prompt_bundle_digest", bundleDigest,
                "prompt_capability_catalog_digest", capabilityCatalogDigest,
                "prompt_reference_date", referenceDate.toString());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("prompt " + field + " must not be blank");
        }
    }
}
