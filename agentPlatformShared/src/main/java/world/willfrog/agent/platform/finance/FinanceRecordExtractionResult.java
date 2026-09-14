package world.willfrog.agent.platform.finance;

import java.util.List;

/** Processor output split into backend persistence, de-marked stdout, and model-safe notices. */
public record FinanceRecordExtractionResult(
        FinanceRecordBatch batch,
        List<FinanceMetricRecord> records,
        String ordinaryStdout,
        List<ModelNotice> modelNotices,
        boolean persisted) {

    public FinanceRecordExtractionResult {
        records = records == null ? List.of() : List.copyOf(records);
        ordinaryStdout = ordinaryStdout == null ? "" : ordinaryStdout;
        modelNotices = modelNotices == null ? List.of() : List.copyOf(modelNotices);
    }

    public record ModelNotice(String code, String message, String action) {
    }
}
