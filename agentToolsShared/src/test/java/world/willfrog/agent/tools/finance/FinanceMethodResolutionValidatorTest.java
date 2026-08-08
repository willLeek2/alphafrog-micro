package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link FinanceMethodResolutionValidator} 对模型输出的全部校验规则。
 */
class FinanceMethodResolutionValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FinanceMethodSpecCatalog catalog = new FinanceMethodSpecCatalog(objectMapper);
    private final FinanceMethodResolutionValidator validator = new FinanceMethodResolutionValidator(catalog);

    @Test
    void validMatchedCandidateIsAccepted() throws Exception {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String json = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"用户希望计算复合增长率\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"匹配成功\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertTrue(result.isValid());
        assertEquals("MATCHED", result.getStatus());
    }

    @Test
    void multipleCandidatesAreAcceptedForAmbiguous() throws Exception {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        FinanceMethodSpec vol = catalog.findByMethodId("finance.risk.annualized_volatility").orElseThrow();
        String json = "{"
                + "\"status\":\"AMBIGUOUS\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"可能指增长\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "},{"
                + "  \"methodId\":\"" + vol.getMethodId() + "\","
                + "  \"version\":\"" + vol.getVersion() + "\","
                + "  \"specDigest\":\"" + vol.getSpecDigest() + "\","
                + "  \"matchReason\":\"可能指波动\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertTrue(result.isValid());
        assertEquals("AMBIGUOUS", result.getStatus());
    }

    @Test
    void outOfCatalogMethodIdIsRejected() throws Exception {
        String json = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"finance.unknown.metric\","
                + "  \"version\":\"1.0.0\","
                + "  \"specDigest\":\"sha256:deadbeef\","
                + "  \"matchReason\":\"unknown\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertFalse(result.isValid());
        assertEquals("CANDIDATE_NOT_IN_CATALOG", result.getErrorCode());
    }

    @Test
    void duplicateTriplesAreRejected() throws Exception {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String json = "{"
                + "\"status\":\"AMBIGUOUS\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"a\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "},{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"b\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertFalse(result.isValid());
        assertEquals("DUPLICATE_CANDIDATE", result.getErrorCode());
    }

    @Test
    void matchedWithZeroCandidatesIsRejected() throws Exception {
        String json = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertFalse(result.isValid());
        assertEquals("STATUS_CANDIDATE_MISMATCH", result.getErrorCode());
    }

    @Test
    void noAdviceWithCandidatesIsRejected() throws Exception {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String json = "{"
                + "\"status\":\"NO_ADVICE\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertFalse(result.isValid());
        assertEquals("STATUS_CANDIDATE_MISMATCH", result.getErrorCode());
    }

    @Test
    void forbiddenDefinitionFieldIsRejected() throws Exception {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String json = "{"
                + "\"status\":\"MATCHED\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"ok\","
                + "  \"definition\":\" forged definition\","
                + "  \"unresolvedTerms\":[],"
                + "  \"clarificationQuestions\":[]"
                + "}],"
                + "\"matchReason\":\"\","
                + "\"unresolvedTerms\":[],"
                + "\"clarificationQuestions\":[]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertFalse(result.isValid());
        assertEquals("FORBIDDEN_MODEL_FIELD", result.getErrorCode());
    }

    @Test
    void vagueTimeTermsProduceClarificationOnly() throws Exception {
        FinanceMethodSpec cagr = catalog.findByMethodId("finance.growth.cagr").orElseThrow();
        String json = "{"
                + "\"status\":\"NEEDS_CLARIFICATION\","
                + "\"candidates\":[{"
                + "  \"methodId\":\"" + cagr.getMethodId() + "\","
                + "  \"version\":\"" + cagr.getVersion() + "\","
                + "  \"specDigest\":\"" + cagr.getSpecDigest() + "\","
                + "  \"matchReason\":\"用户希望比较增长，但起止日不明\","
                + "  \"unresolvedTerms\":[\"这几年\"],"
                + "  \"clarificationQuestions\":[\"希望从哪个交易日算到哪个交易日？\"]"
                + "}],"
                + "\"matchReason\":\"需要澄清时间边界\","
                + "\"unresolvedTerms\":[\"这几年\"],"
                + "\"clarificationQuestions\":[\"希望从哪个交易日算到哪个交易日？\"]"
                + "}";
        JsonNode node = objectMapper.readTree(json);
        FinanceMethodResolutionValidator.ValidationResult result = validator.validate(node);
        assertTrue(result.isValid());
        assertEquals("NEEDS_CLARIFICATION", result.getStatus());
    }
}
