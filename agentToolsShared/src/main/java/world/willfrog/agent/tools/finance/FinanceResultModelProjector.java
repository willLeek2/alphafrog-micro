package world.willfrog.agent.tools.finance;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 金融计算结果的共享模型投影器。其他团队通过本类把内部记录转换为最小模型投影。
 *
 * <p>投影规则（已与技术负责人对齐）：</p>
 * <ul>
 *   <li>判型只信可信 {@code declaredEvidence}：仅 {@code LIBRARY_CALL_DECLARED} 走 canonical narrative；两种 CUSTOM 一律走 {@code formulaDescription}（自定义记录携带完整三元组也不例外）；</li>
 *   <li>数组参数占位符只渲染为“N 个周期收益率样本”（长度摘要），永不暴露内容；</li>
 *   <li>标量参数渲染实际值；</li>
 *   <li>未解析占位符 / 类型不匹配 / 数组或对象直接输出 → 该记录不可投影（返回空）；</li>
 *   <li>{@code displayFormat} 仅用于后续 Markdown 层，本层 {@code value} 保持原始数值；</li>
 *   <li>输出中永远不包含 digest / environment / evidence / identity / version 等后台字段。</li>
 * </ul>
 */
@Component
@Slf4j
public class FinanceResultModelProjector {

    private static final int MAX_HOW_CALCULATED_LENGTH = 2048;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    private final FinanceMethodSpecCatalog catalog;

