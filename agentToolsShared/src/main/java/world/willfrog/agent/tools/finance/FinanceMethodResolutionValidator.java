package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 校验轻量模型结构化输出是否符合共同协议 §3.3。
 *
 * <p>校验失败返回可分类的错误码，供 {@link FinanceMethodTools} 决定是走别名兜底还是返回
 * RESOLVER_BAD_MODEL_OUTPUT。</p>
 */
@Slf4j
public class FinanceMethodResolutionValidator {

    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_REASON_LENGTH = 512;
    private static final int MAX_TERM_LENGTH = 256;
    private static final int MAX_QUESTION_LENGTH = 512;
    private static final int MAX_TERMS = 64;
    private static final int MAX_QUESTIONS = 64;

    private static final Set<String> ALLOWED_ROOT_FIELDS = Set.of(
            "status", "candidates", "matchReason", "unresolvedTerms", "clarificationQuestions");

    private static final Set<String> ALLOWED_CANDIDATE_FIELDS = Set.of(
            "methodId", "version", "specDigest", "matchReason", "unresolvedTerms", "clarificationQuestions");

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "MATCHED", "AMBIGUOUS", "NEEDS_CLARIFICATION", "NO_ADVICE");

    private final FinanceMethodSpecCatalog catalog;

    public FinanceMethodResolutionValidator(FinanceMethodSpecCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 校验模型输出的原始 JSON 节点。
     *
     * @return 成功时返回 Valid 结果；失败时返回 Invalid 并带错误码。
     */
    public ValidationResult validate(JsonNode output) {
        if (output == null || !output.isObject()) {
            return invalid("MODEL_OUTPUT_NOT_OBJECT", "Model output must be a JSON object");
        }

        String status = text(output, "status");
        if (!ALLOWED_STATUSES.contains(status)) {
            return invalid("INVALID_STATUS", "Status must be one of " + ALLOWED_STATUSES);
        }

        JsonNode candidates = output.get("candidates");
        if (candidates == null || !candidates.isArray()) {
            return invalid("MISSING_CANDIDATES", "candidates array is required");
        }
        int candidateCount = candidates.size();
        if (candidateCount > MAX_CANDIDATES) {
            return invalid("TOO_MANY_CANDIDATES",
                    "At most " + MAX_CANDIDATES + " candidates allowed, got " + candidateCount);
        }

        // 状态与候选数量相容性
        if ("MATCHED".equals(status) && candidateCount != 1) {
            return invalid("STATUS_CANDIDATE_MISMATCH", "MATCHED requires exactly 1 candidate");
        }
        if ("AMBIGUOUS".equals(status) && candidateCount < 2) {
            return invalid("STATUS_CANDIDATE_MISMATCH", "AMBIGUOUS requires at least 2 candidates");
        }
        if ("NO_ADVICE".equals(status) && candidateCount != 0) {
            return invalid("STATUS_CANDIDATE_MISMATCH", "NO_ADVICE requires 0 candidates");
        }

        Set<String> seenTriples = new LinkedHashSet<>();
        for (int i = 0; i < candidateCount; i++) {
            JsonNode cand = candidates.get(i);
            if (!cand.isObject()) {
                return invalid("CANDIDATE_NOT_OBJECT", "Candidate at index " + i + " is not an object");
            }
            ValidationResult itemResult = validateCandidate(cand, i);
            if (!itemResult.isValid()) {
                return itemResult;
            }
            String methodId = text(cand, "methodId");
            String version = text(cand, "version");
            String specDigest = text(cand, "specDigest");
            String triple = methodId + "@" + version + "@" + specDigest;
            if (!seenTriples.add(triple)) {
                return invalid("DUPLICATE_CANDIDATE", "Duplicate method triple: " + triple);
            }
            if (catalog.find(methodId, version, specDigest).isEmpty()) {
                return invalid("CANDIDATE_NOT_IN_CATALOG",
                        "Candidate method triple not in current catalog: " + triple);
            }
        }

        // 根级字段严格 allowlist
        ValidationResult rootFieldResult = validateRootFields(output);
        if (!rootFieldResult.isValid()) {
            return rootFieldResult;
        }

        String matchReason = textOrNull(output, "matchReason");
        if (matchReason != null && matchReason.length() > MAX_REASON_LENGTH) {
            return invalid("MATCH_REASON_TOO_LONG",
                    "matchReason exceeds " + MAX_REASON_LENGTH + " chars");
        }

        ValidationResult termsResult = validateStringArray(output, "unresolvedTerms", MAX_TERMS, MAX_TERM_LENGTH);
        if (!termsResult.isValid()) {
            return termsResult;
        }
        ValidationResult questionsResult = validateStringArray(output, "clarificationQuestions", MAX_QUESTIONS, MAX_QUESTION_LENGTH);
        if (!questionsResult.isValid()) {
            return questionsResult;
        }

        return valid(status, candidates);
    }

    private ValidationResult validateCandidate(JsonNode cand, int index) {
        String methodId = text(cand, "methodId");
        if (methodId.isBlank()) {
            return invalid("MISSING_METHOD_ID", "Candidate " + index + " missing methodId");
        }
        String version = text(cand, "version");
        if (version.isBlank()) {
            return invalid("MISSING_VERSION", "Candidate " + index + " missing version");
        }
        String specDigest = text(cand, "specDigest");
        if (specDigest.isBlank()) {
            return invalid("MISSING_SPEC_DIGEST", "Candidate " + index + " missing specDigest");
        }
        String matchReason = text(cand, "matchReason");
        if (matchReason.length() > MAX_REASON_LENGTH) {
            return invalid("MATCH_REASON_TOO_LONG",
                    "Candidate " + index + " matchReason exceeds " + MAX_REASON_LENGTH + " chars");
        }

        ValidationResult termsResult = validateStringArray(cand, "unresolvedTerms", MAX_TERMS, MAX_TERM_LENGTH);
        if (!termsResult.isValid()) {
            return termsResult.withPrefix("Candidate " + index + " ");
        }
        ValidationResult questionsResult = validateStringArray(cand, "clarificationQuestions", MAX_QUESTIONS, MAX_QUESTION_LENGTH);
        if (!questionsResult.isValid()) {
            return questionsResult.withPrefix("Candidate " + index + " ");
        }

        // 候选级字段严格 allowlist
        ValidationResult candFieldResult = validateCandidateFields(cand, index);
        if (!candFieldResult.isValid()) {
            return candFieldResult;
        }

        return valid(null, null);
    }

    private ValidationResult validateRootFields(JsonNode output) {
        Iterator<String> it = output.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!ALLOWED_ROOT_FIELDS.contains(name)) {
                return invalid("FORBIDDEN_MODEL_FIELD", "Root field not allowed: " + name);
            }
        }
        return valid(null, null);
    }

    private ValidationResult validateCandidateFields(JsonNode cand, int index) {
        Iterator<String> it = cand.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!ALLOWED_CANDIDATE_FIELDS.contains(name)) {
                return invalid("FORBIDDEN_MODEL_FIELD",
                        "Candidate " + index + " field not allowed: " + name);
            }
        }
        return valid(null, null);
    }

    private ValidationResult validateStringArray(JsonNode parent, String fieldName, int maxCount, int maxItemLength) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return valid(null, null);
        }
        if (!node.isArray()) {
            return invalid("INVALID_" + upperSnake(fieldName), fieldName + " must be an array");
        }
        if (node.size() > maxCount) {
            return invalid("TOO_MANY_" + upperSnake(fieldName),
                    fieldName + " exceeds " + maxCount + " items");
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isTextual()) {
                return invalid("INVALID_" + upperSnake(fieldName),
                        fieldName + "[" + i + "] is not a string");
            }
            if (item.asText().length() > maxItemLength) {
                return invalid("INVALID_" + upperSnake(fieldName),
                        fieldName + "[" + i + "] exceeds " + maxItemLength + " chars");
            }
        }
        return valid(null, null);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private String upperSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private ValidationResult invalid(String code, String message) {
        return new ValidationResult(false, code, message, null, null);
    }

    private ValidationResult valid(String status, JsonNode candidates) {
        return new ValidationResult(true, null, null, status, candidates);
    }

    /**
     * 校验结果。valid 时携带 status 与 candidates 节点；invalid 时携带错误码与可读消息。
     */
    @Getter
    public static final class ValidationResult {
        private final boolean valid;
        private final String errorCode;
        private final String errorMessage;
        private final String status;
        private final JsonNode candidates;

        private ValidationResult(boolean valid, String errorCode, String errorMessage,
                                 String status, JsonNode candidates) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.status = status;
            this.candidates = candidates;
        }

        public boolean isValid() {
            return valid;
        }

        public ValidationResult withPrefix(String prefix) {
            if (valid) {
                return this;
            }
            return new ValidationResult(false, errorCode, prefix + errorMessage, null, null);
        }
    }
}
