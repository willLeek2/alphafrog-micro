package world.willfrog.agent.tools.finance;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 金融方法规范的不可变运行时模型。字段直接对应 canonical JSON 的顶层键。
 *
 * <p>本类只保存已经构建插件校验和整理后的数据；运行时不再解析 YAML 或 schema。</p>
 */
@Getter
@Builder(toBuilder = true)
@ToString(of = {"methodId", "version", "specDigest"})
public final class FinanceMethodSpec {

    private final String schemaVersion;
    private final String methodId;
    private final String version;
    private final String displayName;
    private final String definition;

    @Builder.Default
    private final FinanceResolverHints resolverHints = FinanceResolverHints.empty();

    /**
     * 方法执行参数定义。键为参数名（canonical parameter key），值为参数描述。
     * 使用 LinkedHashMap 保持 canonical JSON 中的稳定顺序。
     */
    @Builder.Default
    private final Map<String, FinanceParameter> parameters = Collections.emptyMap();

    @Builder.Default
    private final Map<String, Map<String, Object>> conventions = Collections.emptyMap();

    @Builder.Default
    private final Map<String, Map<String, Object>> extensions = Collections.emptyMap();

    @Builder.Default
    private final List<FinanceOutput> outputs = Collections.emptyList();

    private final FinanceLibraryBinding libraryBinding;

    @Builder.Default
    private final List<String> sourceRefs = Collections.emptyList();

    /** 构建期写入的 specDigest；运行时 {@link FinanceMethodSpecCatalog} 会复算并校验。 */
    private final String specDigest;

    /**
     * Resolver 提示子结构：别名、常见说法、澄清维度。
     */
    @Getter
    @Builder
    @ToString
    public static final class FinanceResolverHints {
        @Builder.Default
        private final List<String> aliases = Collections.emptyList();
        @Builder.Default
        private final List<String> commonPhrases = Collections.emptyList();
        @Builder.Default
        private final List<ClarificationDimension> clarificationDimensions = Collections.emptyList();

        public static FinanceResolverHints empty() {
            return builder().build();
        }
    }

    /**
     * 澄清维度：id 与问题文案。
     */
    @Getter
    @Builder
    @ToString
    public static final class ClarificationDimension {
        private final String id;
        private final String question;
    }

    /**
     * 方法参数定义。name 必须等于 canonical JSON 中的参数键（L144 不变式）。
     */
    @Getter
    @Builder
    @ToString(of = {"name", "type", "required"})
    public static final class FinanceParameter {
        private final String name;
        private final String type;
        private final Boolean required;
        private final Object defaultValue;
        private final Object minimum;
        private final Object maximum;
        private final List<Object> enumValues;
        private final String meaning;
        private final String description;
    }

    /**
     * 方法输出项。
     */
    @Getter
    @Builder
    @ToString
    public static final class FinanceOutput {
        private final String name;
        private final String unit;
        private final String description;
        private final String displayFormat;
    }

    /**
     * 公共库绑定。
     */
    @Getter
    @Builder
    @ToString
    public static final class FinanceLibraryBinding {
        private final String packageName;
        private final String function;
        private final String apiCompatRange;
    }

    /**
     * 以不可变 Map 返回参数定义，键 = canonical 参数名。
     */
    public Map<String, FinanceParameter> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public Map<String, Map<String, Object>> getConventions() {
        return Collections.unmodifiableMap(conventions);
    }

    public Map<String, Map<String, Object>> getExtensions() {
        return Collections.unmodifiableMap(extensions);
    }

    public List<String> getSourceRefs() {
        return Collections.unmodifiableList(sourceRefs);
    }

    public List<FinanceOutput> getOutputs() {
        return Collections.unmodifiableList(outputs);
    }
}