    public FinanceResultModelProjector(FinanceMethodSpecCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 把内部结果输入投影为模型可见的最小输出。
     *
     * @param in 投影输入
     * @return 若不可投影则返回 Optional.empty()
     */
    public Optional<FinanceResultProjection> project(FinanceResultProjectionInput in) {
        if (in == null) {
            return Optional.empty();
        }
        if (!in.renderable()) {
            return Optional.empty();
        }

        boolean methodIdBlank = isBlank(in.methodId());
        boolean versionBlank = isBlank(in.methodVersion());
        boolean specDigestBlank = isBlank(in.specDigest());

        // 方法身份 all-or-none，与 evidence 无关：partial triple 一律 fail-closed。
        boolean anyIdentity = !methodIdBlank || !versionBlank || !specDigestBlank;
        boolean completeIdentity = !methodIdBlank && !versionBlank && !specDigestBlank;
        if (anyIdentity && !completeIdentity) {
            return Optional.empty();
        }

        // 判型只信可信的 declaredEvidence（由持久化侧按记录声明写入）：仅 LIBRARY_CALL_DECLARED
        // 走 canonical narrative；两种 CUSTOM 一律走 formulaDescription——自定义记录允许携带
        // 完整三元组，但 evidence 仍是 CUSTOM，不得因此冒用 canonical 说明。null/未知 → fail-closed。
        if (in.declaredEvidence() == FinanceDeclaredEvidence.CUSTOM_WITH_CHECKS
                || in.declaredEvidence() == FinanceDeclaredEvidence.CUSTOM_UNVERIFIED) {
            return projectCustom(in);
        }
        if (in.declaredEvidence() != FinanceDeclaredEvidence.LIBRARY_CALL_DECLARED) {
            return Optional.empty();
        }

        // LIBRARY_CALL_DECLARED 必须携带完整三元组（此处即全空）→ 不可投影
        if (!completeIdentity) {
            return Optional.empty();
        }

        // 完整三元组但目录未命中：不可投影
        Optional<FinanceMethodSpec> specOpt = catalog.find(in.methodId(), in.methodVersion(), in.specDigest());
        if (specOpt.isEmpty()) {
            return Optional.empty();
        }

        FinanceMethodSpec spec = specOpt.get();
        String narrativeTemplate = findNarrativeTemplate(spec);
        if (narrativeTemplate == null || narrativeTemplate.isBlank()) {
            return Optional.empty();
        }

        // canonical unit 精确校验
        String canonicalUnit = canonicalUnit(spec);
        String inputUnit = in.unit() == null ? "" : in.unit().trim();
        if (canonicalUnit == null || !inputUnit.equals(canonicalUnit)) {
            return Optional.empty();
        }

        Map<String, Object> effectiveParams = effectiveParameters(spec, in.parameters());
        if (!requiredParametersSatisfied(spec, effectiveParams)) {
            return Optional.empty();
        }
        if (!allParameterTypesValid(spec, effectiveParams)) {
            return Optional.empty();
        }

        String howCalculated = fillTemplate(narrativeTemplate, effectiveParams, spec);
        if (howCalculated == null) {
            return Optional.empty();
        }
        if (howCalculated.length() > MAX_HOW_CALCULATED_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new FinanceResultProjection(spec.getDisplayName(), in.value(), canonicalUnit, howCalculated));
    }

    private Optional<FinanceResultProjection> projectCustom(FinanceResultProjectionInput in) {
        String formula = in.formulaDescription() == null ? "" : in.formulaDescription().trim();
        if (formula.isBlank() || formula.length() > MAX_HOW_CALCULATED_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new FinanceResultProjection("自定义计算", in.value(), in.unit(), formula));
    }

    private Map<String, Object> effectiveParameters(FinanceMethodSpec spec, Map<String, Object> provided) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, FinanceMethodSpec.FinanceParameter> e : spec.getParameters().entrySet()) {
            String key = e.getKey();
            FinanceMethodSpec.FinanceParameter param = e.getValue();
            if (provided != null && provided.containsKey(key)) {
                result.put(key, provided.get(key));
            } else if (param.getDefaultValue() != null) {
                result.put(key, param.getDefaultValue());
            } else {
                result.put(key, null);
            }
        }
        return result;
    }

    private String fillTemplate(String template, Map<String, Object> params, FinanceMethodSpec spec) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!params.containsKey(key)) {
                log.debug("Template placeholder not a known parameter: {}", key);
                return null;
            }
            Object value = params.get(key);
            if (value == null) {
                // 缺少且无默认值：不可投影
                return null;
            }
            FinanceMethodSpec.FinanceParameter param = spec.getParameters().get(key);
            String replacement = renderValue(value, param == null ? null : param.getType());
            if (replacement == null) {
                return null; // 类型不匹配或数组/对象直接输出
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String renderValue(Object value, String declaredType) {
        if (value == null) {
            return null;
        }
        String type = declaredType == null ? "" : declaredType.toLowerCase();
        if ("array".equals(type)) {
            if (value instanceof Collection<?> c) {
                return c.size() + " 个周期收益率样本";
            }
            if (value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value);
                return length + " 个周期收益率样本";
            }
            return null; // array 参数收到标量 → 不可投影
        }
        if ("object".equals(type)) {
            return null; // v1 无对象参数
        }
        // 标量类型：拒绝 Collection/数组
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            return null;
        }
        if ("string".equals(type)) {
            return value instanceof String ? (String) value : null;
        }
        if ("boolean".equals(type)) {
            return value instanceof Boolean ? String.valueOf(value) : null;
        }
        if ("number".equals(type) || "integer".equals(type)) {
            return value instanceof Number ? String.valueOf(value) : null;
        }
        // 未知类型：仅接受标量兜底
        if (value instanceof Number || value instanceof String || value instanceof Boolean) {
            if (value instanceof String s) {
                if (s.length() > 256) {
                    return null; // 标量字符串过长，视为不可投影
                }
                return s;
            }
            return String.valueOf(value);
        }
        return null;
    }

    private String findNarrativeTemplate(FinanceMethodSpec spec) {
        for (Map<String, Object> ns : spec.getConventions().values()) {
            Object template = ns.get("narrativeTemplate");
            if (template instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private String canonicalUnit(FinanceMethodSpec spec) {
        List<FinanceMethodSpec.FinanceOutput> outputs = spec.getOutputs();
        if (outputs == null || outputs.size() != 1) {
            return null;
        }
        String unit = outputs.get(0).getUnit();
        if (unit == null || unit.isBlank()) {
            return null;
        }
        return unit.trim();
    }

    private boolean allParameterTypesValid(FinanceMethodSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, FinanceMethodSpec.FinanceParameter> e : spec.getParameters().entrySet()) {
            Object value = params.get(e.getKey());
            if (value == null) {
                continue; // 存在性由 requiredParametersSatisfied 负责；可选无值时无需类型校验
            }
            if (!isValueTypeValid(value, e.getValue().getType())) {
                return false;
            }
        }
        return true;
    }

    private boolean isValueTypeValid(Object value, String declaredType) {
        String type = declaredType == null ? "" : declaredType.toLowerCase();
        return switch (type) {
            case "array" -> value instanceof Collection<?> || value.getClass().isArray();
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number && isMathematicalInteger((Number) value);
            case "object" -> false; // v1 fail-closed
            default -> true; // 未声明类型时不额外收紧，避免破坏既有规范
        };
    }

    private boolean isMathematicalInteger(Number value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof Double d) {
            return Double.isFinite(d) && d == Math.rint(d);
        }
        if (value instanceof Float f) {
            return Float.isFinite(f) && f == Math.rint(f);
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0;
        }
        return false;
    }

    private boolean requiredParametersSatisfied(FinanceMethodSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, FinanceMethodSpec.FinanceParameter> e : spec.getParameters().entrySet()) {
            FinanceMethodSpec.FinanceParameter param = e.getValue();
            Boolean required = param.getRequired();
            if (required != null && required && params.get(e.getKey()) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 投影输入。
     */
    public record FinanceResultProjectionInput(
            String methodId,
            String methodVersion,
            String specDigest,
            Number value,
            String unit,
            Map<String, Object> parameters,
            String formulaDescription,
            boolean renderable,
            FinanceDeclaredEvidence declaredEvidence
    ) {
    }

    /**
     * 可信的记录声明证据类型（仅作投影判型输入，永不进入输出）。
     * 与持久化记录的 declaredEvidence 字段一一对应；判型不得改用内部生效证据，
     * 公共库记录即使被后台核对降级，也仍须 canonical narrative。
     */
    public enum FinanceDeclaredEvidence {
        LIBRARY_CALL_DECLARED,
        CUSTOM_WITH_CHECKS,
        CUSTOM_UNVERIFIED
    }

    /**
     * 投影输出：模型可见的最小字段。
     */
    public record FinanceResultProjection(
            String method,
            Number value,
            String unit,
            String howCalculated
    ) {
    }
}
