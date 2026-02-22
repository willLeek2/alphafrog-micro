package world.willfrog.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PythonStaticPrecheckService {

    private static final Pattern DATASET_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern DATASET_ID_PLACEHOLDER_IN_CODE = Pattern.compile("\\{\\s*dataset_id\\s*}");
    private static final Pattern DATASET_ID_DEFINE_PATTERN = Pattern.compile("\\bdataset_id\\s*=");
    private static final Pattern FORBIDDEN_DATASETS_PATH_PATTERN = Pattern.compile("(?i)(^|[^A-Za-z0-9_])/datasets(/|\\b)");

    public Result check(String code, String datasetId, Map<String, Object> runArgs) {
        List<String> issues = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        String normalizedCode = code == null ? "" : code;
        String normalizedDatasetId = datasetId == null ? "" : datasetId.trim();

        if (normalizedCode.isBlank()) {
            issues.add("code 不能为空");
        }
        if (normalizedDatasetId.isBlank()) {
            issues.add("dataset_id 不能为空");
        } else if (!DATASET_ID_PATTERN.matcher(normalizedDatasetId).matches()) {
            issues.add("dataset_id 格式非法，仅允许字母数字._-");
        }

        if (!normalizedCode.isBlank() && FORBIDDEN_DATASETS_PATH_PATTERN.matcher(normalizedCode).find()) {
            issues.add("禁止在代码中使用 /datasets 路径，请改用 /sandbox/input/<dataset_id>/... 或 /tmp 输出");
        }

        if (!normalizedCode.isBlank()
                && DATASET_ID_PLACEHOLDER_IN_CODE.matcher(normalizedCode).find()
                && !DATASET_ID_DEFINE_PATTERN.matcher(normalizedCode).find()) {
            issues.add("代码引用了 dataset_id 变量但未定义，请先赋值后再使用");
        }

        report.put("code_length", normalizedCode.length());
        report.put("dataset_id", normalizedDatasetId);
        report.put("run_args", runArgs == null ? Map.of() : runArgs);
        report.put("issues", issues);

        if (!issues.isEmpty()) {
            return Result.builder()
                    .passed(false)
                    .category(TodoFailureCategory.STATIC)
                    .errorCode("STATIC_PRECHECK_FAILED")
                    .message(String.join("; ", issues))
                    .report(report)
                    .build();
        }

        return Result.builder()
                .passed(true)
                .category(null)
                .errorCode("")
                .message("")
                .report(report)
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class Result {
        private boolean passed;
        private TodoFailureCategory category;
        private String errorCode;
        private String message;
        private Map<String, Object> report;
    }
}

