package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把轻量模型输出的候选补全为建议工具输出：定义、执行输入、来源引用与库样例。
 *
 * <p>库样例仅在目标环境清单可用且 {@code libraryBinding.apiCompatRange} 匹配目标包
 * {@code api_version} 时才生成；否则不伪造环境身份，也不返回未经核对的样例。</p>
 */
@Component
@Slf4j
public class FinanceMethodSuggestionRenderer {

    private final FinanceMethodSpecCatalog specCatalog;
    private final FinanceMethodKnowledgeCatalog knowledgeCatalog;
    private final ObjectMapper objectMapper;

    public FinanceMethodSuggestionRenderer(FinanceMethodSpecCatalog specCatalog,
                                            FinanceMethodKnowledgeCatalog knowledgeCatalog,
                                            ObjectMapper objectMapper) {
        this.specCatalog = specCatalog;
        this.knowledgeCatalog = knowledgeCatalog;
        this.objectMapper = objectMapper;
    }

    /**
     * 渲染单个候选。
     *
     * @param methodId      模型返回的方法 ID
     * @param version       模型返回的版本
     * @param specDigest    模型返回的摘要
     * @param matchReason   模型返回的匹配理由
     * @param unresolvedTerms 模型返回的未解决表达
     * @param clarificationQuestions 模型返回的澄清问题
     * @param targetEnvironment 目标环境清单（可空）
     * @return 建议工具输出中的 suggestion 对象（Map 形式，便于最终序列化）
     */
    public Map<String, Object> render(String methodId,
                                     String version,
                                     String specDigest,
                                     String matchReason,
                                     List<String> unresolvedTerms,
                                     List<String> clarificationQuestions,
                                     TargetEnvironment targetEnvironment) {
        FinanceMethodSpec spec = specCatalog.find(methodId, version, specDigest)
                .orElseThrow(() -> new IllegalStateException(
                        "Method triple disappeared from catalog after validation: " + methodId));

        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("methodId", methodId);
        suggestion.put("version", version);
        suggestion.put("specDigest", specDigest);
        suggestion.put("displayName", spec.getDisplayName());
        suggestion.put("matchReason", matchReason);
        suggestion.put("definition", spec.getDefinition());
        suggestion.put("requiredExecutionInputs", renderExecutionInputs(spec));
        suggestion.put("sourceRefs", renderSourceRefs(spec));
        suggestion.put("library", renderLibrary(spec, targetEnvironment));
        suggestion.put("sample", renderSample(spec, targetEnvironment));
        suggestion.put("unresolvedTerms", unresolvedTerms == null ? Collections.emptyList() : unresolvedTerms);
        suggestion.put("clarificationQuestions", clarificationQuestions == null ? Collections.emptyList() : clarificationQuestions);
        return suggestion;
    }

    private List<Map<String, Object>> renderExecutionInputs(FinanceMethodSpec spec) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, FinanceMethodSpec.FinanceParameter> e : spec.getParameters().entrySet()) {
            String key = e.getKey();
            FinanceMethodSpec.FinanceParameter param = e.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", key);
            item.put("meaning", param.getMeaning() == null || param.getMeaning().isBlank()
                    ? param.getDescription()
                    : param.getMeaning());
            item.put("type", param.getType());
            item.put("required", param.getRequired() != null && param.getRequired());
            if (param.getDefaultValue() != null) {
                item.put("defaultValue", param.getDefaultValue());
            }
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> renderSourceRefs(FinanceMethodSpec spec) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String ref : spec.getSourceRefs()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", ref);
            knowledgeCatalog.resolve(spec.getMethodId(), spec.getVersion(), spec.getSpecDigest())
                    .ifPresent(k -> item.put("section", k.section()));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> renderLibrary(FinanceMethodSpec spec, TargetEnvironment env) {
        if (spec.getLibraryBinding() == null) {
            return null;
        }
        if (env == null || env.environmentId() == null || env.environmentId().isBlank()) {
            return null;
        }
        FinanceMethodSpec.FinanceLibraryBinding binding = spec.getLibraryBinding();
        Map<String, Object> lib = new LinkedHashMap<>();
        lib.put("package", binding.getPackageName());
        lib.put("function", binding.getFunction());
        lib.put("apiCompatRange", binding.getApiCompatRange());
        if (isCompatible(binding.getApiCompatRange(), env, binding.getPackageName())) {
            lib.put("available", true);
        }
        return lib;
    }

    private String renderSample(FinanceMethodSpec spec, TargetEnvironment env) {
        if (spec.getLibraryBinding() == null) {
            return null;
        }
        if (env == null || env.environmentId() == null || env.environmentId().isBlank()) {
            return null;
        }
        FinanceMethodSpec.FinanceLibraryBinding binding = spec.getLibraryBinding();
        if (!isCompatible(binding.getApiCompatRange(), env, binding.getPackageName())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("import ").append(binding.getPackageName()).append("\n");
        sb.append(binding.getPackageName()).append(".").append(binding.getFunction()).append("(");
        boolean first = true;
        for (String key : spec.getParameters().keySet()) {
            if (!first) sb.append(", ");
            sb.append(key).append("=...");
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    private boolean isCompatible(String apiCompatRange, TargetEnvironment env, String packageName) {
        if (apiCompatRange == null || apiCompatRange.isBlank() || packageName == null || packageName.isBlank()) {
            return false;
        }
        Optional<String> targetApi = env.packageApis().stream()
                .filter(p -> packageName.equals(p.name()))
                .map(TargetEnvironment.PackageApi::apiVersion)
                .findFirst();
        if (targetApi.isEmpty()) {
            return false;
        }
        return SemverRange.check(apiCompatRange, targetApi.get());
    }

    /**
     * 目标环境清单。
     */
    public record TargetEnvironment(String environmentId, List<PackageApi> packageApis) {
        public TargetEnvironment {
            packageApis = packageApis == null ? Collections.emptyList() : List.copyOf(packageApis);
        }

        public record PackageApi(String name, String version, String apiVersion) {
        }
    }

    /**
     * 极简语义化版本范围检查，仅覆盖协议当前使用的 {@code >=M.<x>.<y>,<N.0.0} 形式。
     */
    static final class SemverRange {

        static boolean check(String range, String version) {
            if (range == null || version == null) {
                return false;
            }
            int[] target = parse(version);
            if (target == null) {
                return false;
            }
            String[] parts = range.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) {
                    continue;
                }
                if (part.startsWith(">=")) {
                    int[] bound = parse(part.substring(2).trim());
                    if (bound == null || compare(target, bound) < 0) {
                        return false;
                    }
                } else if (part.startsWith(">")) {
                    int[] bound = parse(part.substring(1).trim());
                    if (bound == null || compare(target, bound) <= 0) {
                        return false;
                    }
                } else if (part.startsWith("<=")) {
                    int[] bound = parse(part.substring(2).trim());
                    if (bound == null || compare(target, bound) > 0) {
                        return false;
                    }
                } else if (part.startsWith("<")) {
                    int[] bound = parse(part.substring(1).trim());
                    if (bound == null || compare(target, bound) >= 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static int[] parse(String version) {
            String[] parts = version.split("\\.");
            if (parts.length < 1) {
                return null;
            }
            int[] result = new int[3];
            try {
                for (int i = 0; i < 3; i++) {
                    result[i] = i < parts.length ? Integer.parseInt(parts[i].trim()) : 0;
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return result;
        }

        private static int compare(int[] a, int[] b) {
            for (int i = 0; i < 3; i++) {
                int cmp = Integer.compare(a[i], b[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }
}
