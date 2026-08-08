package world.willfrog.agent.tools.finance;

import lombok.extern.slf4j.Slf4j;

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
 *   <li>数组参数占位符只渲染为“N 个周期收益率样本”（长度摘要），永不暴露内容；</li>
 *   <li>标量参数渲染实际值；</li>
 *   <li>未解析占位符 / 类型不匹配 / 数组或对象直接输出 → 该记录不可投影（返回空）；</li>
 *   <li>{@code displayFormat} 仅用于后续 Markdown 层，本层 {@code value} 保持原始数值；</li>
 *   <li>输出中永远不包含 digest / environment / evidence / identity / version 等后台字段。</li>
 * </ul>
 */
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

        // 自定义计算：没有完整方法三元组
        boolean hasTriple = notBlank(in.methodId()) && notBlank(in.methodVersion()) && notBlank(in.specDigest());
        if (!hasTriple) {
            return projectCustom(in);
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

        Map<String, Object> effectiveParams = effectiveParameters(spec, in.parameters());
        String howCalculated = fillTemplate(narrativeTemplate, effectiveParams, spec);
        if (howCalculated == null) {
            return Optional.empty();
        }
        howCalculated = truncate(howCalculated, MAX_HOW_CALCULATED_LENGTH);
        return Optional.of(new FinanceResultProjection(spec.getDisplayName(), in.value(), in.unit(), howCalculated));
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
            String replacement = renderValue(value);
            if (replacement == null) {
                return null; // 类型不匹配或数组/对象直接输出
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String renderValue(Object value) {
        if (value instanceof Number || value instanceof String || value instanceof Boolean) {
            if (value instanceof String s) {
                if (s.length() > 256) {
                    return null; // 标量字符串过长，视为不可投影
                }
                return s;
            }
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> c) {
            return c.size() + " 个周期收益率样本";
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            return length + " 个周期收益率样本";
        }
        // 对象或其他类型直接输出不可投影
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

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
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
            boolean renderable
    ) {
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
