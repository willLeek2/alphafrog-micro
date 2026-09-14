package world.willfrog.agent.tools.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import world.willfrog.agent.platform.finance.FinanceMetricRecord;
import world.willfrog.agent.platform.finance.FinanceRecordExtractionResult;
import world.willfrog.agent.platform.finance.FinanceToolResultFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges persisted finance records to the canonical MethodSpec model projector.
 *
 * <p>The adapter deliberately consumes the persisted allowlisted fields rather than raw marker
 * JSON. A projection failure rejects the whole model-visible batch and becomes one actionable
 * warning; backend identities, digests, evidence and environment facts are never copied into the
 * returned value.</p>
 */
@Component
public class FinanceResultModelAdapter {

    private static final String REJECTED_NOTICE_CODE = "FINANCE_RESULT_REJECTED";
    private static final FinanceRecordExtractionResult.ModelNotice REJECTED_NOTICE =
            new FinanceRecordExtractionResult.ModelNotice(
                    REJECTED_NOTICE_CODE,
                    "本次结构化金融结果没有被接收",
                    "检查 report_custom() 的必填字段，减少单批记录数量后重试");

    private final ObjectMapper objectMapper;
    private final FinanceResultModelProjector projector;

    public FinanceResultModelAdapter(
            ObjectMapper objectMapper,
            FinanceResultModelProjector projector) {
        this.objectMapper = objectMapper;
        this.projector = projector;
    }

    /**
     * Projects one processor result for the shared sync/async formatter.
     */
    public ProjectionBatch project(FinanceRecordExtractionResult extraction) {
        if (extraction == null) {
            return new ProjectionBatch(List.of(), List.of());
        }

        List<FinanceToolResultFormatter.FinanceModelResult> results = new ArrayList<>();
        List<FinanceRecordExtractionResult.ModelNotice> notices =
                new ArrayList<>(extraction.modelNotices());
        boolean projectionRejected = false;

        for (FinanceMetricRecord record : extraction.records()) {
            if (record == null || !Boolean.TRUE.equals(record.getRenderable())) {
                continue;
            }
            Optional<FinanceToolResultFormatter.FinanceModelResult> projected = project(record);
            if (projected.isPresent()) {
                results.add(projected.get());
            } else {
                projectionRejected = true;
            }
        }

        if (projectionRejected) {
            // Rendering is batch-atomic just like schema acceptance: never show a partial batch
            // when one persisted row cannot be proven against the canonical MethodSpec.
            results.clear();
            if (notices.stream()
                    .noneMatch(notice -> REJECTED_NOTICE_CODE.equals(notice.code()))) {
                notices.add(REJECTED_NOTICE);
            }
        }
        return new ProjectionBatch(results, notices);
    }

    private Optional<FinanceToolResultFormatter.FinanceModelResult> project(
            FinanceMetricRecord record) {
        try {
            JsonNode valueNode = objectMapper.readTree(record.getValueJson());
            JsonNode parametersNode = objectMapper.readTree(record.getParametersJson());
            if (valueNode == null || !valueNode.isNumber()
                    || parametersNode == null || !parametersNode.isObject()) {
                return Optional.empty();
            }

            Map<String, Object> parameters = objectMapper.convertValue(
                    parametersNode, new TypeReference<Map<String, Object>>() { });
            FinanceResultModelProjector.FinanceDeclaredEvidence declaredEvidence =
                    parseDeclaredEvidence(record.getDeclaredEvidence());
            if (declaredEvidence == null) {
                return Optional.empty();
            }
            FinanceResultModelProjector.FinanceResultProjectionInput input =
                    new FinanceResultModelProjector.FinanceResultProjectionInput(
                            record.getMethodId(),
                            record.getMethodVersion(),
                            record.getSpecDigest(),
                            valueNode.numberValue(),
                            record.getUnit(),
                            parameters,
                            record.getFormulaDescription(),
                            true,
                            declaredEvidence);

            return projector.project(input).map(projection ->
                    new FinanceToolResultFormatter.FinanceModelResult(
                            projection.method(),
                            projection.value(),
                            projection.unit(),
                            projection.howCalculated()));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private FinanceResultModelProjector.FinanceDeclaredEvidence parseDeclaredEvidence(
            String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FinanceResultModelProjector.FinanceDeclaredEvidence.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record ProjectionBatch(
            List<FinanceToolResultFormatter.FinanceModelResult> results,
            List<FinanceRecordExtractionResult.ModelNotice> notices) {

        public ProjectionBatch {
            results = results == null ? List.of() : List.copyOf(results);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }
}
