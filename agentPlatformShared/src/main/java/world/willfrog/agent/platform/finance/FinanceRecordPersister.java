package world.willfrog.agent.platform.finance;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.FinanceMetricRecordMapper;
import world.willfrog.agent.platform.mapper.FinanceRecordBatchMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Transactional idempotency boundary for one executePython finance batch. */
@Component
public class FinanceRecordPersister {
    private final FinanceRecordBatchMapper batchMapper;
    private final FinanceMetricRecordMapper recordMapper;

    public FinanceRecordPersister(
            FinanceRecordBatchMapper batchMapper,
            FinanceMetricRecordMapper recordMapper) {
        this.batchMapper = batchMapper;
        this.recordMapper = recordMapper;
    }

    @Transactional
    public PersistenceOutcome persist(FinanceRecordBatch batch, List<FinanceMetricRecord> records) {
        int inserted = batchMapper.insertIgnore(batch);
        if (inserted == 0) {
            FinanceRecordBatch existing = batchMapper.findByIdentity(
                    batch.getRunId(), batch.getTodoId(), batch.getExecutePythonToolCallId());
            if (existing == null || !Objects.equals(
                    existing.getBatchContentDigest(), batch.getBatchContentDigest())) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RECORD_BATCH_IDENTITY_CONFLICT",
                        "Finance record batch identity already exists with different content");
            }
            assertExistingRecordsMatch(batch, records);
            return PersistenceOutcome.ALREADY_PRESENT_SAME;
        }

        for (FinanceMetricRecord record : records) {
            int recordInserted = recordMapper.insertIgnore(record);
            if (recordInserted == 1) {
                continue;
            }
            FinanceMetricRecord existing = recordMapper.findByIdentity(
                    record.getRunId(), record.getTodoId(), record.getExecutePythonToolCallId(),
                    record.getRecordIndex(), record.getRawDigest());
            if (!sameRecord(existing, record)) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RECORD_IDENTITY_CONFLICT",
                        "Finance record identity already exists with different content");
            }
        }
        return PersistenceOutcome.INSERTED;
    }

    private void assertExistingRecordsMatch(
            FinanceRecordBatch batch,
            List<FinanceMetricRecord> expectedRecords) {
        List<FinanceMetricRecord> existing = recordMapper.listByBatch(
                batch.getRunId(), batch.getTodoId(), batch.getExecutePythonToolCallId())
                .stream().sorted(Comparator.comparing(FinanceMetricRecord::getRecordIndex)).toList();
        List<FinanceMetricRecord> expected = expectedRecords.stream()
                .sorted(Comparator.comparing(FinanceMetricRecord::getRecordIndex)).toList();
        if (existing.size() != expected.size()) {
            throw new FinanceRecordProcessingException(
                    "FINANCE_RECORD_BATCH_IDENTITY_CONFLICT",
                    "Persisted finance record count differs from idempotent replay");
        }
        for (int index = 0; index < existing.size(); index++) {
            if (!sameRecord(existing.get(index), expected.get(index))) {
                throw new FinanceRecordProcessingException(
                        "FINANCE_RECORD_BATCH_IDENTITY_CONFLICT",
                        "Persisted finance record content differs from idempotent replay");
            }
        }
    }

    private static boolean sameRecord(FinanceMetricRecord left, FinanceMetricRecord right) {
        return left != null
                && Objects.equals(left.getRecordId(), right.getRecordId())
                && Objects.equals(left.getRunId(), right.getRunId())
                && Objects.equals(left.getTodoId(), right.getTodoId())
                && Objects.equals(left.getExecutePythonToolCallId(), right.getExecutePythonToolCallId())
                && Objects.equals(left.getRecordIndex(), right.getRecordIndex())
                && Objects.equals(left.getRawDigest(), right.getRawDigest())
                && Objects.equals(left.getRawPayload(), right.getRawPayload())
                && Objects.equals(left.getSourceResolverToolCallId(), right.getSourceResolverToolCallId())
                && Objects.equals(left.getMethodId(), right.getMethodId())
                && Objects.equals(left.getMethodVersion(), right.getMethodVersion())
                && Objects.equals(left.getSpecDigest(), right.getSpecDigest())
                && Objects.equals(left.getValueJson(), right.getValueJson())
                && Objects.equals(left.getUnit(), right.getUnit())
                && Objects.equals(left.getParametersJson(), right.getParametersJson())
                && Objects.equals(left.getInputRefsJson(), right.getInputRefsJson())
                && Objects.equals(left.getChecksJson(), right.getChecksJson())
                && Objects.equals(left.getFormulaDescription(), right.getFormulaDescription())
                && Objects.equals(left.getDeclaredEvidence(), right.getDeclaredEvidence())
                && Objects.equals(left.getEffectiveInternalEvidence(), right.getEffectiveInternalEvidence())
                && Objects.equals(left.getActualEnvironmentId(), right.getActualEnvironmentId())
                && Objects.equals(left.getRenderable(), right.getRenderable())
                && Objects.equals(left.getValidationErrorJson(), right.getValidationErrorJson());
    }

    public enum PersistenceOutcome {
        INSERTED,
        ALREADY_PRESENT_SAME
    }
}
