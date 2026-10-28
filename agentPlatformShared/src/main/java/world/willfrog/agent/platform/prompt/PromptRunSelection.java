package world.willfrog.agent.platform.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlockedException;
import world.willfrog.racing.idl.PromptScope;
import world.willfrog.racing.idl.PromptSelection;
import world.willfrog.racing.idl.ValueSourceKind;

import static world.willfrog.alphafrogmicro.common.racing.error.ModelSendBlocked.PROMPT_UNREADABLE;

/**
 * 根运行的提示词定位。旧摘要仅供历史解析，真实集合选择保存完整平台定位而不保存正文。
 */
public record PromptRunSelection(
        int schemaVersion,
        String bundleVersion,
        String variant,
        String bundleDigest,
        String capabilityCatalogDigest,
        LocalDate referenceDate,
        String racingSelectionJson) {

    public static final int SCHEMA_VERSION = 1;
    public static final int RACING_SCHEMA_VERSION = 2;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 保留旧摘要结构的构造入口，不将摘要转换成正文版本。 */
    public PromptRunSelection(int schemaVersion, String bundleVersion, String variant, String bundleDigest,
                              String capabilityCatalogDigest, LocalDate referenceDate) {
        this(schemaVersion, bundleVersion, variant, bundleDigest, capabilityCatalogDigest, referenceDate, null);
    }

    public PromptRunSelection {
        if (schemaVersion == SCHEMA_VERSION) {
            require(bundleVersion, "bundleVersion");
            require(variant, "variant");
            require(bundleDigest, "bundleDigest");
            require(capabilityCatalogDigest, "capabilityCatalogDigest");
            if (racingSelectionJson != null) throw blocked("旧摘要结构不能混入真实集合选择");
        } else if (schemaVersion == RACING_SCHEMA_VERSION) {
            if (referenceDate == null) throw blocked("真实集合选择缺少固定日期");
            if (bundleVersion != null || variant != null || bundleDigest != null || capabilityCatalogDigest != null) {
                throw blocked("真实集合选择不能使用旧摘要字段");
            }
            PromptSelection selection = parseRacing(racingSelectionJson);
            validateRacing(selection);
            racingSelectionJson = printRacing(selection);
        } else {
            throw new IllegalArgumentException("unsupported prompt selection schema: " + schemaVersion);
        }
        if (referenceDate == null) {
            throw new IllegalArgumentException("prompt referenceDate must not be null");
        }
    }

    public Map<String, Object> toExtMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", schemaVersion);
        if (schemaVersion == RACING_SCHEMA_VERSION) {
            out.put("reference_date", referenceDate.toString());
            out.put("racing_selection", racingNode());
            return out;
        }
        out.put("bundle_version", bundleVersion);
        out.put("variant", variant);
        out.put("bundle_digest", bundleDigest);
        out.put("capability_catalog_digest", capabilityCatalogDigest);
        out.put("reference_date", referenceDate.toString());
        return out;
    }

    public Map<String, Object> toObservationMap() {
        if (schemaVersion == RACING_SCHEMA_VERSION) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("prompt_selection_schema", schemaVersion);
            out.put("prompt_racing_selection", racingNode());
            out.put("prompt_reference_date", referenceDate.toString());
            return out;
        }
        return Map.of(
                "prompt_bundle_version", bundleVersion,
                "prompt_variant", variant,
                "prompt_bundle_digest", bundleDigest,
                "prompt_capability_catalog_digest", capabilityCatalogDigest,
                "prompt_reference_date", referenceDate.toString());
    }

    /** 参数是ext中的prompt_selection对象；未知结构不读取当前默认作为替代。 */
    public static PromptRunSelection fromExt(JsonNode node) {
        try {
            if (node == null || !node.isObject() || !node.path("schema_version").isIntegralNumber()
                    || !node.path("schema_version").canConvertToInt()) {
                throw blocked("根运行提示词选择结构无效");
            }
            int schema = node.get("schema_version").intValue();
            LocalDate date = LocalDate.parse(text(node, "reference_date"));
            if (schema == SCHEMA_VERSION) {
                return new PromptRunSelection(schema, text(node, "bundle_version"), text(node, "variant"),
                        text(node, "bundle_digest"), text(node, "capability_catalog_digest"), date);
            }
            if (schema == RACING_SCHEMA_VERSION) {
                JsonNode selection = node.get("racing_selection");
                if (selection == null || !selection.isObject()) throw blocked("根运行缺少真实平台选择");
                for (String field : new String[] {"bundle_version", "variant", "bundle_digest", "capability_catalog_digest"}) {
                    if (node.hasNonNull(field)) throw blocked("真实集合选择混入旧摘要字段");
                }
                return new PromptRunSelection(schema, null, null, null, null, date, selection.toString());
            }
            throw blocked("未知提示词选择结构需要补录");
        } catch (ModelSendBlockedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw blocked("根运行提示词选择无法解析");
        }
    }

    public static PromptRunSelection fromRacing(PromptSelection selection, LocalDate referenceDate) {
        if (selection == null || referenceDate == null) throw blocked("真实集合选择缺少定位或固定日期");
        return new PromptRunSelection(RACING_SCHEMA_VERSION, null, null, null, null,
                referenceDate, printRacing(selection));
    }

    public PromptSelection racingSelection() {
        if (schemaVersion != RACING_SCHEMA_VERSION) throw blocked("旧提示词摘要尚未补录真实正文版本");
        return parseRacing(racingSelectionJson);
    }

    @Override
    public String toString() {
        return "PromptRunSelection[schemaVersion=" + schemaVersion + ", referenceDate=" + referenceDate + "]";
    }

    private JsonNode racingNode() {
        try {
            return JSON.readTree(racingSelectionJson);
        } catch (Exception failure) {
            throw blocked("根运行提示词选择无法序列化");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw blocked("根运行提示词选择缺少字段：" + field);
        }
        return value.textValue();
    }

    private static PromptSelection parseRacing(String json) {
        try {
            if (json == null || json.isBlank() || !JSON.readTree(json).isObject()) {
                throw blocked("真实集合选择必须为JSON对象");
            }
            PromptSelection.Builder builder = PromptSelection.newBuilder();
            JsonFormat.parser().merge(json, builder);
            return builder.build();
        } catch (ModelSendBlockedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw blocked("真实集合选择JSON无法解析");
        }
    }

    private static String printRacing(PromptSelection selection) {
        try {
            return JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace().print(selection);
        } catch (Exception failure) {
            throw blocked("真实集合选择无法序列化");
        }
    }

    private static void validateRacing(PromptSelection selection) {
        check(selection.hasRoot() && selection.hasTarget(), "真实集合选择缺少根或目标");
        checkText(selection.getRoot().getOwnerNamespace());
        checkText(selection.getRoot().getOwnerKind());
        checkText(selection.getRoot().getOwnerScopeInstanceId());
        checkText(selection.getRoot().getRootId());
        checkText(selection.getTarget().getEnvironment());
        checkText(selection.getTarget().getTargetScopeInstanceId());
        checkText(selection.getTarget().getTargetDeploymentId());
        // 主范围路由可为空；构建代际不参与逻辑目标的唯一身份。
        checkText(selection.getTarget().getExpectedTargetGenerationId());
        checkText(selection.getSelectionId());
        checkText(selection.getSelectionKey());
        check(selection.hasSelectedTarget() && selection.getSelectedTarget().hasPromptSetVersion(),
                "根运行必须选择明确集合版本");
        var set = selection.getSelectedTarget().getPromptSetVersion();
        check(set.hasScope(), "真实集合选择缺少资源范围");
        checkScope(set.getScope());
        checkText(set.getPromptSetId());
        check(set.getVersion() > 0, "真实集合版本必须为正数");
        check(selection.hasOrigin() && selection.getOrigin().hasSourceScope(), "真实集合选择缺少实际来源");
        checkScope(selection.getOrigin().getSourceScope());
        checkText(selection.getOrigin().getBusinessName());
        check(selection.getOrigin().getDefaultRevision() > 0, "真实集合选择缺少默认修订");
        check(selection.getOrigin().hasValueSource()
                && selection.getOrigin().getValueSource().getKind() == ValueSourceKind.ORDINARY_DEFAULT,
                "真实集合选择缺少普通默认来源");
        var source = selection.getOrigin().getValueSource();
        if (source.hasConfigurationVersion()) {
            check(source.getConfigurationVersion() > 0, "真实集合选择包含无效配置版本");
        }
        if (source.hasCandidateId()) checkText(source.getCandidateId());
    }

    private static void checkScope(PromptScope scope) {
        checkText(scope.getEnvironment());
        checkText(scope.getScopeInstanceId());
    }

    private static void checkText(String value) {
        check(value != null && !value.isBlank(), "真实集合选择的标识不完整");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw blocked(reason);
    }

    private static ModelSendBlockedException blocked(String reason) {
        return new ModelSendBlockedException(PROMPT_UNREADABLE, reason);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("prompt " + field + " must not be blank");
        }
    }
}
